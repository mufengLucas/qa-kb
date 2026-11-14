package com.qa.kb.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.kb.security.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 空间权限端到端契约（P2 Phase 3）：真实过滤链 + 真实 SQLite。
 *
 * <p>覆盖：受限空间对非成员隐藏与 403、成员加入后可见可读、空间角色收紧写（全局 MEMBER +
 * 空间 VIEWER → 403）、无成员记录回退全局角色、管理操作需空间 ADMIN、回收站/标签/搜索按可见空间过滤。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("空间权限契约：受限空间 / 成员角色 / 回退与过滤")
class SpacePermissionContractTest {

  private static final String ADMIN_PASSWORD = "SpaceTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dbPath =
        TEMP_DIR.resolve("kb-space-perm-test.db").toAbsolutePath().toString().replace('\\', '/');
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:sqlite:"
                + dbPath
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.datasource.hikari.connection-init-sql", () -> "PRAGMA foreign_keys=ON");
    registry.add("kb.seed.enabled", () -> "true");
    registry.add("kb.seed.demo", () -> "false");
    registry.add("kb.seed.admin-password", () -> ADMIN_PASSWORD);
    registry.add("kb.backup.enabled", () -> "false");
    registry.add("logging.file.name", () -> TEMP_DIR.resolve("kb-space-perm-test.log").toString());
  }

  @Test
  @DisplayName("受限空间：非成员列表不出现、单点读 403；全局 ADMIN 不受限")
  void restrictedSpaceHidesFromNonMembers() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long secretId = createSpace(admin, csrf, "机密空间", "perm-secret", true);
    long pageId = createPage(admin, csrf, secretId, "受限页面");
    createUser(admin, csrf, "permhide", "PermHide@2026");
    Cookie member = login("permhide", "PermHide@2026");

    assertThat(spaceIds(member)).doesNotContain(secretId);
    mvc.perform(get("/api/spaces/" + secretId).cookie(member)).andExpect(status().isForbidden());
    mvc.perform(get("/api/spaces/" + secretId + "/pages/tree").cookie(member))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/pages/" + pageId).cookie(member)).andExpect(status().isForbidden());
    mvc.perform(get("/api/pages/" + pageId + "/revisions").cookie(member))
        .andExpect(status().isForbidden());

    // 全局 ADMIN 始终可见可读
    assertThat(spaceIds(admin)).contains(secretId);
    mvc.perform(get("/api/spaces/" + secretId).cookie(admin)).andExpect(status().isOk());
    mvc.perform(get("/api/pages/" + pageId).cookie(admin)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("加入成员后可见可读；空间 VIEWER 收紧写，升为 MEMBER 后可写")
  void membershipGrantsReadAndRoleControlsWrite() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long spaceId = createSpace(admin, csrf, "角色空间", "perm-role", true);
    createUser(admin, csrf, "permrole", "PermRole@2026");
    long userId = userId(admin, "permrole");
    Cookie member = login("permrole", "PermRole@2026");

    // 未加入前：写也被拒
    mvc.perform(
            post("/api/spaces/" + spaceId + "/pages")
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"草稿\"}"))
        .andExpect(status().isForbidden());

    // 加为空间 VIEWER（全局仍是 MEMBER，Spring 层不拦，控制器空间层收紧）
    upsertMember(admin, csrf, spaceId, userId, "VIEWER");
    assertThat(spaceIds(member)).contains(spaceId);
    mvc.perform(get("/api/spaces/" + spaceId + "/pages/tree").cookie(member))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/spaces/" + spaceId + "/pages")
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"草稿\"}"))
        .andExpect(status().isForbidden());

    // 升为 MEMBER 后可写；空间设置仍需空间 ADMIN（全局 MEMBER 管不了）
    upsertMember(admin, csrf, spaceId, userId, "MEMBER");
    mvc.perform(
            post("/api/spaces/" + spaceId + "/pages")
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"正式页\"}"))
        .andExpect(status().isCreated());
    mvc.perform(
            put("/api/spaces/" + spaceId)
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"角色空间\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/spaces/" + spaceId).cookie(member, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("回退语义：开放空间无成员记录按全局角色；有记录按空间角色（可收紧）")
  void globalRoleFallsBackOnlyWithoutMembership() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long openId = createSpace(admin, csrf, "开放空间", "perm-open", false);
    createUser(admin, csrf, "permfb", "PermFb@2026");
    long userId = userId(admin, "permfb");
    Cookie member = login("permfb", "PermFb@2026");

    // 无成员记录 → 回退全局角色：全局 MEMBER 可写开放空间
    mvc.perform(
            post("/api/spaces/" + openId + "/pages")
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"回退可写\"}"))
        .andExpect(status().isCreated());

    // 空间成员记录存在 → 按空间角色收紧：VIEWER 不可写
    upsertMember(admin, csrf, openId, userId, "VIEWER");
    mvc.perform(
            post("/api/spaces/" + openId + "/pages")
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"收紧后\"}"))
        .andExpect(status().isForbidden());

    // 全局 ADMIN 不受任何收紧影响
    mvc.perform(
            post("/api/spaces/" + openId + "/pages")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"管理员可写\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("管理端点：非空间 ADMIN 拒绝，创建者（空间 ADMIN）放行")
  void memberManagementRequiresSpaceAdmin() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long spaceId = createSpace(admin, csrf, "管理空间", "perm-manage", true);
    createUser(admin, csrf, "permadmin1", "PermAdm@2026");
    createUser(admin, csrf, "permadmin2", "PermAd2@2026");
    long plainId = userId(admin, "permadmin1");
    long bossId = userId(admin, "permadmin2");
    Cookie plain = login("permadmin1", "PermAdm@2026");

    // 普通成员读成员名单可以（开放给登录用户的空间读），管理动作不行
    mvc.perform(get("/api/spaces/" + spaceId + "/members").cookie(admin))
        .andExpect(status().isOk());

    upsertMember(admin, csrf, spaceId, plainId, "VIEWER");
    mvc.perform(
            post("/api/spaces/" + spaceId + "/members")
                .cookie(plain, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + bossId + ",\"role\":\"MEMBER\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/spaces/" + spaceId + "/members/" + bossId).cookie(plain, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isForbidden());

    // 全局 ADMIN 管理成员；upsert 重复提交改角色不报错
    upsertMember(admin, csrf, spaceId, plainId, "MEMBER");
    upsertMember(admin, csrf, spaceId, plainId, "VIEWER");
    JsonNode members = getJson(admin, "/api/spaces/" + spaceId + "/members");
    // 创建者 admin 自动入组 ADMIN + permadmin1
    assertThat(members.size()).isEqualTo(2);
    assertThat(findMember(members, "permadmin1").path("role").asText()).isEqualTo("VIEWER");
    assertThat(findMember(members, "admin").path("role").asText()).isEqualTo("ADMIN");

    // 移出 permadmin1 后名单只剩创建者；非成员身份回归 403
    mvc.perform(delete("/api/spaces/" + spaceId + "/members/" + plainId).cookie(admin, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    JsonNode afterRemoval = getJson(admin, "/api/spaces/" + spaceId + "/members");
    assertThat(afterRemoval.size()).isEqualTo(1);
    assertThat(afterRemoval.get(0).path("login").asText()).isEqualTo("admin");
    mvc.perform(get("/api/spaces/" + spaceId).cookie(plain)).andExpect(status().isForbidden());

    // 校验：用户不存在 404，角色名非法 400
    mvc.perform(
            post("/api/spaces/" + spaceId + "/members")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999999,\"role\":\"MEMBER\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/spaces/" + spaceId + "/members")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + plainId + ",\"role\":\"MANAGER\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("回收站 / 标签 / 搜索：受限空间内容对非成员不可见")
  void listingsAreFilteredByVisibleSpaces() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long secretId = createSpace(admin, csrf, "过滤空间", "perm-filter", true);
    long secretPage = createPageWithContent(admin, csrf, secretId, "密文页面", "这里有密文内容");
    long openId = createSpace(admin, csrf, "对照空间", "perm-contrast", false);
    long openPage = createPageWithContent(admin, csrf, openId, "对照页面", "这里也有密文内容");
    createUser(admin, csrf, "permfilter", "PermFlt@2026");
    Cookie observer = login("permfilter", "PermFlt@2026");

    // 受限页面打标签 → 标签只属于受限空间
    mvc.perform(
            put("/api/pages/" + secretPage + "/tags")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"绝密标记\"]}"))
        .andExpect(status().isNoContent());

    // 搜索（2 字查询走 LIKE 路径，双端一致）：非成员只看得到开放空间的命中
    JsonNode observerHits = getJson(observer, "/api/search?q=密文");
    assertThat(hitIds(observerHits)).contains(openPage).doesNotContain(secretPage);
    JsonNode adminHits = getJson(admin, "/api/search?q=密文");
    assertThat(hitIds(adminHits)).contains(openPage, secretPage);

    // 标签云：非成员看不到受限空间的标签
    assertThat(tagNames(getJson(observer, "/api/tags"))).doesNotContain("绝密标记");
    assertThat(tagNames(getJson(admin, "/api/tags"))).contains("绝密标记");

    // 删除受限页面进回收站 → 非成员的回收站里不出现
    mvc.perform(delete("/api/pages/" + secretPage).cookie(admin, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    assertThat(pageIds(getJson(observer, "/api/pages/trash"))).doesNotContain(secretPage);
    assertThat(pageIds(getJson(admin, "/api/pages/trash"))).contains(secretPage);

    // 加入成员后全部解禁
    long observerId = userId(admin, "permfilter");
    upsertMember(admin, csrf, secretId, observerId, "MEMBER");
    assertThat(tagNames(getJson(observer, "/api/tags"))).contains("绝密标记");
    assertThat(pageIds(getJson(observer, "/api/pages/trash"))).contains(secretPage);

    // 已软删的页面搜不到（searchLike 过滤 deleted_at），恢复后再搜才能命中
    mvc.perform(post("/api/pages/" + secretPage + "/restore").cookie(observer, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    assertThat(hitIds(getJson(observer, "/api/search?q=密文"))).contains(secretPage);
    mvc.perform(get("/api/pages/" + openPage + "/tags").cookie(observer))
        .andExpect(status().isOk());
  }

  /* --------------------------------------------------------------- helpers */

  private long createSpace(Cookie session, Cookie csrf, String name, String slug, boolean restricted)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/spaces")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"slug\":\""
                            + slug
                            + "\",\"restricted\":"
                            + restricted
                            + "}"))
            .andExpect(status().isCreated())
            .andReturn();
    return readTree(result).path("id").asLong();
  }

  private long createPage(Cookie session, Cookie csrf, long spaceId, String title) throws Exception {
    return createPageWithContent(session, csrf, spaceId, title, "正文内容");
  }

  private long createPageWithContent(
      Cookie session, Cookie csrf, long spaceId, String title, String content) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/spaces/" + spaceId + "/pages")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"title\":\""
                            + title
                            + "\",\"contentMd\":\""
                            + content
                            + "\",\"parentId\":null}"))
            .andExpect(status().isCreated())
            .andReturn();
    return readTree(result).path("id").asLong();
  }

  private void createUser(Cookie session, Cookie csrf, String login, String password)
      throws Exception {
    mvc.perform(
            post("/api/users")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"login\":\""
                        + login
                        + "\",\"name\":\"成员-"
                        + login
                        + "\",\"password\":\""
                        + password
                        + "\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isCreated());
  }

  private long userId(Cookie session, String login) throws Exception {
    for (JsonNode user : getJson(session, "/api/members")) {
      if (login.equals(user.path("login").asText())) {
        return user.path("id").asLong();
      }
    }
    throw new AssertionError("user not found: " + login);
  }

  private void upsertMember(
      Cookie session, Cookie csrf, long spaceId, long userId, String role) throws Exception {
    mvc.perform(
            post("/api/spaces/" + spaceId + "/members")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + ",\"role\":\"" + role + "\"}"))
        .andExpect(status().isCreated());
  }

  private List<Long> spaceIds(Cookie session) throws Exception {
    List<Long> ids = new ArrayList<>();
    for (JsonNode row : getJson(session, "/api/spaces")) {
      ids.add(row.path("id").asLong());
    }
    return ids;
  }

  private List<Long> hitIds(JsonNode hits) {
    List<Long> ids = new ArrayList<>();
    for (JsonNode hit : hits) {
      ids.add(hit.path("id").asLong());
    }
    return ids;
  }

  private List<Long> pageIds(JsonNode rows) {
    List<Long> ids = new ArrayList<>();
    for (JsonNode row : rows) {
      ids.add(row.path("id").asLong());
    }
    return ids;
  }

  private List<String> tagNames(JsonNode rows) {
    List<String> names = new ArrayList<>();
    for (JsonNode row : rows) {
      names.add(row.path("name").asText());
    }
    return names;
  }

  private static JsonNode findMember(JsonNode members, String login) {
    for (JsonNode row : members) {
      if (login.equals(row.path("login").asText())) {
        return row;
      }
    }
    throw new AssertionError("member not found: " + login);
  }

  private JsonNode getJson(Cookie session, String url) throws Exception {
    MvcResult result = mvc.perform(get(url).cookie(session)).andExpect(status().isOk()).andReturn();
    return readTree(result);
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private Cookie loginAsAdmin() throws Exception {
    return login("admin", ADMIN_PASSWORD);
  }

  private Cookie login(String login, String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    Cookie session = result.getResponse().getCookie(SessionAuthenticationFilter.COOKIE_NAME);
    assertThat(session).as("login must set the session cookie").isNotNull();
    return session;
  }

  private Cookie freshCsrfCookie() throws Exception {
    MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
    Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
    assertThat(csrf).as("XSRF-TOKEN cookie must be set").isNotNull();
    return csrf;
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("qa-kb-space-perm-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}

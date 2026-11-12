package com.qa.kb.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
import com.qa.kb.security.SessionAuthenticationFilter;

/**
 * 两个已取证缺陷的端到端回归（真实过滤链 + 真实 SQLite schema + 真实登录）。
 *
 * <ol>
 *   <li>删空间 409（MySQL ERROR 1452）：空间下有嵌套父/子页时，必须先删 pages 行再删空间行。
 *       SQLite 开了 {@code PRAGMA foreign_keys=ON}，顺序错了同样失败，可挡住回归。
 *   <li>周报保存 404：周报关联了已软删项目时，保存端返回 400 并提示刷新（不再 404/静默），
 *       读取端 JOIN 过滤让残留关联在刷新后不再出现。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("缺陷回归：删空间 1452 / 周报失效关联 400")
class DefectRegressionTest {

  private static final String ADMIN_PASSWORD = "DefectTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private Clock clock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dbPath =
        TEMP_DIR.resolve("kb-defect-test.db").toAbsolutePath().toString().replace('\\', '/');
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
    registry.add("logging.file.name", () -> TEMP_DIR.resolve("kb-defect-test.log").toString());
  }

  @Test
  @DisplayName("删空间：嵌套父/子页先删 pages 再删空间行 → 204 而非 409")
  void deleteSpaceWithNestedPagesReturns204() throws Exception {
    Cookie session = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long spaceId =
        createSpace(session, csrf, "回归-删空间", "regress-del-space").id();
    long parentId =
        createPage(session, csrf, spaceId, null, "父页面").id();
    long childId =
        createPage(session, csrf, spaceId, parentId, "子页面").id();
    assertThat(childId).isPositive();

    mvc.perform(
            delete("/api/spaces/" + spaceId)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/spaces/" + spaceId).cookie(session)).andExpect(status().isNotFound());
    mvc.perform(get("/api/pages/" + parentId).cookie(session)).andExpect(status().isNotFound());
    mvc.perform(get("/api/pages/" + childId).cookie(session)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("周报关联已软删项目：保存 400 提示刷新，读取端不再返回残留关联")
  void weeklyReportWithDeletedProjectReturns400() throws Exception {
    Cookie session = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long projectId = createProject(session, csrf, "回归-失效关联项目", "REGX1").id();
    LocalDate monday =
        LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    String payload =
        "{\"done\":\"完成回归\",\"plan\":\"继续\",\"blockers\":\"\",\"status\":\"draft\","
            + "\"projectIds\":[" + projectId + "]}";

    // 关联仍有效：保存 204，读取端返回该关联
    mvc.perform(
            put("/api/reports/weekly/" + monday)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/reports/weekly/" + monday + "/projects").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(projectId));

    // 软删项目：列表立即不可见
    mvc.perform(
            delete("/api/projects/" + projectId)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.project.id == " + projectId + ")]").isEmpty());

    // 同一 payload 再次保存 → 400，消息区分「已删除」并提示刷新（不再是 404）
    mvc.perform(
            put("/api/reports/weekly/" + monday)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已删除")))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("刷新")));

    // 读取端 JOIN 过滤：残留关联在刷新后不再出现
    mvc.perform(get("/api/reports/weekly/" + monday + "/projects").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /* --------------------------------------------------------------- helpers */

  private record Created(long id) {}

  private Created createSpace(Cookie session, Cookie csrf, String name, String slug)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/spaces")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return new Created(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong());
  }

  private Created createPage(
      Cookie session, Cookie csrf, long spaceId, Long parentId, String title) throws Exception {
    String body =
        parentId == null
            ? "{\"title\":\"" + title + "\",\"contentMd\":\"# " + title + "\"}"
            : "{\"parentId\":" + parentId + ",\"title\":\"" + title + "\"}";
    MvcResult result =
        mvc.perform(
                post("/api/spaces/" + spaceId + "/pages")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return new Created(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong());
  }

  private Created createProject(Cookie session, Cookie csrf, String name, String key)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/projects")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"" + name + "\",\"projectKey\":\"" + key + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return new Created(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong());
  }

  private Cookie loginAsAdmin() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"))
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
      return Files.createTempDirectory("qa-kb-defect-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}

package com.qa.kb.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.kb.security.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 审计日志端到端契约（P2 Phase 2）：真实过滤链 + 拦截器 + 真实 SQLite。
 *
 * <p>覆盖：写操作产生审计行、GET 不产生、登录失败留痕、非 ADMIN 读取 403、成员删除后审计保留。
 *
 * <p>注意：读取审计必须复用既有会话——每次登录都会写一条 LOGIN 审计，若读取时重新登录会把
 * 目标行顶掉（ORDER BY id DESC 取到的是 LOGIN 而非被测行）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("审计契约：写操作采集 / 登录留痕 / ADMIN 读取")
class AuditContractTest {

  private static final String ADMIN_PASSWORD = "AuditTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dbPath =
        TEMP_DIR.resolve("kb-audit-test.db").toAbsolutePath().toString().replace('\\', '/');
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
    registry.add("logging.file.name", () -> TEMP_DIR.resolve("kb-audit-test.log").toString());
  }

  @Test
  @DisplayName("写操作产生审计行，GET 读取不产生")
  void writeIsAuditedButReadIsNot() throws Exception {
    Cookie session = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long before = auditTotal(session);

    // 两次 GET：不产生审计
    mvc.perform(get("/api/spaces").cookie(session)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/audit").cookie(session)).andExpect(status().isOk());
    assertThat(auditTotal(session)).as("GET 不应产生审计").isEqualTo(before);

    // 一次写：产生 1 行
    mvc.perform(
            post("/api/spaces")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"审计空间\",\"slug\":\"audit-space\"}"))
        .andExpect(status().isCreated());

    assertThat(auditTotal(session)).as("写操作应产生 1 行审计").isEqualTo(before + 1);
    JsonNode latest = latestAudit(session);
    assertThat(latest.path("action").asText()).isEqualTo("POST /api/spaces");
    assertThat(latest.path("targetType").asText()).isEqualTo("spaces");
    assertThat(latest.path("userLogin").asText()).isEqualTo("admin");
    assertThat(latest.path("detail").asText()).contains("status=201");
  }

  @Test
  @DisplayName("登录失败留痕 LOGIN_FAILED，成功留痕 LOGIN")
  void loginFailuresAreAudited() throws Exception {
    // 先取一个会话用于读取，避免读取动作本身再触发 LOGIN 行
    Cookie reader = loginAsAdmin();

    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"admin\",\"password\":\"WrongPass@2026\"}"))
        .andExpect(status().isUnauthorized());

    JsonNode latest = latestAudit(reader);
    assertThat(latest.path("action").asText()).isEqualTo("LOGIN_FAILED");
    assertThat(latest.path("targetType").asText()).isEqualTo("user");
    assertThat(latest.path("targetId").asText()).isEqualTo("admin");
    assertThat(latest.path("userId").isNull())
        .as("失败登录无用户 id")
        .isTrue();

    // 成功登录 → 最新一行为 LOGIN
    Cookie fresh = loginAsAdmin();
    JsonNode loginRow = latestAudit(fresh);
    assertThat(loginRow.path("action").asText()).isEqualTo("LOGIN");
    assertThat(loginRow.path("userLogin").asText()).isEqualTo("admin");
    assertThat(fresh).isNotNull();
  }

  @Test
  @DisplayName("成员删除后审计行保留，登录名显示为空")
  void auditSurvivesUserDeletion() throws Exception {
    Cookie adminSession = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    // 建成员 → 以成员身份写一条 → 删除成员
    MvcResult created =
        mvc.perform(
                post("/api/users")
                    .cookie(adminSession, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"login\":\"audituser\",\"name\":\"审计员\",\"password\":\"AuditUs3r@2026\",\"role\":\"MEMBER\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    long userId =
        objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();

    Cookie memberSession = login("audituser", "AuditUs3r@2026");
    mvc.perform(
            post("/api/spaces")
                .cookie(memberSession, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"成员空间\",\"slug\":\"member-space\"}"))
        .andExpect(status().isCreated());

    mvc.perform(
            delete("/api/users/" + userId)
                .cookie(adminSession, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());

    // 该成员产生的审计行仍在，但 LEFT JOIN users 后登录名为 null
    JsonNode memberRow = findAuditByUser(adminSession, "POST /api/spaces", userId);
    assertThat(memberRow)
        .as("成员删除后其审计行必须保留（audit_log 不设 user_id 外键）")
        .isNotNull();
    assertThat(memberRow.path("userLogin").isNull())
        .as("成员已删除，JOIN 不到登录名")
        .isTrue();

    // 删除操作本身也留痕
    JsonNode latest = latestAudit(adminSession);
    assertThat(latest.path("action").asText()).isEqualTo("DELETE /api/users/" + userId);
    assertThat(latest.path("userLogin").asText()).isEqualTo("admin");
  }

  @Test
  @DisplayName("非 ADMIN 读取审计 403")
  void auditIsAdminOnly() throws Exception {
    Cookie adminSession = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    mvc.perform(
            post("/api/users")
                .cookie(adminSession, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"login\":\"auditviewer\",\"name\":\"访客\",\"password\":\"AuditV13w@2026\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isCreated());

    Cookie viewerSession = login("auditviewer", "AuditV13w@2026");
    mvc.perform(get("/api/admin/audit").cookie(viewerSession))
        .andExpect(status().isForbidden());

    // 管理员可读
    mvc.perform(get("/api/admin/audit").cookie(adminSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber())
        .andExpect(jsonPath("$.items").isArray());
  }

  /* --------------------------------------------------------------- helpers */

  private long auditTotal(Cookie session) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/admin/audit?limit=1").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("total").asLong();
  }

  private JsonNode latestAudit(Cookie session) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/admin/audit?limit=1").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
    assertThat(items.size()).as("审计表不应为空").isPositive();
    return items.get(0);
  }

  /** 按 action 与 userId 找到指定审计行；找不到返回 null。 */
  private JsonNode findAuditByUser(Cookie session, String action, long userId) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/admin/audit?limit=200").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    for (JsonNode row :
        objectMapper.readTree(result.getResponse().getContentAsString()).path("items")) {
      if (action.equals(row.path("action").asText()) && row.path("userId").asLong() == userId) {
        return row;
      }
    }
    return null;
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
      return Files.createTempDirectory("qa-kb-audit-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}

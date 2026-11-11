package com.qa.kb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Regression test for the browser CSRF contract.
 *
 * <p>The SPA reads the {@code XSRF-TOKEN} cookie and echoes it verbatim in the
 * {@code X-XSRF-TOKEN} header. Spring Security 6 XOR-masks tokens by default, which silently
 * rejects that raw value with 403. These tests pin the raw double-submit behaviour end to end
 * (real filter chain, real SQLite schema, real login) so the defect cannot come back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CSRF cookie contract")
class CsrfCookieContractTest {

  private static final String ADMIN_PASSWORD = "CsrfTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dbPath = TEMP_DIR.resolve("kb-csrf-test.db").toAbsolutePath().toString().replace('\\', '/');
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:sqlite:"
                + dbPath
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
    // application.yml 固定为 MySQL 驱动与 MySQL 连接初始化 SQL，SQLite 测试必须覆盖掉
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.datasource.hikari.connection-init-sql", () -> "PRAGMA foreign_keys=ON");
    registry.add("kb.seed.enabled", () -> "true");
    registry.add("kb.seed.demo", () -> "false");
    registry.add("kb.seed.admin-password", () -> ADMIN_PASSWORD);
    registry.add("kb.backup.enabled", () -> "false");
    registry.add(
        "logging.file.name", () -> TEMP_DIR.resolve("kb-csrf-test.log").toString());
  }

  @Test
  @DisplayName("the CSRF cookie is script-readable and equals the JSON body token")
  void csrfCookieMatchesBodyToken() throws Exception {
    MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

    Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
    assertThat(cookie).as("XSRF-TOKEN cookie must be set").isNotNull();
    assertThat(cookie.isHttpOnly())
        .as("the SPA must be able to read the token with document.cookie")
        .isFalse();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("token").asText())
        .as("body token must be the unmasked value the cookie carries")
        .isEqualTo(cookie.getValue());
    assertThat(body.path("headerName").asText()).isEqualTo("X-XSRF-TOKEN");
  }

  @Test
  @DisplayName("a mutation without a token is still rejected")
  void mutationWithoutTokenIsRejected() throws Exception {
    mvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"nobody\",\"name\":\"N\",\"password\":\"Str0ngPass!x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("forbidden"));
  }

  @Test
  @DisplayName("the raw cookie token authorizes create, update and delete")
  void rawCookieTokenAuthorizesUserAdministration() throws Exception {
    Cookie session = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    MvcResult created =
        mvc.perform(
                post("/api/users")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"login\":\"csrf-probe\",\"name\":\"CSRF 探针\","
                            + "\"password\":\"Probe0Pass!x\",\"role\":\"MEMBER\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.login").value("csrf-probe"))
            .andReturn();

    // Behave like the browser cookie jar: adopt whatever the server last sent.
    csrf = nextCsrfCookie(created, csrf);
    assertThat(csrf)
        .as("an authenticated mutation must not clear the double-submit cookie")
        .isNotNull();

    long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    assertThat(id).isPositive();

    MvcResult updated =
        mvc.perform(
                put("/api/users/" + id)
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"CSRF 探针\",\"role\":\"ADMIN\",\"active\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andReturn();

    csrf = nextCsrfCookie(updated, csrf);
    assertThat(csrf)
        .as("a second mutation must still be accepted with the same cookie jar")
        .isNotNull();

    mvc.perform(
            delete("/api/users/" + id)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/users").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.login == 'csrf-probe')]").isEmpty());
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

  /**
   * Mirrors a browser cookie jar: keep the previous cookie unless the server sent a new one, and
   * drop it when the server expired it ({@code Max-Age=0}).
   */
  private static Cookie nextCsrfCookie(MvcResult result, Cookie previous) {
    Cookie fromServer = result.getResponse().getCookie("XSRF-TOKEN");
    if (fromServer == null) {
      return previous;
    }
    return fromServer.getMaxAge() == 0 ? null : fromServer;
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("qa-kb-csrf-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}

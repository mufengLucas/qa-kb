package com.qa.kb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.kb.db.MySqlTestDatabase;
import com.qa.kb.security.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
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
 * A2 外发通知 · 成员邮箱的端到端契约（真实过滤链 + 真实 MySQL）。
 *
 * <p>覆盖：email 随创建落库并在列表回读、更新覆盖、缺省字段不抹掉已有邮箱、
 * 空串清空、脏地址 400 进不了库。老库缺列由 {@code SchemaInitializer.ensureColumn}
 * 幂等补齐——本测试能跑通本身就证明补列生效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("成员邮箱契约：落库 / 更新 / 保持 / 清空 / 校验")
class UserEmailContractTest {

  private static final String ADMIN_PASSWORD = "EmailTest@2026";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    MySqlTestDatabase.register(registry, "useremail");
    registry.add("kb.seed.enabled", () -> "true");
    registry.add("kb.seed.demo", () -> "false");
    registry.add("kb.seed.admin-password", () -> ADMIN_PASSWORD);
    registry.add("kb.backup.enabled", () -> "false");
    registry.add("kb.notify.enabled", () -> "false");
  }

  @Test
  @DisplayName("创建带邮箱：落库后列表回读一致")
  void createPersistsEmail() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    mvc.perform(
            post("/api/users")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"login\":\"mailuser1\",\"name\":\"邮件成员\",\"password\":\"MailTest@2026\","
                        + "\"role\":\"MEMBER\",\"email\":\"u1@example.com\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("u1@example.com"));

    JsonNode list = getJson(admin, "/api/users");
    assertThat(findByLogin(list, "mailuser1").path("email").asText())
        .isEqualTo("u1@example.com");
  }

  @Test
  @DisplayName("更新邮箱：覆盖生效；请求缺 email 字段时保持原值不被抹掉")
  void updateOverwritesAndMissingFieldKeeps() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    createUser(admin, csrf, "mailuser2", "MailTest@2026", "u2@example.com");
    long id = userId(admin, "mailuser2");

    // 显式更新 → 覆盖
    mvc.perform(
            put("/api/users/" + id)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"邮件成员\",\"email\":\"u2-new@example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("u2-new@example.com"));

    // 请求里没有 email 字段（老客户端/别的入口）→ 保持已有的新地址
    mvc.perform(
            put("/api/users/" + id)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"邮件成员改名\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("u2-new@example.com"));

    // 空串 = 显式清空（该成员不再接收外发邮件）
    mvc.perform(
            put("/api/users/" + id)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"邮件成员改名\",\"email\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(""));
  }

  @Test
  @DisplayName("脏地址 400：创建与更新两个入口都拦，库里不会出现非法邮箱")
  void malformedEmailIsRejected() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    mvc.perform(
            post("/api/users")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"login\":\"mailuser3\",\"name\":\"坏邮箱\",\"password\":\"MailTest@2026\","
                        + "\"email\":\"definitely-not-an-email\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));

    createUser(admin, csrf, "mailuser4", "MailTest@2026", "u4@example.com");
    long id = userId(admin, "mailuser4");
    mvc.perform(
            put("/api/users/" + id)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"坏邮箱更新\",\"email\":\"@@@\"}"))
        .andExpect(status().isBadRequest());

    // 两个坏请求都没落库：mailuser3 不存在，mailuser4 邮箱原样
    JsonNode list = getJson(admin, "/api/users");
    assertThat(findByLogin(list, "mailuser3")).isNull();
    assertThat(findByLogin(list, "mailuser4").path("email").asText())
        .isEqualTo("u4@example.com");
  }

  @Test
  @DisplayName("邮箱不出现在登录接口（AuthController.UserView 不带 email 字段）")
  void loginResponseDoesNotExposeEmail() throws Exception {
    Cookie admin = loginAsAdmin();
    createUser(admin, freshCsrfCookie(), "mailuser5", "MailTest@2026", "u5@example.com");

    Cookie session = login("mailuser5", "MailTest@2026");
    MvcResult me =
        mvc.perform(get("/api/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode node = objectMapper.readTree(me.getResponse().getContentAsString());
    assertThat(node.has("email")).as("登录视图不应多带邮箱字段").isFalse();
  }

  /* --------------------------------------------------------------- helpers */

  private void createUser(Cookie session, Cookie csrf, String login, String password,
      String email) throws Exception {
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
                        + "\",\"role\":\"MEMBER\",\"email\":\""
                        + email
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  private long userId(Cookie session, String login) throws Exception {
    JsonNode list = getJson(session, "/api/users");
    JsonNode user = findByLogin(list, login);
    assertThat(user).as("用户 " + login + " 应存在").isNotNull();
    return user.path("id").asLong();
  }

  private static JsonNode findByLogin(JsonNode list, String login) {
    for (JsonNode user : list) {
      if (user.path("login").asText().equals(login)) {
        return user;
      }
    }
    return null;
  }

  private JsonNode getJson(Cookie session, String url) throws Exception {
    MvcResult result =
        mvc.perform(get(url).cookie(session)).andExpect(status().isOk()).andReturn();
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
}

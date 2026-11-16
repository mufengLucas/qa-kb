package com.qa.kb.testcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.kb.db.MySqlTestDatabase;
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
 * 用例管理端到端契约（P2 Phase 4）：真实过滤链 + 真实 MySQL（一次性 schema）。
 *
 * <p>覆盖：成员 CRUD 全链路与创建人归属、VIEWER 只读（写 403）、执行记录统计聚合与随用例
 * 级联删除、模块/关键字筛选与 LIKE 通配符转义、非法枚举 400、写操作进审计。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("用例契约：CRUD / 权限 / 执行统计 / 筛选转义")
class TestCaseContractTest {

  private static final String ADMIN_PASSWORD = "CaseTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    MySqlTestDatabase.register(registry, "testcase");
    registry.add("kb.seed.enabled", () -> "true");
    registry.add("kb.seed.demo", () -> "false");
    registry.add("kb.seed.admin-password", () -> ADMIN_PASSWORD);
    registry.add("kb.backup.enabled", () -> "false");
    registry.add("logging.file.name", () -> TEMP_DIR.resolve("kb-testcase-test.log").toString());
  }

  @Test
  @DisplayName("成员 CRUD：创建人归属自己，更新生效，删除后 404")
  void memberCrudLifecycle() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    createUser(admin, csrf, "casemember", "CaseMem@2026", "MEMBER");
    Cookie member = login("casemember", "CaseMem@2026");

    long id = createCase(member, csrf, "接口", "接口正向用例", null, null);

    JsonNode detail = getJson(member, "/api/test-cases/" + id);
    assertThat(detail.path("creatorLogin").asText())
        .as("创建人必须是发起请求的成员")
        .isEqualTo("casemember");
    assertThat(detail.path("priority").asText()).isEqualTo("P2");
    assertThat(detail.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(detail.path("runCount").asInt()).isZero();

    mvc.perform(
            put("/api/test-cases/" + id)
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"module\":\"接口\",\"title\":\"接口反向用例\",\"priority\":\"P0\","
                        + "\"status\":\"ARCHIVED\"}"))
        .andExpect(status().isOk());

    JsonNode updated = getJson(member, "/api/test-cases/" + id);
    assertThat(updated.path("title").asText()).isEqualTo("接口反向用例");
    assertThat(updated.path("priority").asText()).isEqualTo("P0");
    assertThat(updated.path("status").asText()).isEqualTo("ARCHIVED");

    assertThat(caseIds(query(member, "module", "接口"))).contains(id);
    assertThat(texts(getJson(member, "/api/test-cases").path("modules")))
        .as("筛选下拉来自已用模块")
        .contains("接口");

    mvc.perform(
            delete("/api/test-cases/" + id)
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/test-cases/" + id).cookie(member)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("VIEWER 只读：GET 200，增删改与记录执行一律 403")
  void viewerIsReadOnly() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    createUser(admin, csrf, "caseviewer", "CaseV1ew@2026", "VIEWER");
    Cookie viewer = login("caseviewer", "CaseV1ew@2026");
    long id = createCase(admin, csrf, "登录", "访客可见用例", null, null);

    mvc.perform(get("/api/test-cases").cookie(viewer)).andExpect(status().isOk());
    mvc.perform(get("/api/test-cases/" + id).cookie(viewer)).andExpect(status().isOk());
    mvc.perform(get("/api/test-cases/" + id + "/runs").cookie(viewer))
        .andExpect(status().isOk());

    mvc.perform(
            post("/api/test-cases")
                .cookie(viewer, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"访客不该能建\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/test-cases/" + id)
                .cookie(viewer, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"访客不该能改\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/test-cases/" + id + "/runs")
                .cookie(viewer, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"PASS\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/test-cases/" + id)
                .cookie(viewer, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isForbidden());

    // 用例本体未被上述写操作动过
    assertThat(getJson(admin, "/api/test-cases/" + id).path("title").asText())
        .isEqualTo("访客可见用例");
  }

  @Test
  @DisplayName("执行记录：统计聚合正确，删一条回落，删用例随行消失")
  void runStatsAggregateAndCascade() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    long id = createCase(admin, csrf, "回归", "登录回归用例", null, null);

    recordRun(admin, csrf, id, "PASS", "");
    recordRun(admin, csrf, id, "FAIL", "环境不可用");
    recordRun(admin, csrf, id, "PASS", "复测通过");

    JsonNode detail = getJson(admin, "/api/test-cases/" + id);
    assertThat(detail.path("runCount").asInt()).isEqualTo(3);
    assertThat(detail.path("passCount").asInt()).isEqualTo(2);
    assertThat(detail.path("failCount").asInt()).isEqualTo(1);
    assertThat(detail.path("lastResult").asText())
        .as("最近一次是最后记录的那条")
        .isEqualTo("PASS");

    JsonNode runs = getJson(admin, "/api/test-cases/" + id + "/runs");
    assertThat(runs.size()).isEqualTo(3);
    assertThat(runResults(runs))
        .as("列表按 id 倒序，最近一次在最前")
        .containsExactly("PASS", "FAIL", "PASS");

    // 删掉最新那条（列表按 id 倒序，第 0 条即最后一次 PASS）
    long latestRun = runs.get(0).path("id").asLong();
    mvc.perform(
            delete("/api/test-cases/" + id + "/runs/" + latestRun)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());

    JsonNode after = getJson(admin, "/api/test-cases/" + id);
    assertThat(after.path("runCount").asInt()).isEqualTo(2);
    assertThat(after.path("passCount").asInt()).isEqualTo(1);
    assertThat(after.path("failCount").asInt()).isEqualTo(1);
    assertThat(after.path("lastResult").asText()).isEqualTo("FAIL");
    assertThat(getJson(admin, "/api/test-cases/" + id + "/runs").size()).isEqualTo(2);

    // 删用例 → 详情与执行记录一并 404
    mvc.perform(
            delete("/api/test-cases/" + id)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/test-cases/" + id).cookie(admin)).andExpect(status().isNotFound());
    mvc.perform(get("/api/test-cases/" + id + "/runs").cookie(admin))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("筛选：module 精确匹配；关键字里 % 与 _ 被转义，不放大成全表匹配")
  void filtersAndLikeEscaping() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long pct = createCase(admin, csrf, "支付", "支付成功率 100%达标", null, null);
    long plain = createCase(admin, csrf, "支付", "支付成功率 100 达标", null, null);
    long underscore = createCase(admin, csrf, "接口", "字段 AB_CD 校验", null, null);
    long noise = createCase(admin, csrf, "接口", "字段 ABXCD 校验", null, null);
    long ungrouped = createCase(admin, csrf, "", "未分组用例", null, null);

    assertThat(caseIds(query(admin, "module", "支付")))
        .containsExactlyInAnyOrder(pct, plain);
    assertThat(query(admin, "module", "支付").path("total").asInt()).isEqualTo(2);

    // 100% 里的 % 是字面量：只有真的带 % 的标题才命中
    assertThat(caseIds(query(admin, "q", "100%")))
        .as("% 必须被转义，否则 '100%' 会退化成 '100' + 任意后缀")
        .containsExactly(pct)
        .doesNotContain(plain);

    // _ 同理：AB_CD 只命中字面量，不会命中 ABXCD
    assertThat(caseIds(query(admin, "q", "AB_CD")))
        .containsExactly(underscore)
        .doesNotContain(noise);

    // 未分组用例（module 空串）在默认列表里正常出现，module 字段就是空串
    JsonNode ungroupedPage = query(admin, "q", "未分组用例");
    assertThat(caseIds(ungroupedPage)).containsExactly(ungrouped);
    assertThat(ungroupedPage.path("items").get(0).path("module").asText()).isEmpty();
    assertThat(caseIds(ungroupedPage)).doesNotContain(pct);
  }

  @Test
  @DisplayName("非法输入：未知优先级/状态/结果与空标题一律 400")
  void invalidInputReturns400() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    long id = createCase(admin, csrf, "校验", "校验用例", null, null);

    assertBadRequest(postCase(admin, csrf, "{\"title\":\"t\",\"priority\":\"P9\"}"), 400);
    assertBadRequest(postCase(admin, csrf, "{\"title\":\"t\",\"status\":\"RUNNING\"}"), 400);
    assertBadRequest(postCase(admin, csrf, "{\"title\":\"  \"}"), 400);
    assertBadRequest(postRun(admin, csrf, id, "{\"result\":\"MAYBE\"}"), 400);
    assertBadRequest(postRun(admin, csrf, id, "{\"result\":\"  \"}"), 400);

    // 非法状态当筛选值同样 400
    mvc.perform(get("/api/test-cases").param("status", "RUNNING").cookie(admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("写操作进审计：targetType = test-cases，targetId = 用例 id")
  void writesAreAudited() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    long id = createCase(admin, csrf, "审计", "被审计的用例", null, null);

    JsonNode created = latestAudit(admin);
    assertThat(created.path("action").asText()).isEqualTo("POST /api/test-cases");
    assertThat(created.path("targetType").asText()).isEqualTo("test-cases");
    assertThat(created.path("targetId").isNull())
        .as("创建请求路径里没有用例 id，targetId 为空")
        .isTrue();
    assertThat(created.path("detail").asText()).contains("status=201");

    // 更新请求路径带 id → targetId 落到用例 id
    mvc.perform(
            put("/api/test-cases/" + id)
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"module\":\"审计\",\"title\":\"被审计的用例\"}"))
        .andExpect(status().isOk());

    JsonNode updated = latestAudit(admin);
    assertThat(updated.path("action").asText()).isEqualTo("PUT /api/test-cases/" + id);
    assertThat(updated.path("targetType").asText()).isEqualTo("test-cases");
    assertThat(updated.path("targetId").asText()).isEqualTo(String.valueOf(id));
  }

  /* --------------------------------------------------------------- helpers */

  private void assertBadRequest(MvcResult result, int expected) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(expected);
  }

  private MvcResult postCase(Cookie session, Cookie csrf, String body) throws Exception {
    return mvc
        .perform(
            post("/api/test-cases")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private MvcResult postRun(Cookie session, Cookie csrf, long caseId, String body)
      throws Exception {
    return mvc
        .perform(
            post("/api/test-cases/" + caseId + "/runs")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private long createCase(
      Cookie session, Cookie csrf, String module, String title, String priority, String status)
      throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("module", module);
    body.put("title", title);
    if (priority != null) {
      body.put("priority", priority);
    }
    if (status != null) {
      body.put("status", status);
    }
    MvcResult result =
        mvc.perform(
                post("/api/test-cases")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return readTree(result).path("id").asLong();
  }

  private void recordRun(Cookie session, Cookie csrf, long caseId, String result, String note)
      throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("result", result);
    body.put("note", note);
    mvc.perform(
            post("/api/test-cases/" + caseId + "/runs")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());
  }

  private void createUser(Cookie session, Cookie csrf, String login, String password, String role)
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
                        + "\",\"role\":\""
                        + role
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  /** 单参数筛选查询（module / q / status 共用）。 */
  private JsonNode query(Cookie session, String key, String value) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/test-cases").cookie(session).param(key, value))
            .andExpect(status().isOk())
            .andReturn();
    return readTree(result);
  }

  private List<Long> caseIds(JsonNode page) {
    List<Long> ids = new ArrayList<>();
    for (JsonNode row : page.path("items")) {
      ids.add(row.path("id").asLong());
    }
    return ids;
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode row : array) {
      values.add(row.asText());
    }
    return values;
  }

  private static List<String> runResults(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode row : array) {
      values.add(row.path("result").asText());
    }
    return values;
  }

  private JsonNode getJson(Cookie session, String url) throws Exception {
    MvcResult result = mvc.perform(get(url).cookie(session)).andExpect(status().isOk()).andReturn();
    return readTree(result);
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode latestAudit(Cookie session) throws Exception {
    JsonNode items = getJson(session, "/api/admin/audit?limit=1").path("items");
    assertThat(items.size()).as("审计表不应为空").isPositive();
    return items.get(0);
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
      return Files.createTempDirectory("qa-kb-testcase-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}

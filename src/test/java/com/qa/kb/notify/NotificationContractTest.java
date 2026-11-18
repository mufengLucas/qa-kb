package com.qa.kb.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.kb.db.MySqlTestDatabase;
import com.qa.kb.security.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 站内通知端到端契约（P2 Phase 5）：真实过滤链 + 真实 MySQL（一次性 schema）。
 *
 * <p>覆盖：扫描幂等生成周报提醒与逾期任务提醒、收件箱读取与未读数、标记已读的归属校验
 * （他人 404）、VIEWER 可清自己的红点、未登录 401、缺 CSRF 403、通知写操作不进审计。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("通知契约：扫描生成 / 收件箱 / 已读归属 / 权限")
// 同一 schema 内方法共享数据：先建周报提醒（幂等基准），再建逾期，最后做已读/权限断言
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationContractTest {

  private static final String ADMIN_PASSWORD = "NotifyTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    MySqlTestDatabase.register(registry, "notify");
    registry.add("kb.seed.enabled", () -> "true");
    registry.add("kb.seed.demo", () -> "false");
    registry.add("kb.seed.admin-password", () -> ADMIN_PASSWORD);
    registry.add("kb.backup.enabled", () -> "false");
    // 扫描由测试显式触发（POST /api/notifications/scan），不等 cron
    registry.add("kb.notify.enabled", () -> "false");
    registry.add("logging.file.name", () -> TEMP_DIR.resolve("kb-notify-test.log").toString());
  }

  @Test
  @Order(1)
  @DisplayName("扫描：幂等生成周报提醒，重复执行不产生重复通知")
  void scanIsIdempotentForWeeklyReminder() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    // 测试库只有 admin 一个启用成员且没写过本周周报 → 首次扫描生成 1 条
    JsonNode first = postScan(admin, csrf);
    assertThat(first.path("weeklyReminders").asInt()).isEqualTo(1);
    // A2 外发的原料也随响应返回：本次真正新增的明细
    assertThat(first.path("newNotices").size()).isEqualTo(1);
    assertThat(first.path("newNotices").get(0).path("type").asText()).isEqualTo("weekly_report");

    JsonNode inbox = getInbox(admin);
    assertThat(inbox.path("unread").asLong()).isEqualTo(1);
    JsonNode latest = inbox.path("items").get(0);
    assertThat(latest.path("type").asText()).isEqualTo("weekly_report");
    assertThat(latest.path("title").asText()).isEqualTo("周报提醒");
    assertThat(latest.path("body").asText()).contains("周报尚未提交");
    assertThat(latest.path("readAt").isNull()).isTrue();

    // 幂等：第二次扫描 0 条，收件箱仍只有 1 条，也没有可供外发的新增
    JsonNode second = postScan(admin, csrf);
    assertThat(second.path("weeklyReminders").asInt()).isZero();
    assertThat(second.path("newNotices").size()).isZero();
    assertThat(getInbox(admin).path("items").size()).isEqualTo(1);
  }

  @Test
  @Order(2)
  @DisplayName("扫描：逾期任务通知负责人；未逾期 / 已完成不产生")
  void scanNotifiesAssigneeForOverdueTaskOnly() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    createUser(admin, csrf, "notifymember", "NotifyMem@2026", "MEMBER");
    long memberId = userId(admin, "notifymember");

    long projectId = createProject(admin, csrf, "通知项目", "NOTIFY01");
    // 截止昨天、状态进行中 → 逾期；负责人 = 成员
    createTask(
        admin,
        csrf,
        projectId,
        "逾期的联调任务",
        memberId,
        LocalDate.now().minusDays(1));

    JsonNode result = postScan(admin, csrf);
    assertThat(result.path("overdueTasks").asInt()).isEqualTo(1);

    // 通知落在负责人收件箱，不在触发者（owner=admin）收件箱
    Cookie member = login("notifymember", "NotifyMem@2026");
    JsonNode memberInbox = getInbox(member);
    // 成员也会收到本周周报提醒 → 未读 2 条；逾期提醒 id 更新、排在最前
    assertThat(memberInbox.path("unread").asLong()).isEqualTo(2);
    JsonNode overdue = memberInbox.path("items").get(0);
    assertThat(overdue.path("type").asText()).isEqualTo("task_overdue");
    assertThat(overdue.path("title").asText()).isEqualTo("任务逾期");
    assertThat(overdue.path("body").asText())
        .contains("逾期的联调任务")
        .contains("通知项目");
    assertThat(overdue.path("refType").asText()).isEqualTo("project");
    assertThat(overdue.path("refId").asLong()).isEqualTo(projectId);

    // admin 只有周报提醒（任务有启用负责人，不回落 owner）
    JsonNode adminTypes = getInbox(admin);
    assertThat(adminTypes.path("unread").asLong()).isEqualTo(1);
    assertThat(adminTypes.path("items").get(0).path("type").asText())
        .isEqualTo("weekly_report");

    // 改为已完成 → 再扫不新增
    patchTaskStatus(admin, csrf, taskIdOf(admin, projectId), "done");
    JsonNode rescan = postScan(admin, csrf);
    assertThat(rescan.path("overdueTasks").asInt()).isZero();
    // 成员收件箱仍是周报提醒 + 逾期提醒两条，没有因重复扫描而膨胀
    assertThat(getInbox(member).path("items").size()).isEqualTo(2);
  }

  @Test
  @Order(3)
  @DisplayName("已读归属：自己的 200 清零，他人的 404（不泄露存在性）")
  void markReadEnforcesOwnership() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    createUser(admin, csrf, "readmember", "ReadMem@2026", "MEMBER");
    postScan(admin, csrf);

    JsonNode adminInbox = getInbox(admin);
    long adminNoticeId = adminInbox.path("items").get(0).path("id").asLong();
    assertThat(adminInbox.path("unread").asLong()).isEqualTo(1);

    // 成员试图清 admin 的通知 → 404
    Cookie member = login("readmember", "ReadMem@2026");
    mvc.perform(
            post("/api/notifications/" + adminNoticeId + "/read")
                .cookie(member, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNotFound());
    assertThat(getInbox(admin).path("unread").asLong())
        .as("他人标记失败后未读数不变")
        .isEqualTo(1);

    // admin 自己标记 → 200，未读清零
    mvc.perform(
            post("/api/notifications/" + adminNoticeId + "/read")
                .cookie(admin, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isOk());
    JsonNode afterRead = getInbox(admin);
    assertThat(afterRead.path("unread").asLong()).isZero();
    assertThat(afterRead.path("items").get(0).path("readAt").isNull()).isFalse();

    // read-all 幂等：没有未读时照样 200
    mvc.perform(post("/api/notifications/read-all").cookie(admin, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isOk());
  }

  @Test
  @Order(4)
  @DisplayName("VIEWER 可读自己的收件箱并清红点，但不能触发扫描")
  void viewerCanReadAndClearOwnInboxButNotScan() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    createUser(admin, csrf, "notifyviewer", "NotifyV@2026", "VIEWER");
    postScan(admin, csrf);

    Cookie viewer = login("notifyviewer", "NotifyV@2026");
    // 周报提醒发给所有启用成员 → viewer 也有 1 条未读
    JsonNode inbox = getInbox(viewer);
    assertThat(inbox.path("unread").asLong()).isEqualTo(1);
    long id = inbox.path("items").get(0).path("id").asLong();

    // 清自己的红点是个人状态写入，不按内容写权限拦（403 在这里是错的）
    mvc.perform(post("/api/notifications/" + id + "/read")
            .cookie(viewer, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isOk());

    // 扫描会为全员生成数据 → 仍需 ADMIN/MEMBER
    mvc.perform(post("/api/notifications/scan")
            .cookie(viewer, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(5)
  @DisplayName("安全：未登录 401；缺 CSRF 403")
  void securityBaseline() throws Exception {
    mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());

    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    // 不带 X-XSRF-TOKEN 头 → CSRF 拒绝
    mvc.perform(post("/api/notifications/read-all").cookie(admin))
        .andExpect(status().isForbidden());
    // 带了过期/伪造 token 同样拒绝
    mvc.perform(post("/api/notifications/read-all").cookie(admin)
            .header("X-XSRF-TOKEN", "not-a-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(6)
  @DisplayName("审计：通知的写操作（扫描/标记已读）不进 audit_log")
  void notificationWritesAreNotAudited() throws Exception {
    Cookie admin = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    postScan(admin, csrf);
    JsonNode inbox = getInbox(admin);
    long id = inbox.path("items").get(0).path("id").asLong();
    mvc.perform(post("/api/notifications/" + id + "/read")
            .cookie(admin, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/notifications/read-all").cookie(admin, csrf)
            .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isOk());

    JsonNode latest = latestAudit(admin);
    assertThat(latest.path("action").asText())
        .as("通知写操作只是个人收件箱状态，不应产生审计噪音")
        .doesNotStartWith("POST /api/notifications");
  }

  /* --------------------------------------------------------------- helpers */

  private JsonNode postScan(Cookie session, Cookie csrf) throws Exception {
    MvcResult result =
        mvc.perform(post("/api/notifications/scan")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isOk())
            .andReturn();
    return readTree(result);
  }

  private JsonNode getInbox(Cookie session) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/notifications").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    return readTree(result);
  }

  private long taskIdOf(Cookie session, long projectId) throws Exception {
    JsonNode detail =
        readTree(
            mvc.perform(get("/api/projects/" + projectId).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
    return detail.path("tasks").get(0).path("task").path("id").asLong();
  }

  private void patchTaskStatus(Cookie session, Cookie csrf, long taskId, String status)
      throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/tasks/" + taskId + "/status")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"))
        .andExpect(status().isOk());
  }

  private long createProject(Cookie session, Cookie csrf, String name, String key)
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
    return readTree(result).path("id").asLong();
  }

  private void createTask(
      Cookie session,
      Cookie csrf,
      long projectId,
      String title,
      long assigneeId,
      LocalDate dueDate)
      throws Exception {
    mvc.perform(
            post("/api/projects/" + projectId + "/tasks")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\""
                        + title
                        + "\",\"assigneeId\":"
                        + assigneeId
                        + ",\"dueDate\":\""
                        + dueDate
                        + "\"}"))
        .andExpect(status().isCreated());
  }

  private long userId(Cookie session, String login) throws Exception {
    MvcResult result =
        mvc.perform(get("/api/users").cookie(session)).andExpect(status().isOk()).andReturn();
    for (JsonNode user : readTree(result)) {
      if (user.path("login").asText().equals(login)) {
        return user.path("id").asLong();
      }
    }
    throw new AssertionError("找不到用户 " + login);
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

  private JsonNode latestAudit(Cookie session) throws Exception {
    JsonNode items = getJson(session, "/api/admin/audit?limit=1").path("items");
    assertThat(items.size()).as("审计表不应为空").isPositive();
    return items.get(0);
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
      return Files.createTempDirectory("qa-kb-notify-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}

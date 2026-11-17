package com.qa.kb.notify;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.NotificationRow;
import com.qa.kb.persistence.NotificationMapper;
import com.qa.kb.persistence.NotificationMapper.OverdueTask;
import com.qa.kb.persistence.ReportMapper;
import com.qa.kb.project.ProgressCalculator;
import com.qa.kb.report.ReportWeekCalculator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 站内通知（P2 Phase 5 最小集：周报提醒 + 任务逾期）。
 *
 * <p>两类通知都由每日扫描生成，靠 {@code (user_id, dedup_key)} 唯一键幂等：周报提醒
 * {@code weekly:<周一>} 每周每人一条，任务逾期 {@code overdue:<taskId>:<dueDate>} 每个
 * 截止日一条——扫描重复执行（或服务重启补跑）都不会产生重复行。
 *
 * <p>逾期判定的唯一口径是 {@link ProgressCalculator#isOverdue}：SQL 只做粗筛拉候选行，
 * 最终以它为准，与项目页「逾期」标记保持一致。
 */
public class NotificationService {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  public static final String TYPE_WEEKLY_REPORT = "weekly_report";
  public static final String TYPE_TASK_OVERDUE = "task_overdue";

  private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日");

  private final NotificationMapper notificationMapper;
  private final ReportMapper reportMapper;
  private final Clock clock;

  public NotificationService(
      NotificationMapper notificationMapper, ReportMapper reportMapper, Clock clock) {
    this.notificationMapper = notificationMapper;
    this.reportMapper = reportMapper;
    this.clock = clock;
  }

  /** 列表结果：未读数 + 当前页（未读在前，同状态按 id 倒序）。 */
  public record NotificationPage(long unread, List<NotificationRow> items) {}

  /** 一次扫描的产出，供调度日志与测试断言。 */
  public record ScanResult(int weeklyReminders, int overdueTasks) {}

  /* ------------------------------------------------------------------ 读取 */

  public NotificationPage list(long userId, Integer limit) {
    int cleanLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    return new NotificationPage(
        notificationMapper.countUnread(userId),
        notificationMapper.listByUser(userId, cleanLimit));
  }

  /** 标记单条已读。不属于当前用户一律 404（不泄露他人通知是否存在）。 */
  public void markRead(long userId, long id) {
    if (notificationMapper.countOwned(id, userId) == 0) {
      throw ApiException.notFound("通知不存在");
    }
    notificationMapper.markRead(id, userId, LocalDateTime.now(clock));
  }

  /** 全部标记已读，返回受影响条数（已读过的不重复计）。 */
  public long markAllRead(long userId) {
    return notificationMapper.markAllRead(userId, LocalDateTime.now(clock));
  }

  /* ------------------------------------------------------------------ 扫描 */

  /**
   * 每日扫描：先补本周周报提醒，再补逾期任务提醒。幂等，可重复调用。
   *
   * @param today 业务日期（由调度器用业务时区的 Clock 取，测试可注入任意日期）
   */
  public ScanResult scan(LocalDate today) {
    if (today == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    return new ScanResult(remindMissingWeekly(today), remindOverdueTasks(today));
  }

  /**
   * 周报提醒：本周（周一为 weekStart）还没提交周报的启用成员，每人一条。
   *
   * <p>复用 {@code /api/reports/missing?week=} 同一个查询，通知名单与周报页展示的
   * 「未提交名单」口径完全一致；dedup 键按周，周内重复扫描不会重复提醒。
   */
  private int remindMissingWeekly(LocalDate today) {
    LocalDate weekStart = ReportWeekCalculator.weekOf(today).start();
    String weekLabel =
        weekStart.format(DAY_FORMAT) + " ~ " + weekStart.plusDays(6).format(DAY_FORMAT);
    int created = 0;
    for (Long userId : reportMapper.missingUserIds(weekStart)) {
      created +=
          notificationMapper.insertIfAbsent(
              userId,
              TYPE_WEEKLY_REPORT,
              "周报提醒",
              "本周（" + weekLabel + "）周报尚未提交，请到「周报」页填写。",
              "week",
              weekStart.toEpochDay(),
              "weekly:" + weekStart);
    }
    return created;
  }

  /**
   * 任务逾期：跨项目拉未完成且截止日早于今天的任务，给负责人（无负责人回落项目 owner）
   * 各生成一条。收件人已停用时回落 owner；两者都不可用则跳过。
   *
   * <p>dedup 键含 dueDate：改期后再次逾期会重新提醒，持续逾期不会每天刷屏。
   */
  private int remindOverdueTasks(LocalDate today) {
    Set<Long> activeUsers = activeUserIds();
    int created = 0;
    for (OverdueTask task : notificationMapper.listOverdueTasks(today)) {
      // SQL 已粗筛 status <> 'done' 且 due_date < today；这里用唯一口径复核。
      if (!ProgressCalculator.isOverdue(task.status(), task.dueDate(), today)) {
        continue;
      }
      Long recipient = resolveRecipient(task, activeUsers);
      if (recipient == null) {
        continue;
      }
      String body =
          "任务「"
              + task.title()
              + "」（"
              + task.projectName()
              + "）已逾期，截止 "
              + task.dueDate().format(DAY_FORMAT)
              + "。";
      created +=
          notificationMapper.insertIfAbsent(
              recipient,
              TYPE_TASK_OVERDUE,
              "任务逾期",
              body,
              "project",
              task.projectId(),
              "overdue:" + task.taskId() + ":" + task.dueDate());
    }
    return created;
  }

  /** 收件人：启用的负责人优先，其次启用的项目 owner，都没有则跳过。 */
  private static Long resolveRecipient(OverdueTask task, Set<Long> activeUsers) {
    if (task.assigneeId() != null && activeUsers.contains(task.assigneeId())) {
      return task.assigneeId();
    }
    if (activeUsers.contains(task.ownerId())) {
      return task.ownerId();
    }
    return null;
  }

  private Set<Long> activeUserIds() {
    // 扫描每天最多一次、团队规模有限：一次性取启用成员 id 做内存判定，避免逐行查库。
    return Set.copyOf(notificationMapper.listActiveUserIds());
  }
}

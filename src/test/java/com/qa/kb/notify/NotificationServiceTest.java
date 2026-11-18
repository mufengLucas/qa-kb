package com.qa.kb.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.NotificationRow;
import com.qa.kb.persistence.NotificationMapper;
import com.qa.kb.persistence.NotificationMapper.OverdueTask;
import com.qa.kb.persistence.ReportMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService")
class NotificationServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  /** 2026-11-16 是周一：周报 dedup 键与逾期日期都可用它推算。 */
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

  @Mock private NotificationMapper notificationMapper;
  @Mock private ReportMapper reportMapper;

  private NotificationService service;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-16T02:00:00Z"), ZONE);
    service = new NotificationService(notificationMapper, reportMapper, clock);
  }

  private static NotificationRow row(long id, long userId, LocalDateTime readAt) {
    return new NotificationRow(
        id, userId, "weekly_report", "周报提醒", "本周周报尚未提交", "week", 46970,
        "weekly:2026-11-16", readAt, LocalDateTime.of(2026, 11, 16, 9, 0));
  }

  private static OverdueTask overdue(
      long taskId, String status, LocalDate dueDate, Long assigneeId, long ownerId) {
    return new OverdueTask(
        taskId, "登录联调", status, dueDate, assigneeId, ownerId, 7L, "支付平台");
  }

  /* ------------------------------------------------------------------ 读取 */

  @Test
  @DisplayName("列表：limit 缺省 50、越界夹取到 [1,200]，未读数一并返回")
  void listClampsLimitAndCarriesUnread() {
    when(notificationMapper.countUnread(1L)).thenReturn(3L);
    when(notificationMapper.listByUser(1L, 50)).thenReturn(List.of(row(9L, 1L, null)));

    NotificationService.NotificationPage page = service.list(1L, null);

    assertThat(page.unread()).isEqualTo(3);
    assertThat(page.items()).hasSize(1);

    when(notificationMapper.listByUser(1L, 200)).thenReturn(List.of());
    assertThat(service.list(1L, 9999).items()).isEmpty();
    when(notificationMapper.listByUser(1L, 1)).thenReturn(List.of());
    assertThat(service.list(1L, -3).items()).isEmpty();
    verify(notificationMapper).listByUser(1L, 1);
  }

  @Test
  @DisplayName("标记已读：他人通知 404（不泄露存在性），自己的正常落库")
  void markReadChecksOwnership() {
    when(notificationMapper.countOwned(9L, 1L)).thenReturn(1L);
    when(notificationMapper.markRead(eq(9L), eq(1L), any())).thenReturn(1);

    service.markRead(1L, 9L);

    verify(notificationMapper).markRead(eq(9L), eq(1L), any(LocalDateTime.class));

    when(notificationMapper.countOwned(9L, 2L)).thenReturn(0L);
    assertThatThrownBy(() -> service.markRead(2L, 9L))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("通知不存在");
    verify(notificationMapper, never()).markRead(eq(9L), eq(2L), any());
  }

  @Test
  @DisplayName("全部已读：返回受影响条数")
  void markAllReadReturnsAffectedCount() {
    when(notificationMapper.markAllRead(eq(1L), any())).thenReturn(5);

    assertThat(service.markAllRead(1L)).isEqualTo(5);
  }

  /* ------------------------------------------------------------------ 扫描 */

  @Test
  @DisplayName("扫描：日期为空直接 400，不碰库")
  void scanRejectsNullDate() {
    assertThatThrownBy(() -> service.scan(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("日期");
    verify(notificationMapper, never()).insertIfAbsent(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("周报提醒：本周未提交成员各一条，dedup 键 = weekly:<周一>")
  void weeklyReminderTargetsMissingUsers() {
    when(reportMapper.missingUserIds(MONDAY)).thenReturn(List.of(2L, 3L));
    when(notificationMapper.insertIfAbsent(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(1);

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.weeklyReminders()).isEqualTo(2);
    assertThat(result.overdueTasks()).isZero();
    // A2 外发的原料：新增明细带上收件人与正文
    assertThat(result.newNotices()).hasSize(2);
    NotificationService.NewNotice first = result.newNotices().get(0);
    assertThat(first.userId()).isEqualTo(2L);
    assertThat(first.type()).isEqualTo(NotificationService.TYPE_WEEKLY_REPORT);
    assertThat(first.title()).isEqualTo("周报提醒");
    assertThat(first.body()).contains("周报尚未提交");
    verify(notificationMapper)
        .insertIfAbsent(
            eq(2L),
            eq(NotificationService.TYPE_WEEKLY_REPORT),
            eq("周报提醒"),
            anyString(),
            eq("week"),
            eq(MONDAY.toEpochDay()),
            eq("weekly:" + MONDAY));
    // 扫描日期是周内任意一天，dedup 键都落在本周一
    clearInvocations(notificationMapper);
    service.scan(MONDAY.plusDays(3));
    verify(notificationMapper)
        .insertIfAbsent(
            eq(2L), anyString(), anyString(), anyString(), anyString(), anyLong(),
            eq("weekly:" + MONDAY));
  }

  @Test
  @DisplayName("周报提醒：全员已提交时不产生任何通知")
  void weeklyReminderSkipsWhenEveryoneSubmitted() {
    when(reportMapper.missingUserIds(any())).thenReturn(List.of());

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.weeklyReminders()).isZero();
    verify(notificationMapper, never()).insertIfAbsent(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("逾期任务：通知启用的负责人，dedup 键 = overdue:<taskId>:<dueDate>")
  void overdueTaskNotifiesActiveAssignee() {
    when(reportMapper.missingUserIds(any())).thenReturn(List.of());
    when(notificationMapper.listActiveUserIds()).thenReturn(List.of(1L, 4L));
    when(notificationMapper.listOverdueTasks(MONDAY))
        .thenReturn(List.of(overdue(11L, "doing", MONDAY.minusDays(2), 4L, 1L)));
    when(notificationMapper.insertIfAbsent(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(1);

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.overdueTasks()).isEqualTo(1);
    assertThat(result.newNotices()).hasSize(1);
    assertThat(result.newNotices().get(0).userId()).isEqualTo(4L);
    assertThat(result.newNotices().get(0).type()).isEqualTo(NotificationService.TYPE_TASK_OVERDUE);
    verify(notificationMapper)
        .insertIfAbsent(
            eq(4L),
            eq(NotificationService.TYPE_TASK_OVERDUE),
            eq("任务逾期"),
            anyString(),
            eq("project"),
            eq(7L),
            eq("overdue:11:" + MONDAY.minusDays(2)));
  }

  @Test
  @DisplayName("逾期任务：负责人缺省或停用回落项目 owner；都不可用则跳过")
  void overdueTaskFallsBackToOwner() {
    when(reportMapper.missingUserIds(any())).thenReturn(List.of());
    // 启用成员只有 owner 1：任务 11 有启用负责人 4 → 给 4；任务 12 负责人为空 → 给 owner；
    // 任务 13 负责人 9 已停用且 owner 8 也停用 → 跳过
    when(notificationMapper.listActiveUserIds()).thenReturn(List.of(1L, 4L));
    when(notificationMapper.listOverdueTasks(MONDAY))
        .thenReturn(
            List.of(
                overdue(11L, "todo", MONDAY.minusDays(1), 4L, 1L),
                overdue(12L, "blocked", MONDAY.minusDays(3), null, 1L),
                overdue(13L, "todo", MONDAY.minusDays(1), 9L, 8L)));
    when(notificationMapper.insertIfAbsent(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(1);

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.overdueTasks()).isEqualTo(2);
    verify(notificationMapper).insertIfAbsent(eq(1L), anyString(), anyString(), anyString(),
        anyString(), anyLong(), anyString());
    verify(notificationMapper).insertIfAbsent(eq(4L), anyString(), anyString(), anyString(),
        anyString(), anyLong(), anyString());
    verify(notificationMapper, never())
        .insertIfAbsent(eq(8L), anyString(), anyString(), anyString(), anyString(), anyLong(),
            anyString());
    verify(notificationMapper, never())
        .insertIfAbsent(eq(9L), anyString(), anyString(), anyString(), anyString(), anyLong(),
            anyString());
  }

  @Test
  @DisplayName("逾期口径与项目页一致：done 或截止日不早于今天一律不通知")
  void overdueUsesProgressCalculatorAsSingleSourceOfTruth() {
    when(reportMapper.missingUserIds(any())).thenReturn(List.of());
    when(notificationMapper.listActiveUserIds()).thenReturn(List.of(1L, 4L));
    // SQL 粗筛可能带回边界行（如状态刚被并发改写），最终判定只认 ProgressCalculator
    when(notificationMapper.listOverdueTasks(MONDAY))
        .thenReturn(
            List.of(
                overdue(21L, "done", MONDAY.minusDays(5), 4L, 1L),
                overdue(22L, "doing", MONDAY, 4L, 1L)));

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.overdueTasks()).isZero();
    verify(notificationMapper, never()).insertIfAbsent(
        anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("幂等：插入返回 0（已存在）计入产出为 0，不视为错误")
  void scanCountsOnlyFreshInserts() {
    when(reportMapper.missingUserIds(any())).thenReturn(List.of(2L));
    when(notificationMapper.listActiveUserIds()).thenReturn(List.of(1L));
    when(notificationMapper.listOverdueTasks(MONDAY)).thenReturn(List.of());
    when(notificationMapper.insertIfAbsent(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(0);

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.weeklyReminders()).isZero();
    assertThat(result.newNotices()).as("dedup 命中的不算新增，外发汇总不会重发").isEmpty();
  }

  @Test
  @DisplayName("新增明细只收真正落库的：同批里 dedup 返回 0 的那条不进 ScanResult")
  void newNoticesOnlyIncludeFreshInserts() {
    when(reportMapper.missingUserIds(any())).thenReturn(List.of(2L, 3L));
    when(notificationMapper.insertIfAbsent(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(1, 0);

    NotificationService.ScanResult result = service.scan(MONDAY);

    assertThat(result.weeklyReminders()).isEqualTo(1);
    assertThat(result.newNotices()).hasSize(1);
    assertThat(result.newNotices().get(0).userId()).isEqualTo(2L);
  }
}

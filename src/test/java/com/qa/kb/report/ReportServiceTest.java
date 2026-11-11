package com.qa.kb.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.model.ReportItemRow;
import com.qa.kb.model.ReportProjectLink;
import com.qa.kb.model.ReportRow;
import com.qa.kb.model.ReportWithUser;
import com.qa.kb.model.TaskRow;
import com.qa.kb.persistence.ProjectMapper;
import com.qa.kb.persistence.ReportItemMapper;
import com.qa.kb.persistence.ReportMapper;
import com.qa.kb.project.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService")
class ReportServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

  @Mock private ReportMapper reportMapper;
  @Mock private ReportItemMapper reportItemMapper;
  @Mock private ProjectMapper projectMapper;
  @Mock private ProjectService projectService;

  private ReportService service;

  @BeforeEach
  void setUp() {
    service = new ReportService(reportMapper, reportItemMapper, projectMapper, projectService, CLOCK);
  }

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 11, 9);

  private static ReportItemRow itemRow(long id, Long taskId, boolean done) {
    return itemRow(id, taskId, taskId == null ? null : 1L, done);
  }

  private static ReportItemRow itemRow(long id, Long taskId, Long projectId, boolean done) {
    return new ReportItemRow(
        id,
        77L,
        "条目",
        done,
        taskId,
        projectId,
        1,
        LocalDateTime.of(2026, 11, 5, 10, 0),
        LocalDateTime.of(2026, 11, 5, 10, 0));
  }

  private static TaskRow taskRow(long id) {
    return new TaskRow(
        id, 1L, null, "关联任务", null, "todo", 2, null, null, 0, LocalDateTime.now());
  }

  private static ReportRow report(
      long id, long userId, LocalDate date, String done, String blockers, String status) {
    return new ReportRow(
        id,
        userId,
        date,
        done,
        "plan",
        blockers,
        status,
        LocalDateTime.of(2026, 11, 5, 10, 0),
        LocalDateTime.of(2026, 11, 5, 10, 0));
  }

  private static ProjectRow project(long id) {
    return new ProjectRow(
        id, "P" + id, "K" + id, 1L, "ACTIVE", null, null, "");
  }

  // ---------- saveDaily ----------

  @Test
  void saveDailyRejectsNullDate() {
    assertThatThrownBy(() -> service.saveDaily(1L, null, "d", "p", "b", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(reportMapper, never()).upsert(anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void saveDailyRejectsFutureDate() {
    assertThatThrownBy(
            () -> service.saveDaily(1L, TODAY.plusDays(1), "d", "p", "b", "submitted", List.of()))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(reportMapper, never()).upsert(anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void saveDailyAllowsTodayAndBackfill() {
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));
    when(reportMapper.findIdByUserAndDate(1L, TODAY.minusDays(3))).thenReturn(Optional.of(8L));

    service.saveDaily(1L, TODAY, "d", "p", "b", "submitted", List.of());
    service.saveDaily(1L, TODAY.minusDays(3), "d", "p", "b", "submitted", List.of());

    verify(reportMapper, times(2)).upsert(eq(1L), any(), eq("d"), eq("p"), eq("b"), eq("submitted"));
  }

  @Test
  void saveDailyDefaultsBlankStatusToDraft() {
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));

    service.saveDaily(1L, TODAY, "d", "p", "b", "  ", List.of());

    verify(reportMapper).upsert(1L, TODAY, "d", "p", "b", "draft");
  }

  @Test
  void saveDailyNormalizesStatusCase() {
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));

    service.saveDaily(1L, TODAY, "d", "p", "b", "SUBMITTED", List.of());

    verify(reportMapper).upsert(1L, TODAY, "d", "p", "b", "submitted");
  }

  @Test
  void saveDailyRejectsUnknownStatus() {
    assertThatThrownBy(() -> service.saveDaily(1L, TODAY, "d", "p", "b", "approved", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void saveDailyTrimsTextAndTurnsNullIntoEmpty() {
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));

    service.saveDaily(1L, TODAY, "  done  ", null, "\n blocker \n", null, null);

    verify(reportMapper).upsert(1L, TODAY, "done", "", "blocker", "draft");
  }

  @Test
  void saveDailyRejectsOverlongText() {
    String tooLong = "x".repeat(ReportService.MAX_TEXT_LENGTH + 1);

    assertThatThrownBy(() -> service.saveDaily(1L, TODAY, tooLong, "p", "b", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void saveDailyRejectsUnknownProject() {
    when(projectMapper.findById(5L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.saveDaily(1L, TODAY, "d", "p", "b", null, List.of(5L)))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(reportMapper, never()).upsert(anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void saveDailyDedupesProjectIds() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1L)));
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));

    service.saveDaily(1L, TODAY, "d", "p", "b", null, List.of(1L, 1L, 1L));

    verify(reportMapper).deleteProjectLinks(7L);
    verify(reportMapper).insertProjectLink(7L, 1L);
  }

  @Test
  void saveDailyReplacesLinksAfterUpsert() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1L)));
    when(projectMapper.findById(2L)).thenReturn(Optional.of(project(2L)));
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));

    service.saveDaily(1L, TODAY, "d", "p", "b", "submitted", List.of(1L, 2L));

    InOrder order = inOrder(reportMapper);
    order.verify(reportMapper).upsert(1L, TODAY, "d", "p", "b", "submitted");
    order.verify(reportMapper).findIdByUserAndDate(1L, TODAY);
    order.verify(reportMapper).deleteProjectLinks(7L);
    order.verify(reportMapper).insertProjectLink(7L, 1L);
    order.verify(reportMapper).insertProjectLink(7L, 2L);
  }

  @Test
  void saveDailyFailsWhenUpsertDoesNotYieldAnId() {
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.saveDaily(1L, TODAY, "d", "p", "b", null, List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void saveDailyIgnoresNullProjectIds() {
    when(reportMapper.findIdByUserAndDate(1L, TODAY)).thenReturn(Optional.of(7L));

    service.saveDaily(1L, TODAY, "d", "p", "b", null, null);

    verify(reportMapper).deleteProjectLinks(7L);
    verify(reportMapper, never()).insertProjectLink(anyLong(), anyLong());
  }

  // ---------- read side ----------

  @Test
  void findReturnsMapperResult() {
    ReportRow row = report(3, 1, TODAY, "d", "", "draft");
    when(reportMapper.findByUserAndDate(1L, TODAY)).thenReturn(Optional.of(row));

    assertThat(service.find(1L, TODAY)).contains(row);
  }

  @Test
  void reportsOnDelegates() {
    when(reportMapper.listByDate(TODAY))
        .thenReturn(
            List.of(
                new ReportWithUser(
                    1L, 1L, "张三", TODAY, "d", "p", "", "submitted", null, null)));

    assertThat(service.reportsOn(TODAY)).extracting(ReportWithUser::userName).containsExactly("张三");
  }

  @Test
  void missingUsersDelegates() {
    when(reportMapper.missingUserIds(TODAY)).thenReturn(List.of(2L, 3L));

    assertThat(service.missingUsers(TODAY)).containsExactly(2L, 3L);
  }

  @Test
  void betweenDelegates() {
    when(reportMapper.listBetween(TODAY.minusDays(6), TODAY)).thenReturn(List.of());

    assertThat(service.between(TODAY.minusDays(6), TODAY)).isEmpty();
  }

  @Test
  void betweenRejectsInvertedRange() {
    assertThatThrownBy(() -> service.between(TODAY, TODAY.minusDays(1)))
        .isInstanceOf(IllegalArgumentException.class);
    verify(reportMapper, never()).listBetween(any(), any());
  }

  @Test
  void projectIdsOfDelegates() {
    when(reportMapper.listProjectIds(1L, TODAY)).thenReturn(List.of(4L));

    assertThat(service.projectIdsOf(1L, TODAY)).containsExactly(4L);
  }

  // ---------- weekly ----------

  @Test
  void weeklyAggregatesTheMondayToSundayWeekOfTheReferenceDate() {
    LocalDate monday = LocalDate.of(2026, 11, 2);
    LocalDate sunday = LocalDate.of(2026, 11, 8);
    when(reportMapper.listByUserBetween(1L, monday, sunday))
        .thenReturn(
            List.of(
                report(10, 1, monday, "完成登录接口", "等待环境", "submitted"),
                report(11, 1, monday.plusDays(2), "完成搜索", "", "submitted"),
                report(12, 1, monday.plusDays(3), "草稿不算", "", "draft")));
    when(reportMapper.listProjectLinksBetween(1L, monday, sunday))
        .thenReturn(List.of(new ReportProjectLink(10L, 5L), new ReportProjectLink(11L, 6L)));

    WeeklyReportAggregator.WeeklyReport weekly = service.weekly(1L, LocalDate.of(2026, 11, 5));

    assertThat(weekly.range()).isEqualTo(new WeekRange(monday, sunday));
    assertThat(weekly.submittedCount()).isEqualTo(2);
    assertThat(weekly.doneItems()).extracting(WeeklyReportAggregator.ReportLine::text)
        .containsExactly("完成登录接口", "完成搜索");
    assertThat(weekly.projectIds()).containsExactly(5L, 6L);
  }

  @Test
  void weeklyMissingDaysStopAtBusinessToday() {
    LocalDate monday = LocalDate.of(2026, 11, 2);
    LocalDate sunday = LocalDate.of(2026, 11, 8);
    when(reportMapper.listByUserBetween(1L, monday, sunday))
        .thenReturn(List.of(report(10, 1, TODAY, "done", "", "submitted")));
    when(reportMapper.listProjectLinksBetween(1L, monday, sunday)).thenReturn(List.of());

    WeeklyReportAggregator.WeeklyReport weekly = service.weekly(1L, TODAY);

    // Mon 2, Tue 3, Wed 4 are before today (Thu 5) and not submitted. Fri is in the future.
    assertThat(weekly.missingWeekdays())
        .containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4));
  }

  @Test
  void weeklyRejectsNullReference() {
    assertThatThrownBy(() -> service.weekly(1L, null)).isInstanceOf(IllegalArgumentException.class);
    verify(reportMapper, never()).listByUserBetween(anyLong(), any(), any());
  }

  @Test
  void weeklyAcceptsEmptyWeek() {
    LocalDate monday = LocalDate.of(2026, 11, 2);
    when(reportMapper.listByUserBetween(1L, monday, monday.plusDays(6))).thenReturn(List.of());
    when(reportMapper.listProjectLinksBetween(1L, monday, monday.plusDays(6))).thenReturn(List.of());

    WeeklyReportAggregator.WeeklyReport weekly = service.weekly(1L, TODAY);

    assertThat(weekly.submittedReports()).isEmpty();
    assertThat(weekly.submittedCount()).isZero();
  }

  @Test
  void weeklyGroupsProjectLinksPerReport() {
    LocalDate monday = LocalDate.of(2026, 11, 2);
    when(reportMapper.listByUserBetween(1L, monday, monday.plusDays(6)))
        .thenReturn(
            List.of(
                report(10, 1, monday, "a", "", "submitted"),
                report(11, 1, monday.plusDays(1), "b", "", "submitted")));
    when(reportMapper.listProjectLinksBetween(1L, monday, monday.plusDays(6)))
        .thenReturn(List.of(new ReportProjectLink(11L, 9L)));

    WeeklyReportAggregator.WeeklyReport weekly = service.weekly(1L, TODAY);

    assertThat(weekly.projectIds()).containsExactly(9L);
    verify(reportMapper, times(1)).listProjectLinksBetween(eq(1L), eq(monday), any());
  }

  // ---------- saveWeekly（周报保存：周一 / 未来周校验） ----------

  @Test
  void saveWeeklyRejectsNonMonday() {
    LocalDate sunday = MONDAY.plusDays(6);

    assertThatThrownBy(() -> service.saveWeekly(1L, sunday, "", "", "", "draft", null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(reportMapper, never())
        .upsert(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void saveWeeklyRejectsFutureWeek() {
    assertThatThrownBy(() -> service.saveWeekly(1L, NEXT_MONDAY, "", "", "", "draft", null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(reportMapper, never())
        .upsert(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void saveWeeklySavesOnMondayAndRefreshesLinks() {
    when(reportMapper.findIdByUserAndDate(1L, MONDAY)).thenReturn(Optional.of(77L));
    when(projectMapper.findById(3L)).thenReturn(Optional.of(project(3)));

    service.saveWeekly(1L, MONDAY, "本周完成", "下周计划", "", "submitted", List.of(3L));

    InOrder inOrder = inOrder(reportMapper);
    inOrder.verify(reportMapper).upsert(1L, MONDAY, "本周完成", "下周计划", "", "submitted");
    inOrder.verify(reportMapper).deleteProjectLinks(77L);
    inOrder.verify(reportMapper).insertProjectLink(77L, 3L);
  }

  // ---------- 清单读取 ----------

  @Test
  void listItemsReturnsEmptyWhenReportNotYetCreated() {
    when(reportMapper.findIdByUserAndDate(1L, MONDAY)).thenReturn(Optional.empty());

    assertThat(service.listItems(1L, MONDAY)).isEmpty();
    verify(reportItemMapper, never()).listByReport(anyLong());
  }

  @Test
  void listItemsDelegatesForMondayWeek() {
    when(reportMapper.findIdByUserAndDate(1L, MONDAY)).thenReturn(Optional.of(77L));
    when(reportItemMapper.listByReport(77L)).thenReturn(List.of(itemRow(5L, null, false)));

    assertThat(service.listItems(1L, MONDAY)).hasSize(1);
  }

  @Test
  void listItemsRejectsNonMonday() {
    assertThatThrownBy(() -> service.listItems(1L, NEXT_MONDAY.plusDays(1)))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  // ---------- addItem ----------

  @Test
  void addItemRejectsBlankTitle() {
    assertThatThrownBy(() -> service.addItem(1L, MONDAY, "   ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(reportItemMapper, never())
        .insert(anyLong(), anyString(), anyBoolean(), any(), any(), anyInt());
  }

  @Test
  void addItemRejectsNonMonday() {
    assertThatThrownBy(() -> service.addItem(1L, NEXT_MONDAY, "写用例", null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void addItemCreatesLightweightEntryWithoutAnyLink() {
    when(reportMapper.findIdByUserAndDate(1L, MONDAY)).thenReturn(Optional.of(77L));
    when(reportItemMapper.nextSort(77L)).thenReturn(3);
    when(reportItemMapper.lastInsertId()).thenReturn(5L);
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, null, false)));

    ReportItemRow created = service.addItem(1L, MONDAY, "写用例", null, null);

    // 占位行仅在缺失时插入（不覆盖已保存正文），条目不挂任务/项目
    verify(reportMapper).insertIfAbsent(1L, MONDAY);
    verify(reportItemMapper).insert(77L, "写用例", false, null, null, 3);
    verify(projectService, never()).createTask(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any());
    assertThat(created.id()).isEqualTo(5L);
  }

  @Test
  void addItemWithProjectCreatesRealTaskAndBackfillsTaskId() {
    when(reportMapper.findIdByUserAndDate(1L, MONDAY)).thenReturn(Optional.of(77L));
    when(reportItemMapper.nextSort(77L)).thenReturn(1);
    when(reportItemMapper.lastInsertId()).thenReturn(5L);
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, 10L, false)));
    when(projectService.createTask(3L, "周报新任务", null, null, "todo", null, null, null))
        .thenReturn(new ProjectService.TaskView(taskRow(10L), false));

    service.addItem(1L, MONDAY, "周报新任务", 3L, null);

    verify(reportItemMapper).insert(77L, "周报新任务", false, 10L, 3L, 1);
  }

  @Test
  void addItemWithExistingTaskLinksItAndDerivesProject() {
    when(projectMapper.findTask(10L)).thenReturn(Optional.of(taskRow(10L)));
    when(reportMapper.findIdByUserAndDate(1L, MONDAY)).thenReturn(Optional.of(77L));
    when(reportItemMapper.nextSort(77L)).thenReturn(1);
    when(reportItemMapper.lastInsertId()).thenReturn(5L);
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, 10L, false)));

    service.addItem(1L, MONDAY, "挂到既有任务", null, 10L);

    // 挂既有任务：projectId 取任务自身所属项目
    verify(reportItemMapper).insert(77L, "挂到既有任务", false, 10L, 1L, 1);
    verify(projectService, never()).createTask(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any());
  }

  @Test
  void addItemRejectsUnknownTask() {
    when(projectMapper.findTask(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.addItem(1L, MONDAY, "x", null, 404L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(reportItemMapper, never())
        .insert(anyLong(), anyString(), anyBoolean(), any(), any(), anyInt());
  }

  // ---------- updateItem（勾选 ↔ 任务状态同步） ----------

  @Test
  void updateItemCheckingSyncsLinkedTaskToDone() {
    when(reportItemMapper.findByIdForUser(5L, 1L))
        .thenReturn(Optional.of(itemRow(5L, 10L, false)), Optional.of(itemRow(5L, 10L, true)));

    ReportItemRow updated = service.updateItem(1L, 5L, true, null);

    verify(reportItemMapper).updateDone(5L, true);
    verify(projectService).updateTaskStatus(10L, "done");
    assertThat(updated.done()).isTrue();
  }

  @Test
  void updateItemUncheckingRevertsLinkedTaskToDoing() {
    when(reportItemMapper.findByIdForUser(5L, 1L))
        .thenReturn(Optional.of(itemRow(5L, 10L, true)), Optional.of(itemRow(5L, 10L, false)));

    service.updateItem(1L, 5L, false, null);

    verify(reportItemMapper).updateDone(5L, false);
    verify(projectService).updateTaskStatus(10L, "doing");
  }

  @Test
  void updateItemWithoutLinkedTaskSkipsStatusSync() {
    when(reportItemMapper.findByIdForUser(5L, 1L))
        .thenReturn(Optional.of(itemRow(5L, null, false)), Optional.of(itemRow(5L, null, true)));

    service.updateItem(1L, 5L, true, null);

    verify(reportItemMapper).updateDone(5L, true);
    verify(projectService, never()).updateTaskStatus(anyLong(), anyString());
  }

  @Test
  void updateItemCanRenameWithoutTouchingDone() {
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, null, false)));

    service.updateItem(1L, 5L, null, "  新标题  ");

    verify(reportItemMapper).updateTitle(5L, "新标题");
    verify(reportItemMapper, never()).updateDone(anyLong(), anyBoolean());
    verify(projectService, never()).updateTaskStatus(anyLong(), anyString());
  }

  @Test
  void updateItemRejectsBlankTitle() {
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, null, false)));

    assertThatThrownBy(() -> service.updateItem(1L, 5L, null, " "))
        .isInstanceOf(IllegalArgumentException.class);
    verify(reportItemMapper, never()).updateTitle(anyLong(), anyString());
  }

  @Test
  void updateItemOwnedBySomeoneElseReturns404() {
    when(reportItemMapper.findByIdForUser(5L, 2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateItem(2L, 5L, true, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(reportItemMapper, never()).updateDone(anyLong(), anyBoolean());
  }

  // ---------- linkItemProject（下拉关联项目 → 建任务回填） ----------

  @Test
  void linkItemProjectCreatesTaskAndBackfillsIds() {
    when(reportItemMapper.findByIdForUser(5L, 1L))
        .thenReturn(
            Optional.of(itemRow(5L, null, false)),
            Optional.of(itemRow(5L, 10L, 3L, false)));
    when(projectService.createTask(3L, "条目", null, null, "todo", null, null, null))
        .thenReturn(new ProjectService.TaskView(taskRow(10L), false));

    ReportItemRow linked = service.linkItemProject(1L, 5L, 3L);

    verify(reportItemMapper).updateLink(5L, 10L, 3L);
    assertThat(linked.taskId()).isEqualTo(10L);
    assertThat(linked.projectId()).isEqualTo(3L);
  }

  @Test
  void linkItemProjectIsIdempotentWhenAlreadyLinked() {
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, 10L, true)));

    ReportItemRow linked = service.linkItemProject(1L, 5L, 9L);

    verify(reportItemMapper, never()).updateLink(anyLong(), anyLong(), anyLong());
    verify(projectService, never()).createTask(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any());
    assertThat(linked.taskId()).isEqualTo(10L);
  }

  @Test
  void linkItemProjectOwnedBySomeoneElseReturns404() {
    when(reportItemMapper.findByIdForUser(5L, 2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.linkItemProject(2L, 5L, 3L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  // ---------- deleteItem ----------

  @Test
  void deleteItemRemovesRowAndKeepsLinkedTask() {
    when(reportItemMapper.findByIdForUser(5L, 1L)).thenReturn(Optional.of(itemRow(5L, 10L, true)));

    service.deleteItem(1L, 5L);

    verify(reportItemMapper).delete(5L);
    verify(projectService, never()).updateTaskStatus(anyLong(), anyString());
  }

  @Test
  void deleteItemOwnedBySomeoneElseReturns404() {
    when(reportItemMapper.findByIdForUser(5L, 2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteItem(2L, 5L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(reportItemMapper, never()).delete(anyLong());
  }
}

package com.qa.kb.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.MilestoneRow;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.model.ProjectWithProgress;
import com.qa.kb.model.TaskRow;
import com.qa.kb.persistence.ProjectMapper;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService")
class ProjectServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

  @Mock private ProjectMapper projectMapper;

  private ProjectService service;

  @BeforeEach
  void setUp() {
    service = new ProjectService(projectMapper, CLOCK);
  }

  private static ProjectWithProgress withProgress(
      long id, String status, Long total, Long done) {
    return new ProjectWithProgress(
        id, "项目" + id, "P" + id, 1L, status, null, null, "", total, done);
  }

  private static ProjectRow project(long id) {
    return new ProjectRow(id, "项目" + id, "P" + id, 1L, "ACTIVE", null, null, "");
  }

  private static TaskRow task(
      long id, String status, LocalDate due, int progress) {
    return new TaskRow(
        id,
        1L,
        null,
        "任务" + id,
        2L,
        status,
        2,
        null,
        due,
        progress,
        LocalDateTime.of(2026, 11, 1, 9, 0));
  }

  // ---------- list ----------

  @Test
  void listMapsCountsToProgress() {
    when(projectMapper.listWithProgress())
        .thenReturn(
            List.of(withProgress(1, "ACTIVE", 4L, 1L), withProgress(2, "DONE", 0L, 0L)));

    List<ProjectService.ProjectSummary> projects = service.list();

    assertThat(projects).hasSize(2);
    assertThat(projects.get(0).progress()).isEqualTo(new Progress(1, 4, 25));
    assertThat(projects.get(1).progress()).isEqualTo(new Progress(0, 0, 0));
  }

  @Test
  void listTreatsNullCountsAsZero() {
    when(projectMapper.listWithProgress()).thenReturn(List.of(withProgress(1, "ACTIVE", null, null)));

    assertThat(service.list().get(0).progress().total()).isZero();
  }

  // ---------- detail ----------

  @Test
  void detailRejectsUnknownProject() {
    when(projectMapper.progressOf(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail(99L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void detailAssemblesMilestonesAndFlagsOverdueTasks() {
    when(projectMapper.progressOf(1L)).thenReturn(Optional.of(withProgress(1, "ACTIVE", 2L, 1L)));
    when(projectMapper.listMilestones(1L))
        .thenReturn(
            List.of(new MilestoneRow(3L, 1L, "发布", LocalDate.of(2026, 12, 1), "OPEN")));
    when(projectMapper.listTasks(1L))
        .thenReturn(
            List.of(
                task(10, "doing", TODAY.minusDays(1), 40),
                task(11, "done", TODAY.minusDays(3), 100),
                task(12, "todo", TODAY.plusDays(2), 0)));

    ProjectService.ProjectDetail detail = service.detail(1L);

    assertThat(detail.progress()).isEqualTo(new Progress(1, 2, 50));
    assertThat(detail.milestones()).hasSize(1);
    assertThat(detail.tasks()).extracting(view -> view.task().id()).containsExactly(10L, 11L, 12L);
    assertThat(detail.tasks()).extracting(ProjectService.TaskView::overdue)
        .containsExactly(true, false, false);
  }

  // ---------- create ----------

  @Test
  void createRejectsBlankName() {
    assertThatThrownBy(
            () -> service.create("  ", "QA", 1L, null, null, null, "desc"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(projectMapper, never()).insert(any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void createRejectsBlankKey() {
    assertThatThrownBy(() -> service.create("项目", " ", 1L, null, null, null, "desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createNormalizesKeyToUppercase() {
    when(projectMapper.insert("项目", "QA", 1L, "ACTIVE", null, null, "desc")).thenReturn(1);
    when(projectMapper.lastInsertId()).thenReturn(9L);
    when(projectMapper.findById(9L)).thenReturn(Optional.of(project(9)));

    service.create("项目", " qa ", 1L, null, null, null, "desc");

    verify(projectMapper).insert("项目", "QA", 1L, "ACTIVE", null, null, "desc");
  }

  @Test
  void createRejectsKeyWithInvalidCharacters() {
    assertThatThrownBy(() -> service.create("项目", "QA-1", 1L, null, null, null, "desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createDefaultsStatusToActiveAndRejectsUnknownStatus() {
    when(projectMapper.insert(anyString(), anyString(), anyLong(), eq("ACTIVE"), any(), any(), any()))
        .thenReturn(1);
    when(projectMapper.lastInsertId()).thenReturn(9L);
    when(projectMapper.findById(9L)).thenReturn(Optional.of(project(9)));

    service.create("项目", "QA", 1L, " ", null, null, "desc");

    assertThatThrownBy(() -> service.create("项目", "QA", 1L, "PAUSED", null, null, "desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsInvertedDates() {
    assertThatThrownBy(
            () ->
                service.create(
                    "项目", "QA", 1L, "ACTIVE", TODAY, TODAY.minusDays(1), "desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createInsertsAndReloadsTheProject() {
    when(projectMapper.insert("项目", "QA", 1L, "PLANNING", TODAY, TODAY.plusDays(30), "desc"))
        .thenReturn(1);
    when(projectMapper.lastInsertId()).thenReturn(9L);
    when(projectMapper.findById(9L)).thenReturn(Optional.of(project(9)));

    assertThat(service.create("项目", "qa", 1L, "planning", TODAY, TODAY.plusDays(30), "desc").id())
        .isEqualTo(9L);
  }

  @Test
  void createMapsDuplicateKeyToConflict() {
    when(projectMapper.insert(anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
        .thenThrow(new DuplicateKeyException("uk_projects_key"));

    assertThatThrownBy(() -> service.create("项目", "QA", 1L, null, null, null, "desc"))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  // ---------- tasks ----------

  @Test
  void updateTaskStatusRejectsUnknownTask() {
    when(projectMapper.findTask(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateTaskStatus(99L, "done"))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void updateTaskStatusRejectsInvalidStatus() {
    assertThatThrownBy(() -> service.updateTaskStatus(10L, "shipped"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(projectMapper, never()).updateTask(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void updateTaskStatusDoneSetsProgressTo100() {
    when(projectMapper.findTask(10L))
        .thenReturn(Optional.of(task(10, "doing", null, 40)))
        .thenReturn(Optional.of(task(10, "done", null, 100)));

    service.updateTaskStatus(10L, "done");

    verify(projectMapper).updateTask(10L, "done", 100);
  }

  @Test
  void updateTaskStatusTodoResetsProgressTo0() {
    when(projectMapper.findTask(10L))
        .thenReturn(Optional.of(task(10, "blocked", null, 70)))
        .thenReturn(Optional.of(task(10, "todo", null, 0)));

    service.updateTaskStatus(10L, "todo");

    verify(projectMapper).updateTask(10L, "todo", 0);
  }

  @Test
  void updateTaskStatusDoingClampsExistingProgress() {
    when(projectMapper.findTask(10L))
        .thenReturn(Optional.of(task(10, "todo", null, 100)))
        .thenReturn(Optional.of(task(10, "doing", null, 99)));

    service.updateTaskStatus(10L, "DOING");

    verify(projectMapper).updateTask(10L, "doing", 99);
  }

  @Test
  void updateTaskStatusReturnsReloadedTaskWithOverdueFlag() {
    when(projectMapper.findTask(10L))
        .thenReturn(Optional.of(task(10, "todo", TODAY.minusDays(1), 10)))
        .thenReturn(Optional.of(task(10, "doing", TODAY.minusDays(1), 10)));

    ProjectService.TaskView view = service.updateTaskStatus(10L, "doing");

    assertThat(view.task().status()).isEqualTo("doing");
    assertThat(view.task().progress()).isEqualTo(10);
    assertThat(view.overdue()).isTrue();
    verify(projectMapper).updateTask(10L, "doing", 10);
  }

  // ---------- delete / restore（软删除） ----------

  @Test
  void deleteSoftDeletesAndFreesTheProjectKey() {
    when(projectMapper.findById(9L)).thenReturn(Optional.of(project(9)));

    service.delete(9L);

    // key 改写为 P9#9，释放唯一索引供同标识新项目使用
    verify(projectMapper).softDelete(eq(9L), eq("P9#9"), any(LocalDateTime.class));
  }

  @Test
  void deleteRejectsUnknownProject() {
    when(projectMapper.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(99L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(projectMapper, never()).softDelete(anyLong(), anyString(), any());
  }

  @Test
  void restoreClearsTheFlagAndStripsTheKeySuffix() {
    when(projectMapper.findDeletedById(9L))
        .thenReturn(
            Optional.of(new ProjectRow(9L, "项目9", "P9#9", 1L, "ACTIVE", null, null, "")));

    service.restore(9L);

    verify(projectMapper).restore(9L, "P9");
  }

  @Test
  void restoreRejectsProjectThatIsNotDeleted() {
    when(projectMapper.findDeletedById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.restore(99L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(projectMapper, never()).restore(anyLong(), anyString());
  }

  @Test
  void restoreMapsDuplicateKeyToConflict() {
    when(projectMapper.findDeletedById(9L))
        .thenReturn(
            Optional.of(new ProjectRow(9L, "项目9", "P9#9", 1L, "ACTIVE", null, null, "")));
    doThrow(new DuplicateKeyException("uk_projects_key")).when(projectMapper).restore(9L, "P9");

    assertThatThrownBy(() -> service.restore(9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  // ---------- createTask（新建任务） ----------

  @Test
  void createTaskRejectsUnknownProject() {
    when(projectMapper.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createTask(99L, "任务", null, null, null, null, null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(projectMapper, never())
        .insertTask(anyLong(), any(), anyString(), any(), anyString(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void createTaskRejectsBlankTitle() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));

    assertThatThrownBy(() -> service.createTask(1L, "  ", null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(projectMapper, never())
        .insertTask(anyLong(), any(), anyString(), any(), anyString(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void createTaskRejectsUnknownStatus() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));

    assertThatThrownBy(() -> service.createTask(1L, "T", null, null, "shipped", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(projectMapper, never())
        .insertTask(anyLong(), any(), anyString(), any(), anyString(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void createTaskRejectsMilestoneOfOtherProject() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));
    when(projectMapper.findMilestone(1L, 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createTask(1L, "T", 99L, null, null, null, null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(projectMapper, never())
        .insertTask(anyLong(), any(), anyString(), any(), anyString(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void createTaskRejectsInactiveAssignee() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));
    when(projectMapper.countActiveUser(777L)).thenReturn(0L);

    assertThatThrownBy(() -> service.createTask(1L, "T", null, 777L, null, null, null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(projectMapper, never())
        .insertTask(anyLong(), any(), anyString(), any(), anyString(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void createTaskDefaultsStatusAndClampsPriority() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));
    when(projectMapper.lastInsertId()).thenReturn(10L);
    when(projectMapper.findTask(10L)).thenReturn(Optional.of(task(10, "todo", null, 0)));

    service.createTask(1L, "T", null, null, null, 9, null, null);

    // status 缺省 todo、priority 9 夹取到 4、新任务 progress 0
    verify(projectMapper).insertTask(1L, null, "T", null, "todo", 4, null, null, 0);
  }

  @Test
  void createTaskDoneStatusSetsProgress100() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));
    when(projectMapper.lastInsertId()).thenReturn(10L);
    when(projectMapper.findTask(10L)).thenReturn(Optional.of(task(10, "done", null, 100)));

    service.createTask(1L, "T", null, null, "done", null, null, null);

    verify(projectMapper).insertTask(1L, null, "T", null, "done", 2, null, null, 100);
  }

  @Test
  void createTaskInsertsAndReloadsWithOverdueFlag() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));
    when(projectMapper.countActiveUser(2L)).thenReturn(1L);
    when(projectMapper.lastInsertId()).thenReturn(10L);
    when(projectMapper.findTask(10L))
        .thenReturn(Optional.of(task(10, "todo", TODAY.minusDays(1), 0)));

    ProjectService.TaskView view =
        service.createTask(1L, "新任务", null, 2L, null, 3, null, TODAY.minusDays(1));

    verify(projectMapper)
        .insertTask(1L, null, "新任务", 2L, "todo", 3, null, TODAY.minusDays(1), 0);
    assertThat(view.task().id()).isEqualTo(10L);
    assertThat(view.overdue()).isTrue();
  }

  // ---------- createMilestone（新建里程碑） ----------

  @Test
  void createMilestoneRejectsUnknownProject() {
    when(projectMapper.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createMilestone(99L, "发布", null, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(projectMapper, never()).insertMilestone(anyLong(), anyString(), any(), anyString());
  }

  @Test
  void createMilestoneRejectsBlankName() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));

    assertThatThrownBy(() -> service.createMilestone(1L, " ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(projectMapper, never()).insertMilestone(anyLong(), anyString(), any(), anyString());
  }

  @Test
  void createMilestoneRejectsUnknownStatus() {
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));

    assertThatThrownBy(() -> service.createMilestone(1L, "发布", null, "LATE"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(projectMapper, never()).insertMilestone(anyLong(), anyString(), any(), anyString());
  }

  @Test
  void createMilestoneDefaultsStatusToOpenAndReturnsRow() {
    LocalDate due = LocalDate.of(2026, 12, 1);
    when(projectMapper.findById(1L)).thenReturn(Optional.of(project(1)));
    when(projectMapper.lastInsertId()).thenReturn(55L);
    when(projectMapper.findMilestone(1L, 55L))
        .thenReturn(Optional.of(new MilestoneRow(55L, 1L, "发布", due, "OPEN")));

    MilestoneRow created = service.createMilestone(1L, "发布", due, null);

    verify(projectMapper).insertMilestone(1L, "发布", due, "OPEN");
    assertThat(created.id()).isEqualTo(55L);
    assertThat(created.status()).isEqualTo("OPEN");
  }
}

package com.qa.kb.project;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.MilestoneRow;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.model.ProjectWithProgress;
import com.qa.kb.model.TaskRow;
import com.qa.kb.persistence.ProjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** Projects, milestones and tasks. Progress always uses {@code done / total}. */
public class ProjectService {

  public static final int MAX_NAME_LENGTH = 150;
  public static final int MAX_KEY_LENGTH = 10;
  public static final int MAX_TASK_TITLE_LENGTH = 200;
  public static final int DEFAULT_PRIORITY = 2;
  private static final Set<String> PROJECT_STATUSES =
      Set.of("PLANNING", "ACTIVE", "DONE", "ARCHIVED");
  private static final Set<String> MILESTONE_STATUSES = Set.of("OPEN", "DONE");
  private static final java.util.regex.Pattern KEY_PATTERN =
      java.util.regex.Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");

  public record ProjectSummary(ProjectWithProgress project, Progress progress) {}

  public record TaskView(TaskRow task, boolean overdue) {}

  public record ProjectDetail(
      ProjectWithProgress project,
      Progress progress,
      List<MilestoneRow> milestones,
      List<TaskView> tasks) {}

  private final ProjectMapper projectMapper;
  private final Clock clock;

  public ProjectService(ProjectMapper projectMapper, Clock clock) {
    this.projectMapper = projectMapper;
    this.clock = clock;
  }

  public List<ProjectSummary> list() {
    return projectMapper.listWithProgress().stream()
        .map(project -> new ProjectSummary(project, progressOf(project)))
        .toList();
  }

  public ProjectDetail detail(long id) {
    ProjectWithProgress project =
        projectMapper.progressOf(id).orElseThrow(() -> ApiException.notFound("项目不存在"));
    LocalDate today = LocalDate.now(clock);
    List<TaskView> tasks =
        projectMapper.listTasks(id).stream()
            .map(task -> new TaskView(task, isOverdue(task, today)))
            .toList();
    return new ProjectDetail(
        project, progressOf(project), projectMapper.listMilestones(id), tasks);
  }

  @Transactional
  public ProjectRow create(
      String name,
      String key,
      long ownerId,
      String status,
      LocalDate startDate,
      LocalDate endDate,
      String description) {
    String cleanName = requireText(name, MAX_NAME_LENGTH, "项目名称");
    String cleanKey = normalizeKey(key);
    String cleanStatus = normalizeProjectStatus(status);
    requireDates(startDate, endDate);
    String cleanDescription = description == null ? "" : description.strip();

    long id;
    try {
      projectMapper.insert(
          cleanName, cleanKey, ownerId, cleanStatus, startDate, endDate, cleanDescription);
      id = projectMapper.lastInsertId();
    } catch (DuplicateKeyException duplicate) {
      throw ApiException.conflict("项目标识已存在: " + cleanKey);
    }

    return projectMapper.findById(id).orElseThrow(() -> ApiException.notFound("项目不存在"));
  }

  /**
   * 软删除项目：只打 {@code deleted_at} 标记，里程碑、任务、日报关联原样保留，列表与详情不再可见。
   *
   * <p>同时释放 {@code project_key}（改写为 {@code 原key#id}），否则删除后无法再建同标识的项目。
   */
  @Transactional
  public void delete(long id) {
    ProjectRow existing =
        projectMapper.findById(id).orElseThrow(() -> ApiException.notFound("项目不存在"));
    projectMapper.softDelete(id, existing.projectKey() + "#" + id, LocalDateTime.now(clock));
  }

  /** 恢复被软删除的项目：清除标记并还原原项目标识。 */
  @Transactional
  public void restore(long id) {
    ProjectRow deleted =
        projectMapper
            .findDeletedById(id)
            .orElseThrow(() -> ApiException.notFound("项目不存在或未被删除"));
    String key = deleted.projectKey();
    int hash = key.indexOf('#');
    try {
      projectMapper.restore(id, hash >= 0 ? key.substring(0, hash) : key);
    } catch (DuplicateKeyException duplicate) {
      // 删除期间已有新项目占用了原标识
      throw ApiException.conflict("原项目标识已被其他项目占用: " + (hash >= 0 ? key.substring(0, hash) : key));
    }
  }

  @Transactional
  public TaskView updateTaskStatus(long taskId, String status) {
    String cleanStatus = normalizeTaskStatus(status);
    TaskRow existing =
        projectMapper.findTask(taskId).orElseThrow(() -> ApiException.notFound("任务不存在"));

    int nextProgress =
        switch (cleanStatus) {
          case "done" -> 100;
          case "todo" -> 0;
          default -> clampProgress(existing.progress());
        };
    projectMapper.updateTask(taskId, cleanStatus, nextProgress);

    TaskRow updated =
        projectMapper.findTask(taskId).orElseThrow(() -> ApiException.notFound("任务不存在"));
    return new TaskView(updated, isOverdue(updated, LocalDate.now(clock)));
  }

  /** 新建里程碑：项目必须存在（未软删），名称必填，状态缺省 OPEN。 */
  @Transactional
  public MilestoneRow createMilestone(long projectId, String name, LocalDate dueDate, String status) {
    requireProject(projectId);
    String cleanName = requireText(name, MAX_NAME_LENGTH, "里程碑名称");
    String cleanStatus = normalizeMilestoneStatus(status);

    projectMapper.insertMilestone(projectId, cleanName, dueDate, cleanStatus);
    long id = projectMapper.lastInsertId();
    return projectMapper
        .findMilestone(projectId, id)
        .orElseThrow(() -> ApiException.notFound("里程碑不存在"));
  }

  /**
   * 新建任务：项目必须存在（未软删）；标题必填；状态缺省 todo；优先级缺省 2 并夹取到 [1,4]；
   * 里程碑必须属于该项目；负责人必须是启用状态的用户。
   */
  @Transactional
  public TaskView createTask(
      long projectId,
      String title,
      Long milestoneId,
      Long assigneeId,
      String status,
      Integer priority,
      LocalDate startDate,
      LocalDate dueDate) {
    requireProject(projectId);
    String cleanTitle = requireText(title, MAX_TASK_TITLE_LENGTH, "任务标题");
    String cleanStatus =
        status == null || status.isBlank() ? "todo" : normalizeTaskStatus(status);
    int cleanPriority = priority == null ? DEFAULT_PRIORITY : clampPriority(priority);
    requireDates(startDate, dueDate);
    if (milestoneId != null) {
      projectMapper
          .findMilestone(projectId, milestoneId)
          .orElseThrow(() -> ApiException.badRequest("里程碑不属于该项目"));
    }
    if (assigneeId != null && projectMapper.countActiveUser(assigneeId) == 0) {
      throw ApiException.badRequest("负责人不存在或已停用");
    }

    int progress = "done".equals(cleanStatus) ? 100 : 0;
    projectMapper.insertTask(
        projectId,
        milestoneId,
        cleanTitle,
        assigneeId,
        cleanStatus,
        cleanPriority,
        startDate,
        dueDate,
        progress);
    long id = projectMapper.lastInsertId();
    TaskRow created =
        projectMapper.findTask(id).orElseThrow(() -> ApiException.notFound("任务不存在"));
    return new TaskView(created, isOverdue(created, LocalDate.now(clock)));
  }

  private void requireProject(long projectId) {
    projectMapper.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
  }

  private static int clampPriority(int priority) {
    return Math.max(1, Math.min(4, priority));
  }

  private static String normalizeMilestoneStatus(String status) {
    if (status == null || status.isBlank()) {
      return "OPEN";
    }
    String clean = status.strip().toUpperCase(Locale.ROOT);
    if (!MILESTONE_STATUSES.contains(clean)) {
      throw new IllegalArgumentException("未知的里程碑状态: " + status);
    }
    return clean;
  }

  private static Progress progressOf(ProjectWithProgress project) {
    int total = project.totalTasks() == null ? 0 : project.totalTasks().intValue();
    int done = project.doneTasks() == null ? 0 : project.doneTasks().intValue();
    return ProgressCalculator.of(Math.min(done, total), total);
  }

  private static boolean isOverdue(TaskRow task, LocalDate today) {
    String status = task.status() == null ? "todo" : task.status();
    return ProgressCalculator.isOverdue(status, task.dueDate(), today);
  }

  private static int clampProgress(Integer progress) {
    if (progress == null) {
      return 0;
    }
    return Math.max(0, Math.min(99, progress));
  }

  private static String requireText(String text, int max, String label) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    String clean = text.strip();
    if (clean.length() > max) {
      throw new IllegalArgumentException(label + "不能超过 " + max + " 个字符");
    }
    return clean;
  }

  private static String normalizeKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("项目标识不能为空");
    }
    String clean = key.strip().toUpperCase(Locale.ROOT);
    if (clean.length() > MAX_KEY_LENGTH || !KEY_PATTERN.matcher(clean).matches()) {
      throw new IllegalArgumentException("项目标识必须为 2-10 位字母或数字，且以字母开头");
    }
    return clean;
  }

  private static String normalizeProjectStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ACTIVE";
    }
    String clean = status.strip().toUpperCase(Locale.ROOT);
    if (!PROJECT_STATUSES.contains(clean)) {
      throw new IllegalArgumentException("未知的项目状态: " + status);
    }
    return clean;
  }

  private static String normalizeTaskStatus(String status) {
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("任务状态不能为空");
    }
    String clean = status.strip().toLowerCase(Locale.ROOT);
    if (!ProgressCalculator.TASK_STATUSES.contains(clean)) {
      throw new IllegalArgumentException("未知的任务状态: " + status);
    }
    return clean;
  }

  private static void requireDates(LocalDate startDate, LocalDate endDate) {
    if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("结束日期不能早于开始日期");
    }
  }
}

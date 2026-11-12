package com.qa.kb.report;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.ReportItemRow;
import com.qa.kb.model.ReportProjectLink;
import com.qa.kb.model.ReportRow;
import com.qa.kb.model.ReportWithUser;
import com.qa.kb.persistence.ProjectMapper;
import com.qa.kb.persistence.ReportItemMapper;
import com.qa.kb.persistence.ReportMapper;
import com.qa.kb.project.ProjectService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily reports and their weekly roll-up.
 *
 * <p>A report is unique per {@code (user_id, report_date)} and is upserted. Business dates are
 * timezone-free ISO dates resolved against the team clock ({@code Asia/Shanghai}), so "today" is
 * stable regardless of the server's default zone.
 *
 * <p>周报复用同一张表：{@code report_date} 存<b>周一日期</b>即为该周的周报（唯一索引天然保证
 * 每周一份），历史日报数据原样保留。周报清单条目存于 {@code report_items}。
 */
public class ReportService {

  public static final int MAX_TEXT_LENGTH = 20_000;
  public static final int MAX_ITEM_TITLE_LENGTH = 200;
  private static final Set<String> STATUSES = Set.of("draft", "submitted");

  private final ReportMapper reportMapper;
  private final ReportItemMapper reportItemMapper;
  private final ProjectMapper projectMapper;
  private final ProjectService projectService;
  private final Clock clock;

  public ReportService(
      ReportMapper reportMapper,
      ReportItemMapper reportItemMapper,
      ProjectMapper projectMapper,
      ProjectService projectService,
      Clock clock) {
    this.reportMapper = reportMapper;
    this.reportItemMapper = reportItemMapper;
    this.projectMapper = projectMapper;
    this.projectService = projectService;
    this.clock = clock;
  }

  @Transactional
  public void saveDaily(
      long userId,
      LocalDate date,
      String done,
      String plan,
      String blockers,
      String status,
      List<Long> projectIds) {
    doSave(userId, requireWritableDate(date), done, plan, blockers, status, projectIds);
  }

  /** 周报保存：weekStart 必须是周一，且不得晚于本周一（禁止填写未来周报）。 */
  @Transactional
  public void saveWeekly(
      long userId,
      LocalDate weekStart,
      String done,
      String plan,
      String blockers,
      String status,
      List<Long> projectIds) {
    doSave(userId, requireWeekStart(weekStart), done, plan, blockers, status, projectIds);
  }

  private void doSave(
      long userId,
      LocalDate day,
      String done,
      String plan,
      String blockers,
      String status,
      List<Long> projectIds) {
    String cleanStatus = normalizeStatus(status);
    String cleanDone = cleanText(done, "完成事项");
    String cleanPlan = cleanText(plan, "明日计划");
    String cleanBlockers = cleanText(blockers, "阻塞项");
    List<Long> projects = knownProjects(projectIds);

    reportMapper.upsert(userId, day, cleanDone, cleanPlan, cleanBlockers, cleanStatus);
    long reportId =
        reportMapper
            .findIdByUserAndDate(userId, day)
            .orElseThrow(() -> new IllegalStateException("日报保存后未能读取编号"));

    reportMapper.deleteProjectLinks(reportId);
    for (Long projectId : projects) {
      reportMapper.insertProjectLink(reportId, projectId);
    }
  }

  public Optional<ReportRow> find(long userId, LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    return reportMapper.findByUserAndDate(userId, date);
  }

  public List<ReportWithUser> reportsOn(LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    return reportMapper.listByDate(date);
  }

  public List<Long> missingUsers(LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    return reportMapper.missingUserIds(date);
  }

  public List<ReportWithUser> between(LocalDate from, LocalDate to) {
    requireRange(from, to);
    return reportMapper.listBetween(from, to);
  }

  public List<ReportRow> mine(long userId, LocalDate from, LocalDate to) {
    requireRange(from, to);
    return reportMapper.listByUserBetween(userId, from, to);
  }

  public List<Long> projectIdsOf(long userId, LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    return reportMapper.listProjectIds(userId, date);
  }

  /* ------------------------------------------------------------------ 周报清单 */

  /** 某周的清单：周报尚未创建时返回空列表（而不是报错）。 */
  public List<ReportItemRow> listItems(long userId, LocalDate weekStart) {
    requireWeekStart(weekStart);
    return reportMapper
        .findIdByUserAndDate(userId, weekStart)
        .map(reportItemMapper::listByReport)
        .orElse(List.of());
  }

  /**
   * 添加清单条目。
   *
   * <ul>
   *   <li>纯轻量条目：不带任何关联，仅存 title；
   *   <li>带 {@code projectId}：同步创建一条真实项目任务（title + todo），回填 {@code task_id}；
   *   <li>带 {@code taskId}：直接挂到既有任务（校验存在后回填 {@code project_id}）。
   * </ul>
   *
   * <p>周报正文尚未保存时会先插入一份空草稿行作为挂载点（仅缺失时插入，不覆盖已有正文）。
   */
  @Transactional
  public ReportItemRow addItem(
      long userId, LocalDate weekStart, String title, Long projectId, Long taskId) {
    requireWeekStart(weekStart);
    String cleanTitle = cleanItemTitle(title);

    Long linkedTaskId = null;
    Long linkedProjectId = null;
    if (taskId != null) {
      var task =
          projectMapper
              .findTask(taskId)
              .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskId));
      linkedTaskId = task.id();
      linkedProjectId = task.projectId();
    } else if (projectId != null) {
      ProjectService.TaskView task =
          projectService.createTask(projectId, cleanTitle, null, null, "todo", null, null, null);
      linkedTaskId = task.task().id();
      linkedProjectId = projectId;
    }

    reportMapper.insertIfAbsent(userId, weekStart);
    long reportId =
        reportMapper
            .findIdByUserAndDate(userId, weekStart)
            .orElseThrow(() -> new IllegalStateException("周报占位行创建后未能读取编号"));
    int sort = reportItemMapper.nextSort(reportId);
    reportItemMapper.insert(reportId, cleanTitle, false, linkedTaskId, linkedProjectId, sort);
    long itemId = reportItemMapper.lastInsertId();
    return reportItemMapper
        .findByIdForUser(itemId, userId)
        .orElseThrow(() -> new IllegalStateException("清单条目创建后未能读取编号"));
  }

  /** 更新条目：勾选状态变化时同步关联任务（done ↔ doing），可顺带改标题。 */
  @Transactional
  public ReportItemRow updateItem(long userId, long itemId, Boolean done, String title) {
    ReportItemRow item =
        reportItemMapper
            .findByIdForUser(itemId, userId)
            .orElseThrow(() -> ApiException.notFound("清单条目不存在"));

    if (title != null) {
      reportItemMapper.updateTitle(itemId, cleanItemTitle(title));
    }
    if (done != null && done != item.done()) {
      reportItemMapper.updateDone(itemId, done);
      if (item.taskId() != null) {
        projectService.updateTaskStatus(item.taskId(), done ? "done" : "doing");
      }
    }
    return reportItemMapper
        .findByIdForUser(itemId, userId)
        .orElseThrow(() -> ApiException.notFound("清单条目不存在"));
  }

  /**
   * 给已存在的轻量条目关联项目：同步创建一条真实任务（title + todo）并回填 task_id / project_id。
   *
   * <p>已关联过的条目直接返回（幂等，避免重复建任务）。
   */
  @Transactional
  public ReportItemRow linkItemProject(long userId, long itemId, long projectId) {
    ReportItemRow item =
        reportItemMapper
            .findByIdForUser(itemId, userId)
            .orElseThrow(() -> ApiException.notFound("清单条目不存在"));
    if (item.taskId() != null) {
      return item;
    }
    ProjectService.TaskView task =
        projectService.createTask(projectId, item.title(), null, null, "todo", null, null, null);
    reportItemMapper.updateLink(itemId, task.task().id(), projectId);
    return reportItemMapper
        .findByIdForUser(itemId, userId)
        .orElseThrow(() -> ApiException.notFound("清单条目不存在"));
  }

  /** 删除条目；关联的任务保留不动（数据不随清单消失）。 */
  @Transactional
  public void deleteItem(long userId, long itemId) {
    ReportItemRow item =
        reportItemMapper
            .findByIdForUser(itemId, userId)
            .orElseThrow(() -> ApiException.notFound("清单条目不存在"));
    reportItemMapper.delete(item.id());
  }

  /** 周起始校验：必填、必须是周一、不得晚于本周一。 */
  private LocalDate requireWeekStart(LocalDate weekStart) {
    if (weekStart == null) {
      throw new IllegalArgumentException("周起始日期不能为空");
    }
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw ApiException.badRequest("周报起始日必须是周一");
    }
    LocalDate thisMonday = LocalDate.now(clock).with(DayOfWeek.MONDAY);
    if (weekStart.isAfter(thisMonday)) {
      throw ApiException.badRequest("不能填写未来周的周报");
    }
    return weekStart;
  }

  private static String cleanItemTitle(String title) {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("清单条目标题不能为空");
    }
    String clean = title.strip();
    if (clean.length() > MAX_ITEM_TITLE_LENGTH) {
      throw new IllegalArgumentException(
          "清单条目标题不能超过 " + MAX_ITEM_TITLE_LENGTH + " 个字符");
    }
    return clean;
  }

  public WeeklyReportAggregator.WeeklyReport weekly(long userId, LocalDate reference) {
    if (reference == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    WeekRange range = ReportWeekCalculator.weekOf(reference);
    LocalDate today = LocalDate.now(clock);
    List<ReportRow> reports =
        reportMapper.listByUserBetween(userId, range.start(), range.end());
    Map<Long, List<Long>> links =
        reportMapper.listProjectLinksBetween(userId, range.start(), range.end()).stream()
            .filter(link -> link != null && link.reportId() != null && link.projectId() != null)
            .collect(
                Collectors.groupingBy(
                    ReportProjectLink::reportId,
                    Collectors.mapping(ReportProjectLink::projectId, Collectors.toList())));
    return WeeklyReportAggregator.aggregate(reference, today, reports, links);
  }

  private LocalDate requireWritableDate(LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("日期不能为空");
    }
    if (date.isAfter(LocalDate.now(clock))) {
      throw ApiException.badRequest("不能填写未来日期的日报");
    }
    return date;
  }

  private static void requireRange(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("起止日期不能为空");
    }
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("结束日期不能早于开始日期");
    }
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return "draft";
    }
    String clean = status.strip().toLowerCase(Locale.ROOT);
    if (!STATUSES.contains(clean)) {
      throw new IllegalArgumentException("未知的日报状态: " + status);
    }
    return clean;
  }

  private static String cleanText(String text, String label) {
    if (text == null) {
      return "";
    }
    String clean = text.strip();
    if (clean.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException(label + "不能超过 " + MAX_TEXT_LENGTH + " 个字符");
    }
    return clean;
  }

  private List<Long> knownProjects(List<Long> projectIds) {
    if (projectIds == null || projectIds.isEmpty()) {
      return List.of();
    }
    Set<Long> unique = new LinkedHashSet<>();
    for (Long projectId : projectIds) {
      if (projectId == null || !unique.add(projectId)) {
        continue;
      }
      if (projectMapper.findById(projectId).isPresent()) {
        continue;
      }
      // 已软删除的项目在勾选列表里不显示、用户无法自行取消 → 明确 400 而非 404，
      // 提示刷新后重新提交（读取端已过滤，刷新后该残留关联不再出现）。
      if (projectMapper.findDeletedById(projectId).isPresent()) {
        throw ApiException.badRequest(
            "关联项目已删除: " + projectId + "，请刷新页面后重新提交（失效关联会随之清除）");
      }
      throw ApiException.badRequest("关联项目不存在: " + projectId);
    }
    return List.copyOf(unique);
  }
}

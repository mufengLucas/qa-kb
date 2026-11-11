package com.qa.kb.web;

import com.qa.kb.model.ReportItemRow;
import com.qa.kb.model.ReportRow;
import com.qa.kb.model.ReportWithUser;
import com.qa.kb.report.ReportService;
import com.qa.kb.report.WeeklyReportAggregator;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

  public record DailyReportRequest(
      String done, String plan, String blockers, String status, List<Long> projectIds) {}

  /** 添加周报清单条目：标题必填；带 projectId 会同步建任务，带 taskId 则挂既有任务。 */
  public record ItemRequest(
      @NotBlank @Size(max = 200) String title, Long projectId, Long taskId) {}

  /** 更新清单条目：done 可空（不改）、title 可空（不改）、projectId 可空（关联项目任务）。 */
  public record ItemPatchRequest(Boolean done, String title, Long projectId) {}

  private final ReportService reportService;
  private final Clock clock;

  public ReportController(ReportService reportService, Clock clock) {
    this.reportService = reportService;
    this.clock = clock;
  }

  /**
   * 当天日报：已写返回记录，未写返回 **200 空体**而不是 404。
   *
   * <p>「今天还没写日报」是正常业务状态而非资源缺失；用 404 表达会让浏览器控制台
   * 每天都打印一条 Failed to load resource 噪音。前端拿到空体后回落到空白草稿。
   */
  @GetMapping("/daily/{date}")
  public ResponseEntity<ReportRow> myReport(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService
        .find(user.id(), date)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.ok().build());
  }

  /* ------------------------------------------------------------------ 周报 */

  /**
   * 读取本周报（{@code weekStart} 为周一）。已保存返回记录，未保存返回 200 空体——
   * 与日报端点一致，空体表示"这份周报还没写"，前端回落到空白表单。
   */
  @GetMapping("/weekly/{weekStart}")
  public ResponseEntity<ReportRow> myWeekly(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService
        .find(user.id(), weekStart)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.ok().build());
  }

  @GetMapping("/weekly/{weekStart}/projects")
  public List<Long> weeklyProjectIds(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService.projectIdsOf(user.id(), weekStart);
  }

  /** 保存周报正文（weekStart 必须是周一，且不得晚于本周一）。 */
  @PutMapping("/weekly/{weekStart}")
  public ResponseEntity<Void> saveWeekly(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
      @RequestBody DailyReportRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    reportService.saveWeekly(
        user.id(),
        weekStart,
        body.done(),
        body.plan(),
        body.blockers(),
        body.status(),
        body.projectIds());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/weekly/{weekStart}/items")
  public List<ReportItemRow> weeklyItems(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService.listItems(user.id(), weekStart);
  }

  @PostMapping("/weekly/{weekStart}/items")
  public ResponseEntity<ReportItemRow> addWeeklyItem(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
      @Valid @RequestBody ItemRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    ReportItemRow created =
        reportService.addItem(user.id(), weekStart, body.title(), body.projectId(), body.taskId());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  /** 更新清单条目：勾选状态（联动任务状态）、改标题、或关联项目（建任务回填）。 */
  @PatchMapping("/items/{id}")
  public ReportItemRow updateItem(
      @PathVariable long id, @RequestBody ItemPatchRequest body, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    ReportItemRow updated = reportService.updateItem(user.id(), id, body.done(), body.title());
    if (body.projectId() != null) {
      updated = reportService.linkItemProject(user.id(), id, body.projectId());
    }
    return updated;
  }

  @DeleteMapping("/items/{id}")
  public ResponseEntity<Void> deleteItem(@PathVariable long id, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    reportService.deleteItem(user.id(), id);
    return ResponseEntity.noContent().build();
  }

  /** 团队周报：{@code week} 为周一日期，返回该周提交的周报行。 */
  @GetMapping(value = "/team", params = "week")
  public List<ReportWithUser> teamOnWeek(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
    return reportService.reportsOn(week);
  }

  /** 本周未提交周报的启用成员。 */
  @GetMapping(value = "/missing", params = "week")
  public List<Long> missingWeek(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
    return reportService.missingUsers(week);
  }

  @PutMapping("/daily/{date}")
  public ResponseEntity<Void> saveReport(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestBody DailyReportRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    reportService.saveDaily(
        user.id(),
        date,
        body.done(),
        body.plan(),
        body.blockers(),
        body.status(),
        body.projectIds());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/daily/{date}/projects")
  public List<Long> projectIdsOf(
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService.projectIdsOf(user.id(), date);
  }

  @GetMapping("/daily")
  public List<ReportWithUser> reportsOn(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return reportService.reportsOn(date == null ? LocalDate.now(clock) : date);
  }

  @GetMapping("/missing")
  public List<Long> missing(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return reportService.missingUsers(date == null ? LocalDate.now(clock) : date);
  }

  @GetMapping("/weekly/me")
  public WeeklyReportAggregator.WeeklyReport myWeek(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService.weekly(user.id(), date == null ? LocalDate.now(clock) : date);
  }

  @GetMapping("/weekly")
  public WeeklyReportAggregator.WeeklyReport weekOf(
      @RequestParam long userId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return reportService.weekly(userId, date == null ? LocalDate.now(clock) : date);
  }

  @GetMapping("/mine")
  public List<ReportRow> mine(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return reportService.mine(
        user.id(),
        from == null ? LocalDate.now(clock).minusDays(6) : from,
        to == null ? LocalDate.now(clock) : to);
  }
}

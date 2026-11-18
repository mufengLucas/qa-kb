package com.qa.kb.web;

import com.qa.kb.common.Csv;
import com.qa.kb.model.TestCaseRunExportRow;
import com.qa.kb.model.TestCaseRunRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import com.qa.kb.testcase.TestCaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用例管理与执行记录（P2 Phase 4；A1 导入导出见 {@code /export} 与 {@code /import}）。
 *
 * <p>权限无特殊配置：SecurityConfig 已覆盖——读需登录，写需 ADMIN/MEMBER，VIEWER 写 403；
 * 写操作由 {@link AuditInterceptor} 统一留痕（targetType = {@code test-cases}）。
 */
@RestController
@RequestMapping("/api")
public class TestCaseController {

  public record TestCaseRequest(
      @Size(max = TestCaseService.MAX_MODULE_LENGTH) String module,
      @NotBlank @Size(max = TestCaseService.MAX_TITLE_LENGTH) String title,
      @Size(max = TestCaseService.MAX_TEXT_LENGTH) String precondition,
      @Size(max = TestCaseService.MAX_TEXT_LENGTH) String steps,
      @Size(max = TestCaseService.MAX_TEXT_LENGTH) String expected,
      @Size(max = 10) String priority,
      @Size(max = 20) String status) {}

  public record RunRequest(
      @NotBlank @Size(max = 10) String result,
      @Size(max = TestCaseService.MAX_NOTE_LENGTH) String note) {}

  private final TestCaseService testCaseService;

  public TestCaseController(TestCaseService testCaseService) {
    this.testCaseService = testCaseService;
  }

  /* ------------------------------------------------------------------ 用例 */

  /**
   * 列表：{@code module} 精确、{@code status} 精确、{@code q} 模糊（标题/模块/步骤）。
   * 响应带 {@code modules}（全部已用模块），前端筛选下拉一次请求拿全。
   */
  @GetMapping("/test-cases")
  public ResponseEntity<TestCaseService.CasePage> list(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer offset) {
    return ResponseEntity.ok(testCaseService.list(module, q, status, limit, offset));
  }

  @PostMapping("/test-cases")
  public ResponseEntity<TestCaseView> create(
      @Valid @RequestBody TestCaseRequest body, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    TestCaseView created =
        testCaseService.create(
            body.module(),
            body.title(),
            body.precondition(),
            body.steps(),
            body.expected(),
            body.priority(),
            body.status(),
            user.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/test-cases/{id}")
  public ResponseEntity<TestCaseView> get(@PathVariable long id) {
    return ResponseEntity.ok(testCaseService.get(id));
  }

  @PutMapping("/test-cases/{id}")
  public ResponseEntity<TestCaseView> update(
      @PathVariable long id, @Valid @RequestBody TestCaseRequest body) {
    return ResponseEntity.ok(
        testCaseService.update(
            id,
            body.module(),
            body.title(),
            body.precondition(),
            body.steps(),
            body.expected(),
            body.priority(),
            body.status()));
  }

  @DeleteMapping("/test-cases/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    testCaseService.delete(id);
    return ResponseEntity.noContent().build();
  }

  /* ------------------------------------------------------------ 导入导出（A1） */

  /**
   * 用例导出 CSV：与列表同一套筛选（module/q/status），命中全量不分页。
   * UTF-8 BOM + RFC 5987 文件名，Excel 双击打开不乱码。
   */
  @GetMapping("/test-cases/export")
  public ResponseEntity<byte[]> exportCases(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status) {
    List<TestCaseView> rows = testCaseService.exportCases(module, q, status);
    StringBuilder csv = new StringBuilder();
    csv.append(Csv.row("module", "title", "precondition", "steps", "expected", "priority", "status"))
        .append("\r\n");
    for (TestCaseView row : rows) {
      csv.append(
              Csv.row(
                  row.module(),
                  row.title(),
                  row.precondition(),
                  row.steps(),
                  row.expected(),
                  row.priority(),
                  row.status()))
          .append("\r\n");
    }
    return csvResponse("test-cases-" + LocalDate.now() + ".csv", csv.toString());
  }

  /** 执行记录导出 CSV：一行一条执行（跨用例全量），带用例模块/标题与执行人。 */
  @GetMapping("/test-cases/runs/export")
  public ResponseEntity<byte[]> exportRuns() {
    List<TestCaseRunExportRow> rows = testCaseService.exportRuns();
    StringBuilder csv = new StringBuilder();
    csv.append(
            Csv.row(
                "module", "title", "result", "note", "executorLogin", "executorName", "executedAt"))
        .append("\r\n");
    DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    for (TestCaseRunExportRow row : rows) {
      csv.append(
              Csv.row(
                  row.module(),
                  row.title(),
                  row.result(),
                  row.note(),
                  row.executorLogin(),
                  row.executorName(),
                  row.executedAt() == null ? "" : row.executedAt().format(time)))
          .append("\r\n");
    }
    return csvResponse("test-case-runs-" + LocalDate.now() + ".csv", csv.toString());
  }

  /**
   * CSV 导入（multipart 字段 {@code file}）：合法行入库、坏行逐条回执，不整体失败。
   * 单次上限 {@link TestCaseService#MAX_IMPORT_ROWS} 行，超限或缺 title 表头整体 400。
   */
  @PostMapping("/test-cases/import")
  public ResponseEntity<TestCaseService.ImportResult> importCases(
      @RequestParam("file") MultipartFile file, Authentication authentication)
      throws IOException {
    SessionUser user = CurrentUser.require(authentication);
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("请选择要导入的 CSV 文件");
    }
    String content = new String(file.getBytes(), StandardCharsets.UTF_8);
    return ResponseEntity.ok(testCaseService.importCases(content, user.id()));
  }

  /** CSV 下载响应：UTF-8 BOM 前缀 + RFC 5987 文件名（Excel/WPS 中文文件名不乱码）。 */
  private static ResponseEntity<byte[]> csvResponse(String filename, String body) {
    byte[] bytes = ("\uFEFF" + body).getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
    headers.setContentDisposition(
        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
    return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
  }

  /* ------------------------------------------------------------ A3：报表 */

  /** 报表概览：模块/优先级分布、结果汇总、每日趋势、失败 Top 模块 */
  @GetMapping("/test-reports/stats")
  public ResponseEntity<Map<String, Object>> stats(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status) {
    Map<String, Object> result = new HashMap<>();
    result.put("modulePriority", testCaseService.statModulePriority(module, status, q));
    result.put("resultSummary", testCaseService.statResultSummary(module, status, q));
    result.put("dailyTrend", testCaseService.statDailyTrend(module, status, q));
    result.put("failedTopModules", testCaseService.statFailedTopModules(module, status, q));
    return ResponseEntity.ok(result);
  }

  /** 报表 CSV 导出：复用 A1 模式，含模块/标题/优先级/状态/通过率/最后执行时间 */
  @GetMapping("/test-reports/export")
  public ResponseEntity<byte[]> exportTestReports(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status) {
    List<TestCaseView> rows = testCaseService.exportTestReports(module, q, status);
    StringBuilder csv = new StringBuilder();
    csv.append(Csv.row("module", "title", "priority", "status", "passRate", "lastRunAt"))
        .append("\r\n");
    for (TestCaseView row : rows) {
      int fc = row.failCount();
      double passRate = row.passCount() + fc > 0
          ? (double) row.passCount() / (row.passCount() + fc) * 100
          : 0.0;
      csv.append(
              Csv.row(
                  row.module(),
                  row.title(),
                  row.priority(),
                  row.status(),
                  String.format("%.2f", passRate),
                  row.lastRunAt() == null ? "" : row.lastRunAt().toString()))
          .append("\r\n");
    }
    String filename = "test-reports-" + LocalDate.now() + ".csv";
    return csvResponse(filename, csv.toString());
  }

  /* ------------------------------------------------------------ 执行记录 */

  @GetMapping("/test-cases/{id}/runs")
  public ResponseEntity<List<TestCaseRunRow>> runs(@PathVariable long id) {
    return ResponseEntity.ok(testCaseService.listRuns(id));
  }

  @PostMapping("/test-cases/{id}/runs")
  public ResponseEntity<TestCaseRunRow> addRun(
      @PathVariable long id, @Valid @RequestBody RunRequest body, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    TestCaseRunRow run = testCaseService.addRun(id, body.result(), body.note(), user.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(run);
  }

  @DeleteMapping("/test-cases/{id}/runs/{runId}")
  public ResponseEntity<Void> deleteRun(@PathVariable long id, @PathVariable long runId) {
    testCaseService.deleteRun(id, runId);
    return ResponseEntity.noContent().build();
  }
}

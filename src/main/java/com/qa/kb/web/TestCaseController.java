package com.qa.kb.web;

import com.qa.kb.model.TestCaseRunRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import com.qa.kb.testcase.TestCaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 用例管理与执行记录（P2 Phase 4）。
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

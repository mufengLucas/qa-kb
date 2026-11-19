package com.qa.kb.web;

import com.qa.kb.model.TestCaseCommentRow;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import com.qa.kb.testcase.TestCaseCommentService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用例评论 REST API（A4）。
 *
 * <p>路由：{@code /api/test-cases/{caseId}/comments}。@提及复用 {@code notifications} 表落站内信。
 */
@RestController
@RequestMapping("/api/test-cases/{caseId}/comments")
public class TestCaseCommentController {

  private final TestCaseCommentService commentService;

  public TestCaseCommentController(TestCaseCommentService commentService) {
    this.commentService = commentService;
  }

  /** 评论列表（时间正序）。 */
  @GetMapping
  public List<TestCaseCommentRow> list(@PathVariable long caseId) {
    return commentService.list(caseId);
  }

  /** 添加评论。@提及会为被提到的启用用户创建站内通知。 */
  @PostMapping
  public ResponseEntity<TestCaseCommentRow> add(
      @PathVariable long caseId,
      @RequestBody Map<String, String> body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    String content = body.get("body");
    if (content == null || content.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    TestCaseCommentRow created = commentService.add(caseId, user.id(), content);
    return ResponseEntity.ok(created);
  }

  /** 更新评论（仅作者）。 */
  @PutMapping("/{commentId}")
  public ResponseEntity<TestCaseCommentRow> update(
      @PathVariable long caseId,
      @PathVariable long commentId,
      @RequestBody Map<String, String> body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    String content = body.get("body");
    if (content == null || content.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    TestCaseCommentRow updated = commentService.update(commentId, user.id(), content);
    return ResponseEntity.ok(updated);
  }

  /** 删除评论（软删除）。仅作者或管理员。 */
  @DeleteMapping("/{commentId}")
  public ResponseEntity<Void> delete(
      @PathVariable long caseId, @PathVariable long commentId, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    boolean isAdmin = "ADMIN".equals(user.role().name());
    commentService.delete(commentId, user.id(), isAdmin);
    return ResponseEntity.noContent().build();
  }
}

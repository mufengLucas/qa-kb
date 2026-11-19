package com.qa.kb.testcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.NotificationRow;
import com.qa.kb.model.TestCaseCommentRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.persistence.NotificationMapper;
import com.qa.kb.persistence.TestCaseCommentMapper;
import java.time.Clock;
import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("TestCaseCommentService")
class TestCaseCommentServiceTest {

  @Mock private TestCaseCommentMapper commentMapper;
  @Mock private NotificationMapper notificationMapper;
  @Mock private TestCaseService testCaseService;

  private TestCaseCommentService service;

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @BeforeEach
  void setUp() {
    service = new TestCaseCommentService(commentMapper, notificationMapper, testCaseService, CLOCK);
  }

  private static TestCaseView testCase(long id) {
    LocalDateTime at = LocalDateTime.of(2026, 11, 19, 9, 0);
    return new TestCaseView(
        id, "登录", "正常登录", "已有账号", "1. 打开登录页", "进入首页", "P1", "ACTIVE", 1L,
        "admin", "管理员", at, at, 0, 0, 0, null, null);
  }

  private static TestCaseCommentRow comment(long id, long caseId, long authorId, String body) {
    return new TestCaseCommentRow(
        id, caseId, authorId, "alice", "小艾", body,
        LocalDateTime.of(2026, 11, 19, 10, 0), null);
  }

  @Test
  void listReturnsCommentsForExistingCase() {
    when(testCaseService.get(5L)).thenReturn(testCase(5L));
    when(commentMapper.listByCase(5L)).thenReturn(List.of(comment(1L, 5L, 2L, "第一条评论")));

    List<TestCaseCommentRow> result = service.list(5L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).body()).isEqualTo("第一条评论");
  }

  @Test
  void listThrowsForUnknownCase() {
    when(testCaseService.get(99L)).thenThrow(ApiException.notFound("用例不存在"));

    assertThatThrownBy(() -> service.list(99L))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void addCreatesCommentAndSkipsSelfMention() {
    when(testCaseService.get(5L)).thenReturn(testCase(5L));
    when(commentMapper.insert(eq(5L), eq(2L), any())).thenReturn(1);
    when(commentMapper.findLatestByCase(5L))
        .thenReturn(Optional.of(comment(10L, 5L, 2L, "请 @alice 复核")));

    TestCaseCommentRow result = service.add(5L, 2L, "请 @alice 复核");

    assertThat(result.id()).isEqualTo(10L);
    // alice 就是作者本人 → 不给自己发通知
    verify(notificationMapper, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void addNotifiesMentionedUser() {
    when(testCaseService.get(5L)).thenReturn(testCase(5L));
    when(commentMapper.insert(eq(5L), eq(2L), any())).thenReturn(1);
    when(commentMapper.findLatestByCase(5L))
        .thenReturn(Optional.of(comment(10L, 5L, 2L, "请 @bob 复核")));
    when(commentMapper.findUserIdByLogin("bob")).thenReturn(Optional.of(7L));

    service.add(5L, 2L, "请 @bob 复核");

    verify(notificationMapper)
        .insertIfAbsent(
            eq(7L),
            eq("case_comment_mention"),
            eq("用例评论@"),
            any(),
            eq("test_case"),
            eq(5L),
            eq("case_comment_mention:10"));
  }

  @Test
  void addIgnoresUnknownMentionLogin() {
    when(testCaseService.get(5L)).thenReturn(testCase(5L));
    when(commentMapper.insert(eq(5L), eq(2L), any())).thenReturn(1);
    when(commentMapper.findLatestByCase(5L))
        .thenReturn(Optional.of(comment(10L, 5L, 2L, "请 @nobody 复核")));
    when(commentMapper.findUserIdByLogin("nobody")).thenReturn(Optional.empty());

    service.add(5L, 2L, "请 @nobody 复核");

    verify(notificationMapper, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void addRejectsBlankBody() {
    when(testCaseService.get(5L)).thenReturn(testCase(5L));

    assertThatThrownBy(() -> service.add(5L, 2L, "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addRejectsOverlongBody() {
    when(testCaseService.get(5L)).thenReturn(testCase(5L));
    String longBody = "x".repeat(TestCaseCommentService.MAX_BODY_LENGTH + 1);

    assertThatThrownBy(() -> service.add(5L, 2L, longBody))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateAllowsAuthor() {
    when(commentMapper.findById(3L)).thenReturn(Optional.of(comment(3L, 5L, 2L, "旧内容")));
    when(commentMapper.update(eq(3L), eq("新内容"), any())).thenReturn(1);
    when(commentMapper.findById(3L))
        .thenReturn(Optional.of(comment(3L, 5L, 2L, "新内容")));

    TestCaseCommentRow result = service.update(3L, 2L, "新内容");

    assertThat(result.body()).isEqualTo("新内容");
  }

  @Test
  void updateRejectsNonAuthor() {
    when(commentMapper.findById(3L)).thenReturn(Optional.of(comment(3L, 5L, 2L, "旧内容")));

    assertThatThrownBy(() -> service.update(3L, 99L, "篡改"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void deleteAllowsAuthor() {
    when(commentMapper.findById(3L)).thenReturn(Optional.of(comment(3L, 5L, 2L, "内容")));

    service.delete(3L, 2L, false);

    verify(commentMapper).delete(eq(3L), any());
  }

  @Test
  void deleteAllowsAdmin() {
    when(commentMapper.findById(3L)).thenReturn(Optional.of(comment(3L, 5L, 2L, "内容")));

    service.delete(3L, 1L, true);

    verify(commentMapper).delete(eq(3L), any());
  }

  @Test
  void deleteRejectsNonAuthorNonAdmin() {
    when(commentMapper.findById(3L)).thenReturn(Optional.of(comment(3L, 5L, 2L, "内容")));

    assertThatThrownBy(() -> service.delete(3L, 99L, false))
        .isInstanceOf(ApiException.class);
  }
}

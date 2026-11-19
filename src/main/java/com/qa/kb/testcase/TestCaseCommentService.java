package com.qa.kb.testcase;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.NotificationRow;
import com.qa.kb.model.TestCaseCommentRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.persistence.NotificationMapper;
import com.qa.kb.persistence.TestCaseCommentMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用例评论与 @提及（A4）。
 *
 * <p>评论是评审协作的最小闭环：@提及复用 {@code notifications} 表落站内信，
 * 并与 A2 外发通道联动（每日汇总）。评论删除是软删除（{@code deleted_at}）。
 */
public class TestCaseCommentService {

  public static final int MAX_BODY_LENGTH = 2000;

  /** @提及正则：匹配 {@code @login}，login 必须以字母/数字开头。 */
  private static final Pattern MENTION_PATTERN = Pattern.compile("@([a-z0-9][a-z0-9._-]{2,63})");

  private final TestCaseCommentMapper commentMapper;
  private final NotificationMapper notificationMapper;
  private final TestCaseService testCaseService;
  private final Clock clock;

  public TestCaseCommentService(
      TestCaseCommentMapper commentMapper,
      NotificationMapper notificationMapper,
      TestCaseService testCaseService,
      Clock clock) {
    this.commentMapper = commentMapper;
    this.notificationMapper = notificationMapper;
    this.testCaseService = testCaseService;
    this.clock = clock;
  }

  /** 某用例的评论列表（按时间正序）。 */
  public List<TestCaseCommentRow> list(long caseId) {
    testCaseService.get(caseId); // 校验用例存在
    return commentMapper.listByCase(caseId);
  }

  /**
   * 添加评论。@提及会为每个被提到的启用用户创建站内通知
   * （type=case_comment_mention，dedup_key=case_comment_mention:&lt;commentId&gt;）。
   *
   * @return 创建的评论
   */
  @Transactional
  public TestCaseCommentRow add(long caseId, long authorId, String body) {
    testCaseService.get(caseId); // 校验用例存在
    String cleanBody = cleanBody(body);
    commentMapper.insert(caseId, authorId, cleanBody);

    // 回读最新评论（拿 id 用于通知 dedup_key）
    TestCaseCommentRow created =
        commentMapper
            .findLatestByCase(caseId)
            .orElseThrow(() -> new IllegalStateException("评论创建后未能读取"));
    long commentId = created.id();

    // @提及 → 站内通知
    List<Long> mentionedUserIds = extractMentions(cleanBody);
    for (Long userId : mentionedUserIds) {
      if (userId == authorId) {
        continue; // 不给自己发通知
      }
      TestCaseView testCase = testCaseService.get(caseId);
      String title = "用例评论@";
      String notifyBody =
          (testCase.title() != null ? testCase.title() : "（无标题）") + " 中提到了你。";
      notificationMapper.insertIfAbsent(
          userId,
          "case_comment_mention",
          title,
          notifyBody,
          "test_case",
          caseId,
          "case_comment_mention:" + commentId);
    }

    return created;
  }

  /** 更新评论正文（仅作者）。 */
  @Transactional
  public TestCaseCommentRow update(long commentId, long userId, String body) {
    TestCaseCommentRow existing =
        commentMapper
            .findById(commentId)
            .orElseThrow(() -> ApiException.notFound("评论不存在"));
    if (existing.authorId() != userId) {
      throw ApiException.badRequest("只能编辑自己的评论");
    }
    String cleanBody = cleanBody(body);
    commentMapper.update(commentId, cleanBody, LocalDateTime.now(clock));
    return commentMapper
        .findById(commentId)
        .orElseThrow(() -> new IllegalStateException("评论更新后未能读取"));
  }

  /** 删除评论（软删除）。仅作者或管理员可调用。 */
  @Transactional
  public void delete(long commentId, long userId, boolean isAdmin) {
    TestCaseCommentRow existing =
        commentMapper
            .findById(commentId)
            .orElseThrow(() -> ApiException.notFound("评论不存在"));
    if (existing.authorId() != userId && !isAdmin) {
      throw ApiException.badRequest("只能删除自己的评论（管理员可删除任意评论）");
    }
    commentMapper.delete(commentId, LocalDateTime.now(clock));
  }

  /* ------------------------------------------------------------ 内部 */

  /** 正文清洗：去空白、限长。 */
  private static String cleanBody(String body) {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("评论内容不能为空");
    }
    String clean = body.strip();
    if (clean.length() > MAX_BODY_LENGTH) {
      throw new IllegalArgumentException("评论不能超过 " + MAX_BODY_LENGTH + " 个字符");
    }
    return clean;
  }

  /** 从正文中提取 @login 提及，返回启用用户的 id 列表（去重、按出现顺序）。 */
  private List<Long> extractMentions(String body) {
    List<Long> userIds = new ArrayList<>();
    Matcher matcher = MENTION_PATTERN.matcher(body.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String login = matcher.group(1);
      commentMapper.findUserIdByLogin(login).ifPresent(userId -> {
        if (!userIds.contains(userId)) {
          userIds.add(userId);
        }
      });
    }
    return userIds;
  }
}
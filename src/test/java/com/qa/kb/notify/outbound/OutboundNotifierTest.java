package com.qa.kb.notify.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qa.kb.config.KbProperties;
import com.qa.kb.notify.NotificationService.NewNotice;
import com.qa.kb.notify.NotificationService.ScanResult;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.persistence.UserMapper.UserContact;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 外发汇总的编排行为：只发增量、通道不配不发、失败隔离、邮件与 IM 同款内容。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboundNotifier")
class OutboundNotifierTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  /** 业务日期 2026-11-18（周三）。 */
  private static final Instant NOW = Instant.parse("2026-11-17T17:00:00Z");

  @Mock private UserMapper userMapper;
  @Mock private EmailSender emailSender;
  @Mock private WebhookSender webhookSender;

  private KbProperties kb;
  private OutboundNotifier notifier;

  @BeforeEach
  void setUp() {
    kb = new KbProperties();
    Clock clock = Clock.fixed(NOW, ZONE);
    notifier = new OutboundNotifier(kb, userMapper, emailSender, webhookSender, clock);
  }

  private static NewNotice weekly(long userId) {
    return new NewNotice(
        userId, "weekly_report", "周报提醒", "本周（11月16日 ~ 11月22日）周报尚未提交，请到「周报」页填写。");
  }

  private static NewNotice overdue(long userId) {
    return new NewNotice(
        userId, "task_overdue", "任务逾期", "任务「登录联调」（支付平台）已逾期，截止 11月16日。");
  }

  private static ScanResult scanOf(NewNotice... notices) {
    long weekly = List.of(notices).stream().filter(n -> "weekly_report".equals(n.type())).count();
    long overdue = List.of(notices).stream().filter(n -> "task_overdue".equals(n.type())).count();
    return new ScanResult((int) weekly, (int) overdue, List.of(notices));
  }

  /* ------------------------------------------------------------ 门槛 */

  @Test
  @DisplayName("幂等重扫（0 条新增）：任何通道都不碰，也不查联系人")
  void emptyScanSendsNothing() {
    notifier.sendIfAny(scanOf());

    verifyNoInteractions(emailSender, webhookSender, userMapper);
  }

  @Test
  @DisplayName("两个通道都没配置：跳过且不查库（不配置就不启用）")
  void unconfiguredChannelsSkipWithoutDbLookup() {
    when(emailSender.isEnabled()).thenReturn(false);
    when(webhookSender.isEnabled()).thenReturn(false);

    notifier.sendIfAny(scanOf(weekly(2L)));

    verifyNoInteractions(userMapper);
    verify(emailSender, never()).send(anyString(), anyString(), anyList(), anyList());
    verify(webhookSender, never()).send(anyString());
  }

  /* ------------------------------------------------------------ 投递 */

  @Test
  @DisplayName("邮件与 IM 收到同一份汇总：固定列表在 To、成员邮箱走 Bcc")
  void sendsSameDigestToEmailAndIm() {
    when(emailSender.isEnabled()).thenReturn(true);
    when(webhookSender.isEnabled()).thenReturn(true);
    when(userMapper.listContacts())
        .thenReturn(List.of(new UserContact(2L, "张三", "zhang@example.com")));
    kb.getNotify().getEmail().setTo(" oncall@example.com, oncall@example.com , duty@example.com ");

    notifier.sendIfAny(scanOf(weekly(2L), overdue(2L)));

    ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> emailBody = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> bcc = ArgumentCaptor.forClass(List.class);
    verify(emailSender).send(subject.capture(), emailBody.capture(), to.capture(), bcc.capture());
    ArgumentCaptor<String> imBody = ArgumentCaptor.forClass(String.class);
    verify(webhookSender).send(imBody.capture());

    // 同款内容
    assertThat(imBody.getValue()).isEqualTo(emailBody.getValue());
    String digest = emailBody.getValue();
    assertThat(digest)
        .contains("每日通知汇总（2026-11-18）")
        .contains("本次新增 2 条：周报提醒 1 · 任务逾期 1")
        .contains("【周报提醒】")
        .contains("· 张三：")
        .contains("【任务逾期】");

    assertThat(subject.getValue()).isEqualTo("[QA 工作台] 每日通知汇总 2026-11-18");
    // 固定列表去重后进 To，成员邮箱进 Bcc
    assertThat(to.getValue()).containsExactly("oncall@example.com", "duty@example.com");
    assertThat(bcc.getValue()).containsExactly("zhang@example.com");
  }

  @Test
  @DisplayName("邮件挂了不影响 IM，IM 挂了不影响邮件；异常绝不外抛")
  void channelFailuresAreIsolated() {
    when(emailSender.isEnabled()).thenReturn(true);
    when(webhookSender.isEnabled()).thenReturn(true);
    when(userMapper.listContacts()).thenReturn(List.of(new UserContact(3L, "李四", "li@example.com")));
    doThrow(new IllegalStateException("SMTP 535 认证失败"))
        .when(emailSender)
        .send(anyString(), anyString(), anyList(), anyList());
    doThrow(new IllegalStateException("webhook 404")).when(webhookSender).send(anyString());

    assertThatCode(() -> notifier.sendIfAny(scanOf(overdue(3L))))
        .as("外发失败只记日志，不能抛给调度线程/HTTP")
        .doesNotThrowAnyException();

    // 两个通道都尝试过（一个失败不短路另一个）
    verify(emailSender).send(anyString(), anyString(), anyList(), anyList());
    verify(webhookSender).send(anyString());
  }

  @Test
  @DisplayName("邮件启用但没有任何收件人：跳过邮件，IM 照发")
  void emailWithoutRecipientsSkipsButImStillSends() {
    when(emailSender.isEnabled()).thenReturn(true);
    when(webhookSender.isEnabled()).thenReturn(true);
    when(userMapper.listContacts()).thenReturn(List.of(new UserContact(2L, "张三", "")));

    notifier.sendIfAny(scanOf(weekly(2L)));

    verify(emailSender, never()).send(anyString(), anyString(), anyList(), anyList());
    verify(webhookSender).send(anyString());
  }

  /* ------------------------------------------------------------ 纯函数 */

  @Test
  @DisplayName("固定收件人解析：逗号分隔、去空白、去重")
  void parsesFixedRecipients() {
    assertThat(OutboundNotifier.fixedRecipients(null)).isEmpty();
    assertThat(OutboundNotifier.fixedRecipients("  ")).isEmpty();
    assertThat(OutboundNotifier.fixedRecipients("a@x.com,b@x.com , a@x.com ,,"))
        .containsExactly("a@x.com", "b@x.com");
  }

  @Test
  @DisplayName("To/Bcc 切分：有固定列表时成员进 Bcc，没有时成员顶上 To")
  void splitsEnvelope() {
    List<String> members = List.of("m@x.com");
    OutboundNotifier.Envelope withFixed =
        OutboundNotifier.envelope(List.of("f@x.com"), members);
    assertThat(withFixed.to()).containsExactly("f@x.com");
    assertThat(withFixed.bcc()).containsExactly("m@x.com");

    OutboundNotifier.Envelope withoutFixed = OutboundNotifier.envelope(List.of(), members);
    assertThat(withoutFixed.to()).containsExactly("m@x.com");
    assertThat(withoutFixed.bcc()).isEmpty();
  }

  @Test
  @DisplayName("收件人邮箱：去空、去重、排序；联系人缺失的 id 跳过")
  void collectsInvolvedEmails() {
    ScanResult result = scanOf(weekly(2L), weekly(3L), overdue(4L), overdue(2L));
    Map<Long, UserContact> contacts =
        Map.of(
            2L, new UserContact(2L, "张三", " b@x.com "),
            3L, new UserContact(3L, "李四", ""),
            4L, new UserContact(4L, "王五", "a@x.com"));
    // 4L 有邮箱、2L 出现两次只算一次、3L 空邮箱跳过、9L 无联系人跳过
    ScanResult withUnknown =
        new ScanResult(1, 1, List.of(weekly(2L), weekly(9L), overdue(4L), weekly(3L)));

    assertThat(OutboundNotifier.involvedEmails(result, contacts))
        .containsExactly("a@x.com", "b@x.com");
    assertThat(OutboundNotifier.involvedEmails(withUnknown, contacts))
        .containsExactly("a@x.com", "b@x.com");
  }

  @Test
  @DisplayName("汇总截断：明细超 20 行只保留 20 行并给出余量提示")
  void digestTruncatesLongLists() {
    NewNotice[] many = new NewNotice[30];
    for (int i = 0; i < many.length; i++) {
      many[i] = new NewNotice(100L + i, "task_overdue", "任务逾期", "任务「t" + i + "」已逾期。");
    }

    String digest = OutboundNotifier.buildDigest(LocalDate.of(2026, 11, 18), scanOf(many), Map.of());

    assertThat(digest).contains("本次新增 30 条").contains("…其余 10 条见站内「通知」");
    assertThat(digest.lines().filter(l -> l.startsWith("· "))).hasSize(OutboundNotifier.MAX_LINES);
    assertThat(digest.length()).isLessThanOrEqualTo(OutboundNotifier.MAX_CHARS);
  }

  @Test
  @DisplayName("汇总明细：未知收件人用成员#id 兜底，超长单行截断")
  void digestHandlesUnknownContactsAndLongBodies() {
    NewNotice longBody =
        new NewNotice(42L, "task_overdue", "任务逾期", "长".repeat(300));

    String digest =
        OutboundNotifier.buildDigest(LocalDate.of(2026, 11, 18), scanOf(longBody), Map.of());

    assertThat(digest).contains("· 成员#42：");
    assertThat(digest).contains("长".repeat(OutboundNotifier.MAX_LINE_CHARS) + "…");
  }
}

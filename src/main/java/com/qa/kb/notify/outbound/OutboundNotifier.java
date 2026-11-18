package com.qa.kb.notify.outbound;

import com.qa.kb.config.KbProperties;
import com.qa.kb.notify.NotificationService;
import com.qa.kb.notify.NotificationService.NewNotice;
import com.qa.kb.notify.NotificationService.ScanResult;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.persistence.UserMapper.UserContact;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2 外发通知：把一次扫描「真正新增」的站内通知合并成一条每日汇总，投递到已配置的通道
 * （SMTP 邮件与通用 IM Webhook 各自独立开关，不配置就不启用）。
 *
 * <p>三个不变式：
 *
 * <ol>
 *   <li><b>只发增量</b>：幂等重扫产生 0 条新通知时什么都不发——同一份汇总不会因重复触发而重发；
 *   <li><b>失败隔离</b>：任何通道异常只记日志，绝不向调度线程或 HTTP 响应抛出——站内通知
 *       在扫描时已落库，外发是旁路，挂了不能连累扫描；
 *   <li><b>同款内容</b>：邮件与 IM 收到的是同一份汇总文本；收件人 = 固定列表（To）
 *       ∪ 本次新通知的收件人邮箱（Bcc，互不暴露地址）。
 * </ol>
 */
public class OutboundNotifier {

  private static final Logger log = LoggerFactory.getLogger(OutboundNotifier.class);

  /** 汇总明细最多行数：逾期任务成批出现时不让消息爆炸。 */
  static final int MAX_LINES = 20;
  /** 单行明细截断长度。 */
  static final int MAX_LINE_CHARS = 100;
  /** 整体截断长度：IM 机器人普遍 4096 字节上限，1200 字符 ≈ 3600 字节留出余量。 */
  static final int MAX_CHARS = 1200;

  private final KbProperties kb;
  private final UserMapper userMapper;
  private final EmailSender emailSender;
  private final WebhookSender webhookSender;
  private final Clock clock;

  public OutboundNotifier(
      KbProperties kb,
      UserMapper userMapper,
      EmailSender emailSender,
      WebhookSender webhookSender,
      Clock clock) {
    this.kb = kb;
    this.userMapper = userMapper;
    this.emailSender = emailSender;
    this.webhookSender = webhookSender;
    this.clock = clock;
  }

  /**
   * 扫描后调用：有新增且至少一个通道启用才外发。永不抛出。
   *
   * @param result 一次扫描的产出（含新增通知明细）
   */
  public void sendIfAny(ScanResult result) {
    try {
      if (result == null || result.newNotices().isEmpty()) {
        return;
      }
      boolean emailOn = emailSender.isEnabled();
      boolean imOn = webhookSender.isEnabled();
      if (!emailOn && !imOn) {
        log.debug("外发通知：邮件与 IM 均未配置，跳过（本次新增 {} 条）",
            result.newNotices().size());
        return;
      }
      Map<Long, UserContact> contacts = contacts();
      String digest = buildDigest(LocalDate.now(clock), result, contacts);
      if (emailOn) {
        try {
          sendEmail(digest, result, contacts);
        } catch (RuntimeException e) {
          log.warn("邮件外发失败（站内通知不受影响）: {}", e.getMessage(), e);
        }
      }
      if (imOn) {
        try {
          webhookSender.send(digest);
        } catch (RuntimeException e) {
          log.warn("IM 外发失败（站内通知不受影响）: {}", e.getMessage(), e);
        }
      }
      log.info(
          "外发汇总完成: 新增 {} 条, email={}, im={}",
          result.newNotices().size(),
          emailOn,
          imOn);
    } catch (RuntimeException e) {
      log.warn("外发通知整体失败（站内通知不受影响）: {}", e.getMessage(), e);
    }
  }

  private void sendEmail(String digest, ScanResult result, Map<Long, UserContact> contacts) {
    List<String> fixed = fixedRecipients(kb.getNotify().getEmail().getTo());
    List<String> members = involvedEmails(result, contacts);
    Envelope envelope = envelope(fixed, members);
    if (envelope.to().isEmpty() && envelope.bcc().isEmpty()) {
      log.info("邮件通道已启用但没有收件人（检查 KB_NOTIFY_EMAIL_TO 与成员邮箱），本次跳过");
      return;
    }
    String subject =
        "[QA 工作台] 每日通知汇总 " + LocalDate.now(clock);
    emailSender.send(subject, digest, envelope.to(), envelope.bcc());
  }

  private Map<Long, UserContact> contacts() {
    // 团队规模有限：一次取回 id→(姓名, 邮箱)，汇总拼装与收件人解析都走内存
    Map<Long, UserContact> byId = new LinkedHashMap<>();
    for (UserContact contact : userMapper.listContacts()) {
      byId.put(contact.id(), contact);
    }
    return byId;
  }

  /* ------------------------------------------------------------ 纯函数（单测入口） */

  /** 固定收件人：逗号分隔、去空白、去重；空配置 → 空列表。 */
  static List<String> fixedRecipients(String to) {
    if (to == null || to.isBlank()) {
      return List.of();
    }
    return Arrays.stream(to.split(","))
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .distinct()
        .toList();
  }

  /** 主送/密送切分：有固定列表时它在 To、成员邮箱走 Bcc；没有固定列表时成员顶上 To。 */
  record Envelope(List<String> to, List<String> bcc) {}

  static Envelope envelope(List<String> fixed, List<String> members) {
    if (!fixed.isEmpty()) {
      return new Envelope(fixed, members);
    }
    return new Envelope(members, List.of());
  }

  /** 本次新通知收件人的邮箱（去重、去空、排序保证稳定）。 */
  static List<String> involvedEmails(ScanResult result, Map<Long, UserContact> contacts) {
    return result.newNotices().stream()
        .map(NewNotice::userId)
        .distinct()
        .map(contacts::get)
        .filter(Objects::nonNull)
        .map(UserContact::email)
        .map(email -> email == null ? "" : email.strip())
        .filter(email -> !email.isEmpty())
        .distinct()
        .sorted()
        .toList();
  }

  /**
   * 拼装每日汇总：抬头 + 分类计数 + 逐条明细（姓名 + 正文），带行数/单行/整体三层截断。
   * 邮件与 IM 同款文本就是它。
   */
  static String buildDigest(
      LocalDate today, ScanResult result, Map<Long, UserContact> contacts) {
    List<NewNotice> notices = result.newNotices();
    long weekly =
        notices.stream().filter(n -> NotificationService.TYPE_WEEKLY_REPORT.equals(n.type())).count();
    long overdue =
        notices.stream().filter(n -> NotificationService.TYPE_TASK_OVERDUE.equals(n.type())).count();

    StringBuilder sb = new StringBuilder();
    sb.append("QA 工作台 · 每日通知汇总（").append(today).append("）\n\n");
    sb.append("本次新增 ")
        .append(notices.size())
        .append(" 条：周报提醒 ")
        .append(weekly)
        .append(" · 任务逾期 ")
        .append(overdue)
        .append('\n');

    String currentType = null;
    int lines = 0;
    for (NewNotice notice : notices) {
      if (lines >= MAX_LINES) {
        sb.append("…其余 ").append(notices.size() - lines).append(" 条见站内「通知」\n");
        break;
      }
      if (!notice.type().equals(currentType)) {
        // 按通知类型分组（新类型自动成组，向前兼容）
        currentType = notice.type();
        sb.append("\n【").append(notice.title()).append("】\n");
      }
      UserContact contact = contacts.get(notice.userId());
      String name = contact != null && contact.name() != null && !contact.name().isBlank()
          ? contact.name()
          : "成员#" + notice.userId();
      sb.append("· ").append(name).append("：").append(clip(notice.body())).append('\n');
      lines++;
    }

    if (sb.length() > MAX_CHARS) {
      return sb.substring(0, MAX_CHARS) + "\n…（内容过长已截断，完整清单见站内「通知」）";
    }
    return sb.toString();
  }

  private static String clip(String line) {
    return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "…";
  }
}

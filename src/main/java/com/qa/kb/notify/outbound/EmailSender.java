package com.qa.kb.notify.outbound;

import com.qa.kb.config.KbProperties;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * A2 外发通知 · SMTP 邮件通道。
 *
 * <p>启用条件：{@code kb.notify.email.enabled=true} 且 {@code host} 非空——两个都配了才发，
 * 否则整个通道静默跳过（不配置就不启用）。凭据只来自环境变量，不入库、不进仓库。
 *
 * <p>每次发送现场组装 {@link JavaMailSenderImpl}（不用 Spring Mail 自动配置），
 * 便于按端口切换 STARTTLS / SSL，也避免引入 {@code spring.mail.*} 全局配置。
 */
public class EmailSender {

  private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int IO_TIMEOUT_MS = 10_000;

  private final KbProperties kb;

  public EmailSender(KbProperties kb) {
    this.kb = kb;
  }

  /** 通道是否启用：开关打开且 SMTP 主机已配置。 */
  public boolean isEnabled() {
    KbProperties.Email email = kb.getNotify().getEmail();
    return email.isEnabled() && email.getHost() != null && !email.getHost().isBlank();
  }

  /**
   * 发送一封纯文本邮件。未启用或没有收件人时直接返回（由调用方决定是否记日志）。
   *
   * @param to 主送（固定收件人列表）
   * @param bcc 密送（成员邮箱，互不暴露地址）
   */
  public void send(String subject, String text, List<String> to, List<String> bcc) {
    if (!isEnabled()) {
      return;
    }
    if (to.isEmpty() && bcc.isEmpty()) {
      log.info("邮件通道已启用但没有收件人（检查 KB_NOTIFY_EMAIL_TO 与成员邮箱），本次跳过");
      return;
    }
    KbProperties.Email cfg = kb.getNotify().getEmail();
    JavaMailSenderImpl sender = buildSender(cfg);
    try {
      MimeMessageHelper helper =
          new MimeMessageHelper(sender.createMimeMessage(), MimeMessageHelper.MULTIPART_MODE_NO,
              "UTF-8");
      helper.setFrom(resolveFrom(cfg));
      if (!to.isEmpty()) {
        helper.setTo(to.toArray(new String[0]));
      }
      if (!bcc.isEmpty()) {
        helper.setBcc(bcc.toArray(new String[0]));
      }
      helper.setSubject(subject);
      helper.setText(text, false);
      sender.send(helper.getMimeMessage());
      log.info("邮件外发成功: to={}, bcc={}, subject={}", to, bcc, subject);
    } catch (Exception e) {
      // 失败只包装上抛，由 OutboundNotifier 记日志——绝不影响站内通知与调度线程
      throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
    }
  }

  private static JavaMailSenderImpl buildSender(KbProperties.Email cfg) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(cfg.getHost());
    sender.setPort(cfg.getPort());
    boolean auth = cfg.getUsername() != null && !cfg.getUsername().isBlank();
    if (auth) {
      sender.setUsername(cfg.getUsername());
      sender.setPassword(cfg.getPassword());
    }
    Properties props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", String.valueOf(auth));
    props.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
    props.put("mail.smtp.timeout", String.valueOf(IO_TIMEOUT_MS));
    props.put("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT_MS));
    if (cfg.getPort() == 465) {
      props.put("mail.smtp.ssl.enable", "true");
    } else if (cfg.getPort() == 587 || cfg.getPort() == 25) {
      // 587（投稿端口）走 STARTTLS 明文升级；25 通常也支持
      props.put("mail.smtp.starttls.enable", "true");
    }
    return sender;
  }

  /** 发件人：显式 from > username（看着像邮箱时）> 兜底地址。 */
  private static String resolveFrom(KbProperties.Email cfg) {
    if (cfg.getFrom() != null && !cfg.getFrom().isBlank()) {
      return cfg.getFrom().strip();
    }
    if (cfg.getUsername() != null && cfg.getUsername().contains("@")) {
      return cfg.getUsername().strip();
    }
    return "qa-kb@localhost";
  }
}

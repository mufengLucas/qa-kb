package com.qa.kb.notify.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.qa.kb.config.KbProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 邮件通道的启用门槛与空收件人短路。
 *
 * <p>真实 SMTP 投递不在单测范围（需要邮件服务器）；这里守住两条边界：
 * 不配置就不启用、启用了没收件人也不能去连网。
 */
@DisplayName("EmailSender")
class EmailSenderTest {

  private KbProperties kb;
  private EmailSender sender;

  @BeforeEach
  void setUp() {
    kb = new KbProperties();
    sender = new EmailSender(kb);
  }

  @Test
  @DisplayName("启用条件：enabled=true 且 host 非空，缺一不可")
  void enabledRequiresFlagAndHost() {
    assertThat(sender.isEnabled()).isFalse();

    kb.getNotify().getEmail().setEnabled(true);
    assertThat(sender.isEnabled()).as("开了开关但没配主机").isFalse();

    kb.getNotify().getEmail().setHost("smtp.example.com");
    assertThat(sender.isEnabled()).isTrue();

    kb.getNotify().getEmail().setEnabled(false);
    assertThat(sender.isEnabled()).as("配了主机但开关关着").isFalse();
  }

  @Test
  @DisplayName("未启用时 send 直接返回：不抛异常、不触网")
  void sendIsNoOpWhenDisabled() {
    assertThatCode(
            () -> sender.send("主题", "正文", List.of("a@example.com"), List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("启用了但一个收件人都没有：短路返回，绝不去连 SMTP")
  void sendShortCircuitsWithoutRecipients() {
    kb.getNotify().getEmail().setEnabled(true);
    // 主机指向必然连不上的端口——如果 send 没有先判空收件人，这里会抛连接异常
    kb.getNotify().getEmail().setHost("127.0.0.1");
    kb.getNotify().getEmail().setPort(1);

    assertThatCode(() -> sender.send("主题", "正文", List.of(), List.of()))
        .doesNotThrowAnyException();
  }
}

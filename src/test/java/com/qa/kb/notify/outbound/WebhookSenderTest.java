package com.qa.kb.notify.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Webhook 报文与签名的纯函数测试。
 *
 * <p>签名基准向量（dingtalk / feishu 各一组）由 .NET {@code HMACSHA256} 独立计算后固化，
 * 不是在 Java 里用同一段逻辑自证——换实现也必须对上这两个值。
 */
@DisplayName("WebhookSender")
class WebhookSenderTest {

  private static final String SECRET = "testsecret123";

  @Test
  @DisplayName("类型归一化：别名收敛，未识别值兜底 wecom")
  void normalizesTypeAliases() {
    assertThat(WebhookSender.normalizeType("wecom")).isEqualTo("wecom");
    assertThat(WebhookSender.normalizeType("企业微信")).isEqualTo("wecom");
    assertThat(WebhookSender.normalizeType("dingtalk")).isEqualTo("dingtalk");
    assertThat(WebhookSender.normalizeType(" 钉钉 ")).isEqualTo("dingtalk");
    assertThat(WebhookSender.normalizeType("feishu")).isEqualTo("feishu");
    assertThat(WebhookSender.normalizeType("Lark")).isEqualTo("feishu");
    assertThat(WebhookSender.normalizeType("unknown-bot")).isEqualTo("wecom");
    assertThat(WebhookSender.normalizeType(null)).isEqualTo("wecom");
  }

  @Test
  @DisplayName("企微/钉钉报文同形：text.content；飞书是 msg_type.content.text")
  void payloadShapesPerPlatform() {
    String text = "汇总内容";
    assertThat(WebhookSender.payloadFor("wecom", text, "", 0L))
        .isEqualTo("{\"msgtype\":\"text\",\"text\":{\"content\":\"汇总内容\"}}");
    assertThat(WebhookSender.payloadFor("dingtalk", text, "", 0L))
        .isEqualTo("{\"msgtype\":\"text\",\"text\":{\"content\":\"汇总内容\"}}");
    assertThat(WebhookSender.payloadFor("feishu", text, "", 0L))
        .isEqualTo("{\"msg_type\":\"text\",\"content\":{\"text\":\"汇总内容\"}}");
  }

  @Test
  @DisplayName("钉钉加签进 URL（毫秒时间戳），飞书签名进报文（秒时间戳字符串）")
  void signsGoToTheRightPlace() {
    String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";
    String signed = WebhookSender.signedUrl(url, "dingtalk", SECRET, 1700000000000L);
    assertThat(signed)
        .startsWith(url + "&timestamp=1700000000000&sign=")
        // Base64 的 + / = 必须转义，否则钉钉校验不过
        .contains("%2B")
        .doesNotContain("+");

    String payload = WebhookSender.payloadFor("feishu", "hi", SECRET, 1700000000000L);
    assertThat(payload)
        .contains("\"timestamp\":\"1700000000\"")
        .contains("\"sign\":\"iYcwMpmD1osicaNbIWpAMrFWtPtS5VW1nwh4uwh9ga0=\"")
        .contains("\"msg_type\":\"text\"");
  }

  @Test
  @DisplayName("签名基准向量：与 .NET HMACSHA256 独立计算结果一致")
  void signingMatchesIndependentVectors() {
    assertThat(WebhookSender.dingtalkSign(SECRET, 1700000000000L))
        .isEqualTo("KT2B6qG7GXEVi6neKXO5+uGvfvWi9F2yU5lOqG+DDvo=");
    assertThat(WebhookSender.feishuSign(SECRET, 1700000000L))
        .isEqualTo("iYcwMpmD1osicaNbIWpAMrFWtPtS5VW1nwh4uwh9ga0=");
  }

  @Test
  @DisplayName("没配 secret 或平台不支持签名时 URL 原样返回")
  void unsignedWhenSecretMissing() {
    String url = "https://example.com/hook?x=1";
    assertThat(WebhookSender.signedUrl(url, "dingtalk", "", 1L)).isEqualTo(url);
    assertThat(WebhookSender.signedUrl(url, "dingtalk", null, 1L)).isEqualTo(url);
    assertThat(WebhookSender.signedUrl(url, "wecom", SECRET, 1L)).isEqualTo(url);
    assertThat(WebhookSender.signedUrl(url, "feishu", SECRET, 1L)).isEqualTo(url);
  }

  @Test
  @DisplayName("业务错误识别：errcode/code 为 0 视为成功，非 0 是失败（HTTP 仍可能 200）")
  void detectsLogicalErrors() {
    assertThat(WebhookSender.logicalError("")).isFalse();
    assertThat(WebhookSender.logicalError("{\"errcode\":0,\"errmsg\":\"ok\"}")).isFalse();
    assertThat(WebhookSender.logicalError("{\"errcode\":93000,\"errmsg\":\"sign not match\"}"))
        .isTrue();
    assertThat(WebhookSender.logicalError("{\"code\":0,\"msg\":\"success\"}")).isFalse();
    assertThat(WebhookSender.logicalError("{\"code\":19001,\"msg\":\"param invalid\"}")).isTrue();
    // 纯文本错误体不含错误码字段时不误判（由 HTTP 状态码把关）
    assertThat(WebhookSender.logicalError("plain text response")).isFalse();
  }

  @Test
  @DisplayName("JSON 转义：引号、反斜杠、换行与控制字符都转成安全序列")
  void escapesJsonStrings() {
    assertThat(WebhookSender.jsonEscape("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
    assertThat(WebhookSender.jsonEscape("第一行\n第二行\t!")).isEqualTo("第一行\\n第二行\\t!");
    assertThat(WebhookSender.jsonEscape("bell\u0007")).isEqualTo("bell\\u0007");
    assertThat(WebhookSender.jsonEscape("中文与 emoji 🎉")).isEqualTo("中文与 emoji 🎉");
  }
}

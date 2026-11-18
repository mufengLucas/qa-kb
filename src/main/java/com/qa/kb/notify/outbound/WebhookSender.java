package com.qa.kb.notify.outbound;

import com.qa.kb.config.KbProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2 外发通知 · 通用 IM 机器人 Webhook（企业微信 / 钉钉 / 飞书）。
 *
 * <p>启用条件：{@code kb.notify.im.url} 非空——不配置就不启用。报文形状按 {@code type} 分支：
 *
 * <ul>
 *   <li>{@code wecom}（默认）：{@code {"msgtype":"text","text":{"content":"..."}}}
 *   <li>{@code dingtalk}：与企业微信同形；配了 {@code secret} 则按官方加签算法在 URL 上追加
 *       {@code timestamp}（毫秒）与 {@code sign}
 *   <li>{@code feishu}：{@code {"msg_type":"text","content":{"text":"..."}}}；配了 {@code secret}
 *       则在报文里带 {@code timestamp}（秒，字符串）与 {@code sign}
 * </ul>
 *
 * <p>签名算法按两家官方文档实现；密钥留空即不签名（企业微信本就无需签名）。
 */
public class WebhookSender {

  private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final KbProperties kb;
  private final Clock clock;

  public WebhookSender(KbProperties kb, Clock clock) {
    this.kb = kb;
    this.clock = clock;
  }

  /** 通道是否启用：webhook URL 非空。 */
  public boolean isEnabled() {
    String url = kb.getNotify().getIm().getUrl();
    return url != null && !url.isBlank();
  }

  /** 发送一条纯文本汇总。未启用时直接返回；失败抛 {@link IllegalStateException} 由上层记日志。 */
  public void send(String text) {
    KbProperties.Im im = kb.getNotify().getIm();
    String url = im.getUrl().strip();
    if (url.isBlank()) {
      return;
    }
    String type = normalizeType(im.getType());
    long millis = clock.millis();
    String payload = payloadFor(type, text, im.getSecret(), millis);
    post(signedUrl(url, type, im.getSecret(), millis), payload);
  }

  /** 归一化类型：钉钉/飞书的常见别名都收，未识别值兜底为企业微信（与钉钉同形）。 */
  static String normalizeType(String type) {
    String t = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
    return switch (t) {
      case "dingtalk", "ding", "钉钉" -> "dingtalk";
      case "feishu", "lark", "飞书" -> "feishu";
      default -> "wecom";
    };
  }

  /** 组装请求体（纯函数，便于单测）。飞书签名进报文，企微/钉钉签名进 URL。 */
  static String payloadFor(String type, String text, String secret, long millis) {
    String content = jsonEscape(text);
    if ("feishu".equals(type)) {
      if (secret == null || secret.isBlank()) {
        return "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + content + "\"}}";
      }
      long seconds = millis / 1000;
      return "{\"timestamp\":\""
          + seconds
          + "\",\"sign\":\""
          + feishuSign(secret, seconds)
          + "\",\"msg_type\":\"text\",\"content\":{\"text\":\""
          + content
          + "\"}}";
    }
    // 企业微信与钉钉同形
    return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + content + "\"}}";
  }

  /** 钉钉加签：URL 追加 timestamp（毫秒）与 sign；企微/飞书（签名走报文）返回原 URL。 */
  static String signedUrl(String url, String type, String secret, long millis) {
    if (!"dingtalk".equals(type) || secret == null || secret.isBlank()) {
      return url;
    }
    String sign =
        URLEncoder.encode(dingtalkSign(secret, millis), StandardCharsets.UTF_8);
    return url + (url.contains("?") ? "&" : "?") + "timestamp=" + millis + "&sign=" + sign;
  }

  /** 钉钉：key = secret，message = "timestamp\nsecret"，HmacSHA256 后 Base64。 */
  static String dingtalkSign(String secret, long millis) {
    return hmacBase64(secret, millis + "\n" + secret);
  }

  /** 飞书：key = "timestamp\nsecret"，message 为空串，HmacSHA256 后 Base64。 */
  static String feishuSign(String secret, long seconds) {
    return hmacBase64(seconds + "\n" + secret, "");
  }

  private static String hmacBase64(String key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (Exception e) {
      // HmacSHA256 是 JDK 必带算法，到这里只可能是初始化被破坏
      throw new IllegalStateException("签名计算失败", e);
    }
  }

  private static void post(String url, String payload) {
    try {
      HttpClient client =
          HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json;charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      String body = response.body() == null ? "" : response.body();
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "IM webhook HTTP " + response.statusCode() + ": " + clip(body));
      }
      if (logicalError(body)) {
        // 2xx 但业务报错（典型：加签密钥不对、关键词不匹配）
        throw new IllegalStateException("IM webhook 返回错误: " + clip(body));
      }
      log.debug("IM webhook sent: {} bytes", payload.length());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("IM webhook 发送被中断", e);
    } catch (IOException e) {
      throw new IllegalStateException("IM webhook 发送失败: " + e.getMessage(), e);
    }
  }

  /**
   * 识别机器人平台的业务错误：企微/钉钉回 {@code errcode}，飞书回 {@code code}。
   * 成功时分别是 {@code 0}；非 0 视为失败（HTTP 仍可能是 200）。
   */
  static boolean logicalError(String body) {
    if (body.isEmpty()) {
      return false;
    }
    return (body.contains("\"errcode\":") && !body.contains("\"errcode\":0"))
        || (body.contains("\"code\":") && !body.contains("\"code\":0"));
  }

  /** JSON 字符串转义（汇总文本含引号/换行/控制字符时保证报文合法）。 */
  static String jsonEscape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  private static String clip(String s) {
    return s.length() <= 200 ? s : s.substring(0, 200) + "…";
  }
}

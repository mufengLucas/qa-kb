package com.qa.kb.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kb")
public class KbProperties {

  private String timezone = "Asia/Shanghai";
  private Duration sessionTtl = Duration.ofHours(12);
  private String uploadDir = "./data/uploads";
  private final Seed seed = new Seed();
  private final Backup backup = new Backup();
  private final Notify notify = new Notify();

  public ZoneId zoneId() {
    return ZoneId.of(timezone);
  }

  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone;
  }

  public Duration getSessionTtl() {
    return sessionTtl;
  }

  public void setSessionTtl(Duration sessionTtl) {
    this.sessionTtl = sessionTtl;
  }

  public String getUploadDir() {
    return uploadDir;
  }

  public void setUploadDir(String uploadDir) {
    this.uploadDir = uploadDir;
  }

  public Seed getSeed() {
    return seed;
  }

  public Backup getBackup() {
    return backup;
  }

  public Notify getNotify() {
    return notify;
  }

  public static class Seed {
    private boolean enabled = true;
    private boolean demo = true;
    private String adminLogin = "admin";
    private String adminName = "管理员";
    // 仅占位默认值：真实口令一律经环境变量 KB_ADMIN_PASSWORD / KB_DEMO_PASSWORD 注入
    private String adminPassword = "change-me-please-1";
    private String demoPassword = "change-me-demo-1";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isDemo() {
      return demo;
    }

    public void setDemo(boolean demo) {
      this.demo = demo;
    }

    public String getAdminLogin() {
      return adminLogin;
    }

    public void setAdminLogin(String adminLogin) {
      this.adminLogin = adminLogin;
    }

    public String getAdminName() {
      return adminName;
    }

    public void setAdminName(String adminName) {
      this.adminName = adminName;
    }

    public String getAdminPassword() {
      return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
      this.adminPassword = adminPassword;
    }

    public String getDemoPassword() {
      return demoPassword;
    }

    public void setDemoPassword(String demoPassword) {
      this.demoPassword = demoPassword;
    }
  }

  /** Daily zip snapshots: local retention plus an optional off-site directory or network share. */
  public static class Backup {
    private boolean enabled = true;
    private String dir = "./data/backups";
    private int keep = 14;
    private String offsiteDir = "";
    private String cron = "0 30 2 * * *";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getDir() {
      return dir;
    }

    public void setDir(String dir) {
      this.dir = dir;
    }

    public int getKeep() {
      return keep;
    }

    public void setKeep(int keep) {
      this.keep = keep;
    }

    public String getOffsiteDir() {
      return offsiteDir;
    }

    public void setOffsiteDir(String offsiteDir) {
      this.offsiteDir = offsiteDir;
    }

    public String getCron() {
      return cron;
    }

    public void setCron(String cron) {
      this.cron = cron;
    }
  }

  /**
   * 每日站内通知扫描（周报提醒 + 任务逾期），停用后手动触发的端点不受影响。
   *
   * <p>A2 外发通知：email 与 im 两个子通道都「不配置就不启用」——email 要 {@code enabled=true}
   * 且 {@code host} 非空，im 只看 {@code url} 非空；任一通道配好后，扫描新增的通知会合并成
   * 一条每日汇总发出（站内铃铛不受影响，外发失败也只记日志）。
   */
  public static class Notify {
    private boolean enabled = true;
    private String cron = "0 0 9 * * *";
    private final Email email = new Email();
    private final Im im = new Im();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getCron() {
      return cron;
    }

    public void setCron(String cron) {
      this.cron = cron;
    }

    public Email getEmail() {
      return email;
    }

    public Im getIm() {
      return im;
    }
  }

  /** SMTP 邮件通道：凭据只经环境变量注入（公开仓库不落任何真实口令）。 */
  public static class Email {
    private boolean enabled = false;
    private String host = "";
    private int port = 587;
    private String username = "";
    private String password = "";
    private String from = "";
    /** 固定收件人（逗号分隔），与成员邮箱并集投递。 */
    private String to = "";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public String getFrom() {
      return from;
    }

    public void setFrom(String from) {
      this.from = from;
    }

    public String getTo() {
      return to;
    }

    public void setTo(String to) {
      this.to = to;
    }
  }

  /**
   * 通用 IM 机器人 Webhook：{@code type} 决定报文形状（wecom / dingtalk / feishu），
   * {@code url} 留空即整个通道不启用；{@code secret} 可选（钉钉加签、飞书签名）。
   */
  public static class Im {
    private String url = "";
    private String type = "wecom";
    private String secret = "";

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }
  }
}

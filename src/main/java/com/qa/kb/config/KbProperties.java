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
}

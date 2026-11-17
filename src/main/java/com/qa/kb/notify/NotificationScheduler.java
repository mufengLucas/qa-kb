package com.qa.kb.notify;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日通知扫描（P2 Phase 5）。停用：{@code kb.notify.enabled=false}。
 *
 * <p>与 {@code BackupScheduler} 同款模式：cron 可配、时区走 {@code kb.timezone}、
 * 失败只记日志绝不抛出（通知是旁路功能，不能影响调度线程与服务启动）。
 */
@Component
@ConditionalOnProperty(prefix = "kb.notify", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class NotificationScheduler {

  private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

  private final NotificationService notificationService;
  private final Clock clock;

  public NotificationScheduler(NotificationService notificationService, Clock clock) {
    this.notificationService = notificationService;
    this.clock = clock;
  }

  @Scheduled(cron = "${kb.notify.cron:0 0 9 * * *}", zone = "${kb.timezone:Asia/Shanghai}")
  public void runDailyScan() {
    try {
      NotificationService.ScanResult result = notificationService.scan(LocalDate.now(clock));
      log.info(
          "Daily notification scan finished: weekly={}, overdue={}",
          result.weeklyReminders(),
          result.overdueTasks());
    } catch (RuntimeException e) {
      log.error("Daily notification scan failed: {}", e.getMessage(), e);
    }
  }
}

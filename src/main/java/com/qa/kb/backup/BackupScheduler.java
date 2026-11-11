package com.qa.kb.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly backup job. Disable with {@code kb.backup.enabled=false}; the manual admin endpoint keeps
 * working either way.
 */
@Component
@ConditionalOnProperty(prefix = "kb.backup", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class BackupScheduler {

  private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

  private final BackupService backupService;

  public BackupScheduler(BackupService backupService) {
    this.backupService = backupService;
  }

  @Scheduled(cron = "${kb.backup.cron:0 30 2 * * *}", zone = "${kb.timezone:Asia/Shanghai}")
  public void runNightlyBackup() {
    try {
      BackupService.BackupResult result = backupService.runOnce();
      log.info(
          "Nightly backup finished: {} ({} bytes, offsite={})",
          result.file().getFileName(),
          result.bytes(),
          result.offsiteCopied());
    } catch (RuntimeException e) {
      // Never let a failed backup kill the scheduler: the next run should still happen.
      log.error("Nightly backup failed", e);
    }
  }
}

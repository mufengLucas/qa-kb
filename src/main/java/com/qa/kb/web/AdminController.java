package com.qa.kb.web;

import com.qa.kb.backup.BackupService;
import com.qa.kb.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only operational endpoints. Enforced by {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  public record BackupResponse(String file, long bytes, boolean offsiteCopied) {}

  private final BackupService backupService;

  public AdminController(BackupService backupService) {
    this.backupService = backupService;
  }

  /** Triggers a backup immediately, without waiting for the nightly schedule. */
  @PostMapping("/backup")
  public BackupResponse backup() {
    try {
      BackupService.BackupResult result = backupService.runOnce();
      return new BackupResponse(
          result.file().getFileName().toString(), result.bytes(), result.offsiteCopied());
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "backup_failed",
          e.getMessage() == null ? "备份失败" : e.getMessage());
    }
  }
}

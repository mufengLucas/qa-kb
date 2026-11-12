package com.qa.kb.web;

import com.qa.kb.audit.AuditService;
import com.qa.kb.backup.BackupService;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.AuditRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only operational endpoints. Enforced by {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  public record BackupResponse(String file, long bytes, boolean offsiteCopied) {}

  /** 审计分页视图：total 供前端显示总量，items 最新在前。 */
  public record AuditPage(long total, List<AuditRow> items) {}

  private final BackupService backupService;
  private final AuditService auditService;

  public AdminController(BackupService backupService, AuditService auditService) {
    this.backupService = backupService;
    this.auditService = auditService;
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

  /** 操作审计（P2 Phase 2）：最新在前分页；仅 ADMIN（SecurityConfig 已覆盖 /api/admin/**）。 */
  @GetMapping("/audit")
  public AuditPage audit(
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    return new AuditPage(auditService.count(), auditService.page(limit, offset));
  }
}

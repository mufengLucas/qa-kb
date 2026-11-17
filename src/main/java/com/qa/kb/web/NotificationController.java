package com.qa.kb.web;

import com.qa.kb.notify.NotificationService;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知端点（P2 Phase 5）。
 *
 * <p>全部围绕「当前登录用户自己的收件箱」：读需登录（VIEWER 也可读自己的通知），
 * 标记已读是个人状态而非内容写入——但按全局规则仍走 POST（CSRF 双提交）。
 * 他人通知一律 404，不泄露存在性。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationService notificationService;
  private final Clock clock;

  public NotificationController(NotificationService notificationService, Clock clock) {
    this.notificationService = notificationService;
    this.clock = clock;
  }

  /** 收件箱：未读数 + 列表（未读在前，同状态按 id 倒序）。 */
  @GetMapping
  public NotificationService.NotificationPage list(
      @RequestParam(required = false) Integer limit, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return notificationService.list(user.id(), limit);
  }

  /** 标记单条已读；不存在或不属于当前用户 → 404。 */
  @PostMapping("/{id}/read")
  public void markRead(@PathVariable long id, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    notificationService.markRead(user.id(), id);
  }

  /** 全部标记已读，返回受影响条数。 */
  @PostMapping("/read-all")
  public Map<String, Long> markAllRead(Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return Map.of("updated", notificationService.markAllRead(user.id()));
  }

  /** 手工触发一次扫描（幂等，与调度器同一入口；测试与运维用）。 */
  @PostMapping("/scan")
  public NotificationService.ScanResult scan(Authentication authentication) {
    CurrentUser.require(authentication);
    return notificationService.scan(LocalDate.now(clock));
  }
}

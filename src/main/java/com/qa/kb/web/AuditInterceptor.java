package com.qa.kb.web;

import com.qa.kb.audit.AuditService;
import com.qa.kb.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一采集 /api/** 写操作审计（P2 Phase 2）。
 *
 * <p>只拦 mutating 方法（POST/PUT/PATCH/DELETE），GET 读取不产生审计。
 * 在 {@code afterCompletion} 阶段写入：此时业务结果（含状态码）已确定，且写审计失败
 * 由 {@link AuditService#record} 吞掉，绝不影响响应。
 *
 * <p>登录/登出由 {@link AuthController} 显式埋点（拦截器拿不到请求体与登录失败上下文），
 * 本拦截器跳过 {@code /api/auth/login} 避免重复记录。
 */
public class AuditInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

  private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

  /** 登录与登出由 AuthController 显式埋点（LOGIN/LOGIN_FAILED/LOGOUT），拦截器跳过避免双记。 */
  private static final Set<String> EXPLICIT_PATHS = Set.of("/api/auth/login", "/api/auth/logout");

  private final AuditService auditService;

  public AuditInterceptor(AuditService auditService) {
    this.auditService = auditService;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    try {
      if (!MUTATING.contains(request.getMethod())) {
        return;
      }
      String path = request.getRequestURI();
      if (!path.startsWith("/api/") || EXPLICIT_PATHS.contains(path)) {
        return;
      }
      SessionUser user = currentUser();
      auditService.record(
          user == null ? null : user.id(),
          request.getMethod() + " " + path,
          targetType(path),
          targetId(path),
          "status=" + response.getStatus() + " ip=" + WebRequests.clientIp(request));
    } catch (RuntimeException e) {
      // 双保险：AuditService.record 已吞异常，这里防拦截器自身逻辑出错影响响应。
      log.warn("审计拦截器失败 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
    }
  }

  private static SessionUser currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof SessionUser user) {
      return user;
    }
    return null;
  }

  /** 取 /api/ 后第一段作为对象类型，如 /api/spaces/3/pages → spaces。 */
  private static String targetType(String path) {
    String rest = path.substring("/api/".length());
    int slash = rest.indexOf('/');
    return slash < 0 ? rest : rest.substring(0, slash);
  }

  /** 取第二段作为对象 id（若存在），如 /api/spaces/3/pages → 3。 */
  private static String targetId(String path) {
    String rest = path.substring("/api/".length());
    int first = rest.indexOf('/');
    if (first < 0) {
      return null;
    }
    String after = rest.substring(first + 1);
    int second = after.indexOf('/');
    return second < 0 ? after : after.substring(0, second);
  }
}

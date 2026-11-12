package com.qa.kb.web;

import com.qa.kb.auth.AuthService;
import com.qa.kb.audit.AuditService;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionAuthenticationFilter;
import com.qa.kb.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  public record LoginRequest(@NotBlank String login, @NotBlank String password) {}

  public record ChangePasswordRequest(
      @NotBlank String currentPassword, @NotBlank String newPassword) {}

  public record UserView(long id, String login, String name, String role) {
    public static UserView of(SessionUser user) {
      return new UserView(user.id(), user.login(), user.name(), user.role().name());
    }
  }

  private final AuthService authService;
  private final AuditService auditService;

  public AuthController(AuthService authService, AuditService auditService) {
    this.authService = authService;
    this.auditService = auditService;
  }

  @PostMapping("/login")
  public ResponseEntity<UserView> login(
      @Valid @RequestBody LoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String ip = WebRequests.clientIp(request);
    AuthService.LoginResult result;
    try {
      result = authService.login(body.login(), body.password(), ip);
    } catch (RuntimeException e) {
      // 登录失败也要留痕（拦截器跳过本路径，拿不到请求体里的登录名）
      auditService.record(null, "LOGIN_FAILED", "user", body.login(), "ip=" + ip);
      throw e;
    }
    auditService.record(result.user().id(), "LOGIN", "user", result.user().login(), "ip=" + ip);
    response.addHeader(
        HttpHeaders.SET_COOKIE, sessionCookie(result.token(), authService.sessionTtl()).toString());
    return ResponseEntity.ok(UserView.of(result.user()));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    authService.logout(SessionAuthenticationFilter.readToken(request));
    response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString());
    // 登出时会话已吊销，SecurityContext 里的 principal 仍在当前请求内可用
    SessionUser user = currentUserOrNull();
    auditService.record(
        user == null ? null : user.id(),
        "LOGOUT",
        "user",
        user == null ? null : user.login(),
        "ip=" + WebRequests.clientIp(request));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public UserView me(Authentication authentication) {
    return UserView.of(CurrentUser.require(authentication));
  }

  @PostMapping("/password")
  public ResponseEntity<Void> changePassword(
      @Valid @RequestBody ChangePasswordRequest body, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    authService.changePassword(user.id(), body.currentPassword(), body.newPassword());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  public static ResponseCookie sessionCookie(String token, Duration maxAge) {
    return ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, token)
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }

  /** 当前请求已认证用户；登出时会话已吊销但 principal 仍在 SecurityContext 中。 */
  private static SessionUser currentUserOrNull() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof SessionUser user) {
      return user;
    }
    return null;
  }
}

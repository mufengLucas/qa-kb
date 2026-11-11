package com.qa.kb.auth;

import com.qa.kb.common.ApiException;
import com.qa.kb.config.KbProperties;
import com.qa.kb.model.UserRow;
import com.qa.kb.persistence.SessionMapper;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.security.SessionUser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Session-based authentication.
 *
 * <p>Passwords are BCrypt-hashed; sessions are opaque random tokens stored in MySQL so they can be
 * revoked and survive restarts. Failures are rate-limited per IP.
 */
public class AuthService {

  public record LoginResult(String token, SessionUser user, LocalDateTime expiresAt) {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final UserMapper userMapper;
  private final SessionMapper sessionMapper;
  private final PasswordEncoder passwordEncoder;
  private final LoginRateLimiter rateLimiter;
  private final KbProperties properties;
  private final Clock clock;

  public AuthService(
      UserMapper userMapper,
      SessionMapper sessionMapper,
      PasswordEncoder passwordEncoder,
      LoginRateLimiter rateLimiter,
      KbProperties properties,
      Clock clock) {
    this.userMapper = userMapper;
    this.sessionMapper = sessionMapper;
    this.passwordEncoder = passwordEncoder;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.clock = clock;
  }

  public LoginResult login(String login, String rawPassword, String ip) {
    if (rateLimiter.isBlocked(ip)) {
      long waitSeconds = Math.max(1, rateLimiter.retryAfter(ip).toSeconds());
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "too_many_attempts",
          "尝试次数过多，请 " + waitSeconds + " 秒后重试");
    }
    if (login == null || login.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
      throw ApiException.badRequest("用户名和密码不能为空");
    }

    String normalizedLogin = login.strip().toLowerCase(Locale.ROOT);
    Optional<UserRow> candidate = userMapper.findByLogin(normalizedLogin);
    if (candidate.isEmpty()
        || !candidate.get().active()
        || !passwordEncoder.matches(rawPassword, candidate.get().passwordHash())) {
      rateLimiter.recordFailure(ip);
      throw ApiException.unauthorized("用户名或密码错误");
    }

    UserRow user = candidate.get();
    rateLimiter.recordSuccess(ip);

    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime expiresAt = now.plus(properties.getSessionTtl());
    String token = newToken();
    sessionMapper.insert(token, user.id(), now, now, expiresAt);
    return new LoginResult(token, toPrincipal(user), expiresAt);
  }

  public Optional<SessionUser> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    LocalDateTime now = LocalDateTime.now(clock);
    return sessionMapper
        .findValid(token, now)
        .flatMap(session -> userMapper.findById(session.userId()))
        .filter(UserRow::active)
        .map(
            user -> {
              sessionMapper.touch(token, now);
              return toPrincipal(user);
            });
  }

  public void logout(String token) {
    if (token != null && !token.isBlank()) {
      sessionMapper.delete(token);
    }
  }

  public void changePassword(long userId, String currentPassword, String newPassword) {
    UserRow user =
        userMapper
            .findById(userId)
            .orElseThrow(() -> ApiException.notFound("用户不存在"));

    if (currentPassword == null
        || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
      throw ApiException.badRequest("当前密码不正确");
    }

    List<PasswordPolicy.Violation> violations =
        PasswordPolicy.violations(user.login(), newPassword);
    if (!violations.isEmpty()) {
      throw ApiException.badRequest(
          "密码强度不足："
              + violations.stream().map(PasswordPolicy::describe).collect(Collectors.joining("，")));
    }

    userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
  }

  public Duration sessionTtl() {
    return properties.getSessionTtl();
  }

  public boolean isBlocked(String ip) {
    return rateLimiter.isBlocked(ip);
  }

  public Duration retryAfter(String ip) {
    return rateLimiter.retryAfter(ip);
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return TOKEN_ENCODER.encodeToString(bytes);
  }

  static List<PasswordPolicy.Violation> violations(String login, String password) {
    return PasswordPolicy.violations(login, password);
  }

  static SessionUser toPrincipal(UserRow user) {
    return new SessionUser(user.id(), user.login(), user.name(), user.role());
  }
}

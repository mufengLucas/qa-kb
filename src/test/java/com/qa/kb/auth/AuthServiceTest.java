package com.qa.kb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.config.KbProperties;
import com.qa.kb.model.SessionRow;
import com.qa.kb.model.UserRow;
import com.qa.kb.persistence.SessionMapper;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.security.SessionUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

  private static final String IP = "192.168.1.20";

  @Mock private UserMapper userMapper;
  @Mock private SessionMapper sessionMapper;

  private PasswordEncoder encoder;
  private KbProperties properties;
  private MutableClock clock;
  private LoginRateLimiter limiter;
  private AuthService service;

  /** Clock that the test advances explicitly. */
  static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-05T02:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Shanghai");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void setUp() {
    encoder = new BCryptPasswordEncoder(4);
    properties = new KbProperties();
    properties.setSessionTtl(Duration.ofHours(12));
    clock = new MutableClock();
    limiter = new LoginRateLimiter(5, Duration.ofMinutes(5), clock);
    service = new AuthService(userMapper, sessionMapper, encoder, limiter, properties, clock);
  }

  private UserRow user(String password) {
    return new UserRow(
        1L, "alice", "Alice", encoder.encode(password), Role.MEMBER, true, LocalDateTime.now());
  }

  @Test
  void successfulLoginCreatesSessionAndReturnsPrincipal() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    AuthService.LoginResult result = service.login("alice", "P@ssw0rd!2026", IP);

    assertThat(result.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(result.user())
        .isEqualTo(new SessionUser(1L, "alice", "Alice", Role.MEMBER));
    assertThat(result.expiresAt())
        .isEqualTo(LocalDateTime.ofInstant(clock.instant(), clock.getZone()).plusHours(12));

    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(sessionMapper)
        .insert(
            tokenCaptor.capture(),
            eq(1L),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            eq(result.expiresAt()));
    assertThat(tokenCaptor.getValue()).isEqualTo(result.token());
  }

  @Test
  void loginIsCaseInsensitiveOnLoginNameAndTrims() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    service.login("  ALICE  ", "P@ssw0rd!2026", IP);

    verify(userMapper).findByLogin("alice");
  }

  @Test
  void unknownLoginReturnsUnauthorizedAndNoSession() {
    when(userMapper.findByLogin("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.login("ghost", "whatever123", IP))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED));

    verify(sessionMapper, never())
        .insert(any(), anyLong(), any(), any(), any());
  }

  @Test
  void wrongPasswordReturnsUnauthorized() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    assertThatThrownBy(() -> service.login("alice", "wrong-password1", IP))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void inactiveUserCannotLogin() {
    UserRow inactive =
        new UserRow(
            1L,
            "alice",
            "Alice",
            encoder.encode("P@ssw0rd!2026"),
            Role.MEMBER,
            false,
            LocalDateTime.now());
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(inactive));

    assertThatThrownBy(() -> service.login("alice", "P@ssw0rd!2026", IP))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void fiveFailuresBlockFurtherAttemptsWith429() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.login("alice", "wrong-password1", IP))
          .isInstanceOf(ApiException.class);
    }

    assertThatThrownBy(() -> service.login("alice", "P@ssw0rd!2026", IP))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void successfulLoginClearsFailureHistory() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(user("P@ssw0rd!2026")));
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> service.login("alice", "wrong-password1", IP))
          .isInstanceOf(ApiException.class);
    }

    service.login("alice", "P@ssw0rd!2026", IP);

    assertThat(limiter.isBlocked(IP)).isFalse();
    assertThat(limiter.retryAfter(IP)).isEqualTo(Duration.ZERO);
  }

  @Test
  void authenticateReturnsEmptyForMissingToken() {
    assertThat(service.authenticate(null)).isEmpty();
    assertThat(service.authenticate("   ")).isEmpty();
    assertThat(service.authenticate("unknown-token")).isEmpty();
  }

  @Test
  void authenticateReturnsPrincipalForValidSession() {
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    when(sessionMapper.findValid("token", now))
        .thenReturn(Optional.of(new SessionRow("token", 1L, now, now, now.plusHours(1))));
    when(userMapper.findById(1L)).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    assertThat(service.authenticate("token"))
        .contains(new SessionUser(1L, "alice", "Alice", Role.MEMBER));
    verify(sessionMapper).touch("token", now);
  }

  @Test
  void authenticateDropsSessionOfInactiveUser() {
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    when(sessionMapper.findValid("token", now))
        .thenReturn(Optional.of(new SessionRow("token", 1L, now, now, now.plusHours(1))));
    UserRow inactive =
        new UserRow(1L, "alice", "Alice", "hash", Role.MEMBER, false, LocalDateTime.now());
    when(userMapper.findById(1L)).thenReturn(Optional.of(inactive));

    assertThat(service.authenticate("token")).isEmpty();
  }

  @Test
  void logoutDeletesSession() {
    service.logout("token");
    verify(sessionMapper).delete("token");
  }

  @Test
  void changePasswordRejectsWeakPassword() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    assertThatThrownBy(() -> service.changePassword(1L, "P@ssw0rd!2026", "short1"))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void changePasswordRejectsWrongCurrentPassword() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    assertThatThrownBy(() -> service.changePassword(1L, "wrong-password1", "NewP@ssw0rd!2026"))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void changePasswordStoresNewHash() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user("P@ssw0rd!2026")));

    service.changePassword(1L, "P@ssw0rd!2026", "NewP@ssw0rd!2026");

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(userMapper).updatePassword(eq(1L), hash.capture());
    assertThat(encoder.matches("NewP@ssw0rd!2026", hash.getValue())).isTrue();
  }

  @Test
  void changePasswordForUnknownUserIsNotFound() {
    when(userMapper.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.changePassword(99L, "whatever12", "NewP@ssw0rd!2026"))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}

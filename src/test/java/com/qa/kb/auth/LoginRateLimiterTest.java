package com.qa.kb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LoginRateLimiter")
class LoginRateLimiterTest {

  /** Test clock that only moves when the test says so. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
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

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
  private final LoginRateLimiter limiter =
      new LoginRateLimiter(5, Duration.ofMinutes(5), clock);

  @Test
  void defaultPolicyIsFiveFailuresPerFiveMinutes() {
    LoginRateLimiter defaults = new LoginRateLimiter();
    assertThat(defaults.maxFailures()).isEqualTo(5);
    assertThat(defaults.window()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void initiallyNotBlocked() {
    assertThat(limiter.isBlocked("10.0.0.1")).isFalse();
    assertThat(limiter.retryAfter("10.0.0.1")).isEqualTo(Duration.ZERO);
  }

  @Test
  void blocksAfterFiveFailures() {
    for (int i = 0; i < 4; i++) {
      limiter.recordFailure("10.0.0.1");
    }
    assertThat(limiter.isBlocked("10.0.0.1")).isFalse();

    limiter.recordFailure("10.0.0.1");
    assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
    assertThat(limiter.retryAfter("10.0.0.1")).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void unblocksWhenWindowExpires() {
    for (int i = 0; i < 5; i++) {
      limiter.recordFailure("10.0.0.1");
    }
    clock.advance(Duration.ofMinutes(4).plusSeconds(59));
    assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
    assertThat(limiter.retryAfter("10.0.0.1")).isEqualTo(Duration.ofSeconds(1));

    clock.advance(Duration.ofSeconds(1));
    assertThat(limiter.isBlocked("10.0.0.1")).isFalse();
    assertThat(limiter.retryAfter("10.0.0.1")).isEqualTo(Duration.ZERO);
  }

  @Test
  void successfulLoginClearsFailures() {
    for (int i = 0; i < 5; i++) {
      limiter.recordFailure("10.0.0.1");
    }
    assertThat(limiter.isBlocked("10.0.0.1")).isTrue();

    limiter.recordSuccess("10.0.0.1");
    assertThat(limiter.isBlocked("10.0.0.1")).isFalse();
  }

  @Test
  void slidingWindowDropsOldFailures() {
    limiter.recordFailure("10.0.0.1"); // t0, expires at t0+5m
    clock.advance(Duration.ofMinutes(4));
    for (int i = 0; i < 4; i++) {
      limiter.recordFailure("10.0.0.1");
    }
    assertThat(limiter.isBlocked("10.0.0.1")).isTrue();

    clock.advance(Duration.ofMinutes(1).plusSeconds(1));
    assertThat(limiter.isBlocked("10.0.0.1")).isFalse();
  }

  @Test
  void limitsPerIpIndependently() {
    for (int i = 0; i < 5; i++) {
      limiter.recordFailure("10.0.0.1");
    }
    assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
    assertThat(limiter.isBlocked("10.0.0.2")).isFalse();
  }

  @Test
  void rejectsMissingIp() {
    assertThatThrownBy(() -> limiter.isBlocked(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limiter.isBlocked("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limiter.recordFailure(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limiter.recordSuccess(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limiter.retryAfter(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesConstructorArguments() {
    assertThatThrownBy(() -> new LoginRateLimiter(0, Duration.ofMinutes(5), clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LoginRateLimiter(5, Duration.ZERO, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LoginRateLimiter(5, Duration.ofMinutes(5), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isSafeUnderConcurrentFailures() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> errors = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              limiter.recordFailure("10.0.0.9");
              limiter.isBlocked("10.0.0.9");
            } catch (Throwable t) {
              synchronized (errors) {
                errors.add(t);
              }
            }
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    assertThat(errors).isEmpty();
    assertThat(limiter.isBlocked("10.0.0.9")).isTrue();
  }
}

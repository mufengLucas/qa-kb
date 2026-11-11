package com.qa.kb.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sliding-window login failure limiter, keyed by client IP.
 *
 * <p>Default policy from the plan: 5 failures per 5 minutes per IP. The 6th attempt is blocked
 * until the oldest failure leaves the window.
 */
public final class LoginRateLimiter {

  public static final int DEFAULT_MAX_FAILURES = 5;
  public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

  private final int maxFailures;
  private final Duration window;
  private final Clock clock;
  private final Map<String, Deque<Instant>> failures = new HashMap<>();

  public LoginRateLimiter() {
    this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW, Clock.systemUTC());
  }

  public LoginRateLimiter(int maxFailures, Duration window, Clock clock) {
    if (maxFailures < 1) {
      throw new IllegalArgumentException("maxFailures must be >= 1");
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window must be positive");
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock is required");
    }
    this.maxFailures = maxFailures;
    this.window = window;
    this.clock = clock;
  }

  public synchronized boolean isBlocked(String ip) {
    Deque<Instant> attempts = evictExpired(requireIp(ip));
    return attempts.size() >= maxFailures;
  }

  public synchronized void recordFailure(String ip) {
    Deque<Instant> attempts = evictExpired(requireIp(ip));
    attempts.addLast(clock.instant());
  }

  public synchronized void recordSuccess(String ip) {
    failures.remove(requireIp(ip));
  }

  /** Remaining block time; {@link Duration#ZERO} when the IP is not blocked. */
  public synchronized Duration retryAfter(String ip) {
    Deque<Instant> attempts = evictExpired(requireIp(ip));
    if (attempts.size() < maxFailures) {
      return Duration.ZERO;
    }
    Instant unblockAt = attempts.peekFirst().plus(window);
    Duration remaining = Duration.between(clock.instant(), unblockAt);
    return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
  }

  public int maxFailures() {
    return maxFailures;
  }

  public Duration window() {
    return window;
  }

  private Deque<Instant> evictExpired(String ip) {
    Deque<Instant> attempts = failures.computeIfAbsent(ip, key -> new ArrayDeque<>());
    Instant cutoff = clock.instant().minus(window);
    while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
      attempts.removeFirst();
    }
    return attempts;
  }

  private static String requireIp(String ip) {
    if (ip == null || ip.isBlank()) {
      throw new IllegalArgumentException("ip is required");
    }
    return ip.trim().toLowerCase(Locale.ROOT);
  }
}

package com.qa.kb.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Password strength rules for a LAN-only internal tool (no HTTPS, so passwords must be strong). */
public final class PasswordPolicy {

  /** Machine-readable violations so the UI can localize messages. */
  public enum Violation {
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
    MISSING_LETTER_OR_DIGIT,
    SAME_AS_LOGIN,
    TOO_COMMON
  }

  public static final int MIN_LENGTH = 10;
  public static final int MAX_LENGTH = 128;

  private static final Set<String> COMMON_PASSWORDS =
      Set.of(
          "password",
          "password1",
          "password123",
          "qwerty",
          "qwerty123",
          "qwerty12345",
          "12345678",
          "123456789",
          "1234567890",
          "11111111",
          "abc12345",
          "admin123",
          "admin12345",
          "iloveyou1",
          "letmein123",
          "welcome123");

  private PasswordPolicy() {}

  /** Human-readable Chinese message for a violation, shared by login and admin flows. */
  public static String describe(Violation violation) {
    return switch (violation) {
      case EMPTY -> "密码不能为空";
      case TOO_SHORT -> "至少 " + MIN_LENGTH + " 位";
      case TOO_LONG -> "最多 " + MAX_LENGTH + " 位";
      case MISSING_LETTER_OR_DIGIT -> "必须同时包含字母和数字";
      case SAME_AS_LOGIN -> "不能与登录名相同";
      case TOO_COMMON -> "过于常见";
    };
  }

  /** @return all violations; an empty list means the password is acceptable. */
  public static List<Violation> violations(String login, String password) {
    if (password == null || password.isBlank()) {
      return List.of(Violation.EMPTY);
    }

    List<Violation> violations = new ArrayList<>();
    if (password.length() < MIN_LENGTH) {
      violations.add(Violation.TOO_SHORT);
    }
    if (password.length() > MAX_LENGTH) {
      violations.add(Violation.TOO_LONG);
    }

    boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
    boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
    if (!hasLetter || !hasDigit) {
      violations.add(Violation.MISSING_LETTER_OR_DIGIT);
    }

    if (login != null && !login.isBlank() && password.equalsIgnoreCase(login.trim())) {
      violations.add(Violation.SAME_AS_LOGIN);
    }

    if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
      violations.add(Violation.TOO_COMMON);
    }

    return List.copyOf(violations);
  }
}

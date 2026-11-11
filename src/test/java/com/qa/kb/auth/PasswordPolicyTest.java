package com.qa.kb.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.kb.auth.PasswordPolicy.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordPolicy")
class PasswordPolicyTest {

  @Test
  void acceptsStrongPassword() {
    assertThat(PasswordPolicy.violations("alice", "P@ssw0rd!2026")).isEmpty();
  }

  @Test
  void acceptsExactlyMinimumLength() {
    assertThat(PasswordPolicy.violations("alice", "abcdefgh12")).isEmpty();
  }

  @Test
  void acceptsExactlyMaximumLength() {
    String password = "a1" + "x".repeat(126);
    assertThat(password).hasSize(PasswordPolicy.MAX_LENGTH);
    assertThat(PasswordPolicy.violations("alice", password)).isEmpty();
  }

  @Test
  void rejectsNullAndBlank() {
    assertThat(PasswordPolicy.violations("alice", null)).containsExactly(Violation.EMPTY);
    assertThat(PasswordPolicy.violations("alice", "   ")).containsExactly(Violation.EMPTY);
  }

  @Test
  void rejectsTooShort() {
    assertThat(PasswordPolicy.violations("alice", "short1")).contains(Violation.TOO_SHORT);
  }

  @Test
  void rejectsTooLong() {
    String password = "a1" + "x".repeat(127);
    assertThat(PasswordPolicy.violations("alice", password)).contains(Violation.TOO_LONG);
  }

  @Test
  void rejectsPasswordWithoutLetterOrWithoutDigit() {
    assertThat(PasswordPolicy.violations("alice", "abcdefghij"))
        .contains(Violation.MISSING_LETTER_OR_DIGIT);
    assertThat(PasswordPolicy.violations("alice", "1234567890"))
        .contains(Violation.MISSING_LETTER_OR_DIGIT);
  }

  @Test
  void rejectsPasswordEqualToLoginIgnoringCase() {
    assertThat(PasswordPolicy.violations("Alice12345", "alice12345"))
        .contains(Violation.SAME_AS_LOGIN);
  }

  @Test
  void rejectsCommonPasswords() {
    assertThat(PasswordPolicy.violations("alice", "password123")).contains(Violation.TOO_COMMON);
    assertThat(PasswordPolicy.violations("alice", "PASSWORD123")).contains(Violation.TOO_COMMON);
    assertThat(PasswordPolicy.violations("alice", "qwerty12345")).contains(Violation.TOO_COMMON);
  }

  @Test
  void reportsAllViolationsAtOnce() {
    assertThat(PasswordPolicy.violations("alice", "abc"))
        .containsExactlyInAnyOrder(Violation.TOO_SHORT, Violation.MISSING_LETTER_OR_DIGIT);
  }

  @Test
  void nullLoginIsAllowed() {
    assertThat(PasswordPolicy.violations(null, "P@ssw0rd!2026")).isEmpty();
  }
}

package com.qa.kb.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProgressCalculator")
class ProgressCalculatorTest {

  @Test
  void emptyProjectIsZeroPercent() {
    assertThat(ProgressCalculator.of(0, 0)).isEqualTo(new Progress(0, 0, 0));
  }

  @Test
  void calculatesRoundedPercentage() {
    assertThat(ProgressCalculator.of(3, 4).percent()).isEqualTo(75);
    assertThat(ProgressCalculator.of(1, 3).percent()).isEqualTo(33);
    assertThat(ProgressCalculator.of(2, 3).percent()).isEqualTo(67);
    assertThat(ProgressCalculator.of(100, 100).percent()).isEqualTo(100);
  }

  @Test
  @DisplayName("rounds half up (12.5% -> 13%)")
  void roundsHalfUp() {
    assertThat(ProgressCalculator.of(1, 8).percent()).isEqualTo(13);
    assertThat(ProgressCalculator.of(1, 200).percent()).isEqualTo(1);
  }

  @Test
  void rejectsNegativeCounts() {
    assertThatThrownBy(() -> ProgressCalculator.of(-1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProgressCalculator.of(1, -2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDoneGreaterThanTotal() {
    assertThatThrownBy(() -> ProgressCalculator.of(3, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overdueWhenNotDoneAndDueDateInPast() {
    LocalDate today = LocalDate.of(2026, 11, 5);
    assertThat(ProgressCalculator.isOverdue("todo", today.minusDays(1), today)).isTrue();
    assertThat(ProgressCalculator.isOverdue("doing", today.minusDays(1), today)).isTrue();
    assertThat(ProgressCalculator.isOverdue("blocked", today.minusDays(1), today)).isTrue();
  }

  @Test
  void notOverdueWhenDueTodayOrInFuture() {
    LocalDate today = LocalDate.of(2026, 11, 5);
    assertThat(ProgressCalculator.isOverdue("todo", today, today)).isFalse();
    assertThat(ProgressCalculator.isOverdue("todo", today.plusDays(1), today)).isFalse();
  }

  @Test
  void doneTasksAreNeverOverdue() {
    LocalDate today = LocalDate.of(2026, 11, 5);
    assertThat(ProgressCalculator.isOverdue("done", today.minusDays(30), today)).isFalse();
  }

  @Test
  void tasksWithoutDueDateAreNotOverdue() {
    assertThat(ProgressCalculator.isOverdue("todo", null, LocalDate.of(2026, 11, 5))).isFalse();
  }

  @Test
  void rejectsUnknownStatus() {
    LocalDate today = LocalDate.of(2026, 11, 5);
    assertThatThrownBy(() -> ProgressCalculator.isOverdue("someday", today, today))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProgressCalculator.isOverdue(null, today, today))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

package com.qa.kb.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReportWeekCalculator")
class ReportWeekCalculatorTest {

  @Test
  void weekStartsOnMondayAndEndsOnSunday() {
    WeekRange week = ReportWeekCalculator.weekOf(LocalDate.of(2026, 11, 5)); // Thursday
    assertThat(week.start()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(week.end()).isEqualTo(LocalDate.of(2026, 11, 8));
  }

  @Test
  void mondayMapsToItself() {
    LocalDate monday = LocalDate.of(2026, 11, 2);
    assertThat(ReportWeekCalculator.weekOf(monday).start()).isEqualTo(monday);
  }

  @Test
  void sundayBelongsToTheWeekThatStartedSixDaysEarlier() {
    WeekRange week = ReportWeekCalculator.weekOf(LocalDate.of(2026, 11, 8));
    assertThat(week.start()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(week.end()).isEqualTo(LocalDate.of(2026, 11, 8));
  }

  @Test
  @DisplayName("ISO week-based year: 2025-12-29 belongs to 2026-W01")
  void isoWeekYearAtYearBoundary() {
    WeekRange week = ReportWeekCalculator.weekOf(LocalDate.of(2025, 12, 31));
    assertThat(week.start()).isEqualTo(LocalDate.of(2025, 12, 29));
    assertThat(week.end()).isEqualTo(LocalDate.of(2026, 1, 4));
    assertThat(week.isoLabel()).isEqualTo("2026-W01");
  }

  @Test
  @DisplayName("2026 has 53 ISO weeks: 2027-01-01 is 2026-W53")
  void fiftyThirdWeek() {
    WeekRange week = ReportWeekCalculator.weekOf(LocalDate.of(2027, 1, 1));
    assertThat(week.isoLabel()).isEqualTo("2026-W53");
    assertThat(week.end()).isEqualTo(LocalDate.of(2027, 1, 3));
  }

  @Test
  void labelsWeekWithZeroPadding() {
    assertThat(ReportWeekCalculator.weekOf(LocalDate.of(2026, 1, 7)).isoLabel()).isEqualTo("2026-W02");
  }

  @Test
  void sameWeekWithinWeek() {
    assertThat(
            ReportWeekCalculator.sameWeek(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8)))
        .isTrue();
  }

  @Test
  void differentWeeks() {
    assertThat(
            ReportWeekCalculator.sameWeek(
                LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 9)))
        .isFalse();
  }

  @Test
  void sameWeekAcrossYearBoundary() {
    assertThat(
            ReportWeekCalculator.sameWeek(
                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)))
        .isTrue();
  }

  @Test
  void rejectsNullDates() {
    assertThatThrownBy(() -> ReportWeekCalculator.weekOf(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReportWeekCalculator.sameWeek(null, LocalDate.now()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReportWeekCalculator.sameWeek(LocalDate.now(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void weekRangeRejectsNonMondayStart() {
    assertThatThrownBy(
            () -> new WeekRange(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Monday");
  }

  @Test
  void weekRangeRejectsSpanOtherThanSevenDays() {
    assertThatThrownBy(
            () -> new WeekRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void weekRangeRejectsEndBeforeStart() {
    assertThatThrownBy(
            () -> new WeekRange(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 2)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

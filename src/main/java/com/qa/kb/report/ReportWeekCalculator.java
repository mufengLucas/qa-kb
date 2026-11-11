package com.qa.kb.report;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Week-boundary math for daily/weekly reports. Business dates are timezone-free ISO dates. */
public final class ReportWeekCalculator {

  private ReportWeekCalculator() {}

  /** Monday..Sunday week containing {@code anyDayInWeek}. */
  public static WeekRange weekOf(LocalDate anyDayInWeek) {
    if (anyDayInWeek == null) {
      throw new IllegalArgumentException("date is required");
    }
    LocalDate monday =
        anyDayInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return new WeekRange(monday, monday.plusDays(6));
  }

  /**
   * True when {@code candidate} belongs to the same Monday..Sunday week as {@code reference}.
   * Used to keep weekly aggregation correct across month and year boundaries.
   */
  public static boolean sameWeek(LocalDate reference, LocalDate candidate) {
    if (reference == null || candidate == null) {
      throw new IllegalArgumentException("both dates are required");
    }
    return weekOf(reference).start().equals(weekOf(candidate).start());
  }
}

package com.qa.kb.report;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

/** Inclusive Monday..Sunday range for a business week. */
public record WeekRange(LocalDate start, LocalDate end) {

  public WeekRange {
    if (start == null || end == null) {
      throw new IllegalArgumentException("start and end are required");
    }
    if (end.isBefore(start)) {
      throw new IllegalArgumentException("end must not be before start");
    }
    if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new IllegalArgumentException("week must start on Monday");
    }
    if (!end.equals(start.plusDays(6))) {
      throw new IllegalArgumentException("week must span exactly seven days (Monday..Sunday)");
    }
  }

  /** ISO-8601 week label, e.g. {@code 2026-W45}. */
  public String isoLabel() {
    int weekYear = start.get(WeekFields.ISO.weekBasedYear());
    int week = start.get(WeekFields.ISO.weekOfWeekBasedYear());
    return String.format(Locale.ROOT, "%d-W%02d", weekYear, week);
  }
}

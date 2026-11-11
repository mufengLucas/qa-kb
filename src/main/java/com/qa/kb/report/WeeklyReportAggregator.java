package com.qa.kb.report;

import com.qa.kb.model.ReportRow;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure aggregation of one user's week of daily reports into a weekly report.
 *
 * <p>Rules from the plan: merge the week's {@code done} items, collect unresolved blockers, and
 * union the linked projects. Only {@code submitted} reports count; drafts are invisible.
 */
public final class WeeklyReportAggregator {

  /** One line contributed by a daily report. */
  public record ReportLine(LocalDate date, long userId, String text) {}

  public record WeeklyReport(
      WeekRange range,
      List<ReportRow> submittedReports,
      List<ReportLine> doneItems,
      List<ReportLine> blockerItems,
      Set<Long> projectIds,
      int submittedCount,
      List<LocalDate> missingWeekdays) {}

  private WeeklyReportAggregator() {}

  /**
   * @param reference any day inside the week to aggregate
   * @param today business "today" in the team timezone; later weekdays are not counted as missing
   * @param reports reports of the user (may include other weeks and drafts)
   * @param projectIdsByReportId linked projects per report id
   */
  public static WeeklyReport aggregate(
      LocalDate reference,
      LocalDate today,
      List<ReportRow> reports,
      Map<Long, List<Long>> projectIdsByReportId) {

    if (reference == null || today == null) {
      throw new IllegalArgumentException("reference and today are required");
    }
    if (reports == null || projectIdsByReportId == null) {
      throw new IllegalArgumentException("reports and project links are required");
    }

    WeekRange range = ReportWeekCalculator.weekOf(reference);
    List<ReportRow> submitted =
        reports.stream()
            .filter(Objects::nonNull)
            .filter(row -> "submitted".equals(row.status()))
            .filter(row -> row.reportDate() != null)
            .filter(row -> ReportWeekCalculator.sameWeek(reference, row.reportDate()))
            .sorted(
                Comparator.comparing(ReportRow::reportDate)
                    .thenComparing(ReportRow::id, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    List<ReportLine> doneItems = new ArrayList<>();
    List<ReportLine> blockerItems = new ArrayList<>();
    Set<String> seenDone = new LinkedHashSet<>();
    Set<String> seenBlockers = new LinkedHashSet<>();
    Set<Long> projectIds = new LinkedHashSet<>();
    Set<LocalDate> submittedDates = new LinkedHashSet<>();

    for (ReportRow row : submitted) {
      submittedDates.add(row.reportDate());

      for (String line : splitLines(row.done())) {
        if (seenDone.add(line)) {
          doneItems.add(new ReportLine(row.reportDate(), row.userId(), line));
        }
      }
      for (String line : splitLines(row.blockers())) {
        if (seenBlockers.add(line)) {
          blockerItems.add(new ReportLine(row.reportDate(), row.userId(), line));
        }
      }
      projectIds.addAll(
          projectIdsByReportId.getOrDefault(row.id(), List.of()).stream()
              .filter(Objects::nonNull)
              .toList());
    }

    return new WeeklyReport(
        range,
        submitted,
        List.copyOf(doneItems),
        List.copyOf(blockerItems),
        projectIds,
        submitted.size(),
        missingWeekdays(range, today, submittedDates));
  }

  private static List<String> splitLines(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (!line.isEmpty()) {
        lines.add(line);
      }
    }
    return lines;
  }

  private static List<LocalDate> missingWeekdays(
      WeekRange range, LocalDate today, Set<LocalDate> submittedDates) {
    List<LocalDate> missing = new ArrayList<>();
    for (LocalDate day = range.start(); !day.isAfter(range.end()); day = day.plusDays(1)) {
      DayOfWeek dow = day.getDayOfWeek();
      if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
        continue;
      }
      if (day.isAfter(today)) {
        continue;
      }
      if (!submittedDates.contains(day)) {
        missing.add(day);
      }
    }
    return List.copyOf(missing);
  }
}

package com.qa.kb.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.kb.model.ReportRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WeeklyReportAggregator")
class WeeklyReportAggregatorTest {

  private static final long USER = 7L;
  private static final LocalDate REFERENCE = LocalDate.of(2026, 11, 5); // Thursday
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

  private static ReportRow report(long id, LocalDate date, String done, String blockers, String status) {
    return new ReportRow(id, USER, date, done, "", blockers, status, null, null);
  }

  @Test
  void aggregatesSubmittedReportsOfTheWeek() {
    List<ReportRow> reports =
        List.of(
            report(1, LocalDate.of(2026, 11, 2), "完成登录页\n修复超时", "", "submitted"),
            report(2, LocalDate.of(2026, 11, 3), "补充用例", "等待测试环境", "submitted"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, Map.of());

    assertThat(weekly.range().start()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(weekly.submittedCount()).isEqualTo(2);
    assertThat(weekly.doneItems())
        .extracting(WeeklyReportAggregator.ReportLine::text)
        .containsExactly("完成登录页", "修复超时", "补充用例");
    assertThat(weekly.blockerItems())
        .extracting(WeeklyReportAggregator.ReportLine::text)
        .containsExactly("等待测试环境");
  }

  @Test
  void ignoresDrafts() {
    List<ReportRow> reports =
        List.of(
            report(1, LocalDate.of(2026, 11, 2), "定稿内容", "", "submitted"),
            report(2, LocalDate.of(2026, 11, 3), "草稿内容", "草稿阻塞", "draft"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, Map.of());

    assertThat(weekly.submittedCount()).isEqualTo(1);
    assertThat(weekly.doneItems())
        .extracting(WeeklyReportAggregator.ReportLine::text)
        .containsExactly("定稿内容");
    assertThat(weekly.blockerItems()).isEmpty();
  }

  @Test
  void ignoresReportsOutsideTheWeek() {
    List<ReportRow> reports =
        List.of(
            report(1, LocalDate.of(2026, 10, 30), "上周内容", "", "submitted"),
            report(2, LocalDate.of(2026, 11, 3), "本周内容", "", "submitted"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, Map.of());

    assertThat(weekly.doneItems())
        .extracting(WeeklyReportAggregator.ReportLine::text)
        .containsExactly("本周内容");
  }

  @Test
  void deduplicatesRepeatedDoneLinesAndBlanks() {
    List<ReportRow> reports =
        List.of(
            report(1, LocalDate.of(2026, 11, 2), "评审用例\n\n  评审用例  \n写日报", "", "submitted"),
            report(2, LocalDate.of(2026, 11, 3), "写日报", "", "submitted"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, Map.of());

    assertThat(weekly.doneItems())
        .extracting(WeeklyReportAggregator.ReportLine::text)
        .containsExactly("评审用例", "写日报");
  }

  @Test
  void unionsLinkedProjectsInStableOrder() {
    List<ReportRow> reports =
        List.of(
            report(1, LocalDate.of(2026, 11, 2), "x", "", "submitted"),
            report(2, LocalDate.of(2026, 11, 3), "y", "", "submitted"));
    Map<Long, List<Long>> links = new LinkedHashMap<>();
    links.put(1L, List.of(10L, 20L));
    links.put(2L, List.of(20L, 30L));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, links);

    assertThat(weekly.projectIds()).containsExactly(10L, 20L, 30L);
  }

  @Test
  void countsWeekdaysUpToTodayAsMissing() {
    // Thursday; only Monday and Tuesday were submitted -> Wednesday and Thursday are missing.
    List<ReportRow> reports =
        List.of(
            report(1, LocalDate.of(2026, 11, 2), "a", "", "submitted"),
            report(2, LocalDate.of(2026, 11, 3), "b", "", "submitted"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, Map.of());

    assertThat(weekly.missingWeekdays())
        .containsExactly(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5));
  }

  @Test
  void draftsDoNotCountTowardsMissingDays() {
    List<ReportRow> reports =
        List.of(report(1, LocalDate.of(2026, 11, 5), "draft only", "", "draft"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(REFERENCE, TODAY, reports, Map.of());

    assertThat(weekly.missingWeekdays()).contains(LocalDate.of(2026, 11, 5));
  }

  @Test
  void missingWeekdaysStayCorrectAcrossMonthBoundary() {
    // 2026-01-01 is Thursday; the week starts 2025-12-29.
    LocalDate reference = LocalDate.of(2026, 1, 1);
    List<ReportRow> reports =
        List.of(report(1, LocalDate.of(2025, 12, 31), "year end", "", "submitted"));

    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(reference, reference, reports, Map.of());

    assertThat(weekly.range().isoLabel()).isEqualTo("2026-W01");
    assertThat(weekly.missingWeekdays())
        .containsExactly(
            LocalDate.of(2025, 12, 29),
            LocalDate.of(2025, 12, 30),
            LocalDate.of(2026, 1, 1));
  }

  @Test
  void emptyWeekHasNoItems() {
    WeeklyReportAggregator.WeeklyReport weekly =
        WeeklyReportAggregator.aggregate(
            REFERENCE, TODAY, new ArrayList<>(), new HashMap<>());

    assertThat(weekly.submittedReports()).isEmpty();
    assertThat(weekly.doneItems()).isEmpty();
    assertThat(weekly.blockerItems()).isEmpty();
    assertThat(weekly.projectIds()).isEmpty();
    assertThat(weekly.submittedCount()).isZero();
  }

  @Test
  void nullArgumentsAreRejected() {
    assertThatThrownBy(
            () -> WeeklyReportAggregator.aggregate(null, TODAY, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> WeeklyReportAggregator.aggregate(REFERENCE, null, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> WeeklyReportAggregator.aggregate(REFERENCE, TODAY, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> WeeklyReportAggregator.aggregate(REFERENCE, TODAY, List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

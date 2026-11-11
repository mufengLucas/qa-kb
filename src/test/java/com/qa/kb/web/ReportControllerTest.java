package com.qa.kb.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.common.GlobalExceptionHandler;
import com.qa.kb.model.ReportItemRow;
import com.qa.kb.model.ReportRow;
import com.qa.kb.model.ReportWithUser;
import com.qa.kb.report.ReportService;
import com.qa.kb.report.WeekRange;
import com.qa.kb.report.WeeklyReportAggregator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportController")
class ReportControllerTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

  @Mock private ReportService reportService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    var objectMapper =
        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    mvc =
        MockMvcBuilders.standaloneSetup(new ReportController(reportService, CLOCK))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    objectMapper))
            .build();
  }

  private static ReportRow report() {
    return new ReportRow(
        7L, 2L, TODAY, "d", "p", "b", "submitted", LocalDateTime.now(), LocalDateTime.now());
  }

  @Test
  void saveReportDelegatesWithPrincipalAndReturnsNoContent() throws Exception {
    mvc.perform(
            put("/api/reports/daily/2026-11-04")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"done\":\"d\",\"plan\":\"p\",\"blockers\":\"b\","
                        + "\"status\":\"submitted\",\"projectIds\":[5]}"))
        .andExpect(status().isNoContent());

    verify(reportService)
        .saveDaily(2L, LocalDate.of(2026, 11, 4), "d", "p", "b", "submitted", List.of(5L));
  }

  @Test
  void saveReportRejectsMalformedDate() throws Exception {
    mvc.perform(
            put("/api/reports/daily/not-a-date")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_parameter"));
  }

  @Test
  void dailyReportReturnsTheRow() throws Exception {
    when(reportService.find(2L, TODAY)).thenReturn(Optional.of(report()));

    mvc.perform(get("/api/reports/daily/2026-11-05").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("submitted"));
  }

  @Test
  void dailyReportReturnsEmptyBodyWhenNothingWasWritten() throws Exception {
    when(reportService.find(2L, TODAY)).thenReturn(Optional.empty());

    // 「今天还没写日报」是 200 空体（避免浏览器控制台打印 404 噪音），前端回落到空白草稿
    mvc.perform(get("/api/reports/daily/2026-11-05").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(content().string(""));
  }

  @Test
  void dailyListDefaultsToBusinessToday() throws Exception {
    when(reportService.reportsOn(TODAY))
        .thenReturn(
            List.of(
                new ReportWithUser(
                    7L, 2L, "Alice", TODAY, "d", "p", "", "submitted", null, null)));

    mvc.perform(get("/api/reports/daily").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userName").value("Alice"));
  }

  @Test
  void missingDefaultsToBusinessToday() throws Exception {
    when(reportService.missingUsers(TODAY)).thenReturn(List.of(3L));

    mvc.perform(get("/api/reports/missing").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(3));
  }

  @Test
  void weeklyMeUsesPrincipal() throws Exception {
    WeekRange range = new WeekRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8));
    when(reportService.weekly(2L, TODAY))
        .thenReturn(
            new WeeklyReportAggregator.WeeklyReport(
                range, List.of(), List.of(), List.of(), Set.of(), 0, List.of()));

    mvc.perform(get("/api/reports/weekly/me").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.range.start").value("2026-11-02"))
        .andExpect(jsonPath("$.submittedCount").value(0));
  }

  @Test
  void weeklyOfAnotherUserRequiresUserId() throws Exception {
    WeekRange range = new WeekRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8));
    when(reportService.weekly(eq(9L), any()))
        .thenReturn(
            new WeeklyReportAggregator.WeeklyReport(
                range, List.of(), List.of(), List.of(), Set.of(), 0, List.of()));

    mvc.perform(get("/api/reports/weekly?userId=9").principal(TestAuth.ADMIN))
        .andExpect(status().isOk());

    verify(reportService).weekly(9L, TODAY);
  }

  @Test
  void dailyReportExposesLinkedProjectIdsForEditing() throws Exception {
    when(reportService.projectIdsOf(2L, LocalDate.of(2026, 11, 4))).thenReturn(List.of(5L, 6L));

    mvc.perform(get("/api/reports/daily/2026-11-04/projects").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(5))
        .andExpect(jsonPath("$[1]").value(6));

    verify(reportService).projectIdsOf(2L, LocalDate.of(2026, 11, 4));
  }

  @Test
  void dailyReportProjectIdsRejectMalformedDate() throws Exception {
    mvc.perform(get("/api/reports/daily/not-a-date/projects").principal(TestAuth.MEMBER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_parameter"));
  }

  @Test
  void mineDefaultsToTheLastSevenDays() throws Exception {
    when(reportService.mine(2L, TODAY.minusDays(6), TODAY)).thenReturn(List.of());

    mvc.perform(get("/api/reports/mine").principal(TestAuth.MEMBER))
        .andExpect(status().isOk());
  }

  /* ------------------------------------------------------------------ 周报端点 */

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

  private static ReportItemRow item() {
    return new ReportItemRow(
        5L,
        77L,
        "写用例",
        false,
        10L,
        3L,
        1,
        LocalDateTime.of(2026, 11, 5, 10, 0),
        LocalDateTime.of(2026, 11, 5, 10, 0));
  }

  @Test
  void weeklyReportReturnsEmptyBodyWhenNotYetWritten() throws Exception {
    when(reportService.find(2L, MONDAY)).thenReturn(Optional.empty());

    mvc.perform(get("/api/reports/weekly/2026-11-02").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(content().string(""));
  }

  @Test
  void weeklyReportReturnsTheRow() throws Exception {
    when(reportService.find(2L, MONDAY)).thenReturn(Optional.of(report()));

    mvc.perform(get("/api/reports/weekly/2026-11-02").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("submitted"));
  }

  @Test
  void saveWeeklyDelegatesAndReturnsNoContent() throws Exception {
    mvc.perform(
            put("/api/reports/weekly/2026-11-02")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"done\":\"d\",\"plan\":\"p\",\"blockers\":\"b\","
                        + "\"status\":\"submitted\",\"projectIds\":[5]}"))
        .andExpect(status().isNoContent());

    verify(reportService)
        .saveWeekly(2L, MONDAY, "d", "p", "b", "submitted", List.of(5L));
  }

  @Test
  void weeklyProjectIdsDelegates() throws Exception {
    when(reportService.projectIdsOf(2L, MONDAY)).thenReturn(List.of(5L));

    mvc.perform(get("/api/reports/weekly/2026-11-02/projects").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(5));
  }

  @Test
  void weeklyItemsListsForMondayWeek() throws Exception {
    when(reportService.listItems(2L, MONDAY)).thenReturn(List.of(item()));

    mvc.perform(get("/api/reports/weekly/2026-11-02/items").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(5))
        .andExpect(jsonPath("$[0].title").value("写用例"))
        .andExpect(jsonPath("$[0].taskId").value(10));
  }

  @Test
  void addWeeklyItemReturnsCreated() throws Exception {
    when(reportService.addItem(2L, MONDAY, "写用例", 3L, null)).thenReturn(item());

    mvc.perform(
            post("/api/reports/weekly/2026-11-02/items")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"写用例\",\"projectId\":3}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(5));

    verify(reportService).addItem(2L, MONDAY, "写用例", 3L, null);
  }

  @Test
  void addWeeklyItemRejectsBlankTitle() throws Exception {
    mvc.perform(
            post("/api/reports/weekly/2026-11-02/items")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void updateWeeklyItemReturnsUpdatedRow() throws Exception {
    when(reportService.updateItem(2L, 5L, true, null)).thenReturn(item());

    mvc.perform(
            patch("/api/reports/items/5")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"done\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));

    verify(reportService).updateItem(2L, 5L, true, null);
  }

  @Test
  void patchWeeklyItemWithProjectIdLinksProject() throws Exception {
    when(reportService.updateItem(2L, 5L, null, null)).thenReturn(item());
    when(reportService.linkItemProject(2L, 5L, 3L)).thenReturn(item());

    mvc.perform(
            patch("/api/reports/items/5")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));

    verify(reportService).linkItemProject(2L, 5L, 3L);
  }

  @Test
  void deleteWeeklyItemReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/reports/items/5").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(reportService).deleteItem(2L, 5L);
  }

  @Test
  void teamOnWeekUsesWeekParam() throws Exception {
    when(reportService.reportsOn(MONDAY))
        .thenReturn(
            List.of(
                new ReportWithUser(
                    7L, 2L, "Alice", MONDAY, "d", "p", "", "submitted", null, null)));

    mvc.perform(get("/api/reports/team?week=2026-11-02").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userName").value("Alice"));
  }

  @Test
  void missingWeekUsesWeekParam() throws Exception {
    when(reportService.missingUsers(MONDAY)).thenReturn(List.of(3L));

    mvc.perform(get("/api/reports/missing?week=2026-11-02").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(3));
  }
}

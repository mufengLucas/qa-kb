package com.qa.kb.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.kb.auth.Role;
import com.qa.kb.model.ReportRow;
import com.qa.kb.persistence.ProjectMapper;
import com.qa.kb.persistence.ReportMapper;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.report.ReportService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIf("dockerAvailable")
@DisplayName("daily report persistence")
class ReportPersistenceIT extends AbstractMySqlIT {

  private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));

  static boolean dockerAvailable() {
    return DockerAvailability.available();
  }

  @Autowired private UserMapper userMapper;
  @Autowired private ProjectMapper projectMapper;
  @Autowired private ReportService reportService;
  @Autowired private ReportMapper reportMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long userId;
  private long projectA;
  private long projectB;

  @BeforeEach
  void seed() {
    String suffix = Long.toString(System.nanoTime());
    userMapper.insert("it_report_" + suffix, "日报测试", "$2a$10$abcdefghijklmnopqrstuv", Role.MEMBER, true);
    userId = userMapper.lastInsertId();

    projectMapper.insert("项目A", "RA" + suffix.substring(suffix.length() - 5), userId, "ACTIVE", null, null, "");
    projectA = projectMapper.lastInsertId();
    projectMapper.insert("项目B", "RB" + suffix.substring(suffix.length() - 5), userId, "ACTIVE", null, null, "");
    projectB = projectMapper.lastInsertId();
  }

  @Test
  void upsertKeepsExactlyOneRowPerUserAndDate() {
    reportService.saveDaily(userId, TODAY, "第一版", "计划", "", "submitted", List.of());
    reportService.saveDaily(userId, TODAY, "第二版", "计划2", "阻塞", "submitted", List.of());

    ReportRow row = reportService.find(userId, TODAY).orElseThrow();
    assertThat(row.done()).isEqualTo("第二版");
    assertThat(row.blockers()).isEqualTo("阻塞");

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM daily_reports WHERE user_id = ? AND report_date = ?",
            Integer.class,
            userId,
            TODAY);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void projectLinksAreReplacedNotAppended() {
    reportService.saveDaily(userId, TODAY, "d", "p", "b", "submitted", List.of(projectA, projectB));
    assertThat(reportService.projectIdsOf(userId, TODAY))
        .containsExactlyInAnyOrder(projectA, projectB);

    reportService.saveDaily(userId, TODAY, "d", "p", "b", "submitted", List.of(projectB));

    assertThat(reportService.projectIdsOf(userId, TODAY)).containsExactly(projectB);
  }

  @Test
  void weeklyAggregationIgnoresDraftsAndUnionsProjects() {
    LocalDate reference =
        TODAY.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.WEDNESDAY));
    reportService.saveDaily(
        userId,
        reference.minusDays(1),
        "完成登录用例",
        "继续",
        "",
        "submitted",
        List.of(projectA));
    reportService.saveDaily(
        userId, reference, "完成搜索用例", "继续", "", "submitted", List.of(projectB));
    reportService.saveDaily(userId, reference, "草稿内容", "", "", "draft", List.of(projectA));

    var weekly = reportService.weekly(userId, reference);

    assertThat(weekly.submittedCount()).isEqualTo(2);
    assertThat(weekly.projectIds()).containsExactlyInAnyOrder(projectA, projectB);
    assertThat(weekly.doneItems())
        .extracting(item -> item.text())
        .contains("完成登录用例", "完成搜索用例")
        .doesNotContain("草稿内容");
  }

  @Test
  void futureDatesAreRejectedBeforeTouchingTheDatabase() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                reportService.saveDaily(
                    userId, TODAY.plusDays(1), "d", "p", "b", "submitted", List.of()))
        .isInstanceOf(com.qa.kb.common.ApiException.class);

    assertThat(reportService.find(userId, TODAY.plusDays(1))).isEmpty();
  }

  @Test
  void missingUsersQueryExcludesPeopleWhoSubmitted() {
    var missingBefore = reportService.missingUsers(TODAY);
    assertThat(missingBefore).contains(userId);

    reportService.saveDaily(userId, TODAY, "d", "p", "b", "submitted", List.of());

    assertThat(reportService.missingUsers(TODAY)).doesNotContain(userId);
  }

  @Test
  void mapperReturnsReportsWithUserNames() {
    reportService.saveDaily(userId, TODAY, "d", "p", "b", "submitted", List.of());

    assertThat(reportMapper.listByDate(TODAY))
        .anySatisfy(
            row -> {
              assertThat(row.userId()).isEqualTo(userId);
              assertThat(row.userName()).isEqualTo("日报测试");
            });
  }
}

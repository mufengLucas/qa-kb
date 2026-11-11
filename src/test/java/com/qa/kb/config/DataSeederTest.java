package com.qa.kb.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qa.kb.model.PageRow;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.persistence.ProjectMapper;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.page.PageService;
import com.qa.kb.project.ProjectService;
import com.qa.kb.report.ReportService;
import com.qa.kb.space.SpaceService;
import com.qa.kb.user.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSeeder")
class DataSeederTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Mock private UserMapper userMapper;
  @Mock private UserService userService;
  @Mock private SpaceService spaceService;
  @Mock private PageService pageService;
  @Mock private ProjectService projectService;
  @Mock private ReportService reportService;
  @Mock private ProjectMapper projectMapper;

  private KbProperties properties;
  private DataSeeder seeder;

  @BeforeEach
  void setUp() {
    properties = new KbProperties();
    seeder =
        new DataSeeder(
            properties,
            userMapper,
            userService,
            spaceService,
            pageService,
            projectService,
            reportService,
            projectMapper,
            CLOCK);
  }

  private static UserService.UserView userView(long id, String login) {
    return new UserService.UserView(id, login, login, "MEMBER", true);
  }

  private static PageRow page(long id) {
    return new PageRow(
        id, 10L, null, "页面" + id, "page", "", "PUBLISHED", 1L, 0,
        LocalDateTime.now(), LocalDateTime.now(), null);
  }

  @Test
  void disabledSeedingDoesNothing() {
    properties.getSeed().setEnabled(false);

    seeder.run(null);

    verifyNoInteractions(userMapper, userService, spaceService, pageService);
  }

  @Test
  void neverOverwritesAnExistingInstallation() {
    when(userMapper.count()).thenReturn(3L);

    seeder.run(null);

    verifyNoInteractions(userService, spaceService, pageService, projectService);
  }

  @Test
  void seedsOnlyTheAdministratorWhenDemoIsDisabled() {
    properties.getSeed().setDemo(false);
    when(userMapper.count()).thenReturn(0L);
    when(userService.create("admin", "管理员", "change-me-please-1", "ADMIN"))
        .thenReturn(userView(1, "admin"));

    seeder.run(null);

    verify(userService).create("admin", "管理员", "change-me-please-1", "ADMIN");
    verifyNoInteractions(spaceService, pageService, projectService, reportService);
  }

  @Test
  void seedsDemoContentOnce() {
    when(userMapper.count()).thenReturn(0L);
    when(userService.create("admin", "管理员", "change-me-please-1", "ADMIN"))
        .thenReturn(userView(1, "admin"));
    when(userService.create("alice", "爱丽丝", "change-me-demo-1", "MEMBER"))
        .thenReturn(userView(2, "alice"));
    when(userService.create("bob", "鲍勃", "change-me-demo-1", "MEMBER"))
        .thenReturn(userView(3, "bob"));
    when(userService.create("viewer", "访客", "change-me-demo-1", "VIEWER"))
        .thenReturn(userView(4, "viewer"));

    when(spaceService.create(eq("测试规范"), eq("qa-standards"), anyString(), anyString(), eq(1)))
        .thenReturn(new SpaceRow(10L, "测试规范", "qa-standards", "", "", 1));
    when(spaceService.create(eq("接口文档"), eq("api-docs"), anyString(), anyString(), eq(2)))
        .thenReturn(new SpaceRow(11L, "接口文档", "api-docs", "", "", 2));

    when(pageService.create(anyLong(), any(), anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(page(20), page(21), page(22));

    when(projectService.create(
            eq("回归测试项目"), eq("QA2026"), eq(1L), eq("ACTIVE"), any(), any(), anyString()))
        .thenReturn(new ProjectRow(30L, "回归测试项目", "QA2026", 1L, "ACTIVE", null, null, ""));
    when(projectMapper.insertMilestone(eq(30L), eq("用例评审"), any(LocalDate.class), eq("OPEN")))
        .thenReturn(1);
    when(projectMapper.lastInsertId()).thenReturn(40L);

    seeder.run(null);

    verify(userService, times(4)).create(anyString(), anyString(), anyString(), anyString());
    verify(spaceService, times(2)).create(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    verify(pageService, times(3))
        .create(anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
    verify(pageService).setTags(eq(20L), eq(java.util.List.of("规范", "登录")));
    verify(pageService).setTags(eq(22L), eq(java.util.List.of("接口", "入门")));
    verify(projectMapper).insertMilestone(eq(30L), eq("用例评审"), any(LocalDate.class), eq("OPEN"));
    verify(projectMapper, times(3))
        .insertTask(
            eq(30L), any(), anyString(), any(), anyString(),
            org.mockito.ArgumentMatchers.anyInt(), any(), any(),
            org.mockito.ArgumentMatchers.anyInt());
    verify(reportService, times(3))
        .saveDaily(
            anyLong(), any(LocalDate.class), anyString(), anyString(), anyString(), eq("submitted"),
            any());
  }

  @Test
  void doesNotTouchTheDatabaseWhenDisabled() {
    properties.getSeed().setEnabled(false);
    seeder.run(null);
    verify(userMapper, never()).count();
  }
}

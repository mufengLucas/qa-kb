package com.qa.kb.config;

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
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-run bootstrap.
 *
 * <p>Creates the administrator only when the users table is empty, so restarts never overwrite
 * production data. Demo content can be disabled with {@code KB_SEED_DEMO=false}; the admin password
 * must be supplied through {@code KB_ADMIN_PASSWORD} in any real deployment.
 */
@Component
public class DataSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

  private final KbProperties properties;
  private final UserMapper userMapper;
  private final UserService userService;
  private final SpaceService spaceService;
  private final PageService pageService;
  private final ProjectService projectService;
  private final ReportService reportService;
  private final ProjectMapper projectMapper;
  private final Clock clock;

  public DataSeeder(
      KbProperties properties,
      UserMapper userMapper,
      UserService userService,
      SpaceService spaceService,
      PageService pageService,
      ProjectService projectService,
      ReportService reportService,
      ProjectMapper projectMapper,
      Clock clock) {
    this.properties = properties;
    this.userMapper = userMapper;
    this.userService = userService;
    this.spaceService = spaceService;
    this.pageService = pageService;
    this.projectService = projectService;
    this.reportService = reportService;
    this.projectMapper = projectMapper;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    KbProperties.Seed seed = properties.getSeed();
    if (!seed.isEnabled()) {
      return;
    }
    if (userMapper.count() > 0) {
      log.info("Seed skipped: users already exist");
      return;
    }

    UserService.UserView admin =
        userService.create(seed.getAdminLogin(), seed.getAdminName(), seed.getAdminPassword(), "ADMIN");
    log.info("Created administrator '{}'; change the password after first login", admin.login());

    if (!seed.isDemo()) {
      return;
    }

    UserService.UserView alice =
        userService.create("alice", "爱丽丝", seed.getDemoPassword(), "MEMBER");
    UserService.UserView bob = userService.create("bob", "鲍勃", seed.getDemoPassword(), "MEMBER");
    userService.create("viewer", "访客", seed.getDemoPassword(), "VIEWER");

    SpaceRow standards = spaceService.create("测试规范", "qa-standards", "📘", "团队测试流程与规范", 1);
    SpaceRow apiDocs = spaceService.create("接口文档", "api-docs", "🔌", "接口契约与用例", 2);

    PageRow loginGuide =
        pageService.create(
            standards.id(),
            null,
            "登录接口测试规范",
            "# 登录接口测试规范\n\n"
                + "## 必测场景\n\n"
                + "- 正确的用户名与密码可以登录\n"
                + "- 密码错误返回 401，且不泄露账号是否存在\n"
                + "- 连续 5 次失败后按 IP 限流 5 分钟\n"
                + "- 会话 Cookie 必须为 HttpOnly\n",
            "PUBLISHED",
            admin.id());
    pageService.create(
        standards.id(),
        loginGuide.id(),
        "登录接口用例清单",
        "## 用例\n\n1. 正常登录\n2. 空用户名\n3. 错误密码\n4. 限流验证\n",
        "PUBLISHED",
        admin.id());
    PageRow apiHome =
        pageService.create(
            apiDocs.id(),
            null,
            "接口测试入门",
            "# 接口测试入门\n\n使用 REST Assured 或 Postman 编写冒烟用例。\n",
            "PUBLISHED",
            alice.id());

    pageService.setTags(loginGuide.id(), List.of("规范", "登录"));
    pageService.setTags(apiHome.id(), List.of("接口", "入门"));

    ProjectRow project =
        projectService.create(
            "回归测试项目",
            "QA2026",
            admin.id(),
            "ACTIVE",
            LocalDate.now(clock).minusDays(14),
            LocalDate.now(clock).plusDays(30),
            "登录与搜索模块的回归测试");
    projectMapper.insertMilestone(
        project.id(), "用例评审", LocalDate.now(clock).plusDays(5), "OPEN");
    long milestoneId = projectMapper.lastInsertId();
    projectMapper.insertTask(
        project.id(), milestoneId, "编写登录接口用例", alice.id(), "doing", 1, null,
        LocalDate.now(clock).plusDays(2), 50);
    projectMapper.insertTask(
        project.id(), milestoneId, "搭建自动化框架", bob.id(), "todo", 2, null,
        LocalDate.now(clock).plusDays(10), 0);
    projectMapper.insertTask(
        project.id(), null, "回归旧功能", alice.id(), "done", 3, null,
        LocalDate.now(clock).minusDays(1), 100);

    LocalDate today = LocalDate.now(clock);
    reportService.saveDaily(
        alice.id(),
        today,
        "完成登录接口用例 12 条\n评审通过搜索模块设计",
        "补齐异常场景用例",
        "测试环境数据库偶发连接超时",
        "submitted",
        List.of(project.id()));
    reportService.saveDaily(
        bob.id(),
        today,
        "搭建 Playwright 冒烟框架",
        "接入 CI",
        "",
        "submitted",
        List.of(project.id()));
    reportService.saveDaily(
        alice.id(),
        today.minusDays(1),
        "梳理登录接口字段与错误码",
        "编写用例",
        "",
        "submitted",
        List.of(project.id()));

    log.info("Seeded demo spaces, pages, project and reports");
  }
}

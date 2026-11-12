package com.qa.kb.config;

import com.qa.kb.auth.AuthService;
import com.qa.kb.auth.LoginRateLimiter;
import com.qa.kb.attachment.AttachmentService;
import com.qa.kb.audit.AuditService;
import com.qa.kb.backup.BackupService;
import com.qa.kb.page.PageService;
import com.qa.kb.persistence.AttachmentMapper;
import com.qa.kb.persistence.AuditMapper;
import com.qa.kb.persistence.PageMapper;
import com.qa.kb.persistence.ProjectMapper;
import com.qa.kb.persistence.ReportItemMapper;
import com.qa.kb.persistence.ReportMapper;
import com.qa.kb.persistence.RevisionMapper;
import com.qa.kb.persistence.SearchMapper;
import com.qa.kb.persistence.SessionMapper;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.TagMapper;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.project.ProjectService;
import com.qa.kb.report.ReportService;
import com.qa.kb.search.SearchService;
import com.qa.kb.space.SpaceService;
import com.qa.kb.user.UserService;
import com.qa.kb.web.AuditInterceptor;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the framework-free services.
 *
 * <p>The {@link Clock} is fixed to the business timezone so that "today", report dates and session
 * expiry all use {@code Asia/Shanghai} even when the JVM runs in UTC (e.g. inside a container).
 */
@Configuration
@EnableConfigurationProperties(KbProperties.class)
public class AppConfig {

  @Bean
  public Clock clock(KbProperties properties) {
    return Clock.system(ZoneId.of(properties.getTimezone()));
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  /**
   * MyBatis 多方言支持：按 JDBC 产品名识别 MySQL / SQLite，
   * {@code lastInsertId} 等语句在 XML 中用 {@code databaseId} 提供两套实现。
   */
  @Bean
  public DatabaseIdProvider databaseIdProvider() {
    VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
    Properties names = new Properties();
    names.setProperty("MySQL", "mysql");
    names.setProperty("SQLite", "sqlite");
    provider.setProperties(names);
    return provider;
  }

  @Bean
  public LoginRateLimiter loginRateLimiter(Clock clock) {
    return new LoginRateLimiter(
        LoginRateLimiter.DEFAULT_MAX_FAILURES, LoginRateLimiter.DEFAULT_WINDOW, clock);
  }

  @Bean
  public AuthService authService(
      UserMapper userMapper,
      SessionMapper sessionMapper,
      PasswordEncoder passwordEncoder,
      LoginRateLimiter loginRateLimiter,
      KbProperties properties,
      Clock clock) {
    return new AuthService(
        userMapper, sessionMapper, passwordEncoder, loginRateLimiter, properties, clock);
  }

  @Bean
  public SpaceService spaceService(SpaceMapper spaceMapper) {
    return new SpaceService(spaceMapper);
  }

  @Bean
  public PageService pageService(
      PageMapper pageMapper,
      RevisionMapper revisionMapper,
      SpaceMapper spaceMapper,
      TagMapper tagMapper,
      Clock clock) {
    return new PageService(pageMapper, revisionMapper, spaceMapper, tagMapper, clock);
  }

  @Bean
  public AttachmentService attachmentService(
      AttachmentMapper attachmentMapper, PageMapper pageMapper, KbProperties properties) {
    return new AttachmentService(
        attachmentMapper, pageMapper, java.nio.file.Path.of(properties.getUploadDir()));
  }

  @Bean
  public ReportService reportService(
      ReportMapper reportMapper,
      ReportItemMapper reportItemMapper,
      ProjectMapper projectMapper,
      ProjectService projectService,
      Clock clock) {
    return new ReportService(reportMapper, reportItemMapper, projectMapper, projectService, clock);
  }

  @Bean
  public ProjectService projectService(ProjectMapper projectMapper, Clock clock) {
    return new ProjectService(projectMapper, clock);
  }

  @Bean
  public SearchService searchService(SearchMapper searchMapper) {
    return new SearchService(searchMapper);
  }

  @Bean
  public UserService userService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
    return new UserService(userMapper, passwordEncoder);
  }

  @Bean
  public BackupService backupService(
      DataSource dataSource, KbProperties properties, Clock clock) {
    KbProperties.Backup backup = properties.getBackup();
    String offsite = backup.getOffsiteDir();
    return new BackupService(
        dataSource,
        java.nio.file.Path.of(backup.getDir()),
        backup.getKeep(),
        offsite == null || offsite.isBlank() ? null : java.nio.file.Path.of(offsite),
        clock);
  }

  @Bean
  public AuditService auditService(AuditMapper auditMapper) {
    return new AuditService(auditMapper);
  }

  /** P2 审计：统一拦 /api/** 写操作（GET 不产生审计）。 */
  @Bean
  public WebMvcConfigurer auditWebMvcConfigurer(AuditService auditService) {
    AuditInterceptor interceptor = new AuditInterceptor(auditService);
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
      }
    };
  }
}

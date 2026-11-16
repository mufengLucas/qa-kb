package com.qa.kb.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.kb.db.MySqlTestDatabase;
import com.qa.kb.db.SqlScriptRunner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 备份/裁剪/异地副本，跑在真实 MySQL 上（每个用例一个一次性 schema）。
 *
 * <p>导出物是逻辑 dump（{@code kb.sql}），因此"可恢复"的断言就是把 dump 重新灌进一个空库并
 * 回读数据——这正是线上恢复要做的事。
 */
@DisplayName("BackupService")
class BackupServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-06T01:30:00Z"), ZONE); // 09:30 上海时间

  @Test
  @DisplayName("生成可用的 zip 快照，dump 可重新灌回空库")
  void createsRestorableZip(@TempDir Path dir) throws Exception {
    String schema = prepare("backup_zip");

    Path backupDir = dir.resolve("backups");
    BackupService service = new BackupService(dataSource(schema), backupDir, 14, null, CLOCK);

    BackupService.BackupResult result = service.runOnce();

    assertThat(result.file()).exists();
    assertThat(result.file().getFileName().toString()).startsWith("kb-20261106-093000");
    assertThat(result.bytes()).isPositive();
    assertThat(result.offsiteCopied()).isFalse();

    String dump;
    try (ZipFile zip = new ZipFile(result.file().toFile())) {
      ZipEntry entry = zip.getEntry("kb.sql");
      assertThat(entry).as("zip 内必须包含 kb.sql").isNotNull();
      try (InputStream in = zip.getInputStream(entry)) {
        dump = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    assertThat(dump)
        .as("dump 必须带外键开关与数据行")
        .contains("SET FOREIGN_KEY_CHECKS=0")
        .contains("SET FOREIGN_KEY_CHECKS=1")
        .contains("`pages`")
        .contains("登录接口");

    assertRestores(dump);

    // 快照文件不会残留在备份目录里
    assertThat(Files.list(backupDir))
        .allMatch(p -> p.getFileName().toString().endsWith(".zip"));
  }

  @Test
  @DisplayName("只保留最近 N 份备份")
  void prunesOldBackups(@TempDir Path dir) throws Exception {
    String schema = prepare("backup_prune");

    Path backupDir = dir.resolve("backups");
    Files.createDirectories(backupDir);
    for (int day = 1; day <= 16; day++) {
      String name = String.format("kb-202610%02d-010000-000.zip", day);
      Files.writeString(backupDir.resolve(name), "old");
    }

    BackupService service = new BackupService(dataSource(schema), backupDir, 14, null, CLOCK);
    service.runOnce();

    List<Path> zips;
    try (var stream = Files.list(backupDir)) {
      zips = stream.filter(p -> p.toString().endsWith(".zip")).toList();
    }
    assertThat(zips).hasSize(14);
    assertThat(zips)
        .extracting(p -> p.getFileName().toString())
        .doesNotContain("kb-20261001-010000-000.zip", "kb-20261002-010000-000.zip");
  }

  @Test
  @DisplayName("配置了异地目录时复制一份，并按同样规则清理")
  void copiesToOffsiteDirectory(@TempDir Path dir) throws Exception {
    String schema = prepare("backup_offsite");

    Path backupDir = dir.resolve("backups");
    Path offsite = dir.resolve("offsite");
    Files.createDirectories(offsite);
    Files.writeString(offsite.resolve("kb-20261001-010000-000.zip"), "old");

    BackupService service = new BackupService(dataSource(schema), backupDir, 14, offsite, CLOCK);
    BackupService.BackupResult result = service.runOnce();

    assertThat(result.offsiteCopied()).isTrue();
    assertThat(offsite.resolve(result.file().getFileName())).exists();
  }

  // ------------------------------------------------------------------ helpers

  /** 一次性 schema + 一页数据（表结构由本测试自带，数据走 dump 往返）。 */
  private static String prepare(String name) throws Exception {
    String schema = MySqlTestDatabase.schema(name);
    MySqlTestDatabase.recreate(schema);
    try (Connection connection = MySqlTestDatabase.connect(schema)) {
      seed(connection);
    }
    return schema;
  }

  private static void seed(Connection connection) throws Exception {
    try (Statement st = connection.createStatement()) {
      st.execute(
          "CREATE TABLE pages (id INT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(255) NOT NULL)");
      st.execute("INSERT INTO pages (id, title) VALUES (1, '登录接口')");
    }
  }

  /** 恢复演练：空库 → 建表 → 执行 dump → 回读。 */
  private static void assertRestores(String dump) throws Exception {
    String schema = MySqlTestDatabase.schema("backup_restore");
    MySqlTestDatabase.recreate(schema);
    try (Connection connection = MySqlTestDatabase.connect(schema)) {
      seedSchemaOnly(connection);
      for (String sql : SqlScriptRunner.split(dump)) {
        try (Statement st = connection.createStatement()) {
          st.execute(sql);
        }
      }
      try (Statement st = connection.createStatement();
          ResultSet rs = st.executeQuery("SELECT title FROM pages WHERE id = 1")) {
        assertThat(rs.next()).as("恢复后必须能读到那一页").isTrue();
        assertThat(rs.getString(1)).isEqualTo("登录接口");
      }
    }
  }

  private static void seedSchemaOnly(Connection connection) throws Exception {
    try (Statement st = connection.createStatement()) {
      st.execute(
          "CREATE TABLE pages (id INT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(255) NOT NULL)");
    }
  }

  private static DataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
    dataSource.setUrl(MySqlTestDatabase.jdbcUrl(schema));
    dataSource.setUsername(MySqlTestDatabase.username());
    dataSource.setPassword(MySqlTestDatabase.password());
    return dataSource;
  }
}

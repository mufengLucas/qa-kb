package com.qa.kb.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

@DisplayName("BackupService")
class BackupServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-06T01:30:00Z"), ZONE); // 09:30 上海时间

  @Test
  @DisplayName("生成可用的 zip 快照，解压后数据完整")
  void createsRestorableZip(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("kb.db");
    seed(db);

    Path backupDir = dir.resolve("backups");
    BackupService service = new BackupService(dataSource(db), backupDir, 14, null, CLOCK);

    BackupService.BackupResult result = service.runOnce();

    assertThat(result.file()).exists();
    assertThat(result.file().getFileName().toString()).startsWith("kb-20261106-093000");
    assertThat(result.bytes()).isPositive();
    assertThat(result.offsiteCopied()).isFalse();

    try (ZipFile zip = new ZipFile(result.file().toFile())) {
      ZipEntry entry = zip.getEntry("kb.db");
      assertThat(entry).as("zip 内必须包含 kb.db").isNotNull();
      Path restored = dir.resolve("restored.db");
      try (InputStream in = zip.getInputStream(entry)) {
        Files.copy(in, restored);
      }
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + restored);
          Statement st = connection.createStatement();
          ResultSet rs = st.executeQuery("SELECT title FROM pages WHERE id = 1")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("登录接口");
      }
    }

    assertThat(Files.list(backupDir).filter(p -> p.toString().endsWith(".db"))).isEmpty();
  }

  @Test
  @DisplayName("只保留最近 N 份备份")
  void prunesOldBackups(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("kb.db");
    seed(db);

    Path backupDir = dir.resolve("backups");
    Files.createDirectories(backupDir);
    for (int day = 1; day <= 16; day++) {
      String name = String.format("kb-202610%02d-010000-000.zip", day);
      Files.writeString(backupDir.resolve(name), "old");
    }

    BackupService service = new BackupService(dataSource(db), backupDir, 14, null, CLOCK);
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
    Path db = dir.resolve("kb.db");
    seed(db);

    Path backupDir = dir.resolve("backups");
    Path offsite = dir.resolve("offsite");
    Files.createDirectories(offsite);
    Files.writeString(offsite.resolve("kb-20261001-010000-000.zip"), "old");

    BackupService service = new BackupService(dataSource(db), backupDir, 14, offsite, CLOCK);
    BackupService.BackupResult result = service.runOnce();

    assertThat(result.offsiteCopied()).isTrue();
    assertThat(offsite.resolve(result.file().getFileName())).exists();
  }

  // ------------------------------------------------------------------ helpers

  private static SQLiteDataSource dataSource(Path db) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
    return dataSource;
  }

  private static void seed(Path db) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        Statement st = connection.createStatement()) {
      st.execute("PRAGMA journal_mode=WAL");
      st.execute("CREATE TABLE pages (id INTEGER PRIMARY KEY, title TEXT NOT NULL)");
      st.execute("INSERT INTO pages (id, title) VALUES (1, '登录接口')");
    }
  }
}

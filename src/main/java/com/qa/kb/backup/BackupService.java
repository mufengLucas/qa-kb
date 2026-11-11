package com.qa.kb.backup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consistent single-file database backups.
 *
 * <p>SQLite: {@code VACUUM INTO} produces a transactionally consistent copy even while the
 * application is running (WAL mode included). MySQL: a pure-JDBC logical dump ({@code kb.sql}) of
 * every base table, wrapped in {@code SET FOREIGN_KEY_CHECKS=0/1} so restore order does not matter.
 *
 * <p>The copy (or dump) is deflated into {@code kb-YYYYMMDD-HHmmss-SSS.zip} and, when configured,
 * mirrored to an off-site directory. Old archives are pruned by filename so a lexical sort is also
 * a chronological sort.
 */
public class BackupService {

  public record BackupResult(Path file, long bytes, boolean offsiteCopied) {}

  private static final Logger log = LoggerFactory.getLogger(BackupService.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
  private static final Pattern ARCHIVE_NAME = Pattern.compile("^kb-\\d{8}-\\d{6}-\\d{3}\\.zip$");
  private static final String ENTRY_DB = "kb.db";
  private static final String ENTRY_SQL = "kb.sql";
  private static final int BUFFER = 64 * 1024;

  private final DataSource dataSource;
  private final Path backupDir;
  private final int keep;
  private final Path offsiteDir;
  private final Clock clock;

  public BackupService(
      DataSource dataSource, Path backupDir, int keep, Path offsiteDir, Clock clock) {
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource is required");
    }
    if (backupDir == null) {
      throw new IllegalArgumentException("backupDir is required");
    }
    if (keep < 1) {
      throw new IllegalArgumentException("keep must be >= 1");
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock is required");
    }
    this.dataSource = dataSource;
    this.backupDir = backupDir.toAbsolutePath().normalize();
    this.keep = keep;
    this.offsiteDir = offsiteDir == null ? null : offsiteDir.toAbsolutePath().normalize();
    this.clock = clock;
  }

  /** Runs one backup and returns what was written. Safe to call from a scheduler or ad hoc. */
  public synchronized BackupResult runOnce() {
    Path tempDir = null;
    try {
      Files.createDirectories(backupDir);
      String stamp = LocalDateTime.now(clock).format(STAMP);
      Path target = backupDir.resolve("kb-" + stamp + ".zip");

      tempDir = Files.createTempDirectory("qa-kb-backup-");
      Path snapshot = snapshot(tempDir);
      zip(snapshot, target, snapshot.getFileName().toString());

      long bytes = Files.size(target);
      boolean offsiteCopied = copyOffsite(target);
      prune(backupDir);
      if (offsiteDir != null) {
        prune(offsiteDir);
      }

      log.info(
          "Backup written {} ({} bytes, offsite={})", target.getFileName(), bytes, offsiteCopied);
      return new BackupResult(target, bytes, offsiteCopied);
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("数据库备份失败：" + e.getMessage(), e);
    } finally {
      if (tempDir != null) {
        deleteQuietly(tempDir);
      }
    }
  }

  // ------------------------------------------------------------------ internals

  private Path snapshot(Path dir) throws SQLException, IOException {
    try (Connection connection = dataSource.getConnection()) {
      if (!connection.getAutoCommit()) {
        connection.setAutoCommit(true);
      }
      boolean mysql = isMySql(connection);
      Path target = dir.resolve(mysql ? ENTRY_SQL : ENTRY_DB);
      if (mysql) {
        dumpSql(connection, target);
      } else {
        String sqlPath = target.toString().replace('\\', '/').replace("'", "''");
        try (Statement statement = connection.createStatement()) {
          statement.execute("VACUUM INTO '" + sqlPath + "'");
        }
      }
      return target;
    }
  }

  private static boolean isMySql(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("mysql");
  }

  /** MySQL：把每个基表导出为 INSERT 语句（含外键开关），恢复时无需关心表顺序。 */
  private void dumpSql(Connection connection, Path target)
      throws SQLException, IOException {
    List<String> tables = new ArrayList<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                    + "ORDER BY table_name")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      out.write("SET FOREIGN_KEY_CHECKS=0;\n");
      for (String table : tables) {
        dumpTable(connection, table, out);
      }
      out.write("SET FOREIGN_KEY_CHECKS=1;\n");
    }
  }

  private static void dumpTable(Connection connection, String table, BufferedWriter out)
      throws SQLException, IOException {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM `" + table + "`")) {
      ResultSetMetaData meta = rs.getMetaData();
      int columns = meta.getColumnCount();
      String[] names = new String[columns];
      for (int i = 0; i < columns; i++) {
        names[i] = "`" + meta.getColumnName(i + 1) + "`";
      }
      String header =
          "INSERT INTO `" + table + "` (" + String.join(", ", names) + ") VALUES ";
      boolean anyRow = false;
      StringBuilder row = new StringBuilder();
      while (rs.next()) {
        out.write(anyRow ? ",\n" : header);
        anyRow = true;
        row.setLength(0);
        row.append('(');
        for (int i = 1; i <= columns; i++) {
          if (i > 1) {
            row.append(", ");
          }
          appendValue(row, rs, i);
        }
        row.append(')');
        out.write(row.toString());
      }
      if (anyRow) {
        out.write(";\n");
      }
    }
  }

  private static void appendValue(StringBuilder sb, ResultSet rs, int index)
      throws SQLException {
    Object value = rs.getObject(index);
    if (value == null) {
      sb.append("NULL");
    } else if (value instanceof Number) {
      sb.append(value);
    } else if (value instanceof Boolean) {
      sb.append(Boolean.TRUE.equals(value) ? "1" : "0");
    } else {
      sb.append('\'').append(escapeSql(value.toString())).append('\'');
    }
  }

  private static String escapeSql(String text) {
    StringBuilder sb = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '\'' -> sb.append("\\'");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\0' -> sb.append("\\0");
        case '\u001a' -> sb.append("\\Z");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private static void zip(Path source, Path target, String entryName) throws IOException {
    try (OutputStream out = Files.newOutputStream(target);
        ZipOutputStream zip = new ZipOutputStream(out);
        InputStream in = Files.newInputStream(source)) {
      ZipEntry entry = new ZipEntry(entryName);
      entry.setTime(Files.getLastModifiedTime(source).toMillis());
      zip.putNextEntry(entry);
      byte[] buffer = new byte[BUFFER];
      int read;
      while ((read = in.read(buffer)) != -1) {
        zip.write(buffer, 0, read);
      }
      zip.closeEntry();
    }
  }

  private boolean copyOffsite(Path archive) throws IOException {
    if (offsiteDir == null) {
      return false;
    }
    Files.createDirectories(offsiteDir);
    Files.copy(
        archive,
        offsiteDir.resolve(archive.getFileName()),
        StandardCopyOption.REPLACE_EXISTING);
    return true;
  }

  /** Keeps the newest {@code keep} archives in {@code dir}; everything older is deleted. */
  private void prune(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    List<Path> archives;
    try (var stream = Files.list(dir)) {
      archives =
          stream
              .filter(Files::isRegularFile)
              .filter(path -> ARCHIVE_NAME.matcher(path.getFileName().toString()).matches())
              // Names are zero padded, so filename order is chronological order.
              .sorted(
                  Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
              .toList();
    }
    for (int index = keep; index < archives.size(); index++) {
      Path stale = archives.get(index);
      try {
        Files.deleteIfExists(stale);
        log.info("Pruned old backup {}", stale.getFileName());
      } catch (IOException e) {
        log.warn("Could not delete old backup {}: {}", stale, e.getMessage());
      }
    }
  }

  private static void deleteQuietly(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream.sorted(Comparator.reverseOrder()).forEach(BackupService::deleteFileQuietly);
    } catch (IOException e) {
      log.warn("Could not clean temporary backup directory {}: {}", dir, e.getMessage());
    }
  }

  private static void deleteFileQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete temporary file {}: {}", path, e.getMessage());
    }
  }
}

package com.qa.kb.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时建表（幂等）。
 *
 * <p>按 JDBC 元数据自动识别方言：MySQL 执行 {@code /db/migration/V1__init.sql}
 * （CREATE TABLE IF NOT EXISTS），SQLite 执行 PRAGMA + {@code /db/schema.sql}。
 * 两条路径都可重复执行，首次启动自动建库。
 */
@Component
@Order(0)
public class SchemaInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

  private final DataSource dataSource;

  public SchemaInitializer(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      String product = connection.getMetaData().getDatabaseProductName();
      boolean mysql =
          product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
      if (mysql) {
        SqlScriptRunner.run(connection, "/db/migration/V1__init.sql");
        ensureColumn(connection, "projects", "deleted_at", "DATETIME(3) NULL", true);
        ensureColumn(connection, "spaces", "restricted", "TINYINT(1) NOT NULL DEFAULT 0", true);
        log.info("MySQL schema ready");
      } else {
        try (Statement st = connection.createStatement()) {
          st.execute("PRAGMA journal_mode=WAL");
          st.execute("PRAGMA foreign_keys=ON");
          st.execute("PRAGMA busy_timeout=5000");
        }
        SqlScriptRunner.run(connection, "/db/schema.sql");
        ensureColumn(connection, "projects", "deleted_at", "TEXT", false);
        ensureColumn(connection, "spaces", "restricted", "INTEGER NOT NULL DEFAULT 0", false);
        log.info("SQLite schema ready");
      }
    }
  }

  /**
   * 老库补列：建表脚本是 {@code CREATE TABLE IF NOT EXISTS}，不会给已存在的表加新列，
   * 所以每次启动再幂等地检查一遍（首次建库时列已存在，跳过）。
   */
  private static void ensureColumn(
      Connection connection, String table, String column, String ddlType, boolean mysql)
      throws SQLException {
    boolean exists = false;
    if (mysql) {
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT COUNT(*) FROM information_schema.COLUMNS "
                  + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
        ps.setString(1, table);
        ps.setString(2, column);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          exists = rs.getInt(1) > 0;
        }
      }
    } else {
      try (Statement st = connection.createStatement();
          ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
        while (rs.next()) {
          if (column.equalsIgnoreCase(rs.getString("name"))) {
            exists = true;
            break;
          }
        }
      }
    }
    if (!exists) {
      try (Statement st = connection.createStatement()) {
        st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddlType);
      }
      log.info("Added {}.{} column to existing database", table, column);
    }
  }
}

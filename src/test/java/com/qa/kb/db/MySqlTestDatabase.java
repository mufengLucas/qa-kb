package com.qa.kb.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 测试专用 MySQL 库分配器：每个 {@code @SpringBootTest} 类独占一个一次性 schema，
 * 在 {@code @DynamicPropertySource} 里先 DROP 再 CREATE，重跑测试即重置数据。
 *
 * <p>连接信息全部来自环境变量，公开仓库不存放真实口令：
 *
 * <ul>
 *   <li>{@code KB_DB_HOST}（默认 {@code 127.0.0.1}）
 *   <li>{@code KB_DB_PORT}（默认 {@code 3306}）
 *   <li>{@code KB_DB_USER}（默认 {@code root}）
 *   <li>{@code KB_DB_PASSWORD}（<b>必填</b>，缺失时抛出带提示的异常）
 * </ul>
 */
public final class MySqlTestDatabase {

  private static final String HOST = env("KB_DB_HOST", "127.0.0.1");
  private static final String PORT = env("KB_DB_PORT", "3306");
  private static final String USER = env("KB_DB_USER", "root");
  private static final String OPTIONS =
      "useSSL=false&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
          + "&allowPublicKeyRetrieval=true";

  private MySqlTestDatabase() {}

  /** 申请一个一次性测试库并把连接信息注册进 Spring。name 只能是小写字母/数字/下划线。 */
  public static void register(DynamicPropertyRegistry registry, String name) {
    String schema = schema(name);
    recreate(schema);
    registry.add("spring.datasource.url", () -> jdbcUrl(schema));
    registry.add("spring.datasource.username", () -> USER);
    registry.add("spring.datasource.password", MySqlTestDatabase::password);
  }

  /** {@code qa_kb_<name>}，统一前缀便于手工清理。 */
  public static String schema(String name) {
    if (name == null || !name.matches("[a-z0-9_]+")) {
      throw new IllegalArgumentException("schema 名只能由小写字母/数字/下划线组成: " + name);
    }
    return "qa_kb_" + name;
  }

  public static String jdbcUrl(String schema) {
    return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema + "?" + OPTIONS;
  }

  /** 不带库的管理员连接（用来建库/删库）。 */
  public static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/?" + OPTIONS,
        USER, password());
  }

  public static Connection connect(String schema) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(schema), USER, password());
  }

  public static String username() {
    return USER;
  }

  /** DROP + CREATE（空库，建表交给应用启动时的 SchemaInitializer）。 */
  public static void recreate(String schema) {
    try (Connection connection = adminConnection();
        Statement st = connection.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS `" + schema + "`");
      st.execute(
          "CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    } catch (SQLException e) {
      throw new IllegalStateException(
          "无法准备测试 MySQL 库 " + schema + "（" + HOST + ":" + PORT + "）：" + e.getMessage(),
          e);
    }
  }

  public static String password() {
    String value = System.getenv("KB_DB_PASSWORD");
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "缺少环境变量 KB_DB_PASSWORD：测试要连本地 MySQL（"
              + HOST
              + ":"
              + PORT
              + "）。请在当前会话注入后重跑，例如 PowerShell："
              + "$env:KB_DB_PASSWORD='...'（口令只留在会话里，不入库）。");
    }
    return value;
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}

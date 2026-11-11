package com.qa.kb.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 能力门禁：验证 sqlite-jdbc 自带的 SQLite 是否支持 FTS5 + trigram，
 * 以及中文检索的真实行为（≥3 字可命中，2 字必须走 LIKE 兜底）。
 */
class SqliteCapabilityTest {

    private static Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("kb-capability-", ".db");
        dbFile.toFile().deleteOnExit();
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        SqlScriptRunner.run(conn, "/db/schema.sql");

        exec("INSERT INTO users(login,name,password_hash,role) VALUES('zhang','张三','x','ADMIN')");
        exec("INSERT INTO spaces(name,slug) VALUES('测试知识库','qa')");
        exec("INSERT INTO pages(space_id,title,slug,content_md,author_id)"
                + " VALUES(1,'登录模块测试方案','login-module',"
                + "'# 登录模块\n\n覆盖登录接口的超时处理，以及 login timeout 场景。\n\n| 用例 | 结果 |\n|---|---|\n| 正常登录 | 通过 |',1)");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("schema.sql 能建出全部核心表（触发器含 BEGIN...END 也不会被打断）")
    void schemaCreatesAllTables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables).contains(
                "users", "sessions", "settings",
                "spaces", "pages", "page_revisions", "tags", "page_tags", "attachments",
                "projects", "milestones", "tasks",
                "daily_reports", "daily_report_projects", "report_items", "audit_log",
                "pages_fts");
        assertThat(triggers()).contains("pages_ai", "pages_ad", "pages_au");
    }

    @Test
    @DisplayName("WAL 模式生效")
    void walModeEnabled() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    @DisplayName("trigram 能命中 3 字及以上的中文子串")
    void trigramMatchesChineseSubstringAtLeastThreeChars() throws Exception {
        assertThat(ftsCount("登录接口")).as("4 字中文子串").isEqualTo(1);
        assertThat(ftsCount("超时处理")).as("4 字中文子串").isEqualTo(1);
        assertThat(ftsCount("登录模块测试")).as("6 字中文子串").isEqualTo(1);
        assertThat(ftsCount("timeout")).as("英文单词").isEqualTo(1);
    }

    @Test
    @DisplayName("已知限制：trigram 命中不了 2 字中文 —— 所以 1~2 字必须走 LIKE 兜底")
    void trigramCannotMatchTwoCharChineseQuery() throws Exception {
        assertThat(ftsCount("登录")).as("trigram 对 <3 字符查询不返回任何行").isZero();
    }

    @Test
    @DisplayName("LIKE 兜底能命中 2 字中文")
    void likeFallbackMatchesTwoCharQuery() throws Exception {
        assertThat(likeCount("登录")).isEqualTo(1);
        assertThat(likeCount("超时")).isEqualTo(1);
        assertThat(likeCount("不存在的词")).isZero();
    }

    @Test
    @DisplayName("更新页面后 FTS 索引同步（外部内容表 + 触发器）")
    void updateKeepsFtsInSync() throws Exception {
        exec("UPDATE pages SET title='部署流水线说明', content_md='# 部署\n\n覆盖部署流水线与回滚流程。' WHERE id=1");
        assertThat(ftsCount("部署流水")).isEqualTo(1);
        assertThat(ftsCount("登录接口")).as("旧内容必须已从索引移除").isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static void exec(String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static int ftsCount(String query) throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM pages_fts WHERE pages_fts MATCH ?")) {
            ps.setString(1, query);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int likeCount(String query) throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM pages WHERE deleted_at IS NULL AND (title LIKE ? OR content_md LIKE ?)")) {
            ps.setString(1, "%" + query + "%");
            ps.setString(2, "%" + query + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static List<String> triggers() throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='trigger'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}

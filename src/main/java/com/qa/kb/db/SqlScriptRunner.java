package com.qa.kb.db;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行 classpath 下的 SQL 脚本。
 * 语句之间使用 "--> statement-breakpoint" 分隔，避免按分号切割时被触发器里的 BEGIN...END 打断。
 */
public final class SqlScriptRunner {

    public static final String BREAKPOINT = "--> statement-breakpoint";

    private SqlScriptRunner() {
    }

    public static void run(Connection connection, String classpathResource) throws Exception {
        for (String sql : split(read(classpathResource))) {
            if (sql.isBlank()) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
        }
    }

    public static List<String> split(String script) {
        List<String> result = new ArrayList<>();
        // 有 breakpoint 标记按标记切（兼容触发器里的分号）；否则按分号切（MySQL 迁移脚本无标记）。
        String[] chunks =
                script.contains(BREAKPOINT) ? script.split(BREAKPOINT) : script.split(";");
        for (String chunk : chunks) {
            String cleaned = stripLineComments(chunk).trim();
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    static String read(String classpathResource) throws Exception {
        try (InputStream in = SqlScriptRunner.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 SQL 脚本: " + classpathResource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        }
    }

    private static String stripLineComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}

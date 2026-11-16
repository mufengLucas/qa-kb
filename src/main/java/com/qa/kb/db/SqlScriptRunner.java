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
 * 执行 classpath 下的 SQL 脚本（MySQL 迁移脚本）。
 * 语句之间按分号切割，行首 {@code --} 注释会被忽略。
 */
public final class SqlScriptRunner {

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
        for (String chunk : script.split(";")) {
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

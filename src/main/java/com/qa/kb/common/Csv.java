package com.qa.kb.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极简 RFC 4180 CSV 读写（A1 用例/执行导入导出）。
 *
 * <p>只做两件事：解析（引号字段、字段内逗号与换行、{@code ""} 转义）与序列化（按需加引号）。
 * 行分隔统一按 {@code \r\n} / {@code \n} / {@code \r} 任一识别；解析结果按「记录」组织，
 * 一条记录可跨多行（引号内换行）。不引第三方库：功能面窄，自写 80 行更可控。
 *
 * <p>BOM（{@code ﻿}）在解析时剥掉——Excel 另存的 CSV 常带 BOM，不剥会污染首列表头。
 */
public final class Csv {

  private Csv() {}

  /** 解析整段 CSV 文本；引号未闭合抛 {@link IllegalArgumentException}（由全局处理器转 400）。 */
  public static List<List<String>> parse(String content) {
    if (content == null || content.isEmpty()) {
      return new ArrayList<>();
    }
    String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
    List<List<String>> records = new ArrayList<>();
    List<String> current = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    boolean fieldWasQuoted = false;
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < n && text.charAt(i + 1) == '"') {
            cell.append('"');
            i += 2;
            continue;
          }
          quoted = false;
          i++;
          continue;
        }
        cell.append(c);
        i++;
        continue;
      }
      if (c == '"' && cell.length() == 0) {
        quoted = true;
        fieldWasQuoted = true;
        i++;
        continue;
      }
      if (c == ',') {
        current.add(cell.toString());
        cell.setLength(0);
        fieldWasQuoted = false;
        i++;
        continue;
      }
      if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
          i++;
        }
        current.add(cell.toString());
        cell.setLength(0);
        fieldWasQuoted = false;
        records.add(current);
        current = new ArrayList<>();
        i++;
        continue;
      }
      cell.append(c);
      fieldWasQuoted = false;
      i++;
    }
    if (quoted) {
      throw new IllegalArgumentException("CSV 格式错误：引号未闭合");
    }
    // 末尾无换行的最后一行（或空串残留）也要收进来；纯空白尾巴丢弃
    if (cell.length() > 0 || fieldWasQuoted || !current.isEmpty()) {
      current.add(cell.toString());
      records.add(current);
    }
    return records;
  }

  /** 序列化一条记录（varargs 版，null 字段按空值）：导出行的列常有 null，{@code List.of} 见 null 即炸。 */
  public static String row(String... cells) {
    return row(Arrays.asList(cells));
  }

  /** 序列化一条记录：含逗号 / 引号 / 换行的字段加引号，内部引号翻倍。 */
  public static String row(List<String> cells) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(escape(cells.get(i)));
    }
    return sb.toString();
  }

  static String escape(String value) {
    String raw = value == null ? "" : value;
    boolean needQuote =
        raw.contains(",") || raw.contains("\"") || raw.contains("\n") || raw.contains("\r");
    if (!needQuote) {
      return raw;
    }
    return "\"" + raw.replace("\"", "\"\"") + "\"";
  }
}

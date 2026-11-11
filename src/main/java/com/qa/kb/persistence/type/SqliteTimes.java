package com.qa.kb.persistence.type;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * SQLite 没有原生时间类型，sqlite-jdbc 也不支持 {@code getObject(..., LocalDateTime.class)}。
 * 这里统一用 {@code yyyy-MM-dd HH:mm:ss} 文本存储与解析，配套两个 TypeHandler 使用。
 */
public final class SqliteTimes {

  public static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private SqliteTimes() {}

  public static String format(LocalDateTime value) {
    return value.format(DATE_TIME);
  }

  public static String format(LocalDate value) {
    return value.toString();
  }

  public static LocalDateTime parseDateTime(String text) {
    if (text == null) {
      return null;
    }
    String value = text.trim();
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDateTime.parse(stripMillis(value), DATE_TIME);
    } catch (DateTimeParseException ignored) {
      return LocalDateTime.parse(value.replace(' ', 'T'));
    }
  }

  public static LocalDate parseDate(String text) {
    if (text == null) {
      return null;
    }
    String value = text.trim();
    if (value.isEmpty()) {
      return null;
    }
    return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
  }

  private static String stripMillis(String value) {
    int dot = value.indexOf('.');
    if (dot > 0 && value.length() > dot + 1) {
      return value.substring(0, dot);
    }
    return value;
  }
}

package com.qa.kb.persistence.type;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间列统一以文本形式读写：{@code yyyy-MM-dd HH:mm:ss}（日期为 {@code yyyy-MM-dd}）。
 *
 * <p>列类型是 MySQL 的 DATETIME/DATE 时，JDBC 驱动读出来正是这种文本，写回用 setString 也会
 * 被驱动按时间解析，因此 TypeHandler 两侧都稳。配套 {@link LocalDateTypeHandler} /
 * {@link LocalDateTimeTypeHandler} 使用。
 */
public final class SqlTimes {

  public static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private SqlTimes() {}

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

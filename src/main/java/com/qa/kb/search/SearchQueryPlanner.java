package com.qa.kb.search;

/**
 * Decides between FTS5 trigram MATCH and LIKE for a user query.
 *
 * <p>FTS5's trigram tokenizer indexes every 3-character sequence, so a query shorter than 3 code
 * points can never match. That is why 1-2 character Chinese queries (登录 / 超时 / 环境) must fall
 * back to a LIKE scan. See the project plan, section "中文搜索".
 */
public final class SearchQueryPlanner {

  /** Matches the server-side guard in the search mapper. */
  public static final int MAX_QUERY_CODE_POINTS = 100;

  /** trigram 是 3 字符一个 token，短于 3 个码点的查询无法命中。 */
  public static final int FULLTEXT_MIN_CODE_POINTS = 3;

  private SearchQueryPlanner() {}

  public static SearchQuery plan(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("query is required");
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("query is required");
    }

    int codePoints = trimmed.codePointCount(0, trimmed.length());
    if (codePoints > MAX_QUERY_CODE_POINTS) {
      throw new IllegalArgumentException(
          "query must be at most " + MAX_QUERY_CODE_POINTS + " characters");
    }

    if (codePoints >= FULLTEXT_MIN_CODE_POINTS) {
      return new SearchQuery(trimmed, SearchMode.FULLTEXT, null, quote(trimmed));
    }
    return new SearchQuery(trimmed, SearchMode.LIKE, "%" + escapeLike(trimmed) + "%", null);
  }

  /**
   * FTS5 的短语用双引号包裹，内部的双引号用两个双引号转义（不是反斜杠）。
   * 这样 "{@code 登录 接口}" 被当成一个短语而不是两个词的 OR。
   */
  private static String quote(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}

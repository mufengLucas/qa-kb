package com.qa.kb.search;

/**
 * A planned search query.
 *
 * @param raw the trimmed user input
 * @param mode execution strategy
 * @param likePattern value to bind for {@code LIKE ? ESCAPE '\\'}; only set for {@link SearchMode#LIKE}
 * @param booleanQuery value to bind for {@code MATCH (...) AGAINST (? IN BOOLEAN MODE)}; only set for
 *     {@link SearchMode#FULLTEXT}
 */
public record SearchQuery(String raw, SearchMode mode, String likePattern, String booleanQuery) {}

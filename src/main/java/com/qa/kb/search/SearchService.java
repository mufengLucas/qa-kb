package com.qa.kb.search;

import com.qa.kb.model.SearchHit;
import com.qa.kb.persistence.SearchMapper;
import java.util.List;

/**
 * Wiki search entry point.
 *
 * <p>Routes 1-2 character queries to LIKE and 3+ character queries to MySQL FULLTEXT with the ngram
 * parser. The routing rule lives in {@link SearchQueryPlanner} and is identical to the one used by
 * the UI hint.
 */
public class SearchService {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 50;

  private final SearchMapper searchMapper;

  public SearchService(SearchMapper searchMapper) {
    this.searchMapper = searchMapper;
  }

  public List<SearchHit> search(String rawQuery, Long spaceId, Integer limit) {
    SearchQuery query = SearchQueryPlanner.plan(rawQuery);
    int effectiveLimit = resolveLimit(limit);
    return switch (query.mode()) {
      case FULLTEXT ->
          searchMapper.searchFulltext(query.booleanQuery(), spaceId, effectiveLimit);
      case LIKE -> searchMapper.searchLike(query.likePattern(), spaceId, effectiveLimit);
    };
  }

  private static int resolveLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }
}

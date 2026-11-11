package com.qa.kb.persistence;

import com.qa.kb.model.SearchHit;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SearchMapper {

  /**
   * FULLTEXT with the ngram parser. {@code q} must be a quoted boolean-mode phrase produced by
   * {@link com.qa.kb.search.SearchQueryPlanner}.
   */
  List<SearchHit> searchFulltext(
      @Param("q") String booleanQuery,
      @Param("spaceId") Long spaceId,
      @Param("limit") int limit);

  /** LIKE fallback for 1-2 character queries. {@code pattern} is pre-escaped. */
  List<SearchHit> searchLike(
      @Param("pattern") String likePattern,
      @Param("spaceId") Long spaceId,
      @Param("limit") int limit);
}

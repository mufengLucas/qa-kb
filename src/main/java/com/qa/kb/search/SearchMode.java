package com.qa.kb.search;

/** How a free-text query should be executed against MySQL. */
public enum SearchMode {
  /** MySQL FULLTEXT (ngram parser, boolean mode). Suitable for 3+ character queries. */
  FULLTEXT,
  /** Portable LIKE scan. Required for 1-2 character CJK queries, which ngram cannot match. */
  LIKE
}

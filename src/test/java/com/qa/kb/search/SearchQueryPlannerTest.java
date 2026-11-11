package com.qa.kb.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SearchQueryPlanner")
class SearchQueryPlannerTest {

  @Nested
  @DisplayName("input validation")
  class Validation {

    @Test
    void rejectsNull() {
      assertThatThrownBy(() -> SearchQueryPlanner.plan(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("query");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void rejectsBlank(String input) {
      assertThatThrownBy(() -> SearchQueryPlanner.plan(input))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlyOneHundredCharacters() {
      String input = "a".repeat(100);
      assertThat(SearchQueryPlanner.plan(input).mode()).isEqualTo(SearchMode.FULLTEXT);
    }

    @Test
    void rejectsMoreThanOneHundredCharacters() {
      String input = "a".repeat(101);
      assertThatThrownBy(() -> SearchQueryPlanner.plan(input))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("100");
    }

    @Test
    void trimsSurroundingWhitespace() {
      assertThat(SearchQueryPlanner.plan("  登录  ").raw()).isEqualTo("登录");
    }
  }

  @Nested
  @DisplayName("CJK length routing (ngram token size = 2)")
  class Routing {

    @Test
    void singleHanCharacterUsesLike() {
      SearchQuery query = SearchQueryPlanner.plan("登");
      assertThat(query.mode()).isEqualTo(SearchMode.LIKE);
      assertThat(query.likePattern()).isEqualTo("%登%");
      assertThat(query.booleanQuery()).isNull();
    }

    @Test
    void twoHanCharactersUseLike() {
      SearchQuery query = SearchQueryPlanner.plan("登录");
      assertThat(query.mode()).isEqualTo(SearchMode.LIKE);
      assertThat(query.likePattern()).isEqualTo("%登录%");
    }

    @Test
    void threeHanCharactersUseFulltext() {
      SearchQuery query = SearchQueryPlanner.plan("登录接");
      assertThat(query.mode()).isEqualTo(SearchMode.FULLTEXT);
      assertThat(query.booleanQuery()).isEqualTo("\"登录接\"");
      assertThat(query.likePattern()).isNull();
    }

    @Test
    void fourHanCharactersUseFulltext() {
      SearchQuery query = SearchQueryPlanner.plan("登录接口");
      assertThat(query.mode()).isEqualTo(SearchMode.FULLTEXT);
      assertThat(query.booleanQuery()).isEqualTo("\"登录接口\"");
    }

    @Test
    void twoAsciiCharactersUseLike() {
      assertThat(SearchQueryPlanner.plan("ab").mode()).isEqualTo(SearchMode.LIKE);
    }

    @Test
    void englishPhraseUsesQuotedBooleanQuery() {
      SearchQuery query = SearchQueryPlanner.plan("login timeout");
      assertThat(query.mode()).isEqualTo(SearchMode.FULLTEXT);
      assertThat(query.booleanQuery()).isEqualTo("\"login timeout\"");
    }

    @Test
    @DisplayName("counts code points, not UTF-16 units (emoji are single chars)")
    void emojiCountedAsCodePoints() {
      assertThat(SearchQueryPlanner.plan("🔐🔐").mode()).isEqualTo(SearchMode.LIKE);
      assertThat(SearchQueryPlanner.plan("🔐🔐🔐").mode()).isEqualTo(SearchMode.FULLTEXT);
    }

    @Test
    void mixedCjkAndAsciiOfThreeCodePointsUsesFulltext() {
      assertThat(SearchQueryPlanner.plan("v2版").mode()).isEqualTo(SearchMode.FULLTEXT);
    }
  }

  @Nested
  @DisplayName("LIKE escaping")
  class LikeEscaping {

    @Test
    void escapesPercentAndUnderscore() {
      SearchQuery query = SearchQueryPlanner.plan("%_");
      assertThat(query.mode()).isEqualTo(SearchMode.LIKE);
      assertThat(query.likePattern()).isEqualTo("%\\%\\_%");
    }

    @Test
    void escapesBackslash() {
      SearchQuery query = SearchQueryPlanner.plan("a\\");
      assertThat(query.mode()).isEqualTo(SearchMode.LIKE);
      assertThat(query.likePattern()).isEqualTo("%a\\\\%");
    }
  }

  @Nested
  @DisplayName("FULLTEXT escaping")
  class FulltextEscaping {

    @Test
    void escapesDoubleQuotesSoThePhraseStaysIntact() {
      SearchQuery query = SearchQueryPlanner.plan("he said \"hi\"");
      assertThat(query.mode()).isEqualTo(SearchMode.FULLTEXT);
      // FTS5 的字符串字面量里，双引号用两个双引号转义（反斜杠不是转义符）
      assertThat(query.booleanQuery()).isEqualTo("\"he said \"\"hi\"\"\"");
    }

    @Test
    void keepsBackslashAsIs() {
      SearchQuery query = SearchQueryPlanner.plan("c:\\temp");
      // FTS5 中反斜杠没有转义含义，原样传入
      assertThat(query.booleanQuery()).isEqualTo("\"c:\\temp\"");
    }
  }
}

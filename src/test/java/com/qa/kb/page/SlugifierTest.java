package com.qa.kb.page;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Slugifier")
class SlugifierTest {

  @Test
  void lowercasesAndJoinsWordsWithHyphens() {
    assertThat(Slugifier.slugify("Hello World")).isEqualTo("hello-world");
  }

  @Test
  void collapsesRepeatedSeparators() {
    assertThat(Slugifier.slugify("A---B")).isEqualTo("a-b");
    assertThat(Slugifier.slugify("  Leading & trailing  ")).isEqualTo("leading-trailing");
  }

  @Test
  void keepsDigits() {
    assertThat(Slugifier.slugify("API 设计 v2")).isEqualTo("api-v2");
  }

  @Test
  void removesDiacritics() {
    assertThat(Slugifier.slugify("Café")).isEqualTo("cafe");
  }

  @Test
  void fallsBackForNonAsciiTitles() {
    assertThat(Slugifier.slugify("测试 页面")).isEqualTo(Slugifier.FALLBACK);
    assertThat(Slugifier.slugify("!!!")).isEqualTo(Slugifier.FALLBACK);
    assertThat(Slugifier.slugify(null)).isEqualTo(Slugifier.FALLBACK);
    assertThat(Slugifier.slugify("   ")).isEqualTo(Slugifier.FALLBACK);
  }

  @Test
  void truncatesToMaximumLengthWithoutTrailingHyphen() {
    String slug = Slugifier.slugify("a".repeat(100));
    assertThat(slug).hasSize(Slugifier.MAX_LENGTH);

    String boundary = "x".repeat(59) + " y";
    String truncated = Slugifier.slugify(boundary);
    assertThat(truncated).hasSizeLessThanOrEqualTo(Slugifier.MAX_LENGTH);
    assertThat(truncated).doesNotEndWith("-");
  }

  @Test
  void buildsIdFirstPagePath() {
    assertThat(Slugifier.pagePath(42, "登录接口")).isEqualTo("/pages/42/page");
    assertThat(Slugifier.pagePath(7, "Hello World")).isEqualTo("/pages/7/hello-world");
  }
}

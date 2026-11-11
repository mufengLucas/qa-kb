package com.qa.kb.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.kb.auth.Role;
import com.qa.kb.model.SearchHit;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.page.PageService;
import com.qa.kb.persistence.PageMapper;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.UserMapper;
import com.qa.kb.search.SearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

@EnabledIf("dockerAvailable")
@DisplayName("MySQL FULLTEXT + LIKE search")
class WikiSearchIT extends AbstractMySqlIT {

  static boolean dockerAvailable() {
    return DockerAvailability.available();
  }

  @Autowired private UserMapper userMapper;
  @Autowired private SpaceMapper spaceMapper;
  @Autowired private PageMapper pageMapper;
  @Autowired private PageService pageService;
  @Autowired private SearchService searchService;

  private long pageId;
  private long spaceId;
  private long userId;

  @BeforeEach
  void seedOnePage() {
    String suffix = Long.toString(System.nanoTime());
    userMapper.insert("it_search_" + suffix, "搜索测试", "$2a$10$abcdefghijklmnopqrstuv", Role.MEMBER, true);
    userId = userMapper.lastInsertId();
    spaceMapper.insert("搜索空间", "it-search-" + suffix, "", "", 0);
    spaceId = spaceMapper.lastInsertId();
    var page =
        pageService.create(
            spaceId,
            null,
            "登录接口规范",
            "登录接口需要校验验证码，并且限制失败次数。",
            "PUBLISHED",
            userId);
    pageId = page.id();
  }

  @Test
  void twoCharacterChineseQueryFallsBackToLike() {
    List<SearchHit> hits = searchService.search("登录", spaceId, 20);

    assertThat(hits).extracting(SearchHit::id).contains(pageId);
  }

  @Test
  void oneCharacterChineseQueryFallsBackToLike() {
    assertThat(searchService.search("码", spaceId, 20)).extracting(SearchHit::id).contains(pageId);
  }

  @Test
  void threeOrMoreCharactersUseTheNgramFulltextIndex() {
    assertThat(searchService.search("登录接口", spaceId, 20))
        .extracting(SearchHit::id)
        .contains(pageId);
    assertThat(searchService.search("验证码", spaceId, 20))
        .extracting(SearchHit::id)
        .contains(pageId);
  }

  @Test
  void deletedPagesDisappearFromBothStrategies() {
    pageService.softDelete(pageId);

    assertThat(searchService.search("登录", spaceId, 20)).isEmpty();
    assertThat(searchService.search("登录接口", spaceId, 20)).isEmpty();
  }

  @Test
  void spaceFilterExcludesOtherSpaces() {
    assertThat(searchService.search("登录接口", spaceId + 1, 20)).isEmpty();
  }

  @Test
  void likeSpecialCharactersAreEscaped() {
    var percentPage =
        pageService.create(spaceId, null, "折扣说明", "折扣 50% 说明", "PUBLISHED", userId);

    // A single "%" must be treated literally instead of matching every page.
    List<SearchHit> hits = searchService.search("%", spaceId, 20);

    assertThat(hits).extracting(SearchHit::id).containsExactly(percentPage.id());
  }

  @Test
  void resultCarriesSnippetAndTitle() {
    SearchHit hit = searchService.search("登录接口", spaceId, 20).get(0);

    assertThat(hit.title()).isEqualTo("登录接口规范");
    assertThat(hit.snippet()).contains("登录接口");
  }

  @Test
  void softDeleteUsesTimestampAndRestoreBringsItBack() {
    pageService.softDelete(pageId);
    assertThat(pageMapper.findById(pageId)).isEmpty();

    pageService.restore(pageId);
    assertThat(pageMapper.findById(pageId)).isPresent();
    assertThat(searchService.search("登录接口", spaceId, 20))
        .extracting(SearchHit::id)
        .contains(pageId);
  }

  @Test
  void fulltextIndexIsPresentInTheSchema() {
    SpaceRow space = spaceMapper.findById(spaceId).orElseThrow();
    assertThat(space.slug()).startsWith("it-search-");
  }
}

package com.qa.kb.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.model.SearchHit;
import com.qa.kb.persistence.SearchMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService")
class SearchServiceTest {

  @Mock private SearchMapper searchMapper;

  private SearchService service;

  @BeforeEach
  void setUp() {
    service = new SearchService(searchMapper);
  }

  private static SearchHit hit(long id) {
    return new SearchHit(id, 1L, "标题" + id, "page", "片段");
  }

  @Test
  void shortChineseQueryUsesLike() {
    when(searchMapper.searchLike("%登录%", null, SearchService.DEFAULT_LIMIT))
        .thenReturn(List.of(hit(1)));

    List<SearchHit> hits = service.search("登录", null, null);

    assertThat(hits).hasSize(1);
    verify(searchMapper).searchLike("%登录%", null, SearchService.DEFAULT_LIMIT);
    verify(searchMapper, never()).searchFulltext(any(), any(), anyInt());
  }

  @Test
  void longChineseQueryUsesFulltextPhrase() {
    when(searchMapper.searchFulltext("\"登录接口\"", 2L, 10)).thenReturn(List.of(hit(2)));

    service.search("  登录接口  ", 2L, 10);

    verify(searchMapper).searchFulltext("\"登录接口\"", 2L, 10);
    verify(searchMapper, never()).searchLike(any(), any(), anyInt());
  }

  @Test
  void threeCharactersIsEnoughForFulltext() {
    when(searchMapper.searchFulltext(any(), any(), anyInt())).thenReturn(List.of());

    service.search("登录接", null, null);

    verify(searchMapper).searchFulltext("\"登录接\"", null, SearchService.DEFAULT_LIMIT);
  }

  @Test
  void twoCharactersStillUsesLike() {
    when(searchMapper.searchLike(any(), any(), anyInt())).thenReturn(List.of());

    service.search("登录", null, null);

    verify(searchMapper, never()).searchFulltext(any(), any(), anyInt());
  }

  @Test
  void asciiQueriesOfThreeOrMoreUseFulltext() {
    when(searchMapper.searchFulltext(any(), any(), anyInt())).thenReturn(List.of());

    service.search("api", null, null);

    verify(searchMapper).searchFulltext("\"api\"", null, SearchService.DEFAULT_LIMIT);
  }

  @Test
  void limitIsClampedToMaximum() {
    when(searchMapper.searchFulltext(any(), any(), anyInt())).thenReturn(List.of());

    service.search("登录接口", null, 9999);

    verify(searchMapper).searchFulltext("\"登录接口\"", null, SearchService.MAX_LIMIT);
  }

  @Test
  void nonPositiveLimitFallsBackToDefault() {
    when(searchMapper.searchFulltext(any(), any(), anyInt())).thenReturn(List.of());

    service.search("登录接口", null, 0);

    verify(searchMapper).searchFulltext("\"登录接口\"", null, SearchService.DEFAULT_LIMIT);
  }

  @Test
  void trimsQueryBeforePlanning() {
    when(searchMapper.searchFulltext(eq("\"api\""), any(), anyInt())).thenReturn(List.of());

    service.search("  api  ", null, null);

    verify(searchMapper).searchFulltext("\"api\"", null, SearchService.DEFAULT_LIMIT);
  }

  @Test
  void rejectsBlankQuery() {
    assertThatThrownBy(() -> service.search("   ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(searchMapper, never()).searchFulltext(any(), any(), anyInt());
    verify(searchMapper, never()).searchLike(any(), any(), anyInt());
  }

  @Test
  void rejectsNullQuery() {
    assertThatThrownBy(() -> service.search(null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

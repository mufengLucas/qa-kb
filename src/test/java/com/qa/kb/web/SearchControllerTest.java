package com.qa.kb.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.common.GlobalExceptionHandler;
import com.qa.kb.model.SearchHit;
import com.qa.kb.search.SearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchController")
class SearchControllerTest {

  @Mock private SearchService searchService;
  @Mock private com.qa.kb.space.SpaceAccessService access;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new SearchController(searchService, access))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void searchPassesQuerySpaceAndLimit() throws Exception {
    when(searchService.search("登录", 2L, 10))
        .thenReturn(List.of(new SearchHit(1L, 2L, "登录接口", "page", "片段")));

    mvc.perform(
            get("/api/search").param("q", "登录").param("spaceId", "2").param("limit", "10")
                .principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("登录接口"));
  }

  @Test
  void missingQueryIsAClientError() throws Exception {
    mvc.perform(get("/api/search").principal(TestAuth.VIEWER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("missing_parameter"));
  }

  @Test
  void nonNumericSpaceIdIsAClientError() throws Exception {
    mvc.perform(
            get("/api/search").param("q", "登录").param("spaceId", "abc")
                .principal(TestAuth.VIEWER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_parameter"));
  }

  @Test
  void blankQueryIsRejectedByTheServiceRule() throws Exception {
    when(searchService.search("  ", null, null))
        .thenThrow(new IllegalArgumentException("query is required"));

    mvc.perform(get("/api/search").param("q", "  ").principal(TestAuth.VIEWER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"));

    verify(searchService).search("  ", null, null);
  }
}

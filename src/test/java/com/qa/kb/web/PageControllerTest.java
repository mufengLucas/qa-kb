package com.qa.kb.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.common.ApiException;
import com.qa.kb.common.GlobalExceptionHandler;
import com.qa.kb.model.PageRow;
import com.qa.kb.model.PageSummary;
import com.qa.kb.model.RevisionRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.model.TagCount;
import com.qa.kb.page.PageService;
import com.qa.kb.page.PageTreeBuilder;
import com.qa.kb.space.SpaceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("PageController")
class PageControllerTest {

  @Mock private SpaceService spaceService;
  @Mock private PageService pageService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new PageController(spaceService, pageService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static PageRow page(long id, String title) {
    return new PageRow(
        id, 1L, null, title, "page", "内容", "PUBLISHED", 2L, 0, LocalDateTime.now(),
        LocalDateTime.now(), null);
  }

  private static SpaceRow space(long id) {
    return new SpaceRow(id, "空间", "qa", "", "", 0);
  }

  @Test
  void treeReturnsNestedNodes() throws Exception {
    when(spaceService.get(1L)).thenReturn(space(1));
    when(pageService.tree(1L))
        .thenReturn(
            List.of(new PageTreeBuilder.PageNode(10L, null, "根", "page", 0, List.of())));

    mvc.perform(get("/api/spaces/1/pages/tree").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("根"))
        .andExpect(jsonPath("$[0].children").isArray());
  }

  @Test
  void treeRejectsUnknownSpace() throws Exception {
    when(spaceService.get(9L)).thenThrow(ApiException.notFound("空间不存在"));

    mvc.perform(get("/api/spaces/9/pages/tree").principal(TestAuth.MEMBER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }

  @Test
  void createPageUsesThePrincipalAsAuthor() throws Exception {
    when(pageService.create(1L, null, "登录接口", "内容", null, 2L))
        .thenReturn(page(10, "登录接口"));

    mvc.perform(
            post("/api/spaces/1/pages")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"登录接口\",\"contentMd\":\"内容\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(10))
        .andExpect(jsonPath("$.title").value("登录接口"));

    verify(pageService).create(1L, null, "登录接口", "内容", null, 2L);
  }

  @Test
  void createPageRejectsBlankTitle() throws Exception {
    mvc.perform(
            post("/api/spaces/1/pages")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));

    verify(pageService, never())
        .create(anyLong(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void createPageRejectsAnonymousCaller() throws Exception {
    mvc.perform(
            post("/api/spaces/1/pages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"标题\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void updatePageDelegatesWithEditorId() throws Exception {
    when(pageService.update(10L, "新标题", "新内容", null, 2L)).thenReturn(page(10, "新标题"));

    mvc.perform(
            put("/api/pages/10")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新标题\",\"contentMd\":\"新内容\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("新标题"));
  }

  @Test
  void deletePageReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/pages/10").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(pageService).softDelete(10L);
  }

  @Test
  void rollbackUsesThePrincipal() throws Exception {
    when(pageService.rollback(10L, 3L, 2L)).thenReturn(page(10, "旧"));

    mvc.perform(post("/api/pages/10/rollback/3").principal(TestAuth.MEMBER))
        .andExpect(status().isOk());

    verify(pageService).rollback(10L, 3L, 2L);
  }

  @Test
  void revisionsReturnTheHistory() throws Exception {
    when(pageService.revisions(10L))
        .thenReturn(
            List.of(new RevisionRow(3L, 10L, "标题", "内容", 2L, LocalDateTime.now())));

    mvc.perform(get("/api/pages/10/revisions").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("标题"));
  }

  @Test
  void setTagsDelegatesAndReturnsNoContent() throws Exception {
    mvc.perform(
            put("/api/pages/10/tags")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"api\",\"测试\"]}"))
        .andExpect(status().isNoContent());

    verify(pageService).setTags(10L, List.of("api", "测试"));
  }

  @Test
  void tagsOfChecksThePageExists() throws Exception {
    when(pageService.get(10L)).thenReturn(page(10, "标题"));
    when(pageService.tagsOf(10L)).thenReturn(List.of(new TagCount("api", 1L)));

    mvc.perform(get("/api/pages/10/tags").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("api"));
  }

  @Test
  void trashListsDeletedPages() throws Exception {
    when(pageService.trash()).thenReturn(List.of(page(11, "已删除")));

    mvc.perform(get("/api/pages/trash").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("已删除"));
  }

  @Test
  void createSpaceReturnsCreated() throws Exception {
    when(spaceService.create("QA", "qa", null, null, 0)).thenReturn(space(1));

    mvc.perform(
            post("/api/spaces")
                .principal(TestAuth.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"QA\",\"slug\":\"qa\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.slug").value("qa"));
  }

  @Test
  void deleteSpaceReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/spaces/1").principal(TestAuth.ADMIN))
        .andExpect(status().isNoContent());

    verify(spaceService).delete(1L);
  }

  @Test
  void deleteUnknownSpaceReturns404() throws Exception {
    doThrow(ApiException.notFound("空间不存在")).when(spaceService).delete(9L);

    mvc.perform(delete("/api/spaces/9").principal(TestAuth.ADMIN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }
}

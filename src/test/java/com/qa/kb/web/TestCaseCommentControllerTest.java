package com.qa.kb.web;

import static org.mockito.Mockito.doThrow;
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
import com.qa.kb.model.TestCaseCommentRow;
import com.qa.kb.testcase.TestCaseCommentService;
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
@DisplayName("TestCaseCommentController")
class TestCaseCommentControllerTest {

  @Mock private TestCaseCommentService commentService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new TestCaseCommentController(commentService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static TestCaseCommentRow comment(long id, long caseId, long authorId) {
    return new TestCaseCommentRow(
        id,
        caseId,
        authorId,
        "alice",
        "小艾",
        "这条用例缺少边界值",
        LocalDateTime.of(2026, 11, 19, 10, 0),
        null);
  }

  @Test
  void listReturnsComments() throws Exception {
    when(commentService.list(5L))
        .thenReturn(List.of(comment(1L, 5L, 2L), comment(2L, 5L, 3L)));

    mvc.perform(get("/api/test-cases/5/comments").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].body").value("这条用例缺少边界值"))
        .andExpect(jsonPath("$[0].authorLogin").value("alice"))
        .andExpect(jsonPath("$[1].id").value(2));
  }

  @Test
  void addUsesPrincipalAsAuthor() throws Exception {
    when(commentService.add(5L, 2L, "补充边界值"))
        .thenReturn(comment(3L, 5L, 2L));

    mvc.perform(
            post("/api/test-cases/5/comments")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"补充边界值\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(3))
        .andExpect(jsonPath("$.authorId").value(2));

    verify(commentService).add(5L, 2L, "补充边界值");
  }

  @Test
  void addRejectsBlankBody() throws Exception {
    mvc.perform(
            post("/api/test-cases/5/comments")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateReturnsComment() throws Exception {
    when(commentService.update(3L, 2L, "修改后的评论"))
        .thenReturn(comment(3L, 5L, 2L));

    mvc.perform(
            put("/api/test-cases/5/comments/3")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"修改后的评论\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(3));

    verify(commentService).update(3L, 2L, "修改后的评论");
  }

  @Test
  void updateRejectsBlankBody() throws Exception {
    mvc.perform(
            put("/api/test-cases/5/comments/3")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/test-cases/5/comments/3").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(commentService).delete(3L, 2L, false);
  }

  @Test
  void adminCanDeleteAnyComment() throws Exception {
    mvc.perform(delete("/api/test-cases/5/comments/3").principal(TestAuth.ADMIN))
        .andExpect(status().isNoContent());

    verify(commentService).delete(3L, 1L, true);
  }

  @Test
  void unknownCommentReturnsNotFound() throws Exception {
    doThrow(ApiException.notFound("评论不存在"))
        .when(commentService)
        .delete(404L, 2L, false);

    mvc.perform(delete("/api/test-cases/5/comments/404").principal(TestAuth.MEMBER))
        .andExpect(status().isNotFound());
  }
}

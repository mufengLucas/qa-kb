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
import com.qa.kb.model.TestCaseRunRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.testcase.TestCaseService;
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
@DisplayName("TestCaseController")
class TestCaseControllerTest {

  @Mock private TestCaseService testCaseService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new TestCaseController(testCaseService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static TestCaseView view(long id) {
    LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
    return new TestCaseView(
        id, "登录", "正常登录", "已有账号", "1. 打开登录页", "进入首页", "P1", "ACTIVE", 1L,
        "admin", "管理员", at, at, 2, 2, 0, "PASS", at);
  }

  private static TestCaseRunRow run(long id, long caseId) {
    return new TestCaseRunRow(
        id, caseId, "PASS", "冒烟通过", 2L, "alice", "小艾",
        LocalDateTime.of(2026, 11, 12, 11, 0));
  }

  @Test
  void listReturnsPageWithModules() throws Exception {
    when(testCaseService.list("登录", null, null, null, null))
        .thenReturn(
            new TestCaseService.CasePage(1, List.of(view(1L)), List.of("登录", "支付")));

    mvc.perform(get("/api/test-cases").param("module", "登录").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].title").value("正常登录"))
        .andExpect(jsonPath("$.modules[1]").value("支付"))
        .andExpect(jsonPath("$.items[0].runCount").value(2));
  }

  @Test
  void createUsesPrincipalAsCreator() throws Exception {
    when(testCaseService.create(
            "登录", "正常登录", "已有账号", "1. 打开登录页", "进入首页", "P1", null, 2L))
        .thenReturn(view(9L));

    mvc.perform(
            post("/api/test-cases")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"module\":\"登录\",\"title\":\"正常登录\",\"precondition\":\"已有账号\","
                        + "\"steps\":\"1. 打开登录页\",\"expected\":\"进入首页\",\"priority\":\"P1\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(9))
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    verify(testCaseService)
        .create("登录", "正常登录", "已有账号", "1. 打开登录页", "进入首页", "P1", null, 2L);
  }

  @Test
  void createRejectsBlankTitle() throws Exception {
    mvc.perform(
            post("/api/test-cases")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"module\":\"登录\",\"title\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void getReturnsCase() throws Exception {
    when(testCaseService.get(5L)).thenReturn(view(5L));

    mvc.perform(get("/api/test-cases/5").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.module").value("登录"));
  }

  @Test
  void updateReturnsCase() throws Exception {
    when(testCaseService.update(5L, "支付", "改名", null, null, null, null, null))
        .thenReturn(view(5L));

    mvc.perform(
            put("/api/test-cases/5")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"module\":\"支付\",\"title\":\"改名\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));

    verify(testCaseService).update(5L, "支付", "改名", null, null, null, null, null);
  }

  @Test
  void deleteReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/test-cases/1").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(testCaseService).delete(1L);
  }

  @Test
  void deleteUnknownCaseReturnsNotFound() throws Exception {
    doThrow(ApiException.notFound("用例不存在")).when(testCaseService).delete(99L);

    mvc.perform(delete("/api/test-cases/99").principal(TestAuth.MEMBER))
        .andExpect(status().isNotFound());
  }

  @Test
  void runsAreListedAndRecorded() throws Exception {
    when(testCaseService.listRuns(5L)).thenReturn(List.of(run(1L, 5L), run(2L, 5L)));
    when(testCaseService.addRun(5L, "fail", "环境不可用", 2L)).thenReturn(run(3L, 5L));

    mvc.perform(get("/api/test-cases/5/runs").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].result").value("PASS"));

    mvc.perform(
            post("/api/test-cases/5/runs")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"fail\",\"note\":\"环境不可用\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(3));

    verify(testCaseService).addRun(5L, "fail", "环境不可用", 2L);
  }

  @Test
  void recordRunRejectsBlankResult() throws Exception {
    mvc.perform(
            post("/api/test-cases/5/runs")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void deleteRunReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/test-cases/5/runs/3").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(testCaseService).deleteRun(5L, 3L);
  }

  @Test
  void unknownRunReturnsNotFound() throws Exception {
    doThrow(ApiException.notFound("执行记录不存在"))
        .when(testCaseService)
        .deleteRun(5L, 404L);

    mvc.perform(delete("/api/test-cases/5/runs/404").principal(TestAuth.MEMBER))
        .andExpect(status().isNotFound());
  }
}

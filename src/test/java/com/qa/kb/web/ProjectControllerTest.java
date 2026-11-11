package com.qa.kb.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.common.ApiException;
import com.qa.kb.common.GlobalExceptionHandler;
import com.qa.kb.model.MilestoneRow;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.model.ProjectWithProgress;
import com.qa.kb.model.TaskRow;
import com.qa.kb.project.Progress;
import com.qa.kb.project.ProjectService;
import java.time.LocalDate;
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
@DisplayName("ProjectController")
class ProjectControllerTest {

  @Mock private ProjectService projectService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static ProjectWithProgress project() {
    return new ProjectWithProgress(
        1L, "回归测试", "QA", 1L, "ACTIVE", null, null, "", 4L, 1L);
  }

  @Test
  void listReturnsProgress() throws Exception {
    when(projectService.list())
        .thenReturn(List.of(new ProjectService.ProjectSummary(project(), new Progress(1, 4, 25))));

    mvc.perform(get("/api/projects").principal(TestAuth.VIEWER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].project.name").value("回归测试"))
        .andExpect(jsonPath("$[0].progress.percent").value(25));
  }

  @Test
  void detailReturnsMilestonesAndTasks() throws Exception {
    when(projectService.detail(1L))
        .thenReturn(
            new ProjectService.ProjectDetail(project(), new Progress(1, 4, 25), List.of(), List.of()));

    mvc.perform(get("/api/projects/1").principal(TestAuth.MEMBER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.progress.done").value(1));
  }

  @Test
  void createUsesPrincipalAsOwner() throws Exception {
    when(projectService.create(
            "回归测试", "QA", 2L, null, LocalDate.of(2026, 11, 1), null, "说明"))
        .thenReturn(
            new ProjectRow(
                1L, "回归测试", "QA", 2L, "ACTIVE", LocalDate.of(2026, 11, 1), null, "说明"));

    mvc.perform(
            post("/api/projects")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"回归测试\",\"projectKey\":\"QA\","
                        + "\"startDate\":\"2026-11-01\",\"description\":\"说明\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.projectKey").value("QA"));
  }

  @Test
  void createRejectsBlankName() throws Exception {
    mvc.perform(
            post("/api/projects")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"projectKey\":\"QA\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void updateTaskStatusDelegates() throws Exception {
    when(projectService.updateTaskStatus(10L, "done"))
        .thenReturn(
            new ProjectService.TaskView(
                new TaskRow(10L, 1L, null, "任务", 2L, "done", 2, null, null, 100,
                    LocalDateTime.now()),
                false));

    mvc.perform(
            patch("/api/tasks/10/status")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"done\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.status").value("done"))
        .andExpect(jsonPath("$.overdue").value(false));

    verify(projectService).updateTaskStatus(10L, "done");
  }

  @Test
  void updateTaskStatusRejectsBlankStatus() throws Exception {
    mvc.perform(
            patch("/api/tasks/10/status")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/projects/1").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(projectService).delete(1L);
  }

  @Test
  void deleteUnknownProjectReturnsNotFound() throws Exception {
    doThrow(ApiException.notFound("项目不存在")).when(projectService).delete(99L);

    mvc.perform(delete("/api/projects/99").principal(TestAuth.MEMBER))
        .andExpect(status().isNotFound());
  }

  @Test
  void restoreReturnsNoContent() throws Exception {
    mvc.perform(post("/api/projects/1/restore").principal(TestAuth.MEMBER))
        .andExpect(status().isNoContent());

    verify(projectService).restore(1L);
  }

  @Test
  void createTaskReturns201WithTaskView() throws Exception {
    when(projectService.createTask(1L, "新任务", null, null, null, null, null, null))
        .thenReturn(
            new ProjectService.TaskView(
                new TaskRow(
                    10L, 1L, null, "新任务", null, "todo", 2, null, null, 0,
                    LocalDateTime.now()),
                false));

    mvc.perform(
            post("/api/projects/1/tasks")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新任务\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.task.title").value("新任务"))
        .andExpect(jsonPath("$.overdue").value(false));

    verify(projectService).createTask(1L, "新任务", null, null, null, null, null, null);
  }

  @Test
  void createTaskRejectsBlankTitle() throws Exception {
    mvc.perform(
            post("/api/projects/1/tasks")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void createMilestoneReturns201() throws Exception {
    when(projectService.createMilestone(1L, "发布", null, null))
        .thenReturn(new MilestoneRow(55L, 1L, "发布", null, "OPEN"));

    mvc.perform(
            post("/api/projects/1/milestones")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"发布\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("发布"))
        .andExpect(jsonPath("$.status").value("OPEN"));

    verify(projectService).createMilestone(1L, "发布", null, null);
  }

  @Test
  void createMilestoneRejectsBlankName() throws Exception {
    mvc.perform(
            post("/api/projects/1/milestones")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }
}

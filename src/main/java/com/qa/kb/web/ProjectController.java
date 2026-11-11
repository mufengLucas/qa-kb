package com.qa.kb.web;

import com.qa.kb.model.MilestoneRow;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.project.ProjectService;
import com.qa.kb.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProjectController {

  public record ProjectRequest(
      @NotBlank @Size(max = 150) String name,
      @NotBlank @Size(max = 20) String projectKey,
      String status,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      String description) {}

  public record TaskStatusRequest(@NotBlank String status) {}

  public record CreateTaskRequest(
      @NotBlank @Size(max = 200) String title,
      Long milestoneId,
      Long assigneeId,
      String status,
      Integer priority,
      LocalDate startDate,
      LocalDate dueDate) {}

  public record CreateMilestoneRequest(
      @NotBlank @Size(max = 150) String name, LocalDate dueDate, String status) {}

  private final ProjectService projectService;

  public ProjectController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @GetMapping("/projects")
  public List<ProjectService.ProjectSummary> projects() {
    return projectService.list();
  }

  @GetMapping("/projects/{id}")
  public ProjectService.ProjectDetail project(@PathVariable long id) {
    return projectService.detail(id);
  }

  @PostMapping("/projects")
  public ResponseEntity<ProjectRow> createProject(
      @Valid @RequestBody ProjectRequest body, Authentication authentication) {
    ProjectRow created =
        projectService.create(
            body.name(),
            body.projectKey(),
            CurrentUser.require(authentication).id(),
            body.status(),
            body.startDate(),
            body.endDate(),
            body.description());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  /** 软删除（归档）：与空间/页面删除同一权限模型——登录且 ADMIN/MEMBER 即可，不校验 owner。 */
  @DeleteMapping("/projects/{id}")
  public ResponseEntity<Void> deleteProject(@PathVariable long id) {
    projectService.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/projects/{id}/restore")
  public ResponseEntity<Void> restoreProject(@PathVariable long id) {
    projectService.restore(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/projects/{id}/tasks")
  public ResponseEntity<ProjectService.TaskView> createTask(
      @PathVariable long id, @Valid @RequestBody CreateTaskRequest body) {
    ProjectService.TaskView created =
        projectService.createTask(
            id,
            body.title(),
            body.milestoneId(),
            body.assigneeId(),
            body.status(),
            body.priority(),
            body.startDate(),
            body.dueDate());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PostMapping("/projects/{id}/milestones")
  public ResponseEntity<MilestoneRow> createMilestone(
      @PathVariable long id, @Valid @RequestBody CreateMilestoneRequest body) {
    MilestoneRow created =
        projectService.createMilestone(id, body.name(), body.dueDate(), body.status());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PatchMapping("/tasks/{taskId}/status")
  public ProjectService.TaskView updateTaskStatus(
      @PathVariable long taskId, @Valid @RequestBody TaskStatusRequest body) {
    return projectService.updateTaskStatus(taskId, body.status());
  }
}

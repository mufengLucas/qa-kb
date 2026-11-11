package com.qa.kb.persistence;

import com.qa.kb.model.MilestoneRow;
import com.qa.kb.model.ProjectRow;
import com.qa.kb.model.ProjectWithProgress;
import com.qa.kb.model.TaskRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper {

  String PROJECT_COLUMNS =
      "p.id, p.name, p.project_key, p.owner_id, p.status, p.start_date, p.end_date, p.description";

  @Select(
      "SELECT "
          + PROJECT_COLUMNS
          + ", COUNT(t.id) AS total_tasks, "
          + "COALESCE(SUM(CASE WHEN t.status = 'done' THEN 1 ELSE 0 END), 0) AS done_tasks "
          + "FROM projects p LEFT JOIN tasks t ON t.project_id = p.id "
          + "WHERE p.deleted_at IS NULL "
          + "GROUP BY p.id ORDER BY p.status, p.id DESC")
  List<ProjectWithProgress> listWithProgress();

  @Select("SELECT " + PROJECT_COLUMNS + " FROM projects p WHERE p.id = #{id} AND p.deleted_at IS NULL")
  Optional<ProjectRow> findById(@Param("id") long id);

  @Select(
      "SELECT "
          + PROJECT_COLUMNS
          + ", COUNT(t.id) AS total_tasks, "
          + "COALESCE(SUM(CASE WHEN t.status = 'done' THEN 1 ELSE 0 END), 0) AS done_tasks "
          + "FROM projects p LEFT JOIN tasks t ON t.project_id = p.id "
          + "WHERE p.id = #{id} AND p.deleted_at IS NULL GROUP BY p.id")
  Optional<ProjectWithProgress> progressOf(@Param("id") long id);

  @Insert(
      "INSERT INTO projects (name, project_key, owner_id, status, start_date, end_date, description) "
          + "VALUES (#{name}, #{projectKey}, #{ownerId}, #{status}, #{startDate}, #{endDate}, "
          + "#{description})")
  int insert(
      @Param("name") String name,
      @Param("projectKey") String projectKey,
      @Param("ownerId") long ownerId,
      @Param("status") String status,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate,
      @Param("description") String description);

  /** 自增主键回填；SQL 按方言在 {@code mapper/ProjectMapper.xml} 中选择。 */
  long lastInsertId();

  @Select(
      "SELECT id, project_id, name, due_date, status FROM milestones "
          + "WHERE project_id = #{projectId} ORDER BY due_date, id")
  List<MilestoneRow> listMilestones(@Param("projectId") long projectId);

  @Select(
      "SELECT id, project_id, milestone_id, title, assignee_id, status, priority, "
          + "start_date, due_date, progress, updated_at FROM tasks "
          + "WHERE project_id = #{projectId} ORDER BY status, priority, due_date, id")
  List<TaskRow> listTasks(@Param("projectId") long projectId);

  /** 仅当任务所属项目未被软删除时可见，防止对已删除项目里的任务改状态。 */
  @Select(
      "SELECT id, project_id, milestone_id, title, assignee_id, status, priority, "
          + "start_date, due_date, progress, updated_at FROM tasks "
          + "WHERE id = #{id} "
          + "AND EXISTS (SELECT 1 FROM projects p "
          + "WHERE p.id = tasks.project_id AND p.deleted_at IS NULL)")
  Optional<TaskRow> findTask(@Param("id") long id);

  @Update("UPDATE tasks SET status = #{status}, progress = #{progress} WHERE id = #{id}")
  int updateTask(
      @Param("id") long id, @Param("status") String status, @Param("progress") int progress);

  @Insert(
      "INSERT INTO milestones (project_id, name, due_date, status) "
          + "VALUES (#{projectId}, #{name}, #{dueDate}, #{status})")
  int insertMilestone(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("dueDate") LocalDate dueDate,
      @Param("status") String status);

  @Insert(
      "INSERT INTO tasks (project_id, milestone_id, title, assignee_id, status, priority, "
          + "start_date, due_date, progress) "
          + "VALUES (#{projectId}, #{milestoneId}, #{title}, #{assigneeId}, #{status}, "
          + "#{priority}, #{startDate}, #{dueDate}, #{progress})")
  int insertTask(
      @Param("projectId") long projectId,
      @Param("milestoneId") Long milestoneId,
      @Param("title") String title,
      @Param("assigneeId") Long assigneeId,
      @Param("status") String status,
      @Param("priority") int priority,
      @Param("startDate") LocalDate startDate,
      @Param("dueDate") LocalDate dueDate,
      @Param("progress") int progress);

  /**
   * 软删除：打 deleted_at 标记（里程碑、任务、日报关联全部保留）。
   *
   * <p>同时把 {@code project_key} 改写为 {@code 原key#id} 释放唯一索引——项目没有改名接口，
   * 不释放的话删除后同标识的新项目永远409；key 规范是 {@code [A-Z][A-Z0-9]{1,9}}，不含 {@code #}，
   * 恢复时可无损还原。
   */
  @Update(
      "UPDATE projects SET deleted_at = #{now}, project_key = #{newKey} "
          + "WHERE id = #{id} AND deleted_at IS NULL")
  int softDelete(
      @Param("id") long id, @Param("newKey") String newKey, @Param("now") LocalDateTime now);

  /** 恢复软删除的项目：清 deleted_at 并剥掉 key 后缀还原原标识。 */
  @Update(
      "UPDATE projects SET deleted_at = NULL, project_key = #{newKey} "
          + "WHERE id = #{id} AND deleted_at IS NOT NULL")
  int restore(@Param("id") long id, @Param("newKey") String newKey);

  @Select(
      "SELECT "
          + PROJECT_COLUMNS
          + " FROM projects p WHERE p.id = #{id} AND p.deleted_at IS NOT NULL")
  Optional<ProjectRow> findDeletedById(@Param("id") long id);

  /** 里程碑归属校验：只认属于指定项目的里程碑，防止跨项目挂载脏数据。 */
  @Select(
      "SELECT id, project_id, name, due_date, status FROM milestones "
          + "WHERE id = #{milestoneId} AND project_id = #{projectId}")
  Optional<MilestoneRow> findMilestone(
      @Param("projectId") long projectId, @Param("milestoneId") long milestoneId);

  /** 负责人校验：必须是启用状态的用户（否则直插 assignee_id 外键会 500）。 */
  @Select("SELECT COUNT(*) FROM users WHERE id = #{id} AND active = 1")
  long countActiveUser(@Param("id") long id);
}

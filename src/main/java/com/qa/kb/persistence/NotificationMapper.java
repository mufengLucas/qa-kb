package com.qa.kb.persistence;

import com.qa.kb.model.NotificationRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 站内通知读写（P2 Phase 5）。
 *
 * <p>插入走 {@code INSERT ... SELECT ... WHERE NOT EXISTS}（与 {@link ReportMapper#insertIfAbsent}
 * 同款）：MySQL 8 可直接执行，重复扫描时靠 {@code uk_notifications_dedup} 兜底幂等。
 */
@Mapper
public interface NotificationMapper {

  /** 幂等插入：同一 (user_id, dedup_key) 只会有一行，重复调用返回 0。 */
  @Insert(
      "INSERT INTO notifications (user_id, type, title, body, ref_type, ref_id, dedup_key) "
          + "SELECT #{userId}, #{type}, #{title}, #{body}, #{refType}, #{refId}, #{dedupKey} "
          + "WHERE NOT EXISTS "
          + "(SELECT 1 FROM notifications WHERE user_id = #{userId} AND dedup_key = #{dedupKey})")
  int insertIfAbsent(
      @Param("userId") long userId,
      @Param("type") String type,
      @Param("title") String title,
      @Param("body") String body,
      @Param("refType") String refType,
      @Param("refId") long refId,
      @Param("dedupKey") String dedupKey);

  /** 当前用户的通知列表：未读在前、同状态按 id 倒序（最新的先看到）。 */
  @Select(
      "SELECT id, user_id, type, title, body, ref_type, ref_id, dedup_key, read_at, created_at "
          + "FROM notifications WHERE user_id = #{userId} "
          + "ORDER BY (read_at IS NULL) DESC, id DESC LIMIT #{limit}")
  List<NotificationRow> listByUser(@Param("userId") long userId, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM notifications WHERE user_id = #{userId} AND read_at IS NULL")
  long countUnread(@Param("userId") long userId);

  /** 标记单条已读：只动自己的、且只动未读的（重复标记不报错，返回 0）。 */
  @Update(
      "UPDATE notifications SET read_at = #{now} "
          + "WHERE id = #{id} AND user_id = #{userId} AND read_at IS NULL")
  int markRead(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

  @Update("UPDATE notifications SET read_at = #{now} WHERE user_id = #{userId} AND read_at IS NULL")
  int markAllRead(@Param("userId") long userId, @Param("now") LocalDateTime now);

  /** 通知是否存在且属于该用户（404 与 403 的区分依据）。 */
  @Select("SELECT COUNT(*) FROM notifications WHERE id = #{id} AND user_id = #{userId}")
  long countOwned(@Param("id") long id, @Param("userId") long userId);

  /* --------------------------------------------------------------- 每日扫描 */

  /**
   * 跨项目逾期任务（每日扫描数据源）：项目未删除、任务未完成、截止日早于今天。
   *
   * <p>状态与截止日的最终判定仍由 {@code ProgressCalculator.isOverdue} 在服务层执行，
   * 这里只做粗筛把候选行拉回来（含负责人，负责人为 null 时回落项目 owner）。
   */
  @Select(
      "SELECT t.id AS task_id, t.title, t.status, t.due_date, "
          + "t.assignee_id, p.owner_id, p.id AS project_id, p.name AS project_name "
          + "FROM tasks t JOIN projects p ON p.id = t.project_id "
          + "WHERE p.deleted_at IS NULL AND t.status <> 'done' "
          + "AND t.due_date IS NOT NULL AND t.due_date < #{today} "
          + "ORDER BY t.due_date, t.id")
  List<OverdueTask> listOverdueTasks(@Param("today") LocalDate today);

  /** 逾期扫描的一行候选：任务信息 + 潜在收件人（负责人 / 项目 owner）。 */
  record OverdueTask(
      long taskId,
      String title,
      String status,
      LocalDate dueDate,
      Long assigneeId,
      long ownerId,
      long projectId,
      String projectName) {}

  /** 全部启用成员 id（扫描时做收件人资格判定，避免给已停用成员发通知）。 */
  @Select("SELECT id FROM users WHERE active = 1")
  List<Long> listActiveUserIds();
}

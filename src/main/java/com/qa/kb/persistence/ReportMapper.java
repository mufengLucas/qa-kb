package com.qa.kb.persistence;

import com.qa.kb.model.ReportProjectLink;
import com.qa.kb.model.ReportRow;
import com.qa.kb.model.ReportWithUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportMapper {

  @Insert(
      "INSERT INTO daily_reports (user_id, report_date, done, plan, blockers, status) "
          + "VALUES (#{userId}, #{date}, #{done}, #{plan}, #{blockers}, #{status}) AS v "
          + "ON DUPLICATE KEY UPDATE done = v.done, plan = v.plan, blockers = v.blockers, "
          + "status = v.status, updated_at = CURRENT_TIMESTAMP(3)")
  int upsert(
      @Param("userId") long userId,
      @Param("date") LocalDate date,
      @Param("done") String done,
      @Param("plan") String plan,
      @Param("blockers") String blockers,
      @Param("status") String status);

  @Select(
      "SELECT id, user_id, report_date, `done`, `plan`, blockers, status, created_at, updated_at "
          + "FROM daily_reports WHERE user_id = #{userId} AND report_date = #{date}")
  Optional<ReportRow> findByUserAndDate(
      @Param("userId") long userId, @Param("date") LocalDate date);

  @Select(
      "SELECT id, user_id, report_date, `done`, `plan`, blockers, status, created_at, updated_at "
          + "FROM daily_reports WHERE user_id = #{userId} AND report_date BETWEEN #{from} AND #{to} "
          + "ORDER BY report_date")
  List<ReportRow> listByUserBetween(
      @Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  @Select(
      "SELECT r.id, r.user_id, u.name AS user_name, r.report_date, r.`done`, r.`plan`, "
          + "r.blockers, r.status, r.created_at, r.updated_at "
          + "FROM daily_reports r JOIN users u ON u.id = r.user_id "
          + "WHERE r.report_date = #{date} ORDER BY u.name")
  List<ReportWithUser> listByDate(@Param("date") LocalDate date);

  @Select(
      "SELECT r.id, r.user_id, u.name AS user_name, r.report_date, r.`done`, r.`plan`, "
          + "r.blockers, r.status, r.created_at, r.updated_at "
          + "FROM daily_reports r JOIN users u ON u.id = r.user_id "
          + "WHERE r.report_date BETWEEN #{from} AND #{to} "
          + "ORDER BY r.report_date DESC, u.name")
  List<ReportWithUser> listBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

  @Select(
      "SELECT u.id FROM users u WHERE u.active = 1 AND u.id NOT IN "
          + "(SELECT r.user_id FROM daily_reports r WHERE r.report_date = #{date}) ORDER BY u.id")
  List<Long> missingUserIds(@Param("date") LocalDate date);

  @Select(
      "SELECT rp.project_id FROM daily_report_projects rp "
          + "JOIN daily_reports r ON r.id = rp.report_id "
          + "WHERE r.user_id = #{userId} AND r.report_date = #{date}")
  List<Long> listProjectIds(@Param("userId") long userId, @Param("date") LocalDate date);

  @Select(
      "SELECT rp.project_id FROM daily_report_projects rp "
          + "JOIN daily_reports r ON r.id = rp.report_id "
          + "WHERE r.user_id = #{userId} AND r.report_date BETWEEN #{from} AND #{to}")
  List<Long> listProjectIdsBetween(
      @Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  @Select(
      "SELECT rp.report_id, rp.project_id FROM daily_report_projects rp "
          + "JOIN daily_reports r ON r.id = rp.report_id "
          + "WHERE r.user_id = #{userId} AND r.report_date BETWEEN #{from} AND #{to}")
  List<ReportProjectLink> listProjectLinksBetween(
      @Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  @Select(
      "SELECT id FROM daily_reports WHERE user_id = #{userId} AND report_date = #{date}")
  Optional<Long> findIdByUserAndDate(
      @Param("userId") long userId, @Param("date") LocalDate date);

  /**
   * 周报占位行：不存在时插入一份空草稿，已存在时什么都不做。
   *
   * <p>清单条目可以在正文保存之前先添加，此时需要一行 {@code daily_reports} 作为挂载点；
   * 用 upsert 会把已保存的正文清空，所以必须"仅在缺失时插入"。INSERT ... SELECT ... WHERE
   * NOT EXISTS 在 MySQL 8 与 SQLite 上都能执行。
   */
  @Insert(
      "INSERT INTO daily_reports (user_id, report_date, done, plan, blockers, status) "
          + "SELECT #{userId}, #{date}, '', '', '', 'draft' "
          + "WHERE NOT EXISTS "
          + "(SELECT 1 FROM daily_reports WHERE user_id = #{userId} AND report_date = #{date})")
  int insertIfAbsent(@Param("userId") long userId, @Param("date") LocalDate date);

  @Delete("DELETE FROM daily_report_projects WHERE report_id = #{reportId}")
  int deleteProjectLinks(@Param("reportId") long reportId);

  @Insert(
      "INSERT IGNORE INTO daily_report_projects (report_id, project_id) "
          + "VALUES (#{reportId}, #{projectId})")
  int insertProjectLink(@Param("reportId") long reportId, @Param("projectId") long projectId);
}

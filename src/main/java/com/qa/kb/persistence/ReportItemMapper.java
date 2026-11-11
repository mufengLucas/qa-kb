package com.qa.kb.persistence;

import com.qa.kb.model.ReportItemRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 周报清单条目（report_items）。SQL 为双方言通用写法，MySQL/SQLite 均可执行。 */
@Mapper
public interface ReportItemMapper {

  @Insert(
      "INSERT INTO report_items (report_id, title, `done`, task_id, project_id, sort_order) "
          + "VALUES (#{reportId}, #{title}, #{done}, #{taskId}, #{projectId}, #{sortOrder})")
  int insert(
      @Param("reportId") long reportId,
      @Param("title") String title,
      @Param("done") boolean done,
      @Param("taskId") Long taskId,
      @Param("projectId") Long projectId,
      @Param("sortOrder") int sortOrder);

  @Select(
      "SELECT id, report_id, title, `done`, task_id, project_id, sort_order, created_at, updated_at "
          + "FROM report_items WHERE report_id = #{reportId} ORDER BY sort_order, id")
  List<ReportItemRow> listByReport(@Param("reportId") long reportId);

  /** 归属校验：只回读属于该用户名下周报的条目（JOIN daily_reports.user_id）。 */
  @Select(
      "SELECT i.id, i.report_id, i.title, i.`done`, i.task_id, i.project_id, i.sort_order, "
          + "i.created_at, i.updated_at "
          + "FROM report_items i JOIN daily_reports r ON r.id = i.report_id "
          + "WHERE i.id = #{id} AND r.user_id = #{userId}")
  Optional<ReportItemRow> findByIdForUser(@Param("id") long id, @Param("userId") long userId);

  @Select("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM report_items WHERE report_id = #{reportId}")
  int nextSort(@Param("reportId") long reportId);

  /** updated_at 由 MySQL 的 ON UPDATE / SQLite 的 report_items_touch 触发器自动刷新。 */
  @Update("UPDATE report_items SET `done` = #{done} WHERE id = #{id}")
  int updateDone(@Param("id") long id, @Param("done") boolean done);

  @Update("UPDATE report_items SET title = #{title} WHERE id = #{id}")
  int updateTitle(@Param("id") long id, @Param("title") String title);

  /** 回填关联：条目从轻量待办升级为真实项目任务后写入 task_id / project_id。 */
  @Update("UPDATE report_items SET task_id = #{taskId}, project_id = #{projectId} WHERE id = #{id}")
  int updateLink(
      @Param("id") long id, @Param("taskId") long taskId, @Param("projectId") long projectId);

  @Delete("DELETE FROM report_items WHERE id = #{id}")
  int delete(@Param("id") long id);

  /** 自增主键回填：实现见 {@code mapper/ReportItemMapper.xml}（双方言 databaseId）。 */
  long lastInsertId();
}

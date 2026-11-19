package com.qa.kb.persistence;

import com.qa.kb.model.TestCaseCommentRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用例评论读写（A4）。
 *
 * <p>删除是软删除（{@code deleted_at}），@提及复用 {@code notifications} 表落站内信。
 */
@Mapper
public interface TestCaseCommentMapper {

  @Insert(
      "INSERT INTO test_case_comments (case_id, author_id, body) "
          + "VALUES (#{caseId}, #{authorId}, #{body})")
  int insert(@Param("caseId") long caseId, @Param("authorId") long authorId, @Param("body") String body);

  /** 某用例的评论列表（按 id 升序，即时间正序），带作者登录名与姓名。 */
  @Select(
      "SELECT c.id, c.case_id, c.author_id, u.login AS author_login, u.name AS author_name, "
          + "c.body, c.created_at, c.updated_at "
          + "FROM test_case_comments c LEFT JOIN users u ON u.id = c.author_id "
          + "WHERE c.case_id = #{caseId} AND c.deleted_at IS NULL "
          + "ORDER BY c.id")
  List<TestCaseCommentRow> listByCase(@Param("caseId") long caseId);

  /** 取某用例最新一条评论（插入后回读用）。 */
  @Select(
      "SELECT c.id, c.case_id, c.author_id, u.login AS author_login, u.name AS author_name, "
          + "c.body, c.created_at, c.updated_at "
          + "FROM test_case_comments c LEFT JOIN users u ON u.id = c.author_id "
          + "WHERE c.case_id = #{caseId} AND c.deleted_at IS NULL "
          + "ORDER BY c.id DESC LIMIT 1")
  Optional<TestCaseCommentRow> findLatestByCase(@Param("caseId") long caseId);

  @Select(
      "SELECT c.id, c.case_id, c.author_id, u.login AS author_login, u.name AS author_name, "
          + "c.body, c.created_at, c.updated_at "
          + "FROM test_case_comments c LEFT JOIN users u ON u.id = c.author_id "
          + "WHERE c.id = #{id} AND c.deleted_at IS NULL")
  Optional<TestCaseCommentRow> findById(@Param("id") long id);

  /** 更新评论正文与修改时间（仅作者可调用）。 */
  @Update(
      "UPDATE test_case_comments SET body = #{body}, updated_at = #{now} "
          + "WHERE id = #{id} AND deleted_at IS NULL")
  int update(@Param("id") long id, @Param("body") String body, @Param("now") LocalDateTime now);

  /** 软删除评论（作者或管理员可调用）。 */
  @Update(
      "UPDATE test_case_comments SET deleted_at = #{now} "
          + "WHERE id = #{id} AND deleted_at IS NULL")
  int delete(@Param("id") long id, @Param("now") LocalDateTime now);

  /** 评论是否存在且属于该用户（用于权限校验）。 */
  @Select("SELECT COUNT(*) FROM test_case_comments WHERE id = #{id} AND author_id = #{userId}")
  long countOwned(@Param("id") long id, @Param("userId") long userId);

  /** 批量解析 @提及的用户 id（login 必须是启用状态）。 */
  @Select("SELECT id FROM users WHERE login = #{login} AND active = 1")
  Optional<Long> findUserIdByLogin(@Param("login") String login);
}
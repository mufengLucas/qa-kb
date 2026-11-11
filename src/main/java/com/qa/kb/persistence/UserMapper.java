package com.qa.kb.persistence;

import com.qa.kb.auth.Role;
import com.qa.kb.model.UserRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

  @Select(
      "SELECT id, login, name, password_hash, role, active, created_at FROM users WHERE id = #{id}")
  Optional<UserRow> findById(@Param("id") long id);

  @Select(
      "SELECT id, login, name, password_hash, role, active, created_at FROM users WHERE login = #{login}")
  Optional<UserRow> findByLogin(@Param("login") String login);

  @Select("SELECT id, login, name, password_hash, role, active, created_at FROM users ORDER BY id")
  List<UserRow> findAll();

  @Select("SELECT COUNT(*) FROM users")
  long count();

  @Select("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND active = 1")
  long countActiveAdmins();

  @Insert(
      "INSERT INTO users (login, name, password_hash, role, active) "
          + "VALUES (#{login}, #{name}, #{passwordHash}, #{role}, #{active})")
  int insert(
      @Param("login") String login,
      @Param("name") String name,
      @Param("passwordHash") String passwordHash,
      @Param("role") Role role,
      @Param("active") boolean active);

  /** 自增主键回填；SQL 按方言在 {@code mapper/UserMapper.xml} 中选择。 */
  long lastInsertId();

  @Update("UPDATE users SET name = #{name}, role = #{role}, active = #{active} WHERE id = #{id}")
  int update(@Param("id") long id, @Param("name") String name, @Param("role") Role role,
      @Param("active") boolean active);

  @Update("UPDATE users SET password_hash = #{passwordHash} WHERE id = #{id}")
  int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

  @Delete("DELETE FROM users WHERE id = #{id}")
  int delete(@Param("id") long id);

  /**
   * 用户注销时把名下内容转移给接管者。
   *
   * <p>{@code pages.author_id / page_revisions.editor_id / attachments.uploader_id /
   * projects.owner_id} 都是 RESTRICT 外键，直接 DELETE 会触发数据完整性异常；知识库内容不能随成员
   * 离职丢失，因此先转移再删（在 {@code UserService.delete} 的同一事务内）。
   *
   * <p>pages / projects 显式写回 {@code updated_at = updated_at}，抑制 MySQL 的 {@code ON UPDATE
   * CURRENT_TIMESTAMP}——换作者不等于内容更新，不应让页面跳到"最近更新"列表顶部。
   */
  @Update(
      "UPDATE pages SET author_id = #{to}, updated_at = updated_at WHERE author_id = #{from}")
  int reassignAuthor(@Param("from") long from, @Param("to") long to);

  @Update("UPDATE page_revisions SET editor_id = #{to} WHERE editor_id = #{from}")
  int reassignEditor(@Param("from") long from, @Param("to") long to);

  @Update("UPDATE attachments SET uploader_id = #{to} WHERE uploader_id = #{from}")
  int reassignUploads(@Param("from") long from, @Param("to") long to);

  @Update(
      "UPDATE projects SET owner_id = #{to}, updated_at = updated_at WHERE owner_id = #{from}")
  int reassignProjects(@Param("from") long from, @Param("to") long to);

  /** 管理员自己注销时的接管者：另一名启用状态的管理员（由"至少保留一名管理员"守卫保证存在）。 */
  @Select(
      "SELECT id FROM users WHERE role = 'ADMIN' AND active = 1 AND id <> #{id} "
          + "ORDER BY id LIMIT 1")
  Optional<Long> findOtherActiveAdmin(@Param("id") long id);
}

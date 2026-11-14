package com.qa.kb.persistence;

import com.qa.kb.model.SpaceMemberRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 空间成员（P2 Phase 3）。主键 (space_id, user_id)；{@link #upsert} 的 SQL 按方言在 XML 中选择。 */
@Mapper
public interface SpaceMemberMapper {

  @Select(
      "SELECT sm.space_id, sm.user_id, u.login, u.name, sm.role FROM space_members sm "
          + "JOIN users u ON u.id = sm.user_id WHERE sm.space_id = #{spaceId} ORDER BY u.login")
  List<SpaceMemberRow> listBySpace(@Param("spaceId") long spaceId);

  @Select(
      "SELECT role FROM space_members WHERE space_id = #{spaceId} AND user_id = #{userId}")
  Optional<String> findRole(@Param("spaceId") long spaceId, @Param("userId") long userId);

  /** 首插与改角色一体（MySQL ON DUPLICATE KEY / SQLite ON CONFLICT，见 mapper XML）。 */
  int upsert(
      @Param("spaceId") long spaceId, @Param("userId") long userId, @Param("role") String role);

  @Delete("DELETE FROM space_members WHERE space_id = #{spaceId} AND user_id = #{userId}")
  int delete(@Param("spaceId") long spaceId, @Param("userId") long userId);

  /** 删空间前的显式清场（与 {@code deletePagesBySpace} 同一谨慎风格，不单靠外键级联）。 */
  @Delete("DELETE FROM space_members WHERE space_id = #{spaceId}")
  int deleteBySpace(@Param("spaceId") long spaceId);

  /**
   * 可见空间 id：全部开放空间 ∪ 自己有成员记录的空间；{@code admin=1}（全局管理员）时为全部空间。
   * 列表、回收站、标签、搜索统一用它过滤。
   */
  @Select(
      "SELECT id FROM spaces WHERE restricted = 0 OR #{admin} = 1 "
          + "UNION SELECT space_id FROM space_members WHERE user_id = #{userId}")
  List<Long> visibleSpaceIds(@Param("userId") long userId, @Param("admin") int admin);
}

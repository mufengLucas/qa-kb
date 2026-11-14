package com.qa.kb.persistence;

import com.qa.kb.model.SpaceRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SpaceMapper {

  String COLUMNS = "id, name, slug, icon, description, sort, restricted";

  @Select("SELECT " + COLUMNS + " FROM spaces ORDER BY sort, id")
  List<SpaceRow> findAll();

  @Select("SELECT " + COLUMNS + " FROM spaces WHERE id = #{id}")
  Optional<SpaceRow> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM spaces WHERE slug = #{slug}")
  Optional<SpaceRow> findBySlug(@Param("slug") String slug);

  @Insert(
      "INSERT INTO spaces (name, slug, icon, description, sort, restricted) "
          + "VALUES (#{name}, #{slug}, #{icon}, #{description}, #{sort}, #{restricted})")
  int insert(
      @Param("name") String name,
      @Param("slug") String slug,
      @Param("icon") String icon,
      @Param("description") String description,
      @Param("sort") int sort,
      @Param("restricted") boolean restricted);

  /** 自增主键回填；SQL 按方言在 {@code mapper/SpaceMapper.xml} 中选择。 */
  long lastInsertId();

  @Update(
      "UPDATE spaces SET name = #{name}, icon = #{icon}, description = #{description}, "
          + "sort = #{sort}, restricted = #{restricted} WHERE id = #{id}")
  int update(
      @Param("id") long id,
      @Param("name") String name,
      @Param("icon") String icon,
      @Param("description") String description,
      @Param("sort") int sort,
      @Param("restricted") boolean restricted);

  /**
   * 先摘掉空间内的全部页面。
   *
   * <p>MySQL 中 {@code fk_pages_space … ON DELETE CASCADE} 与 {@code fk_pages_parent … ON DELETE
   * SET NULL} 同时作用于 {@code pages}：直接删空间行会级联删页面，再对兄弟页面执行 SET NULL，
   * 而该更新会重新校验已被删除的空间行 → ERROR 1452（对外表现为 409）。先删页面后，删空间行不再
   * 触发任何对 pages 的更新，两条 SQL 在同一事务内等价于原级联语义。
   */
  @Delete("DELETE FROM pages WHERE space_id = #{id}")
  int deletePagesBySpace(@Param("id") long id);

  @Delete("DELETE FROM spaces WHERE id = #{id}")
  int delete(@Param("id") long id);
}

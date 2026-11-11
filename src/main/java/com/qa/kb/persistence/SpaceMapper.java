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

  @Select("SELECT id, name, slug, icon, description, sort FROM spaces ORDER BY sort, id")
  List<SpaceRow> findAll();

  @Select("SELECT id, name, slug, icon, description, sort FROM spaces WHERE id = #{id}")
  Optional<SpaceRow> findById(@Param("id") long id);

  @Select("SELECT id, name, slug, icon, description, sort FROM spaces WHERE slug = #{slug}")
  Optional<SpaceRow> findBySlug(@Param("slug") String slug);

  @Insert(
      "INSERT INTO spaces (name, slug, icon, description, sort) "
          + "VALUES (#{name}, #{slug}, #{icon}, #{description}, #{sort})")
  int insert(
      @Param("name") String name,
      @Param("slug") String slug,
      @Param("icon") String icon,
      @Param("description") String description,
      @Param("sort") int sort);

  /** 自增主键回填；SQL 按方言在 {@code mapper/SpaceMapper.xml} 中选择。 */
  long lastInsertId();

  @Update(
      "UPDATE spaces SET name = #{name}, icon = #{icon}, description = #{description}, "
          + "sort = #{sort} WHERE id = #{id}")
  int update(
      @Param("id") long id,
      @Param("name") String name,
      @Param("icon") String icon,
      @Param("description") String description,
      @Param("sort") int sort);

  @Delete("DELETE FROM spaces WHERE id = #{id}")
  int delete(@Param("id") long id);
}

package com.qa.kb.persistence;

import com.qa.kb.model.PageRow;
import com.qa.kb.model.PageSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PageMapper {

  String COLUMNS =
      "id, space_id, parent_id, title, slug, content_md, status, author_id, sort, "
          + "created_at, updated_at, deleted_at";

  @Select("SELECT " + COLUMNS + " FROM pages WHERE id = #{id} AND deleted_at IS NULL")
  Optional<PageRow> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM pages WHERE id = #{id}")
  Optional<PageRow> findAnyById(@Param("id") long id);

  @Select(
      "SELECT id, parent_id, title, slug, sort FROM pages "
          + "WHERE space_id = #{spaceId} AND deleted_at IS NULL ORDER BY sort, id")
  List<PageSummary> listBySpace(@Param("spaceId") long spaceId);

  @Select(
      "SELECT " + COLUMNS + " FROM pages WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
  List<PageRow> listDeleted();

  @Select(
      "SELECT id, parent_id, title, slug, sort FROM pages "
          + "WHERE parent_id = #{parentId} AND deleted_at IS NULL ORDER BY sort, id")
  List<PageSummary> listChildren(@Param("parentId") long parentId);

  @Insert(
      "INSERT INTO pages (space_id, parent_id, title, slug, content_md, status, author_id, sort) "
          + "VALUES (#{spaceId}, #{parentId}, #{title}, #{slug}, #{contentMd}, #{status}, "
          + "#{authorId}, #{sort})")
  int insert(
      @Param("spaceId") long spaceId,
      @Param("parentId") Long parentId,
      @Param("title") String title,
      @Param("slug") String slug,
      @Param("contentMd") String contentMd,
      @Param("status") String status,
      @Param("authorId") long authorId,
      @Param("sort") int sort);

  /** 自增主键回填；SQL 按方言在 {@code mapper/PageMapper.xml} 中选择。 */
  long lastInsertId();

  @Update(
      "UPDATE pages SET title = #{title}, slug = #{slug}, content_md = #{contentMd}, "
          + "status = #{status}, updated_at = #{now} "
          + "WHERE id = #{id} AND deleted_at IS NULL")
  int update(
      @Param("id") long id,
      @Param("title") String title,
      @Param("slug") String slug,
      @Param("contentMd") String contentMd,
      @Param("status") String status,
      @Param("now") LocalDateTime now);

  @Update("UPDATE pages SET parent_id = #{parentId}, sort = #{sort} WHERE id = #{id}")
  int move(@Param("id") long id, @Param("parentId") Long parentId, @Param("sort") int sort);

  @Update("UPDATE pages SET deleted_at = #{now} WHERE id = #{id} AND deleted_at IS NULL")
  int softDelete(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update("UPDATE pages SET deleted_at = NULL WHERE id = #{id}")
  int restore(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM pages WHERE space_id = #{spaceId} AND deleted_at IS NULL")
  long countBySpace(@Param("spaceId") long spaceId);
}

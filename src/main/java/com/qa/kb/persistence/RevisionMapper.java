package com.qa.kb.persistence;

import com.qa.kb.model.RevisionRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RevisionMapper {

  @Insert(
      "INSERT INTO page_revisions (page_id, title, content_md, editor_id) "
          + "VALUES (#{pageId}, #{title}, #{contentMd}, #{editorId})")
  int insert(
      @Param("pageId") long pageId,
      @Param("title") String title,
      @Param("contentMd") String contentMd,
      @Param("editorId") long editorId);

  @Select(
      "SELECT id, page_id, title, content_md, editor_id, created_at FROM page_revisions "
          + "WHERE page_id = #{pageId} ORDER BY created_at DESC, id DESC")
  List<RevisionRow> listByPage(@Param("pageId") long pageId);

  @Select(
      "SELECT id, page_id, title, content_md, editor_id, created_at FROM page_revisions "
          + "WHERE id = #{id}")
  Optional<RevisionRow> findById(@Param("id") long id);
}

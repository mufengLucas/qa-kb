package com.qa.kb.persistence;

import com.qa.kb.model.AttachmentRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 页面附件元数据。文件本体不进数据库，只存 UUID 存储名（见 AttachmentService）。 */
@Mapper
public interface AttachmentMapper {

  @Insert(
      "INSERT INTO attachments (page_id, filename, stored_name, size, mime, uploader_id) "
          + "VALUES (#{pageId}, #{filename}, #{storedName}, #{size}, #{mime}, #{uploaderId})")
  int insert(
      @Param("pageId") long pageId,
      @Param("filename") String filename,
      @Param("storedName") String storedName,
      @Param("size") long size,
      @Param("mime") String mime,
      @Param("uploaderId") long uploaderId);

  /** 自增主键回填，SQL 在 {@code mapper/AttachmentMapper.xml} 中按 databaseId 分流。 */
  long lastInsertId();

  @Select(
      "SELECT id, page_id, filename, stored_name, size, mime, uploader_id, created_at "
          + "FROM attachments WHERE page_id = #{pageId} ORDER BY id")
  List<AttachmentRow> listByPage(@Param("pageId") long pageId);

  @Select(
      "SELECT id, page_id, filename, stored_name, size, mime, uploader_id, created_at "
          + "FROM attachments WHERE id = #{id}")
  Optional<AttachmentRow> findById(@Param("id") long id);

  @Delete("DELETE FROM attachments WHERE id = #{id}")
  int delete(@Param("id") long id);
}

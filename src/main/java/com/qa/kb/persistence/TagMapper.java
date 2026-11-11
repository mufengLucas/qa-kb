package com.qa.kb.persistence;

import com.qa.kb.model.TagCount;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper {

  @Select(
      "SELECT t.name, COUNT(pt.page_id) AS count FROM tags t "
          + "LEFT JOIN page_tags pt ON pt.tag_id = t.id "
          + "GROUP BY t.id, t.name ORDER BY count DESC, t.name")
  List<TagCount> listAll();

  @Select(
      "SELECT t.name, 1 AS count FROM tags t "
          + "JOIN page_tags pt ON pt.tag_id = t.id WHERE pt.page_id = #{pageId} ORDER BY t.name")
  List<TagCount> listByPage(@Param("pageId") long pageId);

  /**
   * 幂等插入（MySQL {@code INSERT IGNORE}）：重复时不报错也不回填 id，
   * 调用方用 {@link #findIdByName(String)} 再取一次。
   */
  @Insert("INSERT IGNORE INTO tags (name) VALUES (#{name})")
  int upsert(@Param("name") String name);

  @Select("SELECT id FROM tags WHERE name = #{name}")
  long findIdByName(@Param("name") String name);

  @Insert("INSERT IGNORE INTO page_tags (page_id, tag_id) VALUES (#{pageId}, #{tagId})")
  int attach(@Param("pageId") long pageId, @Param("tagId") long tagId);

  @Delete("DELETE FROM page_tags WHERE page_id = #{pageId}")
  int detachAll(@Param("pageId") long pageId);
}

package com.qa.kb.persistence;

import com.qa.kb.model.TagCount;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper {

  /**
   * 可见空间内的标签云（P2 Phase 3）：按 space_id 过滤，只统计这些空间下的页面标签。
   * 调用方保证集合非空（空集合 IN () 语法不合法，见 {@link com.qa.kb.page.PageService#allTags}）。
   */
  @Select(
      "<script>"
          + "SELECT t.name, COUNT(pt.page_id) AS count FROM tags t "
          + "JOIN page_tags pt ON pt.tag_id = t.id "
          + "JOIN pages p ON p.id = pt.page_id "
          + "WHERE p.space_id IN "
          + "<foreach item='id' collection='spaceIds' open='(' separator=',' close=')'>#{id}</foreach> "
          + "GROUP BY t.id, t.name ORDER BY count DESC, t.name"
          + "</script>")
  List<TagCount> listAllInSpaces(@Param("spaceIds") java.util.Collection<Long> spaceIds);

  @Select(
      "SELECT t.name, 1 AS count FROM tags t "
          + "JOIN page_tags pt ON pt.tag_id = t.id WHERE pt.page_id = #{pageId} ORDER BY t.name")
  List<TagCount> listByPage(@Param("pageId") long pageId);

  /**
   * 幂等插入（MySQL {@code INSERT IGNORE} / SQLite {@code ON CONFLICT DO NOTHING}，方言在
   * {@code mapper/TagMapper.xml}）：重复时不报错也不回填 id，
   * 调用方用 {@link #findIdByName(String)} 再取一次。
   */
  int upsert(@Param("name") String name);

  @Select("SELECT id FROM tags WHERE name = #{name}")
  long findIdByName(@Param("name") String name);

  /** 页面挂标签，重复不报错（方言 SQL 见 mapper XML）。 */
  int attach(@Param("pageId") long pageId, @Param("tagId") long tagId);

  @Delete("DELETE FROM page_tags WHERE page_id = #{pageId}")
  int detachAll(@Param("pageId") long pageId);
}

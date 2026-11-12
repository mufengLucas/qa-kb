package com.qa.kb.persistence;

import com.qa.kb.model.AuditRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 操作审计读写。
 *
 * <p>查询端 LEFT JOIN users：成员被删除后审计行仍在，登录名显示为 null（前端标注「已删除」）。
 * LIMIT/OFFSET 在 MySQL 与 SQLite 同形，无需 databaseId 分流。
 */
@Mapper
public interface AuditMapper {

  @Insert(
      "INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
          + "VALUES (#{userId}, #{action}, #{targetType}, #{targetId}, #{detail})")
  int insert(
      @Param("userId") Long userId,
      @Param("action") String action,
      @Param("targetType") String targetType,
      @Param("targetId") String targetId,
      @Param("detail") String detail);

  @Select(
      "SELECT a.id, a.user_id, a.action, a.target_type, a.target_id, a.detail, a.created_at, "
          + "u.login AS user_login, u.name AS user_name "
          + "FROM audit_log a LEFT JOIN users u ON u.id = a.user_id "
          + "ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}")
  List<AuditRow> page(@Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT COUNT(*) FROM audit_log")
  long count();
}

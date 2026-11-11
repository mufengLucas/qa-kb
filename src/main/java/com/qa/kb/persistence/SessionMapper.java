package com.qa.kb.persistence;

import com.qa.kb.model.SessionRow;
import java.time.LocalDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SessionMapper {

  @Insert(
      "INSERT INTO sessions (id, user_id, created_at, last_seen, expires_at) "
          + "VALUES (#{id}, #{userId}, #{createdAt}, #{lastSeen}, #{expiresAt})")
  int insert(
      @Param("id") String id,
      @Param("userId") long userId,
      @Param("createdAt") LocalDateTime createdAt,
      @Param("lastSeen") LocalDateTime lastSeen,
      @Param("expiresAt") LocalDateTime expiresAt);

  @Select(
      "SELECT id, user_id, created_at, last_seen, expires_at FROM sessions "
          + "WHERE id = #{id} AND expires_at > #{now}")
  Optional<SessionRow> findValid(@Param("id") String id, @Param("now") LocalDateTime now);

  @Update("UPDATE sessions SET last_seen = #{now} WHERE id = #{id}")
  int touch(@Param("id") String id, @Param("now") LocalDateTime now);

  @Delete("DELETE FROM sessions WHERE id = #{id}")
  int delete(@Param("id") String id);

  @Delete("DELETE FROM sessions WHERE expires_at <= #{now}")
  int deleteExpired(@Param("now") LocalDateTime now);
}

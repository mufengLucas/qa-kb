package com.qa.kb.persistence.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** SQLite 没有时间类型：统一用 {@code yyyy-MM-dd HH:mm:ss} 文本读写 LocalDateTime。 */
@MappedTypes(LocalDateTime.class)
public class LocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, SqliteTimes.format(parameter));
  }

  @Override
  public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return SqliteTimes.parseDateTime(rs.getString(columnName));
  }

  @Override
  public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return SqliteTimes.parseDateTime(rs.getString(columnIndex));
  }

  @Override
  public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    return SqliteTimes.parseDateTime(cs.getString(columnIndex));
  }
}

package com.qa.kb.persistence.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** 日期统一以 {@code yyyy-MM-dd} 文本读写 LocalDate（见 {@link SqlTimes}）。 */
@MappedTypes(LocalDate.class)
public class LocalDateTypeHandler extends BaseTypeHandler<LocalDate> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, LocalDate parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, SqlTimes.format(parameter));
  }

  @Override
  public LocalDate getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return SqlTimes.parseDate(rs.getString(columnName));
  }

  @Override
  public LocalDate getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return SqlTimes.parseDate(rs.getString(columnIndex));
  }

  @Override
  public LocalDate getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return SqlTimes.parseDate(cs.getString(columnIndex));
  }
}

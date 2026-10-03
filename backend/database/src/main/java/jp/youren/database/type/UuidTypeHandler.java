package jp.youren.database.type;

import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.*;

// PostgreSQL uuid显式作为OTHER绑定，避免驱动将字符串推断为varchar。
@MappedTypes(UUID.class)
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
  @Override
  public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType type)
      throws SQLException {
    statement.setObject(index, value, Types.OTHER);
  }

  @Override
  public UUID getNullableResult(ResultSet result, String column) throws SQLException {
    return result.getObject(column, UUID.class);
  }

  @Override
  public UUID getNullableResult(ResultSet result, int column) throws SQLException {
    return result.getObject(column, UUID.class);
  }

  @Override
  public UUID getNullableResult(CallableStatement result, int column) throws SQLException {
    return result.getObject(column, UUID.class);
  }
}

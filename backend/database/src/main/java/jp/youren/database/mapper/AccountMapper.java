package jp.youren.database.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 工程验收使用代表性账号Mapper，不提供未鉴权HTTP入口。
public interface AccountMapper {
  record AccountRow(UUID id, String email, int authVersion) {}

  int insert(@Param("id") UUID id, @Param("email") String email);

  AccountRow find(@Param("id") UUID id);

  int updateVersion(@Param("id") UUID id, @Param("version") int version);

  int delete(@Param("id") UUID id);
}

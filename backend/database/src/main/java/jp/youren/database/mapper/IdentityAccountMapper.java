package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 账号认证查询与既有工程CRUD分离，避免把内部账号行直接当公开DTO。
public interface IdentityAccountMapper {
  record IdentityRow(UUID id, String email, String status) {
    @Override
    public String toString() {
      return "IdentityRow[redacted]";
    }
  }

  record SettingsRow(boolean allowStrangerDm, boolean pushEnabled, boolean lockscreenPreview) {}

  IdentityRow findEmail(@Param("email") String email);

  IdentityRow find(@Param("id") UUID id);

  IdentityRow lock(@Param("id") UUID id);

  int insert(@Param("id") UUID id, @Param("email") String email, @Param("now") OffsetDateTime now);

  int initializeSettings(@Param("accountId") UUID accountId, @Param("now") OffsetDateTime now);

  int initializePrincipal(
      @Param("id") UUID id, @Param("accountId") UUID accountId, @Param("now") OffsetDateTime now);

  SettingsRow settings(@Param("accountId") UUID accountId);
}

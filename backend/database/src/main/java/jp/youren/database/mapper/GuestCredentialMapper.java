package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 游客持久层仅接收摘要，不接受原凭证；主体与凭证写入由服务层同事务组合。
public interface GuestCredentialMapper {
  record GuestCredentialRow(
      UUID id, UUID principalId, String secretHash, OffsetDateTime expiresAt) {}

  int insertPrincipal(@Param("id") UUID id, @Param("createdAt") OffsetDateTime createdAt);

  int insertCredential(
      @Param("id") UUID id,
      @Param("principalId") UUID principalId,
      @Param("secretHash") String secretHash,
      @Param("createdAt") OffsetDateTime createdAt,
      @Param("expiresAt") OffsetDateTime expiresAt);

  GuestCredentialRow findActive(
      @Param("secretHash") String secretHash, @Param("now") OffsetDateTime now);

  int revoke(@Param("secretHash") String secretHash, @Param("now") OffsetDateTime now);
}

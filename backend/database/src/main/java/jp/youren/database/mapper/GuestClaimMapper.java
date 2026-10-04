package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 接续专用读取包括撤销摘要，只用于已认证账号的归属校验，不能替代匿名鉴权。
public interface GuestClaimMapper {
  record Credential(UUID principalId, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {}

  record Principal(String kind, UUID accountId, OffsetDateTime claimedAt) {}

  record Receipt(String requestHash, OffsetDateTime expiresAt) {}

  record Counts(int posts, int comments) {}

  Credential find(@Param("digest") String digest);

  Credential lockCredential(@Param("digest") String digest);

  Principal lockPrincipal(@Param("id") UUID id);

  int bind(@Param("id") UUID id, @Param("account") UUID account, @Param("now") OffsetDateTime now);

  int revokeAll(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  Receipt receipt(@Param("actor") String actor, @Param("key") String key);

  int insertReceipt(
      @Param("actor") String actor,
      @Param("key") String key,
      @Param("digest") String digest,
      @Param("principal") UUID principal,
      @Param("now") OffsetDateTime now,
      @Param("expiry") OffsetDateTime expiry);

  Counts counts(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}

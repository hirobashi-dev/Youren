package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 普通用户会话只保存两种独立摘要，保留被替换记录用于识别刷新重放。
public interface SessionMapper {
  record SessionRow(
      UUID id,
      UUID accountId,
      UUID familyId,
      String refreshHash,
      String accessHash,
      OffsetDateTime expiresAt,
      OffsetDateTime accessExpiresAt,
      OffsetDateTime revokedAt,
      UUID replacedById) {
    @Override
    public String toString() {
      return "SessionRow[redacted]";
    }
  }

  SessionRow findRefresh(@Param("hash") String hash);

  SessionRow lock(@Param("id") UUID id);

  SessionRow findAccess(@Param("hash") String hash);

  int insert(
      @Param("id") UUID id,
      @Param("accountId") UUID accountId,
      @Param("familyId") UUID familyId,
      @Param("refreshHash") String refreshHash,
      @Param("accessHash") String accessHash,
      @Param("now") OffsetDateTime now,
      @Param("expires") OffsetDateTime expires,
      @Param("accessExpires") OffsetDateTime accessExpires);

  int replace(@Param("id") UUID id, @Param("next") UUID next, @Param("now") OffsetDateTime now);

  int revokeFamily(@Param("family") UUID family, @Param("now") OffsetDateTime now);
}

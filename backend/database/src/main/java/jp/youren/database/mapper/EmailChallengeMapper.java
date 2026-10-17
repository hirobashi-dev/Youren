package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 验证码状态由PostgreSQL权威保存，持锁消费与账号创建必须由同一事务组合。
public interface EmailChallengeMapper {
  record ChallengeRow(
      UUID id,
      String email,
      String purpose,
      String codeHmac,
      int attempts,
      OffsetDateTime expiresAt,
      OffsetDateTime consumedAt) {
    @Override
    public String toString() {
      return "ChallengeRow[redacted]";
    }
  }

  ChallengeRow find(@Param("id") UUID id);

  ChallengeRow lock(@Param("id") UUID id);

  int lockEmail(@Param("email") String email);

  int insert(
      @Param("id") UUID id,
      @Param("email") String email,
      @Param("purpose") String purpose,
      @Param("hmac") String hmac,
      @Param("now") OffsetDateTime now,
      @Param("expires") OffsetDateTime expires);

  int activate(@Param("id") UUID id);

  int attempt(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  int consume(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}

package jp.youren.api.guest;

import java.time.*;
import java.util.UUID;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.GuestCredentialMapper;
import org.springframework.transaction.annotation.Transactional;

// 主体与凭证必须原子创建；原秘密仅作为一次性响应返回，不持久化或记录日志。
public class GuestSessionService {
  private final GuestCredentialMapper mapper;
  private final Clock clock;
  private final Duration lifetime;

  public record GuestSession(String guestToken, UUID principalId, Instant expiresAt) {
    @Override
    public String toString() {
      return "GuestSession[guestToken=[REDACTED], principalId="
          + principalId
          + ", expiresAt="
          + expiresAt
          + "]";
    }
  }

  public GuestSessionService(GuestCredentialMapper mapper, Clock clock, Duration lifetime) {
    if (lifetime == null || lifetime.isNegative() || lifetime.isZero())
      throw new IllegalArgumentException("游客凭证有效期必须为正数");
    this.mapper = mapper;
    this.clock = clock;
    this.lifetime = lifetime;
  }

  @Transactional
  public GuestSession create() {
    // PostgreSQL精度为微秒，响应和持久化使用相同精度以保持到期边界一致。
    var now =
        OffsetDateTime.ofInstant(
            clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
    var expiresAt = now.plus(lifetime);
    var principalId = UUID.randomUUID();
    var token = TokenSecrets.generate();
    mapper.insertPrincipal(principalId, now);
    mapper.insertCredential(
        UUID.randomUUID(), principalId, TokenSecrets.digest(token), now, expiresAt);
    return new GuestSession(token, principalId, expiresAt.toInstant());
  }

  // 只凭随机秘密证明游客主体；主体ID、过期或已撤销摘要不能获得权限。
  public UUID requirePrincipal(String token) {
    validateToken(token);
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var row = mapper.findActive(TokenSecrets.digest(token), now);
    if (row == null
        || !row.expiresAt().isAfter(now)
        || !TokenSecrets.matches(token, row.secretHash())) {
      throw new GuestCredentialException();
    }
    return row.principalId();
  }

  // 撤销幂等且保留摘要，后续接续服务可另行锁定历史凭证核对归属。
  @Transactional
  public void revoke(String token) {
    validateToken(token);
    mapper.revoke(
        TokenSecrets.digest(token), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }

  // 限制格式及长度，防止超大或非法输入进入摘要计算；只接受规范的无填充Base64URL。
  private static void validateToken(String token) {
    if (token == null
        || token.length() < 43
        || token.length() > 1024
        || !token.matches("[A-Za-z0-9_-]+")) {
      throw new GuestCredentialException();
    }
    try {
      var bytes = java.util.Base64.getUrlDecoder().decode(token);
      if (bytes.length < 32
          || !java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(bytes)
              .equals(token)) {
        throw new GuestCredentialException();
      }
    } catch (IllegalArgumentException error) {
      throw new GuestCredentialException();
    }
  }
}

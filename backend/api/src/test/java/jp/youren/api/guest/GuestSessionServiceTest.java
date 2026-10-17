package jp.youren.api.guest;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import jp.youren.database.mapper.GuestCredentialMapper;
import org.junit.jupiter.api.Test;

class GuestSessionServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
  private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  // 此摘要由独立SHA256工具计算，避免用生产摘要工具生成期望值。
  private static final String HASH =
      "0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a";

  // 有效凭证证明游客主体；查询使用服务器Clock，并且不接受不匹配的存储摘要。
  @Test
  void acceptsValidTokenAndRejectsHashMismatch() {
    var mapper = new MemoryMapper();
    var principal = UUID.randomUUID();
    mapper.row =
        new GuestCredentialMapper.GuestCredentialRow(
            UUID.randomUUID(),
            principal,
            HASH,
            OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC));
    var service =
        new GuestSessionService(mapper, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    assertEquals(principal, assertDoesNotThrow(() -> service.requirePrincipal(TOKEN)));
    assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), mapper.queriedAt);
    mapper.row =
        new GuestCredentialMapper.GuestCredentialRow(
            UUID.randomUUID(),
            principal,
            "f".repeat(64),
            OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC));
    assertThrows(GuestCredentialException.class, () -> service.requirePrincipal(TOKEN));
  }

  // 到期瞬间、未知秘密、空秘密、超长输入与撤销秘密全部返回统一安全业务失败。
  @Test
  void rejectsExpiryBoundaryRevokedUnknownAndMalformedTokens() {
    var mapper = new MemoryMapper();
    mapper.row =
        new GuestCredentialMapper.GuestCredentialRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            HASH,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    var service =
        new GuestSessionService(mapper, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    assertThrows(GuestCredentialException.class, () -> service.requirePrincipal(TOKEN));
    mapper.row = null;
    for (var invalid : Arrays.asList(TOKEN, null, "", "short", "x".repeat(1025))) {
      var error =
          assertThrows(GuestCredentialException.class, () -> service.requirePrincipal(invalid));
      assertFalse(error.toString().contains(TOKEN));
    }
    mapper.row =
        new GuestCredentialMapper.GuestCredentialRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            HASH,
            OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC));
    assertDoesNotThrow(() -> service.revoke(TOKEN));
    assertThrows(GuestCredentialException.class, () -> service.requirePrincipal(TOKEN));
    assertDoesNotThrow(() -> service.revoke(TOKEN));
  }

  // 自定义TTL控制到期；凭证结果对象的日志表示不包含原秘密。
  @Test
  void configurableLifetimeUsesServerTimeAndRedactsSecret() {
    var mapper = new MemoryMapper();
    var service =
        new GuestSessionService(mapper, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(2));
    var result = service.create();
    assertEquals(NOW.plusSeconds(7200), result.expiresAt());
    assertFalse(result.toString().contains(result.guestToken()));
    assertEquals(result.principalId(), mapper.row.principalId());
    assertNotEquals(result.guestToken(), mapper.row.secretHash());
    assertThrows(
        IllegalArgumentException.class,
        () -> new GuestSessionService(mapper, Clock.systemUTC(), Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GuestSessionService(mapper, Clock.systemUTC(), Duration.ofSeconds(-1)));
  }

  // 单元替身只模拟外部持久层；SQL过滤、事务及撤销时间由真实PostgreSQL集成测试验证。
  private static final class MemoryMapper implements GuestCredentialMapper {
    GuestCredentialRow row;
    OffsetDateTime queriedAt;
    boolean revoked;

    public int insertPrincipal(UUID id, OffsetDateTime createdAt) {
      return 1;
    }

    public int insertCredential(
        UUID id,
        UUID principalId,
        String secretHash,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
      row = new GuestCredentialRow(id, principalId, secretHash, expiresAt);
      return 1;
    }

    public GuestCredentialRow findActive(String secretHash, OffsetDateTime now) {
      queriedAt = now;
      return revoked ? null : row;
    }

    public int revoke(String secretHash, OffsetDateTime now) {
      if (row != null && row.secretHash().equals(secretHash)) {
        revoked = true;
        return 1;
      }
      return 0;
    }
  }
}

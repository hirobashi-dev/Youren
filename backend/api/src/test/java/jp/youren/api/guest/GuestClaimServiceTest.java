package jp.youren.api.guest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.*;
import org.junit.jupiter.api.Test;

// 真实服务与可控时钟验证锁等待期间到期；这里只替换持久端口模拟数据库等待边界。
class GuestClaimServiceTest {
  static class TestClock extends Clock {
    Instant instant = Instant.parse("2026-10-04T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return instant;
    }
  }

  // 凭证在获取账号锁时有效，但等待主体/凭证锁后已到期，必须拒绝绑定。
  @Test
  void credentialExpiringDuringLockWaitCannotBeClaimed() {
    UUID account = UUID.randomUUID();
    UUID principal = UUID.randomUUID();
    String token = TokenSecrets.generate();
    String digest = TokenSecrets.digest(token);
    var clock = new TestClock();
    var accounts = mock(IdentityAccountMapper.class);
    var mapper = mock(GuestClaimMapper.class);
    when(accounts.lock(account))
        .thenReturn(
            new IdentityAccountMapper.IdentityRow(account, "private@example.com", "active"));
    var credential =
        new GuestClaimMapper.Credential(
            principal,
            OffsetDateTime.ofInstant(clock.instant.plusSeconds(1), ZoneOffset.UTC),
            null);
    when(mapper.find(digest)).thenReturn(credential);
    when(mapper.lockPrincipal(principal))
        .thenReturn(new GuestClaimMapper.Principal("guest", null, null));
    when(mapper.lockCredential(digest))
        .thenAnswer(
            invocation -> {
              clock.instant = clock.instant.plusSeconds(2);
              return credential;
            });
    when(mapper.bind(any(), any(), any())).thenReturn(1);
    when(mapper.counts(any(), any())).thenReturn(new GuestClaimMapper.Counts(0, 0));
    var service = new GuestClaimService(accounts, mapper, clock, 86400);
    var failure =
        assertThrows(AuthFailure.class, () -> service.claim(account, token, UUID.randomUUID()));
    assertEquals(403, failure.status());
  }
}

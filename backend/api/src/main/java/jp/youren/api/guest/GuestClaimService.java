package jp.youren.api.guest;

import java.time.*;
import java.util.UUID;
import jp.youren.api.auth.*;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 账号、主体、凭证依次加锁；绑定、全凭证撤销和幂等回执在一个事务提交。
@Service
public class GuestClaimService {
  private final IdentityAccountMapper accounts;
  private final GuestClaimMapper mapper;
  private final Clock clock;
  private final long receiptSeconds;

  public record Counts(int posts, int comments) {}

  public record Claim(Counts claimedContentCounts) {}

  public GuestClaimService(
      IdentityAccountMapper accounts,
      GuestClaimMapper mapper,
      Clock clock,
      @Value("${youren.guest.claim-receipt-seconds:86400}") long receiptSeconds) {
    if (receiptSeconds <= 0) throw new IllegalArgumentException("幂等回执窗口必须为正数");
    this.accounts = accounts;
    this.mapper = mapper;
    this.clock = clock;
    this.receiptSeconds = receiptSeconds;
  }

  @Transactional
  public Claim claim(UUID account, String token, UUID key) {
    if (!SessionService.validToken(token)) throw AuthFailure.invalid();
    SessionService.requireActive(accounts.lock(account));
    String digest = TokenSecrets.digest(token);
    String actor = account.toString();
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var receipt = mapper.receipt(actor, key.toString());
    if (receipt != null) {
      if (!receipt.requestHash().equals(digest)) throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
      if (!receipt.expiresAt().isAfter(now)) throw new AuthFailure(409, "RESULT_EXPIRED");
    }
    var initial = mapper.find(digest);
    if (initial == null) throw denied();
    var principal = mapper.lockPrincipal(initial.principalId());
    var credential = mapper.lockCredential(digest);
    // 锁等待会推进真实时间，绑定前重新读取时钟，不能用等待前时间接续已到期秘密。
    now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (receipt != null && !receipt.expiresAt().isAfter(now))
      throw new AuthFailure(409, "RESULT_EXPIRED");
    if (principal == null
        || credential == null
        || !credential.expiresAt().isAfter(now)
        || !"guest".equals(principal.kind())) throw denied();
    if (principal.accountId() != null) {
      if (!account.equals(principal.accountId()) || principal.claimedAt() == null) throw denied();
    } else {
      if (credential.revokedAt() != null || principal.claimedAt() != null) throw denied();
      if (mapper.bind(initial.principalId(), account, now) != 1) throw denied();
      mapper.revokeAll(initial.principalId(), now);
    }
    if (receipt == null)
      mapper.insertReceipt(
          actor,
          key.toString(),
          digest,
          initial.principalId(),
          now,
          now.plusSeconds(receiptSeconds));
    var counts = mapper.counts(initial.principalId(), now);
    return new Claim(new Counts(counts.posts(), counts.comments()));
  }

  private static AuthFailure denied() {
    return new AuthFailure(403, "FORBIDDEN");
  }
}

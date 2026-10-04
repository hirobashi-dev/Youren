package jp.youren.api.auth;

import java.time.*;
import java.util.UUID;
import jp.youren.api.security.*;
import jp.youren.database.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 刷新先锁账号再重读锁定会话，重放撤销作为事务结果提交后才抛401。
@Service
public class SessionService {
  private final SessionMapper sessions;
  private final IdentityAccountMapper accounts;
  private final Clock clock;
  private final AuthSettings settings;
  private final TransactionTemplate transaction;

  public record Tokens(String accessToken, String refreshToken) {
    @Override
    public String toString() {
      return "Tokens[REDACTED]";
    }
  }

  private record RefreshDecision(Tokens tokens, AuthFailure failure) {}

  public SessionService(
      SessionMapper sessions,
      IdentityAccountMapper accounts,
      Clock clock,
      AuthSettings settings,
      PlatformTransactionManager manager) {
    this.sessions = sessions;
    this.accounts = accounts;
    this.clock = clock;
    this.settings = settings;
    this.transaction = new TransactionTemplate(manager);
  }

  // 调用方创建账号和会话时共用事务；只向返回对象放一次性原秘密。
  public Tokens create(UUID accountId, UUID familyId) {
    return insert(accountId, familyId, UUID.randomUUID(), now());
  }

  private Tokens insert(UUID accountId, UUID family, UUID id, OffsetDateTime now) {
    String access = TokenSecrets.generate();
    String refresh = TokenSecrets.generate();
    sessions.insert(
        id,
        accountId,
        family,
        TokenSecrets.digest(refresh),
        TokenSecrets.digest(access),
        now,
        now.plusSeconds(settings.refreshTtl()),
        now.plusSeconds(settings.accessTtl()));
    return new Tokens(access, refresh);
  }

  public Tokens refresh(String token) {
    RefreshDecision decision =
        transaction.execute(
            status -> {
              var initial = sessions.findRefresh(TokenSecrets.digest(token));
              if (initial == null) return new RefreshDecision(null, AuthFailure.unauthorized());
              var account = accounts.lock(initial.accountId());
              var locked = sessions.lock(initial.id());
              var now = now();
              if (locked == null || !TokenSecrets.matches(token, locked.refreshHash()))
                return new RefreshDecision(null, AuthFailure.unauthorized());
              // 已替换、撤销或到期旧刷新均触发整个族撤销，历史摘要仍保留以识别后续重放。
              if (locked.revokedAt() != null
                  || locked.replacedById() != null
                  || !locked.expiresAt().isAfter(now)) {
                sessions.revokeFamily(locked.familyId(), now);
                return new RefreshDecision(null, new AuthFailure(401, "SESSION_REVOKED"));
              }
              try {
                requireActive(account);
              } catch (AuthFailure failure) {
                return new RefreshDecision(null, failure);
              }
              var nextId = UUID.randomUUID();
              var pair = insert(account.id(), locked.familyId(), nextId, now);
              sessions.replace(locked.id(), nextId, now);
              return new RefreshDecision(pair, null);
            });
    if (decision == null) throw AuthFailure.unavailable();
    if (decision.failure() != null) throw decision.failure();
    return decision.tokens();
  }

  public CurrentPrincipal requireAccess(String token) {
    if (!validToken(token)) throw AuthFailure.unauthorized();
    var row = sessions.findAccess(TokenSecrets.digest(token));
    var now = now();
    if (row == null
        || row.revokedAt() != null
        || row.replacedById() != null
        || row.accessExpiresAt() == null
        || !row.accessExpiresAt().isAfter(now)
        || !row.expiresAt().isAfter(now)
        || !TokenSecrets.matches(token, row.accessHash()))
      throw new AuthFailure(401, "SESSION_REVOKED");
    requireActive(accounts.find(row.accountId()));
    return new CurrentPrincipal(row.accountId(), row.id(), row.familyId());
  }

  public void logout(CurrentPrincipal principal) {
    transaction.executeWithoutResult(
        status -> {
          accounts.lock(principal.accountId());
          var row = sessions.lock(principal.sessionId());
          if (row == null || row.revokedAt() != null) throw AuthFailure.unauthorized();
          sessions.revokeFamily(row.familyId(), now());
        });
  }

  public static void requireActive(IdentityAccountMapper.IdentityRow account) {
    if (account == null || !account.status().equals("active")) {
      if (account != null && account.status().equals("restricted"))
        throw new AuthFailure(403, "ACCOUNT_RESTRICTED");
      throw AuthFailure.unauthorized();
    }
  }

  public static boolean validToken(String token) {
    if (token == null
        || token.length() < 43
        || token.length() > 1024
        || !token.matches("[A-Za-z0-9_-]+")) return false;
    try {
      byte[] bytes = java.util.Base64.getUrlDecoder().decode(token);
      return bytes.length >= 32
          && java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(
        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
  }
}

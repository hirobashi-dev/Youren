package jp.youren.api.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.AdminMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 管理登录在独立会话表中完成口令、MFA挑战和访问令牌校验，不接受普通账号令牌。
@Service
public class AdminAuthService {
  private static final int SESSION_SECONDS = 28_800;
  private static final String DUMMY_HASH =
      "pbkdf2-sha256$310000$MDEyMzQ1Njc4OWFiY2RlZg$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private final AdminMapper mapper;
  private final Clock clock;
  private final String encryptionKey;
  private final TransactionTemplate transaction;

  public record Challenge(UUID challengeId, OffsetDateTime expiresAt) {}

  public record AdminSession(String accessToken, OffsetDateTime expiresAt, String role) {
    @Override
    public String toString() {
      return "AdminSession[REDACTED]";
    }
  }

  public AdminAuthService(
      AdminMapper mapper,
      Clock clock,
      @Value("${YOUREN_ADMIN_MFA_ENCRYPTION_KEY:}") String encryptionKey,
      PlatformTransactionManager manager) {
    this.mapper = mapper;
    this.clock = clock;
    this.encryptionKey = encryptionKey;
    this.transaction = new TransactionTemplate(manager);
  }

  public Challenge login(String loginName, char[] password) {
    String normalized =
        loginName == null ? "" : loginName.trim().toLowerCase(java.util.Locale.ROOT);
    var account =
        normalized.matches("[a-z0-9][a-z0-9._-]{2,63}") ? mapper.accountByLogin(normalized) : null;
    String passwordHash = account == null ? DUMMY_HASH : account.passwordHash();
    boolean passwordMatches = AdminCredentialCrypto.verifyPassword(password, passwordHash);
    Arrays.fill(password, '\0');
    if (account == null || !passwordMatches || !"active".equals(account.status()))
      throw AuthFailure.unauthorized();
    requireEncryptionKey();
    OffsetDateTime now = now();
    UUID id = UUID.randomUUID();
    OffsetDateTime expires = now.plusMinutes(5);
    transaction.executeWithoutResult(
        status -> mapper.insertChallenge(id, account.id(), now, expires));
    return new Challenge(id, expires);
  }

  public AdminSession complete(UUID challengeId, String code) {
    var decision =
        transaction.execute(
            status -> {
              OffsetDateTime now = now();
              var challenge = mapper.lockChallenge(challengeId);
              if (challenge == null
                  || challenge.consumedAt() != null
                  || !challenge.expiresAt().isAfter(now)
                  || challenge.failedAttempts() >= 5)
                return new Decision(null, AuthFailure.unauthorized());
              var account = mapper.accountById(challenge.adminId());
              if (account == null || !"active".equals(account.status()))
                return new Decision(null, AuthFailure.unauthorized());
              boolean valid =
                  Totp.verify(
                      AdminCredentialCrypto.decryptTotpSecret(
                          account.mfaCiphertext(), requireEncryptionKey()),
                      code,
                      now.toInstant());
              if (!valid) {
                mapper.failChallenge(challenge.id(), now);
                return new Decision(null, AuthFailure.unauthorized());
              }
              if (mapper.consumeChallenge(challenge.id(), now) != 1)
                return new Decision(null, AuthFailure.unauthorized());
              String token = TokenSecrets.generate();
              OffsetDateTime expires = now.plusSeconds(SESSION_SECONDS);
              mapper.insertSession(
                  UUID.randomUUID(), account.id(), TokenSecrets.digest(token), now, expires);
              return new Decision(new AdminSession(token, expires, account.role()), null);
            });
    if (decision == null) throw AuthFailure.unavailable();
    if (decision.failure() != null) throw decision.failure();
    return decision.session();
  }

  private record Decision(AdminSession session, AuthFailure failure) {}

  public AdminPrincipal requireAccess(String token) {
    if (!SessionServiceToken.valid(token)) throw AuthFailure.unauthorized();
    var session = mapper.sessionByToken(TokenSecrets.digest(token), now());
    if (session == null) throw AuthFailure.unauthorized();
    return new AdminPrincipal(session.adminId(), session.role());
  }

  private String requireEncryptionKey() {
    if (encryptionKey == null || encryptionKey.isBlank()) throw AuthFailure.unavailable();
    return encryptionKey;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }

  // 管理访问令牌同普通服务令牌采用随机不透明格式，但查询只访问管理员会话表。
  private static final class SessionServiceToken {
    static boolean valid(String token) {
      if (token == null || token.length() < 43 || token.length() > 1024) return false;
      try {
        byte[] bytes = java.util.Base64.getUrlDecoder().decode(token);
        return bytes.length >= 32
            && java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes)
                .equals(token);
      } catch (IllegalArgumentException error) {
        return false;
      }
    }
  }
}

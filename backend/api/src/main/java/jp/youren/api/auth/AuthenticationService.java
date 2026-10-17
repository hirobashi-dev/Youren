package jp.youren.api.auth;

import java.time.*;
import java.util.UUID;
import jp.youren.api.guest.GuestCredentialException;
import jp.youren.database.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 消费挑战、账号/设置/主体/条款和会话写入一个事务；预期认证失败作为值返回以提交错误尝试。
@Service
public class AuthenticationService {
  private final EmailChallengeMapper challenges;
  private final IdentityAccountMapper accounts;
  private final ConsentRecordMapper consents;
  private final SessionService sessions;
  private final AccountMeQuery me;
  private final AuthSettings settings;
  private final Clock clock;
  private final GuestClaimHandler claims;
  private final jp.youren.api.email.EmailDelivery mail;
  private final TransactionTemplate transaction;

  public record AuthSession(
      String accessToken,
      String refreshToken,
      AccountMeQuery.Me account,
      boolean onboardingRequired,
      String claimStatus) {
    @Override
    public String toString() {
      return "AuthSession[REDACTED]";
    }
  }

  public record Result(AuthSession session, boolean created) {}

  private record Decision(
      SessionService.Tokens tokens, UUID accountId, boolean created, AuthFailure failure) {}

  public AuthenticationService(
      EmailChallengeMapper challenges,
      IdentityAccountMapper accounts,
      ConsentRecordMapper consents,
      SessionService sessions,
      AccountMeQuery me,
      AuthSettings settings,
      Clock clock,
      GuestClaimHandler claims,
      jp.youren.api.email.EmailDelivery mail,
      PlatformTransactionManager manager) {
    this.challenges = challenges;
    this.accounts = accounts;
    this.consents = consents;
    this.sessions = sessions;
    this.me = me;
    this.settings = settings;
    this.clock = clock;
    this.claims = claims;
    this.mail = mail;
    transaction = new TransactionTemplate(manager);
  }

  public Result authenticate(
      UUID id, String code, String purpose, String terms, String privacy, String guestToken) {
    settings.hmacKey();
    // 未配置邮件供应商也拒绝挑战消费，避免仅HMAC配置就意外开放生产登录。
    if (!mail.configured()) throw AuthFailure.unavailable();
    if (purpose.equals("register")
        && (!settings.termsVersion().equals(terms) || !settings.privacyVersion().equals(privacy)))
      throw AuthFailure.invalid();
    Decision decision =
        transaction.execute(
            status -> {
              var initial = challenges.find(id);
              if (initial == null) return rejected(AuthFailure.unauthorized());
              challenges.lockEmail(initial.email());
              var account = accounts.findEmail(initial.email());
              if (account != null) account = accounts.lock(account.id());
              var challenge = challenges.lock(id);
              var now =
                  OffsetDateTime.ofInstant(
                      clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                      ZoneOffset.UTC);
              if (challenge == null
                  || challenge.consumedAt() != null
                  || !challenge.expiresAt().isAfter(now)
                  || challenge.attempts() >= settings.attempts()
                  || !challenge.purpose().equals(purpose))
                return rejected(AuthFailure.unauthorized());
              String expected =
                  ChallengeSecrets.hmac(settings, id, challenge.email(), challenge.purpose(), code);
              if (!ChallengeSecrets.matches(expected, challenge.codeHmac())) {
                challenges.attempt(id, now);
                return rejected(AuthFailure.unauthorized());
              }
              if (account == null && purpose.equals("login")) {
                challenges.consume(id, now);
                return rejected(AuthFailure.unauthorized());
              }
              if (account != null) {
                try {
                  SessionService.requireActive(account);
                } catch (AuthFailure failure) {
                  return rejected(failure);
                }
              }
              boolean created = account == null;
              UUID accountId = created ? UUID.randomUUID() : account.id();
              challenges.consume(id, now);
              if (created) {
                accounts.insert(accountId, challenge.email(), now);
                accounts.initializeSettings(accountId, now);
                accounts.initializePrincipal(UUID.randomUUID(), accountId, now);
                consents.insert(
                    UUID.randomUUID(),
                    accountId,
                    settings.termsVersion(),
                    settings.privacyVersion(),
                    now);
              }
              return new Decision(
                  sessions.create(accountId, UUID.randomUUID()), accountId, created, null);
            });
    if (decision == null) throw AuthFailure.unavailable();
    if (decision.failure() != null) throw decision.failure();
    // 接续仅捕获约定的身份/领域失败；编程错误交由安全500处理，不伪造成功结果。
    String claimStatus = "not_requested";
    if (guestToken != null) {
      try {
        claimStatus = claims.claim(decision.accountId(), guestToken) ? "claimed" : "failed";
      } catch (GuestCredentialException | AuthFailure failure) {
        claimStatus = "failed";
      }
    }
    var account = me.read(decision.accountId());
    return new Result(
        new AuthSession(
            decision.tokens().accessToken(),
            decision.tokens().refreshToken(),
            account,
            account.onboardingRequired(),
            claimStatus),
        decision.created());
  }

  private static Decision rejected(AuthFailure failure) {
    return new Decision(null, null, false, failure);
  }
}

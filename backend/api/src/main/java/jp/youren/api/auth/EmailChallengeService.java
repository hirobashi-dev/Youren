package jp.youren.api.auth;

import java.time.*;
import java.util.UUID;
import jp.youren.api.email.EmailDelivery;
import jp.youren.database.mapper.EmailChallengeMapper;
import org.springframework.stereotype.Service;

// 外部邮件发送在数据库事务外进行；未完成发送的挑战不可消费，失败也不返还限流额度。
@Service
public class EmailChallengeService {
  private final EmailChallengeMapper mapper;
  private final EmailRateLimiter limiter;
  private final EmailDelivery mail;
  private final Clock clock;
  private final AuthSettings settings;

  public record Challenge(UUID challengeId, long expiresIn, long retryAfter) {}

  public EmailChallengeService(
      EmailChallengeMapper mapper,
      EmailRateLimiter limiter,
      EmailDelivery mail,
      Clock clock,
      AuthSettings settings) {
    this.mapper = mapper;
    this.limiter = limiter;
    this.mail = mail;
    this.clock = clock;
    this.settings = settings;
  }

  public Challenge send(String email, String purpose, String ip) {
    settings.hmacKey();
    if (!mail.configured()) throw AuthFailure.unavailable();
    limiter.acquire(email, ip);
    var id = UUID.randomUUID();
    String code = ChallengeSecrets.code();
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    try {
      mapper.insert(
          id,
          email,
          purpose,
          ChallengeSecrets.hmac(settings, id, email, purpose, code),
          now,
          now.plusSeconds(settings.codeTtl()));
      mail.deliver(email, purpose, id, code, now.plusSeconds(settings.codeTtl()).toInstant());
      if (mapper.activate(id) != 1) throw AuthFailure.unavailable();
    } catch (RuntimeException ex) {
      throw AuthFailure.unavailable();
    }
    return new Challenge(id, settings.codeTtl(), settings.cooldown());
  }
}

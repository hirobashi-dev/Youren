package jp.youren.api.auth;

import java.util.Base64;
import org.springframework.core.env.Environment;

// 仅包含服务端参数；toString必须脱敏，环境密钥不进入默认record输出。
public record AuthSettings(
    String challengeSecret,
    long codeTtl,
    int attempts,
    long cooldown,
    int emailHourly,
    int emailDaily,
    int ipHourly,
    int ipDaily,
    long accessTtl,
    long refreshTtl,
    String termsVersion,
    String privacyVersion) {
  public static AuthSettings from(Environment e) {
    return new AuthSettings(
        e.getProperty("youren.auth.challenge-secret", ""),
        positive(e, "code-ttl", 600),
        (int) positive(e, "max-attempts", 5),
        positive(e, "cooldown", 60),
        (int) positive(e, "email-hourly", 5),
        (int) positive(e, "email-daily", 20),
        (int) positive(e, "ip-hourly", 30),
        (int) positive(e, "ip-daily", 100),
        positive(e, "access-ttl", 900),
        positive(e, "refresh-ttl", 2592000),
        e.getProperty("youren.auth.terms-version", "2026-10-04"),
        e.getProperty("youren.auth.privacy-version", "2026-10-04"));
  }

  public AuthSettings {
    if (accessTtl > refreshTtl
        || termsVersion == null
        || termsVersion.isBlank()
        || privacyVersion == null
        || privacyVersion.isBlank()) throw new IllegalArgumentException("认证配置不正确");
  }

  private static long positive(Environment e, String name, long fallback) {
    try {
      long value = Long.parseLong(e.getProperty("youren.auth." + name, Long.toString(fallback)));
      if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException();
      return value;
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("认证配置不正确");
    }
  }

  public byte[] hmacKey() {
    try {
      byte[] key = Base64.getUrlDecoder().decode(challengeSecret);
      if (key.length < 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(challengeSecret))
        throw AuthFailure.unavailable();
      return key;
    } catch (IllegalArgumentException ex) {
      throw AuthFailure.unavailable();
    }
  }

  @Override
  public String toString() {
    return "AuthSettings[secret=REDACTED]";
  }
}

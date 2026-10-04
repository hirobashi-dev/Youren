package jp.youren.api.email;

import java.time.*;
import java.util.*;
import jp.youren.api.auth.*;
import jp.youren.api.security.TokenSecrets;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

// 显式测试配置才提供隔离收件箱；验证码不输出日志，仅存有期限且有容量上限的内存。
@Service
@Profile("auth-test")
@Primary
public class TestEmailInbox implements EmailDelivery {
  private final Map<UUID, Entry> entries = new HashMap<>();
  private final Clock clock;
  private final String keyHash;
  private static final int CAPACITY = 1000;

  private record Entry(String code, Instant expiresAt) {
    @Override
    public String toString() {
      return "Entry[REDACTED]";
    }
  }

  public TestEmailInbox(Environment environment, AuthSettings settings, Clock clock) {
    if (Arrays.stream(environment.getActiveProfiles())
        .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production")))
      throw new IllegalArgumentException("测试邮件配置不可用于生产");
    String key = environment.getProperty("youren.auth.test-inbox-key");
    if (!SessionService.validToken(key)) throw new IllegalArgumentException("测试邮件密钥未正确配置");
    settings.hmacKey();
    // 调试读取权限与验证码验证密钥必须独立，单一密钥泄漏不能同时取得两类权限。
    if (TokenSecrets.matches(key, TokenSecrets.digest(settings.challengeSecret())))
      throw new IllegalArgumentException("测试邮件密钥必须独立配置");
    this.keyHash = TokenSecrets.digest(key);
    this.clock = clock;
  }

  @Override
  public synchronized void deliver(
      String email, String purpose, UUID id, String code, Instant expiresAt) {
    prune();
    if (entries.size() >= CAPACITY || !expiresAt.isAfter(clock.instant()))
      throw AuthFailure.unavailable();
    entries.put(id, new Entry(code, expiresAt));
  }

  public synchronized String read(UUID id, String key, String remoteAddress) {
    // 不信任转发头；只有本机连接与独立高熵密钥共同满足才可读取。
    if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remoteAddress))
      throw new AuthFailure(403, "FORBIDDEN");
    if (!SessionService.validToken(key) || !TokenSecrets.matches(key, keyHash))
      throw AuthFailure.unauthorized();
    prune();
    var entry = entries.get(id);
    if (entry == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    return entry.code();
  }

  private void prune() {
    Instant now = clock.instant();
    entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
  }
}

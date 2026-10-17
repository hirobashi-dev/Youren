package jp.youren.api.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import jp.youren.api.email.TestEmailInbox;
import jp.youren.api.security.TokenSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

// 验证真实HMAC及隔离收件箱边界；不会把低熵验证码当成普通摘要，也不会泄漏密钥。
class EmailChallengeServiceTest {
  static final String SECRET = TokenSecrets.generate();
  static final String INBOX_KEY = TokenSecrets.generate();

  MockEnvironment env() {
    return new MockEnvironment()
        .withProperty("youren.auth.challenge-secret", SECRET)
        .withProperty("youren.auth.test-inbox-key", INBOX_KEY);
  }

  // HMAC绑定挑战、邮箱和用途，改变任一项后不能复用；所有秘密对象toString脱敏。
  @Test
  void keyedChallengeIsBoundToContextAndSecretObjectsAreRedacted() {
    var settings = AuthSettings.from(env());
    UUID id = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    String hmac = ChallengeSecrets.hmac(settings, id, "a@example.com", "login", "123456");
    assertEquals(64, hmac.length());
    assertTrue(
        ChallengeSecrets.matches(
            hmac, ChallengeSecrets.hmac(settings, id, "a@example.com", "login", "123456")));
    assertNotEquals(
        hmac, ChallengeSecrets.hmac(settings, id, "a@example.com", "register", "123456"));
    assertNotEquals(hmac, ChallengeSecrets.hmac(settings, id, "b@example.com", "login", "123456"));
    assertNotEquals(
        hmac,
        ChallengeSecrets.hmac(settings, UUID.randomUUID(), "a@example.com", "login", "123456"));
    assertFalse(settings.toString().contains(SECRET));
    assertFalse(
        new SessionService.Tokens("access-secret", "refresh-secret")
            .toString()
            .contains("access-secret"));
  }

  // 缺少或低熵HMAC均安全拒绝，测试邮件配置不能与任一生产配置组合。
  @Test
  void missingKeysAndMixedProductionProfilesCannotActivateInbox() {
    assertThrows(AuthFailure.class, () -> AuthSettings.from(new MockEnvironment()).hmacKey());
    assertThrows(
        AuthFailure.class,
        () ->
            AuthSettings.from(
                    new MockEnvironment().withProperty("youren.auth.challenge-secret", "short"))
                .hmacKey());
    for (String profile : List.of("prod", "production", "PROD")) {
      var env = env();
      env.setActiveProfiles("auth-test", profile);
      assertThrows(
          IllegalArgumentException.class,
          () -> new TestEmailInbox(env, AuthSettings.from(env), Clock.systemUTC()));
    }
    var missing = env();
    missing.setProperty("youren.auth.test-inbox-key", "");
    assertThrows(
        IllegalArgumentException.class,
        () -> new TestEmailInbox(missing, AuthSettings.from(missing), Clock.systemUTC()));
  }

  // 非本机即使有密钥仍拒绝；到期边界立即不可读，不能经转发头伪造本机。
  @Test
  void inboxDeniesRemoteConnectionsAndExpiredCodes() {
    var env = env();
    Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
    var inbox = new TestEmailInbox(env, AuthSettings.from(env), clock);
    UUID id = UUID.randomUUID();
    inbox.deliver("a@example.com", "login", id, "123456", clock.instant().plusSeconds(600));
    assertEquals("123456", inbox.read(id, INBOX_KEY, "127.0.0.1"));
    assertEquals(
        403, assertThrows(AuthFailure.class, () -> inbox.read(id, INBOX_KEY, "10.0.2.2")).status());
    assertEquals(
        401,
        assertThrows(AuthFailure.class, () -> inbox.read(id, TokenSecrets.generate(), "127.0.0.1"))
            .status());
    assertThrows(
        AuthFailure.class,
        () ->
            inbox.deliver("a@example.com", "login", UUID.randomUUID(), "123456", clock.instant()));
  }

  // 内存容量有上限，不会随着测试运行无界增长；到期条目释放容量且不能再读取。
  @Test
  void inboxCapacityIsBoundedAndExpiredEntriesArePruned() {
    var env = env();
    var clock = new AuthFlowIT.TestClock();
    var inbox = new TestEmailInbox(env, AuthSettings.from(env), clock);
    UUID first = UUID.randomUUID();
    inbox.deliver("a@example.com", "login", first, "123456", clock.instant().plusSeconds(600));
    for (int i = 1; i < 1000; i++)
      inbox.deliver(
          "a@example.com", "login", UUID.randomUUID(), "123456", clock.instant().plusSeconds(600));
    assertThrows(
        AuthFailure.class,
        () ->
            inbox.deliver(
                "a@example.com",
                "login",
                UUID.randomUUID(),
                "123456",
                clock.instant().plusSeconds(600)));
    clock.advance(600);
    assertEquals(
        404,
        assertThrows(AuthFailure.class, () -> inbox.read(first, INBOX_KEY, "127.0.0.1")).status());
    inbox.deliver(
        "a@example.com", "login", UUID.randomUUID(), "123456", clock.instant().plusSeconds(600));
  }

  // Spring配置层也不能把测试入口带入生产，缺密钥即启动失败，而普通配置没有收件箱Bean。
  @Test
  void springProfileAndStartupGuardsFailClosed() {
    var runner =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(TestEmailInbox.class)
            .withBean(AuthSettings.class, () -> AuthSettings.from(env()))
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("youren.auth.test-inbox-key=" + INBOX_KEY);
    runner.run(context -> assertEquals(0, context.getBeansOfType(TestEmailInbox.class).size()));
    for (String production : List.of("prod", "production"))
      runner
          .withInitializer(
              context -> context.getEnvironment().setActiveProfiles("auth-test", production))
          .run(context -> assertNotNull(context.getStartupFailure()));
    runner
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("auth-test"))
        .withPropertyValues("youren.auth.test-inbox-key=")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  // 测试收件箱密钥必须独立，复用HMAC密钥会扩大泄漏后的攻击范围，启动需拒绝。
  @Test
  void inboxKeyCannotReuseChallengeHmacKey() {
    var reused = env().withProperty("youren.auth.test-inbox-key", SECRET);
    assertThrows(
        IllegalArgumentException.class,
        () -> new TestEmailInbox(reused, AuthSettings.from(reused), Clock.systemUTC()));
  }
}

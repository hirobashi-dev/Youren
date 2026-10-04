package jp.youren.api.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import jp.youren.api.ApiApplication;
import jp.youren.api.email.EmailDelivery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 真正HTTP、PostgreSQL行锁及Redis Lua覆盖安全边界；邮件仅在测试内存中保存，不对外开放。
@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(
    classes = {ApiApplication.class, AuthFlowIT.Fakes.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

  static final String POLICY = "2026-10-04";

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
    r.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
    r.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
    r.add(
        "SPRING_DATA_REDIS_URL",
        () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    // 每次运行生成隔离密钥，不能复制为生产设置。
    r.add("youren.auth.challenge-secret", () -> TEST_SECRET);
  }

  static final String TEST_SECRET = jp.youren.api.security.TokenSecrets.generate();

  static final class TestClock extends Clock {
    volatile Instant instant = Instant.parse("2026-10-04T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return instant;
    }

    void advance(long seconds) {
      instant = instant.plusSeconds(seconds);
    }
  }

  static final class Inbox implements EmailDelivery {
    final Map<UUID, String> codes = new ConcurrentHashMap<>();
    final Map<UUID, String> recipients = new ConcurrentHashMap<>();
    final Map<UUID, String> purposes = new ConcurrentHashMap<>();
    final Map<UUID, Instant> expiries = new ConcurrentHashMap<>();
    volatile boolean fail;

    public void deliver(String email, String purpose, UUID id, String code, Instant expiresAt) {
      if (fail) throw new IllegalStateException("mail-secret-detail");
      codes.put(id, code);
      recipients.put(id, email);
      purposes.put(id, purpose);
      expiries.put(id, expiresAt);
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    Inbox inbox() {
      return new Inbox();
    }

    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource source;
  @Autowired StringRedisTemplate redis;
  @Autowired Inbox inbox;
  @Autowired TestClock clock;

  @BeforeEach
  void setup() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            c -> {
              c.serverCommands().flushDb();
              return null;
            });
    inbox.codes.clear();
    inbox.recipients.clear();
    inbox.purposes.clear();
    inbox.expiries.clear();
    inbox.fail = false;
    clock.instant = Instant.parse("2026-10-04T00:00:00Z");
  }

  HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
    return request(path, body, null);
  }

  HttpResponse<String> request(String path, Map<String, ?> body, String token) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var builder =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(8));
      if (token != null) builder.header("Authorization", "Bearer " + token);
      if (body != null)
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  JsonNode data(HttpResponse<String> response) throws Exception {
    return json.readTree(response.body()).get("data");
  }

  UUID challenge(String email, String purpose) throws Exception {
    var response = post("/v1/auth/email-code", Map.of("email", email, "purpose", purpose));
    assertEquals(202, response.statusCode(), response.body());
    var result = data(response);
    assertEquals(3, result.size());
    assertEquals(600, result.get("expiresIn").asInt());
    assertEquals(60, result.get("retryAfter").asInt());
    assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    return UUID.fromString(result.get("challengeId").asText());
  }

  Map<String, Object> registration(UUID id, String code) {
    return Map.of(
        "challengeId",
        id.toString(),
        "code",
        code,
        "termsVersion",
        POLICY,
        "privacyVersion",
        POLICY);
  }

  JsonNode register(String email) throws Exception {
    var id = challenge(email, "register");
    var response = post("/v1/auth/register", registration(id, inbox.codes.get(id)));
    assertEquals(201, response.statusCode(), response.body());
    return data(response);
  }

  int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  // 正确验证码一次注册创建账号、默认设置、主体、条款、会话；返回严格Me且数据库无秘密。
  @Test
  void registrationIsAtomicAndReturnsExactAccountContract() throws Exception {
    var result = register("Mixed@Example.COM");
    assertEquals(5, result.size());
    var me = result.get("account");
    assertEquals(8, me.size());
    assertEquals("mixed@example.com", me.get("email").asText());
    assertEquals("active", me.get("status").asText());
    assertTrue(me.get("profile").isNull());
    assertTrue(me.get("profileVersion").isNull());
    assertTrue(me.get("onboardingRequired").asBoolean());
    assertEquals("not_declared", me.at("/ageDeclaration/status").asText());
    assertTrue(me.at("/ageDeclaration/declaredAt").isNull());
    assertTrue(me.at("/settings/allowStrangerDm").asBoolean());
    assertTrue(me.at("/settings/pushEnabled").asBoolean());
    assertFalse(me.at("/settings/lockscreenPreview").asBoolean());
    assertEquals("not_requested", result.get("claimStatus").asText());
    for (String table :
        List.of("accounts", "account_settings", "principals", "consent_records", "sessions"))
      assertEquals(1, count(table));
    assertEquals(
        POLICY, jdbc.queryForObject("SELECT terms_version FROM consent_records", String.class));
    for (String name : List.of("accessToken", "refreshToken")) {
      String token = result.get(name).asText();
      assertTrue(Base64.getUrlDecoder().decode(token).length >= 32);
      String hash =
          jdbc.queryForObject(
              "SELECT "
                  + (name.equals("accessToken") ? "access_hash" : "refresh_hash")
                  + " FROM sessions",
              String.class);
      assertEquals(64, hash.length());
      assertNotEquals(token, hash);
    }
  }

  // 错误尝试必须提交；第五次后正确验证码也失败，不能通过异常回滚绕过锁定。
  @Test
  void fiveWrongAttemptsPersistAndLockOutCorrectCode() throws Exception {
    var id = challenge("attempt@example.com", "register");
    String correct = inbox.codes.get(id);
    String wrong = correct.equals("000000") ? "000001" : "000000";
    for (int i = 0; i < 5; i++)
      assertEquals(401, post("/v1/auth/register", registration(id, wrong)).statusCode());
    assertEquals(5, jdbc.queryForObject("SELECT attempts FROM email_challenges", Integer.class));
    assertEquals(401, post("/v1/auth/register", registration(id, correct)).statusCode());
    assertEquals(0, count("accounts"));
  }

  // 用途、过期、重复消费和未知挑战均拒绝；不能将登录验证码用于创建账号。
  @Test
  void wrongPurposeExpiredAndReusedChallengeCannotAuthenticate() throws Exception {
    var login = challenge("purpose@example.com", "login");
    assertEquals(
        401, post("/v1/auth/register", registration(login, inbox.codes.get(login))).statusCode());
    var expired = challenge("expire@example.com", "register");
    clock.advance(600);
    assertEquals(
        401,
        post("/v1/auth/register", registration(expired, inbox.codes.get(expired))).statusCode());
    var id = challenge("reuse@example.com", "register");
    assertEquals(
        201, post("/v1/auth/register", registration(id, inbox.codes.get(id))).statusCode());
    assertEquals(
        401, post("/v1/auth/register", registration(id, inbox.codes.get(id))).statusCode());
    assertEquals(
        401, post("/v1/auth/register", registration(UUID.randomUUID(), "123456")).statusCode());
  }

  // 客户端不能任选条款版本；正确验证码仍保留，以允许用户接受服务端要求后再试。
  @Test
  void serverRequiresCurrentPolicyBeforeAccountCreation() throws Exception {
    var id = challenge("policy@example.com", "register");
    var body = new HashMap<>(registration(id, inbox.codes.get(id)));
    body.put("termsVersion", "old");
    assertEquals(422, post("/v1/auth/register", body).statusCode());
    assertEquals(0, count("accounts"));
    assertEquals(0, count("consent_records"));
    assertEquals(
        201, post("/v1/auth/register", registration(id, inbox.codes.get(id))).statusCode());
  }

  // 同意记录写入错误时，账号、设置、会话及验证码消费一起回滚，不留下半注册身份。
  @Test
  void consentFailureRollsBackWholeRegistrationIncludingConsumption() throws Exception {
    var id = challenge("rollback@example.com", "register");
    jdbc.execute(
        "CREATE FUNCTION reject_consent() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'internal-secret'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_consent BEFORE INSERT ON consent_records FOR EACH ROW EXECUTE FUNCTION reject_consent()");
    var response = post("/v1/auth/register", registration(id, inbox.codes.get(id)));
    assertEquals(500, response.statusCode());
    assertFalse(response.body().contains("internal-secret"));
    for (String table :
        List.of("accounts", "principals", "account_settings", "sessions", "consent_records"))
      assertEquals(0, count(table));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM email_challenges WHERE consumed_at IS NOT NULL", Integer.class));
  }

  // 已存在账号转登录，不覆盖设置、资料或插入虚假条款记录；未知邮箱登录不创建账号。
  @Test
  void existingRegistrationAuthenticatesWithoutOverwritingAndUnknownLoginFails() throws Exception {
    var initial = register("existing@example.com");
    jdbc.update("UPDATE account_settings SET push_enabled=false");
    redis.delete(redis.keys("youren:auth:rate:*"));
    var id = challenge("existing@example.com", "register");
    var response = post("/v1/auth/register", registration(id, inbox.codes.get(id)));
    assertEquals(200, response.statusCode());
    assertEquals(initial.at("/account/id"), data(response).at("/account/id"));
    assertFalse(data(response).at("/account/settings/pushEnabled").asBoolean());
    assertEquals(1, count("consent_records"));
    var unknown = challenge("unknown@example.com", "login");
    assertEquals(
        401,
        post(
                "/v1/auth/login",
                Map.of("challengeId", unknown.toString(), "code", inbox.codes.get(unknown)))
            .statusCode());
    assertEquals(1, count("accounts"));
  }

  // 同一正确验证码并发提交，仅一个事务成功，其余不能创建第二账号或会话。
  @Test
  void concurrentChallengeConsumptionSucceedsOnlyOnce() throws Exception {
    var id = challenge("concurrent@example.com", "register");
    var body = registration(id, inbox.codes.get(id));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Integer> action =
          () -> {
            gate.await();
            return post("/v1/auth/register", body).statusCode();
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      gate.countDown();
      var statuses = new ArrayList<>(List.of(a.get(), b.get()));
      Collections.sort(statuses);
      assertEquals(List.of(201, 401), statuses);
    }
    assertEquals(1, count("accounts"));
    assertEquals(1, count("sessions"));
  }

  // 不同验证码、同一规范化邮箱并发注册，用邮箱锁避免唯一索引异常和覆盖。
  @Test
  void concurrentSeparateChallengesForSameEmailReturnOneCreationOneLogin() throws Exception {
    var a = challenge("same@example.com", "register");
    redis.delete(redis.keys("youren:auth:rate:*"));
    var b = challenge("SAME@example.com", "register");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var x =
          pool.submit(
              () -> {
                gate.await();
                return post("/v1/auth/register", registration(a, inbox.codes.get(a))).statusCode();
              });
      var y =
          pool.submit(
              () -> {
                gate.await();
                return post("/v1/auth/register", registration(b, inbox.codes.get(b))).statusCode();
              });
      gate.countDown();
      var statuses = new ArrayList<>(List.of(x.get(), y.get()));
      Collections.sort(statuses);
      assertEquals(List.of(200, 201), statuses);
    }
    assertEquals(1, count("accounts"));
    assertEquals(1, count("consent_records"));
    assertEquals(2, count("sessions"));
  }

  // 发送不区分账号是否存在，成功形状一致且不能含验证码；冷却与邮箱小时上限确实执行。
  @Test
  void emailSendIsUniformAndLimitsAreAtomic() throws Exception {
    register("known@example.com");
    redis.delete(redis.keys("youren:auth:rate:*"));
    var first = challenge("known@example.com", "login");
    var second = challenge("unknown@example.com", "login");
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(
        429,
        post("/v1/auth/email-code", Map.of("email", "known@example.com", "purpose", "login"))
            .statusCode());
    // 单独让冷却键到期，小时及日计数不能被冷却清除。
    for (int i = 0; i < 4; i++) {
      redis.delete(redis.keys("youren:auth:rate:*:cooldown"));
      challenge("known@example.com", "login");
    }
    redis.delete(redis.keys("youren:auth:rate:*:cooldown"));
    assertEquals(
        429,
        post("/v1/auth/email-code", Map.of("email", "known@example.com", "purpose", "login"))
            .statusCode());
    var keys = redis.keys("youren:auth:rate:*");
    assertTrue(keys.stream().noneMatch(k -> k.contains("known") || k.contains("127.0.0.1")));
  }

  // 所有IP窗口共享原子配额；伪造转发头不能绕过服务端IP，且并发总数不得超30。
  @Test
  void ipHourlyBudgetCannotBeBypassedByForwardedHeadersOrConcurrency() throws Exception {
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 34; i++) {
        final int number = i;
        tasks.add(
            () ->
                post(
                        "/v1/auth/email-code",
                        Map.of("email", "ip" + number + "@example.com", "purpose", "login"))
                    .statusCode());
      }
      int accepted = 0;
      for (var result : pool.invokeAll(tasks)) {
        int status = result.get();
        assertTrue(status == 202 || status == 429);
        if (status == 202) accepted++;
      }
      assertEquals(30, accepted);
      assertEquals(30, count("email_challenges"));
    }
    try (var http = HttpClient.newHttpClient()) {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/auth/email-code"))
                  .header("Content-Type", "application/json")
                  .header("X-Forwarded-For", "203.0.113.99")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"email\":\"forward@example.com\",\"purpose\":\"login\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(429, response.statusCode());
    }
  }

  // 邮件故障返回安全503，失败挑战不可消费；验证码摘要必须是带服务器秘密的HMAC。
  @Test
  void failedDeliveryLeavesNoUsableChallengeAndNeverReturnsSecrets() throws Exception {
    inbox.fail = true;
    var response =
        post("/v1/auth/email-code", Map.of("email", "mail@example.com", "purpose", "register"));
    assertEquals(503, response.statusCode());
    assertFalse(response.body().contains("mail-secret-detail"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM email_challenges WHERE consumed_at IS NULL", Integer.class));
    inbox.fail = false;
    redis.delete(redis.keys("youren:auth:rate:*"));
    var id = challenge("hmac@example.com", "register");
    String stored =
        jdbc.queryForObject("SELECT code_hmac FROM email_challenges WHERE id=?", String.class, id);
    assertEquals(64, stored.length());
    assertNotEquals(jp.youren.api.security.TokenSecrets.digest(inbox.codes.get(id)), stored);
  }

  // 刷新使旧访问失效；重放旧刷新令牌需提交整个会话族撤销，不能被401异常回滚。
  @Test
  void rotationAndReplayRevokeAllTokensPersistently() throws Exception {
    var auth = register("rotate@example.com");
    String oldAccess = auth.get("accessToken").asText();
    String oldRefresh = auth.get("refreshToken").asText();
    var response = post("/v1/auth/refresh", Map.of("refreshToken", oldRefresh));
    assertEquals(200, response.statusCode());
    var next = data(response);
    assertEquals(2, next.size());
    assertNotEquals(oldRefresh, next.get("refreshToken").asText());
    assertEquals(401, request("/v1/me", null, oldAccess).statusCode());
    assertEquals(401, post("/v1/auth/refresh", Map.of("refreshToken", oldRefresh)).statusCode());
    assertEquals(401, request("/v1/me", null, next.get("accessToken").asText()).statusCode());
    assertEquals(
        401,
        post("/v1/auth/refresh", Map.of("refreshToken", next.get("refreshToken").asText()))
            .statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM sessions WHERE revoked_at IS NULL", Integer.class));
  }

  // 并发重放仅一次刷新成功，随后成功响应里的后继也必须撤销，不允许活跃族逃逸。
  @Test
  void concurrentRefreshReplayRevokesTheSuccessor() throws Exception {
    var auth = register("replay@example.com");
    String token = auth.get("refreshToken").asText();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<HttpResponse<String>> action =
          () -> {
            gate.await();
            return post("/v1/auth/refresh", Map.of("refreshToken", token));
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      gate.countDown();
      var x = a.get();
      var y = b.get();
      var statuses = new ArrayList<>(List.of(x.statusCode(), y.statusCode()));
      Collections.sort(statuses);
      assertEquals(List.of(200, 401), statuses);
      var successful = data(x.statusCode() == 200 ? x : y);
      assertEquals(
          401,
          post("/v1/auth/refresh", Map.of("refreshToken", successful.get("refreshToken").asText()))
              .statusCode());
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM sessions WHERE revoked_at IS NULL", Integer.class));
  }

  // 退出按已认证会话族撤销；访问与刷新均即时拒绝，其他独立登录会话不受影响。
  @Test
  void logoutRevokesCurrentFamilyButNotIndependentSession() throws Exception {
    var auth = register("logout@example.com");
    redis.delete(redis.keys("youren:auth:rate:*"));
    var id = challenge("logout@example.com", "login");
    var other =
        post("/v1/auth/login", Map.of("challengeId", id.toString(), "code", inbox.codes.get(id)));
    assertEquals(200, other.statusCode());
    var logout = request("/v1/auth/logout", Map.of(), auth.get("accessToken").asText());
    assertEquals(204, logout.statusCode());
    assertEquals(401, request("/v1/me", null, auth.get("accessToken").asText()).statusCode());
    assertEquals(
        401,
        post("/v1/auth/refresh", Map.of("refreshToken", auth.get("refreshToken").asText()))
            .statusCode());
    assertEquals(
        200,
        post("/v1/auth/refresh", Map.of("refreshToken", data(other).get("refreshToken").asText()))
            .statusCode());
  }

  // 访问到期15分钟边界，刷新到期30天边界，账号受限或删除都不能继续认证。
  @Test
  void expiryAndAccountLifecyclePreventAuthentication() throws Exception {
    var auth = register("expiry@example.com");
    clock.advance(900);
    assertEquals(401, request("/v1/me", null, auth.get("accessToken").asText()).statusCode());
    clock.advance(2592000 - 900);
    assertEquals(
        401,
        post("/v1/auth/refresh", Map.of("refreshToken", auth.get("refreshToken").asText()))
            .statusCode());
    clock.instant = Instant.parse("2026-10-04T00:00:00Z");
    redis.delete(redis.keys("youren:auth:rate:*"));
    var other = register("restricted@example.com");
    jdbc.update(
        "UPDATE accounts SET status='restricted' WHERE email_normalized='restricted@example.com'");
    assertEquals(
        403,
        post("/v1/auth/refresh", Map.of("refreshToken", other.get("refreshToken").asText()))
            .statusCode());
    redis.delete(redis.keys("youren:auth:rate:*"));
    var id = challenge("restricted@example.com", "login");
    assertEquals(
        403,
        post("/v1/auth/login", Map.of("challengeId", id.toString(), "code", inbox.codes.get(id)))
            .statusCode());
    jdbc.update("UPDATE accounts SET status='deleted'");
    assertEquals(
        401,
        post("/v1/auth/refresh", Map.of("refreshToken", other.get("refreshToken").asText()))
            .statusCode());
  }

  // 未实现游客接续必须独立失败且保留登录结果，不能伪造接续成功。
  @Test
  void optionalGuestClaimFailureNeverDiscardsAuthenticatedSession() throws Exception {
    var guest = data(post("/v1/guest-sessions", Map.of()));
    var id = challenge("claim@example.com", "register");
    var body = new HashMap<>(registration(id, inbox.codes.get(id)));
    body.put("guestToken", guest.get("guestToken").asText());
    var response = post("/v1/auth/register", body);
    assertEquals(201, response.statusCode());
    assertEquals("failed", data(response).get("claimStatus").asText());
    assertEquals(
        200,
        post(
                "/v1/auth/refresh",
                Map.of("refreshToken", data(response).get("refreshToken").asText()))
            .statusCode());
  }

  // DTO拒绝未知字段、格式错误、空值及未来删除用途；错误不包含原秘密。
  @Test
  void validationRejectsMalformedAndUnknownFieldsSafely() throws Exception {
    for (var body :
        List.of(
            Map.of("email", "bad", "purpose", "login"),
            Map.of("email", "a@example.com", "purpose", "delete_account"),
            Map.of("email", "a@example.com", "purpose", "login", "accountId", "forged"),
            Map.of("email", "", "purpose", "login")))
      assertEquals(422, post("/v1/auth/email-code", body).statusCode());
    assertEquals(
        422,
        post(
                "/v1/auth/register",
                Map.of(
                    "challengeId",
                    "bad",
                    "code",
                    "secret-code",
                    "termsVersion",
                    POLICY,
                    "privacyVersion",
                    POLICY))
            .statusCode());
    assertEquals(
        422,
        post("/v1/auth/login", Map.of("challengeId", UUID.randomUUID().toString(), "code", "12345"))
            .statusCode());
    assertEquals(422, post("/v1/auth/refresh", Map.of("refreshToken", "raw-secret")).statusCode());
    assertEquals(
        401,
        post(
                "/v1/auth/refresh",
                Map.of("refreshToken", jp.youren.api.security.TokenSecrets.generate()))
            .statusCode());
  }

  // 邮箱日上限跨小时窗口保留；被拒绝请求不能提前消耗IP配额。
  @Test
  void dailyEmailBudgetSurvivesHourlyWindowReset() throws Exception {
    for (int i = 0; i < 20; i++) {
      redis.delete(redis.keys("youren:auth:rate:*:cooldown"));
      if (i % 5 == 0) redis.delete(redis.keys("youren:auth:rate:*:hour"));
      challenge("daily@example.com", "login");
    }
    redis.delete(redis.keys("youren:auth:rate:*:cooldown"));
    redis.delete(redis.keys("youren:auth:rate:*:hour"));
    var rejected =
        post("/v1/auth/email-code", Map.of("email", "daily@example.com", "purpose", "login"));
    assertEquals(429, rejected.statusCode());
    assertTrue(Long.parseLong(rejected.headers().firstValue("Retry-After").orElse("0")) > 0);
    assertEquals(20, count("email_challenges"));
    var ipDay = redis.keys("youren:auth:rate:i:*:day").iterator().next();
    assertEquals("20", redis.opsForValue().get(ipDay));
  }

  // IP每日100次上限在小时重置后继续生效，不同邮箱也不能逃避总量限制。
  @Test
  void dailyIpBudgetSurvivesHourlyWindowReset() throws Exception {
    for (int i = 0; i < 100; i++) {
      if (i % 30 == 0) redis.delete(redis.keys("youren:auth:rate:*:hour"));
      challenge("dailyip" + i + "@example.com", "login");
    }
    redis.delete(redis.keys("youren:auth:rate:*:hour"));
    assertEquals(
        429,
        post("/v1/auth/email-code", Map.of("email", "dailyip-over@example.com", "purpose", "login"))
            .statusCode());
    assertEquals(100, count("email_challenges"));
  }

  // Redis暂时停止响应时发送安全503且不创建挑战，恢复后可用，不允许故障绕过配额。
  @Test
  void redisOutageFailsClosedWithoutCreatingChallenge() throws Exception {
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    try {
      var response =
          post("/v1/auth/email-code", Map.of("email", "outage@example.com", "purpose", "register"));
      assertEquals(503, response.statusCode());
      assertFalse(response.body().contains("outage@example.com"));
      assertEquals(0, count("email_challenges"));
    } finally {
      REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
    challenge("recovered@example.com", "register");
  }

  // 客户端读取安全公开策略，不硬编码服务端条款版本；响应不能夹带HMAC或邮件密钥。
  @Test
  void publicPolicyExposesOnlyRequiredVersions() throws Exception {
    var response = request("/v1/auth/policy", null, null);
    assertEquals(200, response.statusCode());
    var policy = data(response);
    assertEquals(2, policy.size());
    assertEquals(POLICY, policy.get("termsVersion").asText());
    assertEquals(POLICY, policy.get("privacyVersion").asText());
    assertFalse(response.body().contains(TEST_SECRET));
  }

  // 外部邮件边界必须收到规范邮箱、正确用途和挑战实际到期时间，不能误投或延长有效期。
  @Test
  void deliveryReceivesNormalizedRecipientPurposeAndAuthoritativeExpiry() throws Exception {
    UUID id = challenge("Mail.Target@Example.COM", "register");
    assertEquals("mail.target@example.com", inbox.recipients.get(id));
    assertEquals("register", inbox.purposes.get(id));
    assertTrue(inbox.codes.get(id).matches("[0-9]{6}"));
    assertEquals(Instant.parse("2026-10-04T00:10:00Z"), inbox.expiries.get(id));
    assertEquals(
        inbox.expiries.get(id),
        jdbc.queryForObject(
                "SELECT expires_at FROM email_challenges WHERE id=?", OffsetDateTime.class, id)
            .toInstant());
  }
}

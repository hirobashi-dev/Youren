package jp.youren.api.profile;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jp.youren.api.ApiApplication;
import jp.youren.api.auth.*;
import jp.youren.api.email.EmailDelivery;
import jp.youren.api.guest.GuestSessionService;
import jp.youren.database.mapper.IdentityAccountMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 资料与接续使用真实HTTP、临时数据库与Redis；只替换外部邮件供应商。
@Testcontainers(disabledWithoutDocker = false)
// 每个测试类的容器都会重新映射端口，禁止复用已关闭容器的Spring连接池。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {ApiApplication.class, ProfileTestSupport.MailConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ProfileTestSupport {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

  static final String SECRET = jp.youren.api.security.TokenSecrets.generate();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
    r.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
    r.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
    r.add(
        "SPRING_DATA_REDIS_URL",
        () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    r.add("youren.auth.challenge-secret", () -> SECRET);
  }

  public static class Inbox implements EmailDelivery {
    final Map<UUID, String> codes = new ConcurrentHashMap<>();

    public void deliver(String email, String purpose, UUID id, String code, Instant expiry) {
      codes.put(id, code);
    }
  }

  @TestConfiguration
  public static class MailConfig {
    @Bean
    @Primary
    Inbox inbox() {
      return new Inbox();
    }
  }

  @LocalServerPort protected int port;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource source;
  @Autowired protected GuestSessionService guests;
  @Autowired IdentityAccountMapper accounts;
  @Autowired SessionService sessions;
  @Autowired protected AuthenticationService authentication;
  @Autowired EmailChallengeService challenges;
  @Autowired Inbox inbox;

  protected record User(UUID id, String token, String email) {}

  @BeforeEach
  void prepare() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
  }

  protected User user() {
    UUID id = UUID.randomUUID();
    String email = id + "@example.com";
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    accounts.insert(id, email, now);
    accounts.initializeSettings(id, now);
    accounts.initializePrincipal(UUID.randomUUID(), id, now);
    return new User(id, sessions.create(id, UUID.randomUUID()).accessToken(), email);
  }

  protected HttpResponse<String> request(
      String method, String path, Object body, String token, String header, String value)
      throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var b =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(12));
      if (token != null) b.header("Authorization", "Bearer " + token);
      if (header != null) b.header(header, value);
      if (body != null) b.header("Content-Type", "application/json");
      b.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  protected JsonNode data(HttpResponse<String> r) throws Exception {
    return json.readTree(r.body()).get("data");
  }

  protected HttpResponse<String> claim(User user, String token, String key) throws Exception {
    return request(
        "POST",
        "/v1/me/guest-claims",
        Map.of("guestToken", token),
        user.token(),
        "Idempotency-Key",
        key);
  }

  protected HttpResponse<String> patch(User user, Object body, String version) throws Exception {
    return request(
        "PATCH",
        "/v1/me/profile",
        body,
        user.token(),
        version == null ? null : "If-Match",
        version);
  }

  protected AuthenticationService.Result login(User user, String guestToken) {
    var challenge = challenges.send(user.email(), "login", "127.0.0.1");
    return authentication.authenticate(
        challenge.challengeId(),
        inbox.codes.get(challenge.challengeId()),
        "login",
        null,
        null,
        guestToken);
  }
}

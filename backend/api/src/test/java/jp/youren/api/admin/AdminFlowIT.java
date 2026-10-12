package jp.youren.api.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import jp.youren.api.ApiApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 真实HTTP/PostgreSQL验证管理员MFA、角色边界、人工审核与举报不会互相隐式处置。
@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminFlowIT {
  private static final String MFA_KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String MFA_SECRET = "JBSWY3DPEHPK3PXP";
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");

  @DynamicPropertySource
  static void dependencies(DynamicPropertyRegistry registry) {
    registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
    registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
    registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
    registry.add("SPRING_DATA_REDIS_URL", () -> "redis://127.0.0.1:1");
    registry.add("YOUREN_ADMIN_MFA_ENCRYPTION_KEY", () -> MFA_KEY);
    registry.add(
        "YOUREN_MEDIA_ROOT",
        () -> Path.of("target", "admin-flow-media").toAbsolutePath().toString());
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired ObjectMapper json;
  @Autowired AdminBootstrapService bootstrap;
  @Autowired jp.youren.database.mapper.AdminMapper adminMapper;
  @Autowired jp.youren.api.media.StoragePort storage;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void prepare() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  // 缺少管理令牌与普通随机用户令牌都不能读取后台，审计员可以读但不能作决定。
  @Test
  void separatesAdminSessionsAndRejectsAuditorWrites() throws Exception {
    var unknownLogin =
        post(
            "/v1/admin/auth/login",
            null,
            null,
            "{\"loginName\":\"unknown-admin\",\"password\":\"a-long-local-admin-password\"}");
    assertEquals(401, unknownLogin.statusCode());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM admin_login_challenges", Integer.class));
    var denied = get("/v1/admin/reports", null);
    assertEquals(401, denied.statusCode());
    var userToken = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    assertEquals(401, get("/v1/admin/reports", userToken).statusCode());

    UUID auditor = addAdmin("audit-reader", "auditor");
    String token = login("audit-reader");
    var listed = get("/v1/admin/reports", token);
    assertEquals(200, listed.statusCode(), listed.body());
    assertEquals(
        403,
        post(
                "/v1/admin/moderation-actions",
                token,
                UUID.randomUUID().toString(),
                "{\"targetType\":\"post\",\"targetId\":\""
                    + UUID.randomUUID()
                    + "\",\"action\":\"approve\",\"reason\":\"人工复核\",\"expectedVersion\":1}")
            .statusCode());
    assertEquals(
        "auditor",
        jdbc.queryForObject("SELECT role FROM admin_accounts WHERE id=?", String.class, auditor));
  }

  // 首位管理员只能创建一次；第二次初始化不覆盖已经建立的管理身份。
  @Test
  void bootstrapsOneReviewerOnlyOnce() {
    String code = Totp.code(MFA_SECRET, Instant.now().getEpochSecond() / 30);
    bootstrap.createFirstReviewer(
        "first-reviewer", "first-admin-local-password".toCharArray(), MFA_SECRET, code);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_accounts", Integer.class));
    assertThrows(
        RuntimeException.class,
        () ->
            bootstrap.createFirstReviewer(
                "second-reviewer", "second-admin-local-password".toCharArray(), MFA_SECRET, code));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_accounts", Integer.class));
  }

  // 禁用账号不得创建MFA挑战，登录响应不得泄露账号是否存在等凭据状态。
  @Test
  void disabledAdministratorCannotStartLoginChallenge() throws Exception {
    UUID disabled = addAdmin("disabled-admin", "reviewer");
    jdbc.update("UPDATE admin_accounts SET status='disabled' WHERE id=?", disabled);
    var response =
        post(
            "/v1/admin/auth/login",
            null,
            null,
            "{\"loginName\":\"disabled-admin\",\"password\":\"a-long-local-admin-password\"}");
    assertEquals(401, response.statusCode());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM admin_login_challenges", Integer.class));
    assertFalse(response.body().contains("disabled-admin"));
  }

  // 只有人工审核动作能公开帖子；举报重复提交幂等，结案只关闭举报不修改目标状态。
  @Test
  void publishesByHumanDecisionAndResolvesReportWithoutChangingContent() throws Exception {
    addAdmin("reviewer", "reviewer");
    String reviewer = login("reviewer");
    var guest = post("/v1/guest-sessions", null, null, "");
    assertEquals(201, guest.statusCode());
    String guestToken = json.readTree(guest.body()).path("data").path("guestToken").asText();
    var postResponse =
        post(
            "/v1/posts",
            null,
            UUID.randomUUID().toString(),
            "{\"title\":\"测试标题\",\"body\":\"测试正文\",\"category\":\"general\"}",
            guestToken);
    assertEquals(201, postResponse.statusCode());
    UUID postId =
        UUID.fromString(json.readTree(postResponse.body()).path("data").path("id").asText());

    UUID mediaId = addPendingDisplayImage(postId);
    String reviewImageKey = adminMapper.adminReviewImageKey(mediaId);
    assertNotNull(reviewImageKey);
    try (var storedImage =
        storage.open(jp.youren.api.media.StoragePort.Area.DISPLAY, reviewImageKey)) {
      assertEquals(
          "display",
          new String(storedImage.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    var queue = get("/v1/admin/content?status=pending&targetType=post", reviewer);
    assertEquals(200, queue.statusCode(), queue.body());
    String imageUrl =
        json.readTree(queue.body()).path("data").get(0).path("images").get(0).path("url").asText();
    URI imageUri = URI.create(imageUrl);
    assertEquals(401, get(imageUri.getRawPath() + "?" + imageUri.getRawQuery(), null).statusCode());
    assertEquals(404, get("/v1/media/" + mediaId + "/display", null).statusCode());
    var preview = get(imageUri.getRawPath() + "?" + imageUri.getRawQuery(), reviewer);
    assertEquals(200, preview.statusCode(), preview.body());
    assertEquals("display", preview.body());

    var approve =
        post(
            "/v1/admin/moderation-actions",
            reviewer,
            UUID.randomUUID().toString(),
            "{\"targetType\":\"post\",\"targetId\":\""
                + postId
                + "\",\"action\":\"approve\",\"reason\":\"人工检查通过\",\"expectedVersion\":1}");
    assertEquals(201, approve.statusCode());
    assertEquals(
        "visible",
        jdbc.queryForObject("SELECT status FROM posts WHERE id=?", String.class, postId));

    var reportBody =
        "{\"targetType\":\"post\",\"targetId\":\""
            + postId
            + "\",\"reasonCode\":\"spam\",\"detail\":\"请审核\"}";
    String reportKey = UUID.randomUUID().toString();
    var report = post("/v1/reports", null, reportKey, reportBody, guestToken);
    var replay = post("/v1/reports", null, reportKey, reportBody, guestToken);
    assertEquals(201, report.statusCode());
    assertEquals(201, replay.statusCode());
    UUID reportId = UUID.fromString(json.readTree(report.body()).path("data").path("id").asText());
    assertEquals(
        reportId.toString(), json.readTree(replay.body()).path("data").path("id").asText());
    var mine = get("/v1/me/reports", null, guestToken);
    assertEquals(200, mine.statusCode(), mine.body());
    assertEquals(
        reportId.toString(), json.readTree(mine.body()).path("data").get(0).path("id").asText());

    var resolve =
        post(
            "/v1/admin/reports/" + reportId + "/resolve",
            reviewer,
            UUID.randomUUID().toString(),
            "{\"resolution\":\"no_action\",\"reason\":\"人工判断不违规\",\"expectedVersion\":1}");
    assertEquals(201, resolve.statusCode(), resolve.body());
    assertEquals(
        "resolved",
        jdbc.queryForObject("SELECT status FROM reports WHERE id=?", String.class, reportId));
    assertEquals(
        "visible",
        jdbc.queryForObject("SELECT status FROM posts WHERE id=?", String.class, postId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation_actions WHERE report_id=?", Integer.class, reportId));
  }

  private UUID addAdmin(String name, String role) {
    UUID id = UUID.randomUUID();
    String hash = AdminCredentialCrypto.passwordHash("a-long-local-admin-password".toCharArray());
    String seed = AdminCredentialCrypto.encryptTotpSecret(MFA_SECRET, MFA_KEY);
    jdbc.update(
        "INSERT INTO admin_accounts(id,login_name,password_hash,status,role,mfa_secret_ciphertext) VALUES(?,?,?,'active',?,?)",
        id,
        name,
        hash,
        role,
        seed);
    return id;
  }

  private UUID addPendingDisplayImage(UUID postId) throws Exception {
    UUID mediaId = UUID.randomUUID();
    UUID owner =
        jdbc.queryForObject("SELECT author_principal_id FROM posts WHERE id=?", UUID.class, postId);
    var stored =
        storage.store(
            jp.youren.api.media.StoragePort.Area.DISPLAY,
            new java.io.ByteArrayInputStream(
                "display".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        "INSERT INTO media_assets(id,owner_principal_id,purpose,object_key,status,mime,size_bytes,upload_expires_at,width,height) VALUES(?,?,'board',?,'approved','image/jpeg',7,?,10,10)",
        mediaId,
        owner,
        UUID.randomUUID().toString(),
        now.plusHours(1));
    jdbc.update(
        "INSERT INTO media_variants(media_id,kind,object_key,size_bytes,width,height) VALUES(?,'display',?,7,10,10)",
        mediaId,
        stored.objectKey());
    jdbc.update("INSERT INTO post_media(post_id,media_id,position) VALUES(?,?,0)", postId, mediaId);
    return mediaId;
  }

  private String login(String name) throws Exception {
    var challenge =
        post(
            "/v1/admin/auth/login",
            null,
            null,
            "{\"loginName\":\"" + name + "\",\"password\":\"a-long-local-admin-password\"}");
    assertEquals(200, challenge.statusCode());
    UUID id =
        UUID.fromString(json.readTree(challenge.body()).path("data").path("challengeId").asText());
    String invalidCode = "000000";
    String validCode = Totp.code(MFA_SECRET, Instant.now().getEpochSecond() / 30);
    if (invalidCode.equals(validCode)) invalidCode = "000001";
    var rejected =
        post(
            "/v1/admin/auth/mfa",
            null,
            null,
            "{\"challengeId\":\"" + id + "\",\"code\":\"" + invalidCode + "\"}");
    assertEquals(401, rejected.statusCode());
    var session =
        post(
            "/v1/admin/auth/mfa",
            null,
            null,
            "{\"challengeId\":\"" + id + "\",\"code\":\"" + validCode + "\"}");
    assertEquals(200, session.statusCode());
    return json.readTree(session.body()).path("data").path("accessToken").asText();
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return get(path, token, null);
  }

  private HttpResponse<String> get(String path, String token, String guest) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(8)).GET();
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (guest != null) builder.header("X-Guest-Token", guest);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String token, String key, String body)
      throws Exception {
    return post(path, token, key, body, null);
  }

  private HttpResponse<String> post(
      String path, String token, String key, String body, String guest) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(8));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (key != null) builder.header("Idempotency-Key", key);
    if (guest != null) builder.header("X-Guest-Token", guest);
    if (body == null || body.isEmpty()) builder.POST(HttpRequest.BodyPublishers.noBody());
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String base() {
    return "http://127.0.0.1:" + port;
  }
}

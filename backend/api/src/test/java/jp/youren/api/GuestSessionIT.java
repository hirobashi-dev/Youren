package jp.youren.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import jp.youren.api.guest.GuestCredentialException;
import jp.youren.api.guest.GuestSessionService;
import jp.youren.api.security.TokenSecrets;
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

// 真实HTTP与临时PostgreSQL验证游客创建、摘要存储及事务，不依赖认证HMAC配置。
@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuestSessionIT {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");

  @DynamicPropertySource
  static void dependencies(DynamicPropertyRegistry registry) {
    registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
    registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
    registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
    registry.add("SPRING_DATA_REDIS_URL", () -> "redis://127.0.0.1:1");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired ObjectMapper json;
  @Autowired GuestSessionService service;

  @BeforeEach
  void prepare() {
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  private HttpResponse<String> create() throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/guest-sessions"))
              .timeout(Duration.ofSeconds(4))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  // 201响应只含合同字段；秘密至少32随机字节，只能在响应出现，数据库只存SHA256摘要。
  @Test
  void createsGuestWithSecureHashAndThirtyDayExpiry() throws Exception {
    Instant before = Instant.now();
    var response = create();
    assertEquals(201, response.statusCode());
    JsonNode envelope = json.readTree(response.body());
    assertEquals(2, envelope.size());
    assertNotNull(UUID.fromString(envelope.get("requestId").asText()));
    var data = envelope.get("data");
    assertEquals(3, data.size());
    String token = data.get("guestToken").asText();
    assertTrue(Base64.getUrlDecoder().decode(token).length >= 32);
    String stored = jdbc.queryForObject("SELECT secret_hash FROM guest_credentials", String.class);
    assertNotEquals(token, stored);
    assertEquals(
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        stored);
    assertEquals(
        data.get("principalId").asText(),
        jdbc.queryForObject("SELECT principal_id::text FROM guest_credentials", String.class));
    assertEquals("guest", jdbc.queryForObject("SELECT kind FROM principals", String.class));
    var expiry = Instant.parse(data.get("expiresAt").asText());
    assertTrue(expiry.isAfter(before.plus(Duration.ofDays(30)).minusSeconds(1)));
    assertTrue(expiry.isBefore(Instant.now().plus(Duration.ofDays(30)).plusSeconds(1)));
    assertEquals(
        expiry,
        jdbc.queryForObject(
                "SELECT expires_at FROM guest_credentials", java.time.OffsetDateTime.class)
            .toInstant());
  }

  // 重复请求创建独立主体与秘密，不将匿名请求绑定到已有游客。
  @Test
  void repeatedRequestsNeverReusePrincipalOrSecret() throws Exception {
    var first = create();
    var second = create();
    assertEquals(201, first.statusCode());
    assertEquals(201, second.statusCode());
    assertNotEquals(
        json.readTree(first.body()).at("/data/guestToken"),
        json.readTree(second.body()).at("/data/guestToken"));
    assertNotEquals(
        json.readTree(first.body()).at("/data/principalId"),
        json.readTree(second.body()).at("/data/principalId"));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM guest_credentials", Integer.class));
  }

  // HTTP返回的秘密可通过真实Mapper鉴权；服务器撤销与数据库到期均立即拒绝。
  @Test
  void persistentRevocationAndExpiryRejectPreviouslyValidTokens() throws Exception {
    var revoked = json.readTree(create().body()).get("data");
    String first = revoked.get("guestToken").asText();
    assertEquals(
        UUID.fromString(revoked.get("principalId").asText()), service.requirePrincipal(first));
    service.revoke(first);
    assertThrows(GuestCredentialException.class, () -> service.requirePrincipal(first));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM guest_credentials WHERE revoked_at IS NOT NULL", Integer.class));
    var expired = json.readTree(create().body()).get("data");
    String second = expired.get("guestToken").asText();
    jdbc.update(
        "UPDATE guest_credentials SET expires_at=created_at + INTERVAL '1 microsecond' WHERE principal_id=?",
        UUID.fromString(expired.get("principalId").asText()));
    assertThrows(GuestCredentialException.class, () -> service.requirePrincipal(second));
  }

  // 凭证写入失败需回滚主体；HTTP错误不能回显内部数据库内容或请求追踪头。
  @Test
  void credentialFailureRollsBackPrincipalAndReturnsSafeError() throws Exception {
    jdbc.execute(
        "CREATE FUNCTION reject_credential() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'secret-internal'; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_reject BEFORE INSERT ON guest_credentials FOR EACH ROW EXECUTE FUNCTION reject_credential()");
    var response = create();
    assertEquals(500, response.statusCode());
    assertEquals("INTERNAL_ERROR", json.readTree(response.body()).get("code").asText());
    assertFalse(response.body().contains("secret-internal"));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM principals", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM guest_credentials", Integer.class));
  }

  // 游客上传通过限时票据写入私有原图；另一主体不能完成，正确主体完成后只得到技术ready变体。
  @Test
  void uploadsProcessesAndKeepsMediaPrivatePerGuest() throws Exception {
    String ownerToken = json.readTree(create().body()).at("/data/guestToken").asText();
    String otherToken = json.readTree(create().body()).at("/data/guestToken").asText();
    var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    var imageBytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", imageBytes));
    byte[] png = imageBytes.toByteArray();
    try (var http = HttpClient.newHttpClient()) {
      var intentResponse =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/media/upload-intents"))
                  .header("X-Guest-Token", ownerToken)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"purpose\":\"board\",\"mime\":\"image/png\",\"sizeBytes\":"
                              + png.length
                              + "}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, intentResponse.statusCode());
      var intent = json.readTree(intentResponse.body()).get("data");
      UUID mediaId = UUID.fromString(intent.get("mediaId").asText());
      String uploadToken = intent.at("/uploadHeaders/X-Upload-Token").asText();
      String uploadUrl = intent.get("uploadUrl").asText();
      assertEquals(
          TokenSecrets.digest(uploadToken),
          jdbc.queryForObject(
              "SELECT checksum FROM media_assets WHERE id=?", String.class, mediaId));

      var wrongOwner =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/media/" + mediaId + "/complete"))
                  .header("X-Guest-Token", otherToken)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, wrongOwner.statusCode());
      var upload =
          http.send(
              HttpRequest.newBuilder(URI.create(uploadUrl))
                  .header("X-Guest-Token", ownerToken)
                  .header("X-Upload-Token", uploadToken)
                  .header("Content-Type", "application/octet-stream")
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(png))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(
          200,
          upload.statusCode(),
          upload.body()
              + " status="
              + jdbc.queryForObject(
                  "SELECT status FROM media_assets WHERE id=?", String.class, mediaId));
      assertEquals("uploaded", json.readTree(upload.body()).at("/data/status").asText());
      var complete =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/media/" + mediaId + "/complete"))
                  .header("X-Guest-Token", ownerToken)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, complete.statusCode());
      assertEquals("approved", json.readTree(complete.body()).at("/data/status").asText());
      var repeatedComplete =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/media/" + mediaId + "/complete"))
                  .header("X-Guest-Token", ownerToken)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, repeatedComplete.statusCode());
      assertEquals("approved", json.readTree(repeatedComplete.body()).at("/data/status").asText());
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM media_variants WHERE media_id=?", Integer.class, mediaId));
      assertEquals(
          "approved",
          jdbc.queryForObject("SELECT status FROM media_assets WHERE id=?", String.class, mediaId));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM media_assets WHERE id=? AND object_key NOT LIKE '%/%'",
              Integer.class, mediaId));
      assertEquals(
          400,
          http.send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + port + "/v1/media/" + mediaId))
                      .header("X-Guest-Token", otherToken)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  // 游客发布先进入待审；公开列表不返回草稿，本人内容入口仍能读取自己的状态。
  @Test
  void submitsPendingPostAndKeepsItOutOfPublicList() throws Exception {
    String token = json.readTree(create().body()).at("/data/guestToken").asText();
    try (var http = HttpClient.newHttpClient()) {
      var createPost =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/posts"))
                  .header("X-Guest-Token", token)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"title\":\"东京散步\",\"body\":\"周末一起散步。\",\"category\":\"city\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, createPost.statusCode(), createPost.body());
      assertEquals("pending", json.readTree(createPost.body()).at("/data/status").asText());
      assertTrue(json.readTree(createPost.body()).at("/data/publishedAt").isNull());
      UUID postId = UUID.fromString(json.readTree(createPost.body()).at("/data/id").asText());

      var publicPosts =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/posts"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, publicPosts.statusCode());
      assertEquals(0, json.readTree(publicPosts.body()).at("/data").size());
      assertTrue(json.readTree(publicPosts.body()).at("/page/nextCursor").isNull());

      var invalidFilter =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/posts?category=unknown"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      // 无效筛选必须明确拒绝，不能伪装成合法的空搜索结果。
      assertEquals(422, invalidFilter.statusCode(), invalidFilter.body());

      var ownContent =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/me/content?kind=posts"))
                  .header("X-Guest-Token", token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, ownContent.statusCode(), ownContent.body());
      assertEquals(postId.toString(), json.readTree(ownContent.body()).at("/data/0/id").asText());
      assertEquals("pending", json.readTree(ownContent.body()).at("/data/0/status").asText());

      var ownDetail =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/v1/me/content/posts/" + postId))
                  .header("X-Guest-Token", token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, ownDetail.statusCode(), ownDetail.body());
      assertEquals(postId.toString(), json.readTree(ownDetail.body()).at("/data/id").asText());
    }
  }

  @Test
  void ownerCanEditPendingPostAndStaleVersionIsRejected() throws Exception {
    String token = json.readTree(create().body()).at("/data/guestToken").asText();
    try (var http = HttpClient.newHttpClient()) {
      var created =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/posts"))
                  .header("X-Guest-Token", token)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"title\":\"旧标题\",\"body\":\"正文\",\"category\":\"general\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      UUID postId = UUID.fromString(json.readTree(created.body()).at("/data/id").asText());
      String url = "http://127.0.0.1:" + port + "/v1/posts/" + postId;
      var update =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .header("X-Guest-Token", token)
                  .header("If-Match", "\"1\"")
                  .header("Content-Type", "application/json")
                  .method(
                      "PATCH",
                      HttpRequest.BodyPublishers.ofString(
                          "{\"title\":\"新标题\",\"body\":\"已修改\",\"category\":\"general\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, update.statusCode(), update.body());
      assertEquals(2, json.readTree(update.body()).at("/data/version").asInt());
      assertEquals("pending", json.readTree(update.body()).at("/data/status").asText());

      var stale =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .header("X-Guest-Token", token)
                  .header("If-Match", "\"1\"")
                  .header("Content-Type", "application/json")
                  .method(
                      "PATCH",
                      HttpRequest.BodyPublishers.ofString(
                          "{\"title\":\"覆盖尝试\",\"body\":\"旧版本\",\"category\":\"general\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      // 旧版本不得覆盖已保存的新版本，响应以冲突状态提示客户端重新加载。
      assertEquals(412, stale.statusCode(), stale.body());
    }
  }

  @Test
  void ownerDeleteHidesPostImmediatelyAndCanRetrySafely() throws Exception {
    String token = json.readTree(create().body()).at("/data/guestToken").asText();
    try (var http = HttpClient.newHttpClient()) {
      var created =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/posts"))
                  .header("X-Guest-Token", token)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"title\":\"待删标题\",\"body\":\"正文\",\"category\":\"general\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      UUID postId = UUID.fromString(json.readTree(created.body()).at("/data/id").asText());
      URI uri = URI.create("http://127.0.0.1:" + port + "/v1/posts/" + postId);
      var first =
          http.send(
              HttpRequest.newBuilder(uri)
                  .header("X-Guest-Token", token)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(204, first.statusCode(), first.body());
      var hidden =
          http.send(
              HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(404, hidden.statusCode());
      var retry =
          http.send(
              HttpRequest.newBuilder(uri)
                  .header("X-Guest-Token", token)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      // 重试删除保持相同终态，不再次产生可见内容或泄露资源是否归他人所有。
      assertEquals(204, retry.statusCode(), retry.body());
    }
  }

  @Test
  void repeatedPostSubmissionWithSameKeyCreatesOnlyOnePost() throws Exception {
    String token = json.readTree(create().body()).at("/data/guestToken").asText();
    String key = UUID.randomUUID().toString();
    String body = "{\"title\":\"幂等标题\",\"body\":\"同一请求\",\"category\":\"general\"}";
    try (var http = HttpClient.newHttpClient()) {
      var first = submitPost(http, token, key, body);
      var repeated = submitPost(http, token, key, body);
      assertEquals(201, first.statusCode(), first.body());
      assertEquals(201, repeated.statusCode(), repeated.body());
      assertEquals(
          json.readTree(first.body()).at("/data/id").asText(),
          json.readTree(repeated.body()).at("/data/id").asText());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class));

      var conflict = submitPost(http, token, key, body.replace("同一请求", "不同请求"));
      // 同一主体复用键但更换请求正文必须冲突，不能创建第二条内容。
      assertEquals(409, conflict.statusCode(), conflict.body());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class));
    }
  }

  private HttpResponse<String> submitPost(HttpClient http, String token, String key, String body)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/posts"))
            .header("X-Guest-Token", token)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

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
}

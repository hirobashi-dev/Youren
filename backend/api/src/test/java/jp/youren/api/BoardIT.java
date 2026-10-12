package jp.youren.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import jp.youren.api.guest.GuestSessionService;
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
    classes = {ApiApplication.class, BoardIT.TimeConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardIT {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");

  @DynamicPropertySource
  static void dependencies(DynamicPropertyRegistry registry) {
    registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
    registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
    registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
    registry.add("SPRING_DATA_REDIS_URL", () -> "redis://127.0.0.1:1");
  }

  // 测试时钟驱动真实HTTP时间边界，避免用休眠模拟到期。
  static class BoardClock extends java.time.Clock {
    volatile Instant current = Instant.now();

    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return current;
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class TimeConfiguration {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    BoardClock boardClock() {
      return new BoardClock();
    }
  }

  @Autowired BoardClock boardClock;
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired ObjectMapper json;
  @Autowired GuestSessionService service;

  @BeforeEach
  void prepare() {
    boardClock.current = Instant.now();
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

  // 收集真实HTTP公开DTO供合同校验脚本读取；测试凭证和请求正文从不写入文件。
  private static final java.util.List<java.util.Map<String, Object>> responses =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  @org.junit.jupiter.api.AfterEach
  void exportContractResponses() throws Exception {
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/board-http-responses.json"),
        json.writeValueAsString(responses),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private HttpResponse<String> capture(String method, String path, HttpResponse<String> response)
      throws Exception {
    if (response.headers().firstValue("Content-Type").orElse("").contains("application/json"))
      responses.add(
          java.util.Map.of(
              "method",
              method.toLowerCase(),
              "path",
              "/v1" + path.split("\\?", 2)[0],
              "status",
              response.statusCode(),
              "body",
              json.readTree(response.body())));
    return response;
  }

  private String token() throws Exception {
    return json.readTree(create().body()).at("/data/guestToken").asText();
  }

  private HttpResponse<String> call(
      String method, String path, String token, String body, int version, String key)
      throws Exception {
    var r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1" + path));
    if (token != null) r.header("X-Guest-Token", token);
    if (body != null) r.header("Content-Type", "application/json");
    if (version > 0) r.header("If-Match", "\"" + version + "\"");
    if (key != null) r.header("Idempotency-Key", key);
    try (var h = HttpClient.newHttpClient()) {
      return capture(
          method,
          path,
          h.send(
              r.method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString()));
    }
  }

  private UUID post(String token) throws Exception {
    var r =
        call(
            "POST",
            "/posts",
            token,
            "{\"title\":\"标题\",\"body\":\"正文\",\"category\":\"general\"}",
            0,
            UUID.randomUUID().toString());
    assertEquals(201, r.statusCode(), r.body());
    return UUID.fromString(json.readTree(r.body()).at("/data/id").asText());
  }

  private void visible(UUID id) {
    jdbc.update(
        "UPDATE posts SET status='visible',published_at=now(),expires_at=now()+interval '1 year' WHERE id=?",
        id);
  }

  // 回复必须属于公开帖子；待审只允许作者读取，越权和旧版本均拒绝，重试不重复创建。
  @Test
  void replyLifecycleHonorsOwnershipVersionAndIdempotency() throws Exception {
    String a = token(), b = token();
    UUID p = post(a);
    String body = "{\"body\":\"e\\u0301👩‍👩‍👧‍👦\"}", key = UUID.randomUUID().toString();
    assertEquals(404, call("POST", "/posts/" + p + "/comments", a, body, 0, key).statusCode());
    visible(p);
    var created = call("POST", "/posts/" + p + "/comments", a, body, 0, key);
    assertEquals(201, created.statusCode(), created.body());
    UUID id = UUID.fromString(json.readTree(created.body()).at("/data/id").asText());
    assertEquals(
        id.toString(),
        json.readTree(call("POST", "/posts/" + p + "/comments", a, body, 0, key).body())
            .at("/data/id")
            .asText());
    assertEquals(
        "é👩‍👩‍👧‍👦",
        jdbc.queryForObject("SELECT body FROM comments WHERE id=?", String.class, id));
    assertEquals(
        0,
        json.readTree(call("GET", "/posts/" + p + "/comments", null, null, 0, null).body())
            .at("/data")
            .size());
    assertEquals(200, call("GET", "/me/content/comments/" + id, a, null, 0, null).statusCode());
    assertEquals(404, call("GET", "/me/content/comments/" + id, b, null, 0, null).statusCode());
    assertEquals(404, call("PATCH", "/comments/" + id, b, body, 1, null).statusCode());
    assertEquals(200, call("PATCH", "/comments/" + id, a, body, 1, null).statusCode());
    assertEquals(412, call("PATCH", "/comments/" + id, a, body, 1, null).statusCode());
    jdbc.update(
        "UPDATE comments SET status='visible',published_at=now(),expires_at=now()+interval '1 year' WHERE id=?",
        id);
    assertEquals(
        1,
        json.readTree(call("GET", "/posts/" + p, null, null, 0, null).body())
            .at("/data/replyCount")
            .asInt());
    assertEquals(
        1,
        json.readTree(call("GET", "/posts/" + p + "/comments", null, null, 0, null).body())
            .at("/data")
            .size());
    assertEquals(
        204,
        call("DELETE", "/comments/" + id, a, null, 0, UUID.randomUUID().toString()).statusCode());
    assertEquals(
        0,
        json.readTree(call("GET", "/posts/" + p + "/comments", null, null, 0, null).body())
            .at("/data")
            .size());
  }

  // 有效回复不泄露到期父帖，个人列表保留到期占位，未知兴趣必须报错。
  @Test
  void expiryAndUnknownFiltersAreNotSilentlyIgnored() throws Exception {
    String a = token();
    UUID p = post(a);
    jdbc.update(
        "UPDATE posts SET status='visible',published_at=now()-interval '2 year' WHERE id=?", p);
    assertEquals(404, call("GET", "/posts/" + p, null, null, 0, null).statusCode());
    assertEquals(
        "expired",
        json.readTree(call("GET", "/me/content?kind=posts", a, null, 0, null).body())
            .at("/data/0/status")
            .asText());
    assertEquals(
        422,
        call("GET", "/posts?interestId=" + UUID.randomUUID(), null, null, 0, null).statusCode());
  }

  @Autowired jp.youren.api.media.MediaService mediaService;
  @Autowired jp.youren.database.mapper.BoardMapper boardMapper;

  private UUID image(String token) throws Exception {
    UUID owner = service.requirePrincipal(token);
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
    byte[] bytes = out.toByteArray();
    var intent =
        mediaService.createIntent(
            owner, "board", "image/png", bytes.length, "http://localhost/v1/media");
    mediaService.upload(
        owner,
        intent.mediaId(),
        intent.uploadHeaders().get("X-Upload-Token"),
        new java.io.ByteArrayInputStream(bytes));
    mediaService.complete(owner, intent.mediaId());
    return intent.mediaId();
  }

  // 多图提交失败必须回滚帖子、关联与回执；图片公开仅限人工通过内容，旧地址隐藏后立即失效。
  @Test
  void imageBindingIsAtomicAndDisplayReadRechecksContent() throws Exception {
    String a = token(), b = token();
    UUID good = image(a), other = image(b), secondGood = image(a);
    String body =
        "{\"title\":\"图片\",\"body\":\"正文\",\"category\":\"general\",\"mediaIds\":[\""
            + good
            + "\",\""
            + other
            + "\"]}";
    assertEquals(
        422, call("POST", "/posts", a, body, 0, UUID.randomUUID().toString()).statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM post_media", Integer.class));
    // 第二张图绑定失败时，第一张图、帖子与先写入的幂等回执也必须随事务回滚。
    jdbc.execute(
        "CREATE FUNCTION fail_second_post_media() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.position=1 THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_second_post_media BEFORE INSERT ON post_media FOR EACH ROW EXECUTE FUNCTION fail_second_post_media()");
    String rollbackKey = UUID.randomUUID().toString();
    String rollbackBody = body.replace(other.toString(), secondGood.toString());
    assertNotEquals(201, call("POST", "/posts", a, rollbackBody, 0, rollbackKey).statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM post_media", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_records WHERE key=?", Integer.class, rollbackKey));
    jdbc.execute("DROP TRIGGER fail_second_post_media ON post_media");
    jdbc.execute("DROP FUNCTION fail_second_post_media()");
    body = body.replace(",\"" + other + "\"", "");
    var created = call("POST", "/posts", a, body, 0, UUID.randomUUID().toString());
    assertEquals(201, created.statusCode(), created.body());
    UUID p = UUID.fromString(json.readTree(created.body()).at("/data/id").asText());
    assertEquals(404, call("GET", "/media/" + good + "/display", null, null, 0, null).statusCode());
    assertEquals(200, call("PATCH", "/posts/" + p, a, body, 1, null).statusCode());
    assertEquals(
        1, boardMapper.publishPost(p, 2, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
    assertEquals(200, call("GET", "/media/" + good + "/display", null, null, 0, null).statusCode());
    var detail = json.readTree(call("GET", "/posts/" + p, null, null, 0, null).body());
    assertEquals(1, detail.at("/data/images").size());
    assertTrue(detail.at("/data/images/0/url").asText().startsWith("http://"));
    assertFalse(detail.toString().contains("objectKey"));
    var first =
        jdbc.queryForObject(
            "SELECT published_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p);
    var expiry =
        jdbc.queryForObject(
            "SELECT expires_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p);
    assertEquals(first.plusYears(1), expiry);
    jdbc.update("UPDATE posts SET status='hidden',version=version+1 WHERE id=?", p);
    assertEquals(404, call("GET", "/media/" + good + "/display", null, null, 0, null).statusCode());
    assertEquals(
        1, boardMapper.publishPost(p, 4, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
    assertEquals(
        expiry,
        jdbc.queryForObject(
            "SELECT expires_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p));
  }

  // 可见字符按NFC及扩展字素计数；空白、超限、跨帖引用和多于三图回复拒绝。
  @Test
  void unicodeAndReplyValidationHonorLimitsAndSamePost() throws Exception {
    String a = token();
    UUID p = post(a);
    visible(p);
    String emoji = "👩‍👩‍👧‍👦";
    var good =
        call(
            "POST",
            "/posts/" + p + "/comments",
            a,
            json.writeValueAsString(java.util.Map.of("body", emoji.repeat(1000))),
            0,
            UUID.randomUUID().toString());
    assertEquals(201, good.statusCode(), good.body());
    UUID reply = UUID.fromString(json.readTree(good.body()).at("/data/id").asText());
    assertEquals(
        422,
        call(
                "POST",
                "/posts/" + p + "/comments",
                a,
                json.writeValueAsString(java.util.Map.of("body", emoji.repeat(1001))),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    assertEquals(
        422,
        call(
                "POST",
                "/posts/" + p + "/comments",
                a,
                json.writeValueAsString(java.util.Map.of("body", "　 ")),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    UUID q = post(a);
    visible(q);
    jdbc.update("UPDATE comments SET status='visible' WHERE id=?", reply);
    assertEquals(
        422,
        call(
                "POST",
                "/posts/" + q + "/comments",
                a,
                json.writeValueAsString(java.util.Map.of("body", "引用", "replyToId", reply)),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    assertEquals(
        422,
        call(
                "POST",
                "/posts/" + p + "/comments",
                a,
                json.writeValueAsString(
                    java.util.Map.of(
                        "body",
                        "",
                        "mediaIds",
                        java.util.List.of(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID()))),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    UUID pic = image(a);
    var photo =
        call(
            "POST",
            "/posts/" + p + "/comments",
            a,
            json.writeValueAsString(
                java.util.Map.of("body", "", "mediaIds", java.util.List.of(pic))),
            0,
            UUID.randomUUID().toString());
    assertEquals(201, photo.statusCode(), photo.body());
    assertEquals(
        422,
        call(
                "POST",
                "/posts/" + p + "/comments",
                a,
                json.writeValueAsString(
                    java.util.Map.of("body", "", "mediaIds", java.util.List.of(pic))),
                0,
                UUID.randomUUID().toString())
            .statusCode());
  }

  // 列表地区兴趣必须真实筛选；同发布时间使用UUID稳定翻页；本人查询也具有游标。
  @Test
  void dictionariesAndStablePaginationAreReal() throws Exception {
    String a = token();
    UUID interest = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO regions(code,level,name_ja,name_zh) VALUES('13','prefecture','東京','东京')");
    jdbc.update("INSERT INTO interests(id,code,name_zh) VALUES(?,'walk','散步')", interest);
    jdbc.update(
        "INSERT INTO regions(code,level,name_ja,name_zh) VALUES('14','prefecture','神奈川','神奈川')");
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "title",
                "主题",
                "body",
                "正文",
                "category",
                "city",
                "regionCode",
                "13",
                "interestIds",
                java.util.List.of(interest)));
    for (int i = 0; i < 3; i++) {
      var r = call("POST", "/posts", a, body, 0, UUID.randomUUID().toString());
      assertEquals(201, r.statusCode(), r.body());
    }
    var regionOnly =
        call(
            "POST",
            "/posts",
            a,
            "{\"title\":\"无兴趣匹配\",\"body\":\"正文\",\"category\":\"city\",\"regionCode\":\"13\"}",
            0,
            UUID.randomUUID().toString());
    var interestOnly =
        call(
            "POST",
            "/posts",
            a,
            json.writeValueAsString(
                java.util.Map.of(
                    "title", "无地区匹配",
                    "body", "正文",
                    "category", "city",
                    "regionCode", "14",
                    "interestIds", java.util.List.of(interest))),
            0,
            UUID.randomUUID().toString());
    assertEquals(201, regionOnly.statusCode(), regionOnly.body());
    assertEquals(201, interestOnly.statusCode(), interestOnly.body());
    jdbc.update("UPDATE posts SET status='visible',published_at='2026-10-12T00:00:00Z'");
    String filter = "/posts?regionCode=13&interestId=" + interest + "&limit=1";
    var first = json.readTree(call("GET", filter, null, null, 0, null).body());
    assertEquals(
        3,
        json.readTree(
                call("GET", filter.replace("limit=1", "limit=10"), null, null, 0, null).body())
            .at("/data")
            .size());
    assertEquals("东京", first.at("/data/0/region/nameZh").asText());
    assertEquals("散步", first.at("/data/0/interests/0/nameZh").asText());
    String cursor = first.at("/page/nextCursor").asText();
    var second =
        json.readTree(call("GET", filter + "&cursor=" + cursor, null, null, 0, null).body());
    assertNotEquals(first.at("/data/0/id"), second.at("/data/0/id"));
    assertEquals(422, call("GET", "/posts?sort=oldest", null, null, 0, null).statusCode());
    assertEquals(400, call("GET", "/posts?cursor=bad", null, null, 0, null).statusCode());
    var own =
        json.readTree(
            call("GET", "/me/content?kind=posts&status=visible&limit=1", a, null, 0, null).body());
    assertTrue(own.at("/page/hasMore").asBoolean());
    var next =
        json.readTree(
            call(
                    "GET",
                    "/me/content?kind=posts&status=visible&limit=1&cursor="
                        + own.at("/page/nextCursor").asText(),
                    a,
                    null,
                    0,
                    null)
                .body());
    assertNotEquals(own.at("/data/0/id"), next.at("/data/0/id"));
  }

  // 回复拥有独立期限；父帖到期只给有效回复入口，禁止新回复及旧媒体访问父帖。
  @Test
  void parentExpiryPreservesOnlyIndependentlyValidReplies() throws Exception {
    String a = token();
    UUID p = post(a);
    jdbc.update(
        "UPDATE posts SET status='visible',published_at='2024-10-12T00:00:00Z' WHERE id=?", p);
    UUID c = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO comments(id,post_id,author_principal_id,body,status,published_at) VALUES(?,?,?,'有效回复','visible',now())",
        c,
        p,
        service.requirePrincipal(a));
    assertEquals(404, call("GET", "/posts/" + p, null, null, 0, null).statusCode());
    assertEquals(
        1,
        json.readTree(call("GET", "/posts/" + p + "/comments", null, null, 0, null).body())
            .at("/data")
            .size());
    assertTrue(
        json.readTree(call("GET", "/me/content/comments/" + c, a, null, 0, null).body())
            .at("/data/parentUnavailable")
            .asBoolean());
    assertEquals(
        404,
        call(
                "POST",
                "/posts/" + p + "/comments",
                a,
                "{\"body\":\"新增\"}",
                0,
                UUID.randomUUID().toString())
            .statusCode());
    jdbc.update("UPDATE posts SET status='hidden' WHERE id=?", p);
    assertEquals(404, call("GET", "/posts/" + p + "/comments", null, null, 0, null).statusCode());
  }

  @Autowired jp.youren.api.auth.SessionService sessions;

  private String accountToken() {
    UUID account = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts(id,email_normalized) VALUES(?,?)",
        account,
        account + "@example.invalid");
    jdbc.update(
        "INSERT INTO principals(id,kind,account_id) VALUES(?,'account',?)",
        UUID.randomUUID(),
        account);
    return sessions.create(account, UUID.randomUUID()).accessToken();
  }

  private HttpResponse<String> accountCall(
      String method, String path, String token, String body, int version) throws Exception {
    var r =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1" + path))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString());
    if (body != null) r.header("Content-Type", "application/json");
    if (version > 0) r.header("If-Match", "\"" + version + "\"");
    try (var h = HttpClient.newHttpClient()) {
      return capture(
          method,
          path,
          h.send(
              r.method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString()));
    }
  }

  // 账号会话必须由真实过滤器认证，账号和游客互不拥有对方内容；客户端状态伪造被拒绝。
  @Test
  void accountAndGuestOwnershipRemainIsolated() throws Exception {
    String a = accountToken(), b = accountToken(), guest = token();
    String body = "{\"title\":\"账号\",\"body\":\"正文\",\"category\":\"general\"}";
    var r = accountCall("POST", "/posts", a, body, 0);
    assertEquals(201, r.statusCode(), r.body());
    UUID id = UUID.fromString(json.readTree(r.body()).at("/data/id").asText());
    assertEquals(200, accountCall("PATCH", "/posts/" + id, a, body, 1).statusCode());
    assertEquals(404, accountCall("PATCH", "/posts/" + id, b, body, 2).statusCode());
    assertEquals(404, call("GET", "/me/content/posts/" + id, guest, null, 0, null).statusCode());
    assertEquals(401, call("GET", "/me/content/posts/" + id, null, null, 0, null).statusCode());
    assertEquals(
        422,
        accountCall("POST", "/posts", a, body.replace("}", ",\"status\":\"visible\"}"), 0)
            .statusCode());
    visible(id);
    var detail = json.readTree(call("GET", "/posts/" + id, null, null, 0, null).body());
    assertEquals("account", detail.at("/data/authorDisplay/kind").asText());
    assertFalse(detail.toString().contains("@example.invalid"));
    var reply = accountCall("POST", "/posts/" + id + "/comments", a, "{\"body\":\"回复\"}", 0);
    assertEquals(201, reply.statusCode(), reply.body());
    UUID c = UUID.fromString(json.readTree(reply.body()).at("/data/id").asText());
    assertEquals(404, accountCall("DELETE", "/comments/" + c, b, null, 0).statusCode());
    assertEquals(204, accountCall("DELETE", "/comments/" + c, a, null, 0).statusCode());
    assertEquals(204, accountCall("DELETE", "/posts/" + id, a, null, 0).statusCode());
  }

  @Autowired jp.youren.api.guest.GuestClaimService claims;

  // 接续成功后账号可管理原游客内容且保留游客展示；旧游客凭证撤销，其他账号仍拒绝。
  @Test
  void claimedGuestContentRemainsManageableByItsAccount() throws Exception {
    String guest = token(), a = accountToken(), other = accountToken();
    UUID p = post(guest);
    visible(p);
    var created =
        call(
            "POST",
            "/posts/" + p + "/comments",
            guest,
            "{\"body\":\"游客回复\"}",
            0,
            UUID.randomUUID().toString());
    UUID c = UUID.fromString(json.readTree(created.body()).at("/data/id").asText());
    claims.claim(sessions.requireAccess(a).accountId(), guest, UUID.randomUUID());
    assertEquals(200, accountCall("GET", "/me/content/posts/" + p, a, null, 0).statusCode());
    var detail = accountCall("GET", "/me/content/comments/" + c, a, null, 0);
    assertEquals(200, detail.statusCode(), detail.body());
    assertFalse(json.readTree(detail.body()).get("data").has("title"));
    assertEquals(
        1,
        json.readTree(accountCall("GET", "/me/content?kind=comments", a, null, 0).body())
            .at("/data")
            .size());
    assertEquals(404, accountCall("GET", "/me/content/comments/" + c, other, null, 0).statusCode());
    assertEquals(401, call("GET", "/me/content/comments/" + c, guest, null, 0, null).statusCode());
    assertEquals(
        200, accountCall("PATCH", "/comments/" + c, a, "{\"body\":\"账号重提\"}", 1).statusCode());
    assertEquals(
        "guest",
        json.readTree(call("GET", "/posts/" + p, null, null, 0, null).body())
            .at("/data/authorDisplay/kind")
            .asText());
    assertEquals(204, accountCall("DELETE", "/posts/" + p, a, null, 0).statusCode());
  }

  // 已发布后驳回重提保留首次期限；到期时不能重新提交正文。
  @Test
  void expiredRejectedPostCannotBeResubmitted() throws Exception {
    String a = token();
    UUID p = post(a);
    jdbc.update(
        "UPDATE posts SET status='visible',published_at='2024-02-29T00:00:00Z' WHERE id=?", p);
    jdbc.update("UPDATE posts SET status='rejected' WHERE id=?", p);
    assertEquals(
        409,
        call(
                "PATCH",
                "/posts/" + p,
                a,
                "{\"title\":\"重提\",\"body\":\"正文\",\"category\":\"general\"}",
                1,
                null)
            .statusCode());
    assertEquals(
        java.time.OffsetDateTime.parse("2025-02-28T00:00:00Z"),
        jdbc.queryForObject(
            "SELECT expires_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p));
  }

  // 同键多图并发提交只产生一份内容，所有请求都获得同一回执而不是已绑定错误。
  @Test
  void concurrentImageSubmissionReplaysAfterWaitingForMediaLock() throws Exception {
    String a = token();
    UUID img = image(a);
    String key = UUID.randomUUID().toString();
    String body =
        json.writeValueAsString(
            java.util.Map.of(
                "title",
                "并发",
                "body",
                "正文",
                "category",
                "general",
                "mediaIds",
                java.util.List.of(img)));
    try (var connection = dataSource.getConnection();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement("SELECT id FROM media_assets WHERE id=? FOR UPDATE")) {
        lock.setObject(1, img);
        lock.executeQuery();
      }
      var start = new java.util.concurrent.CountDownLatch(2);
      java.util.concurrent.Callable<HttpResponse<String>> task =
          () -> {
            start.countDown();
            start.await();
            return call("POST", "/posts", a, body, 0, key);
          };
      var first = pool.submit(task);
      var second = pool.submit(task);
      start.await();
      // 等待数据库确认两请求都在同一媒体行锁阻塞，保证并发场景不会偶然顺序执行。
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%media_assets%'",
                  Integer.class)
              < 2
          && System.nanoTime() < deadline) Thread.sleep(10);
      connection.commit();
      var r1 = first.get();
      var r2 = second.get();
      assertEquals(201, r1.statusCode(), r1.body());
      assertEquals(201, r2.statusCode(), r2.body());
      assertEquals(
          json.readTree(r1.body()).at("/data/id"), json.readTree(r2.body()).at("/data/id"));
    }
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class));
  }

  // 图片锁等待期间父帖到期时必须拒绝回复，不得用等待前的公开状态创建内容。
  @Test
  void commentSubmissionRechecksParentExpiryAfterMediaLockWait() throws Exception {
    String a = token();
    UUID p = post(a), img = image(a);
    visible(p);
    var requestedExpiry =
        java.time.OffsetDateTime.ofInstant(
            boardClock.current.plusSeconds(5), java.time.ZoneOffset.UTC);
    jdbc.update("UPDATE posts SET expires_at=? WHERE id=?", requestedExpiry, p);
    var expiry =
        jdbc.queryForObject(
                "SELECT expires_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p)
            .toInstant();
    // 以数据库读回值校准测试时钟，避免JDBC时区转换让边界场景依赖机器时区。
    boardClock.current = expiry.minusSeconds(5);
    try (var connection = dataSource.getConnection();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement("SELECT id FROM media_assets WHERE id=? FOR UPDATE")) {
        lock.setObject(1, img);
        lock.executeQuery();
      }
      var request =
          pool.submit(
              () ->
                  call(
                      "POST",
                      "/posts/" + p + "/comments",
                      a,
                      "{\"body\":\"等待中的回复\",\"mediaIds\":[\"" + img + "\"]}",
                      0,
                      UUID.randomUUID().toString()));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%media_assets%'",
                  Integer.class)
              < 1
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertTrue(
          jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%media_assets%'",
                  Integer.class)
              >= 1,
          "回复请求应在媒体行锁上等待");
      boardClock.current = expiry.plusSeconds(1);
      assertEquals(404, call("GET", "/posts/" + p, null, null, 0, null).statusCode());
      connection.commit();
      assertEquals(404, request.get().statusCode());
    }
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM comments WHERE post_id=?", Integer.class, p));
  }

  // 两个请求同时基于同一版本编辑时，输家读取提交后的版本并返回明确冲突。
  @Test
  void concurrentCommentEditsReturnVersionConflictForLoser() throws Exception {
    String a = token();
    UUID p = post(a);
    visible(p);
    var created =
        call(
            "POST",
            "/posts/" + p + "/comments",
            a,
            "{\"body\":\"初始回复\"}",
            0,
            UUID.randomUUID().toString());
    UUID id = UUID.fromString(json.readTree(created.body()).at("/data/id").asText());
    try (var connection = dataSource.getConnection();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement("SELECT id FROM comments WHERE id=? FOR UPDATE")) {
        lock.setObject(1, id);
        lock.executeQuery();
      }
      var start = new java.util.concurrent.CountDownLatch(2);
      var one =
          pool.submit(
              () -> {
                start.countDown();
                start.await();
                return call("PATCH", "/comments/" + id, a, "{\"body\":\"修改甲\"}", 1, null);
              });
      var two =
          pool.submit(
              () -> {
                start.countDown();
                start.await();
                return call("PATCH", "/comments/" + id, a, "{\"body\":\"修改乙\"}", 1, null);
              });
      start.await();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%UPDATE comments%'",
                  Integer.class)
              < 1
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertTrue(
          jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%comments%'",
                  Integer.class)
              >= 1,
          "编辑请求应在评论行锁或父帖串行锁上等待");
      connection.commit();
      var first = one.get();
      var second = two.get();
      var status = java.util.List.of(first.statusCode(), second.statusCode());
      assertTrue(status.contains(200), first.body() + " / " + second.body());
      assertTrue(status.contains(412), first.body() + " / " + second.body());
    }
    assertEquals(
        2, jdbc.queryForObject("SELECT version FROM comments WHERE id=?", Integer.class, id));
  }

  // 首次人工发布采用服务端时钟；UTC闰日一年后夹到2月末，到期等号立即拒绝所有公开路径。
  @Test
  void publicationAndMediaRespectExactExpiryBoundary() throws Exception {
    String a = token();
    UUID img = image(a);
    var r =
        call(
            "POST",
            "/posts",
            a,
            json.writeValueAsString(
                java.util.Map.of(
                    "title",
                    "时间",
                    "body",
                    "正文",
                    "category",
                    "general",
                    "mediaIds",
                    java.util.List.of(img))),
            0,
            UUID.randomUUID().toString());
    UUID p = UUID.fromString(json.readTree(r.body()).at("/data/id").asText());
    var first = java.time.OffsetDateTime.parse("2024-02-29T00:00:00Z");
    boardClock.current = first.toInstant();
    assertEquals(1, boardMapper.publishPost(p, 1, first));
    assertEquals(
        first,
        jdbc.queryForObject(
            "SELECT published_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p));
    var expiry = first.plusYears(1);
    boardClock.current = expiry.minusNanos(1000).toInstant();
    assertEquals(200, call("GET", "/posts/" + p, null, null, 0, null).statusCode());
    assertEquals(200, call("GET", "/media/" + img + "/display", null, null, 0, null).statusCode());
    assertEquals(200, call("GET", "/media/" + img + "/access", null, null, 0, null).statusCode());
    boardClock.current = expiry.toInstant();
    assertEquals(404, call("GET", "/posts/" + p, null, null, 0, null).statusCode());
    assertEquals(404, call("GET", "/media/" + img + "/display", null, null, 0, null).statusCode());
    assertEquals(404, call("GET", "/media/" + img + "/access", null, null, 0, null).statusCode());
    jdbc.update("UPDATE posts SET status='expired' WHERE id=?", p);
    // 清理任务标记帖子过期后，过期帖自己的图片仍必须不可读取，不能因终态名称绕过期限过滤。
    assertEquals(404, call("GET", "/media/" + img + "/display", null, null, 0, null).statusCode());
    assertEquals(
        0, json.readTree(call("GET", "/posts", null, null, 0, null).body()).at("/data").size());
  }

  // 帖子标题60、正文2000字素边界以及9图限制在服务端执行；驳回后可重提为待审。
  @Test
  void postLimitsAndRejectedResubmissionPreserveRules() throws Exception {
    String a = token();
    String title = "e\u0301".repeat(60), body = "正文".repeat(1000);
    String input =
        json.writeValueAsString(
            java.util.Map.of("title", title, "body", body, "category", "general"));
    var r = call("POST", "/posts", a, input, 0, UUID.randomUUID().toString());
    assertEquals(201, r.statusCode(), r.body());
    UUID p = UUID.fromString(json.readTree(r.body()).at("/data/id").asText());
    assertEquals(
        "é".repeat(60), jdbc.queryForObject("SELECT title FROM posts WHERE id=?", String.class, p));
    assertEquals(
        422,
        call(
                "POST",
                "/posts",
                a,
                json.writeValueAsString(
                    java.util.Map.of("title", title + "a", "body", body, "category", "general")),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    assertEquals(
        422,
        call(
                "POST",
                "/posts",
                a,
                json.writeValueAsString(
                    java.util.Map.of("title", "标题", "body", body + "a", "category", "general")),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    assertEquals(
        422,
        call(
                "POST",
                "/posts",
                a,
                json.writeValueAsString(
                    java.util.Map.of("title", "　", "body", "正文", "category", "general")),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    var ids = java.util.stream.IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
    assertEquals(
        422,
        call(
                "POST",
                "/posts",
                a,
                json.writeValueAsString(
                    java.util.Map.of(
                        "title", "标题", "body", "正文", "category", "general", "mediaIds", ids)),
                0,
                UUID.randomUUID().toString())
            .statusCode());
    jdbc.update("UPDATE posts SET status='rejected' WHERE id=?", p);
    assertEquals(200, call("PATCH", "/posts/" + p, a, input, 1, null).statusCode());
    assertEquals(
        "pending", jdbc.queryForObject("SELECT status FROM posts WHERE id=?", String.class, p));
    var owner = service.requirePrincipal(a);
    var unready =
        mediaService.createIntent(owner, "board", "image/png", 100, "http://localhost/v1/media");
    assertEquals(
        422,
        call(
                "POST",
                "/posts",
                a,
                json.writeValueAsString(
                    java.util.Map.of(
                        "title",
                        "标题",
                        "body",
                        "正文",
                        "category",
                        "general",
                        "mediaIds",
                        java.util.List.of(unready.mediaId()))),
                0,
                UUID.randomUUID().toString())
            .statusCode());
  }

  // If-Match缺失必须返回合同428；列表是根级data/page结构而不是双层信封。
  @Test
  void responseAndVersionHeadersMatchTheActiveContract() throws Exception {
    String a = token();
    UUID p = post(a);
    var page = json.readTree(call("GET", "/posts", null, null, 0, null).body());
    assertTrue(page.get("data").isArray());
    assertTrue(page.has("page"));
    assertEquals(
        428,
        call(
                "PATCH",
                "/posts/" + p,
                a,
                "{\"title\":\"改\",\"body\":\"正文\",\"category\":\"general\"}",
                0,
                null)
            .statusCode());
  }

  // 图片回复可以省略body；缺失If-Match仍明确428而不是通用参数400。
  @Test
  void imageOnlyInputAndMissingVersionUseContractSemantics() throws Exception {
    String a = token();
    UUID p = post(a);
    visible(p);
    UUID img = image(a);
    var r =
        call(
            "POST",
            "/posts/" + p + "/comments",
            a,
            json.writeValueAsString(java.util.Map.of("mediaIds", java.util.List.of(img))),
            0,
            UUID.randomUUID().toString());
    assertEquals(201, r.statusCode(), r.body());
    assertEquals(
        428,
        call(
                "PATCH",
                "/posts/" + p,
                a,
                "{\"title\":\"改\",\"body\":\"正文\",\"category\":\"general\"}",
                0,
                null)
            .statusCode());
  }

  // 已经公开后驳回再编辑，提交响应也须保留原发布时间和到期时间，不能显示为从未发布。
  @Test
  void rejectedEditResponsePreservesFirstPublicationTimes() throws Exception {
    String a = token();
    UUID p = post(a);
    visible(p);
    var first =
        jdbc.queryForObject(
            "SELECT published_at FROM posts WHERE id=?", java.time.OffsetDateTime.class, p);
    jdbc.update("UPDATE posts SET status='rejected' WHERE id=?", p);
    var response =
        call(
            "PATCH",
            "/posts/" + p,
            a,
            "{\"title\":\"重提\",\"body\":\"修改\",\"category\":\"general\"}",
            1,
            null);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        first.toInstant(),
        Instant.parse(json.readTree(response.body()).at("/data/publishedAt").asText()));
    assertEquals(
        first.plusYears(1).toInstant(),
        Instant.parse(json.readTree(response.body()).at("/data/expiresAt").asText()));
  }
}

package jp.youren.api.guest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import jp.youren.api.profile.ProfileTestSupport;
import jp.youren.api.security.TokenSecrets;
import org.junit.jupiter.api.Test;

class GuestClaimIT extends ProfileTestSupport {
  // 本人接续保留作者模式、统计真实内容并撤销同主体全部凭证；同键重试幂等。
  @Test
  void ownClaimRevokesAllCredentialsPreservesAuthorsAndReplaysCounts() throws Exception {
    var u = user();
    var g = guests.create();
    UUID post = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO posts(id,author_principal_id,title,body) VALUES(?,?,?,?)",
        post,
        g.principalId(),
        "标题",
        "正文");
    jdbc.update(
        "INSERT INTO comments(post_id,author_principal_id,body) VALUES(?,?,?)",
        post,
        g.principalId(),
        "回复");
    String second = TokenSecrets.generate();
    jdbc.update(
        "INSERT INTO guest_credentials(principal_id,secret_hash,expires_at) VALUES(?,?,CURRENT_TIMESTAMP+INTERVAL '1 day')",
        g.principalId(),
        TokenSecrets.digest(second));
    String key = UUID.randomUUID().toString();
    var r = claim(u, g.guestToken(), key);
    assertEquals(200, r.statusCode());
    assertEquals(1, data(r).get("claimedContentCounts").get("posts").asInt());
    assertEquals(1, data(r).get("claimedContentCounts").get("comments").asInt());
    assertFalse(r.body().contains(g.principalId().toString()));
    assertFalse(r.body().contains(g.guestToken()));
    assertEquals(
        u.id(),
        jdbc.queryForObject(
            "SELECT account_id FROM principals WHERE id=?", UUID.class, g.principalId()));
    assertEquals(
        "guest",
        jdbc.queryForObject("SELECT display_mode FROM posts WHERE id=?", String.class, post));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM guest_credentials WHERE principal_id=? AND revoked_at IS NOT NULL",
            Integer.class,
            g.principalId()));
    assertThrows(GuestCredentialException.class, () -> guests.requirePrincipal(second));
    assertEquals(200, claim(u, g.guestToken(), key).statusCode());
    // 回执重放统计当前仍存在的内容，不能返回写死或已过期缓存数量。
    jdbc.update("UPDATE posts SET status='deleted',deleted_at=CURRENT_TIMESTAMP WHERE id=?", post);
    jdbc.update(
        "UPDATE comments SET status='deleted',deleted_at=CURRENT_TIMESTAMP WHERE post_id=?", post);
    var replay = claim(u, g.guestToken(), key);
    assertEquals(0, data(replay).get("claimedContentCounts").get("posts").asInt());
    assertEquals(0, data(replay).get("claimedContentCounts").get("comments").asInt());
    assertEquals(200, claim(u, g.guestToken(), UUID.randomUUID().toString()).statusCode());
    assertEquals(409, claim(u, guests.create().guestToken(), key).statusCode());
    assertEquals(403, claim(user(), g.guestToken(), UUID.randomUUID().toString()).statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_records WHERE key=?", Integer.class, key));
  }

  // 过期、撤销及未知秘密不绑定账号，失败事务不能留下回执。
  @Test
  void invalidCredentialsLeaveNoBindingOrReceipt() throws Exception {
    var u = user();
    var g = guests.create();
    guests.revoke(g.guestToken());
    assertEquals(403, claim(u, g.guestToken(), UUID.randomUUID().toString()).statusCode());
    assertEquals(403, claim(u, TokenSecrets.generate(), UUID.randomUUID().toString()).statusCode());
    var expired = guests.create();
    jdbc.update(
        "UPDATE guest_credentials SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 days',expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE principal_id=?",
        expired.principalId());
    assertEquals(403, claim(u, expired.guestToken(), UUID.randomUUID().toString()).statusCode());
    assertNull(
        jdbc.queryForObject(
            "SELECT account_id FROM principals WHERE id=?", UUID.class, g.principalId()));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
  }

  // 两个账号并发接续只能一个成功；数据库锁而非客户端主体ID决定归属。
  @Test
  void concurrentAccountsCannotStealGuest() throws Exception {
    var first = user();
    var second = user();
    var g = guests.create();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> claim(first, g.guestToken(), UUID.randomUUID().toString()).statusCode());
      var b =
          pool.submit(
              () -> claim(second, g.guestToken(), UUID.randomUUID().toString()).statusCode());
      var statuses = new ArrayList<>(List.of(a.get(), b.get()));
      Collections.sort(statuses);
      assertEquals(List.of(200, 403), statuses);
    }
  }

  // 认证成功后的真实处理器接续游客；无效凭证只导致claim失败，不丢失访问会话。
  @Test
  void loginIntegratesRealClaimAndKeepsSessionOnExpectedFailure() {
    var u = user();
    var g = guests.create();
    var result = login(u, g.guestToken());
    assertEquals("claimed", result.session().claimStatus());
    assertEquals(
        u.id(),
        jdbc.queryForObject(
            "SELECT account_id FROM principals WHERE id=?", UUID.class, g.principalId()));
    var another = user();
    var failed = login(another, TokenSecrets.generate());
    assertEquals("failed", failed.session().claimStatus());
    assertNotNull(failed.session().accessToken());
  }

  // 独立重试必须有UUID幂等键且只能接受guestToken，不能靠principalId接续。
  @Test
  void rejectsMissingMalformedKeyAndIdentityInjection() throws Exception {
    var u = user();
    var g = guests.create();
    assertEquals(
        422,
        request(
                "POST",
                "/v1/me/guest-claims",
                Map.of("guestToken", g.guestToken()),
                u.token(),
                null,
                null)
            .statusCode());
    assertEquals(422, claim(u, g.guestToken(), "bad").statusCode());
    assertEquals(
        422,
        request(
                "POST",
                "/v1/me/guest-claims",
                Map.of("guestToken", g.guestToken(), "principalId", g.principalId()),
                u.token(),
                "Idempotency-Key",
                UUID.randomUUID().toString())
            .statusCode());
  }

  // 回执写入故障时，接续归属和撤销必须回滚，游客秘密仍可用于后续安全重试。
  @Test
  void databaseFailureRollsBackBindingRevocationAndReceipt() throws Exception {
    var u = user();
    var g = guests.create();
    jdbc.execute(
        "CREATE FUNCTION reject_claim_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test receipt failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_claim_receipt BEFORE INSERT ON idempotency_records FOR EACH ROW EXECUTE FUNCTION reject_claim_receipt()");
    var failure = claim(u, g.guestToken(), UUID.randomUUID().toString());
    assertEquals(500, failure.statusCode());
    assertFalse(failure.body().contains("test receipt failure"));
    assertEquals(g.principalId(), guests.requirePrincipal(g.guestToken()));
    assertNull(
        jdbc.queryForObject(
            "SELECT account_id FROM principals WHERE id=?", UUID.class, g.principalId()));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
    jdbc.execute("DROP TRIGGER reject_claim_receipt ON idempotency_records");
    assertEquals(200, claim(u, g.guestToken(), UUID.randomUUID().toString()).statusCode());
  }
}

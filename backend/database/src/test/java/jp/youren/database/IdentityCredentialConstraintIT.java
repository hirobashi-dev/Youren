package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class IdentityCredentialConstraintIT extends DatabaseTestSupport {
  // 游客到期时间必填且晚于创建；防止产生永不过期或创建时已无效的凭证。
  @Test
  void guestExpiryMustExistAndFollowCreation() {
    flyway.migrate();
    jdbc.execute(
        "INSERT INTO principals(id,kind) VALUES ('00000000-0000-0000-0000-000000000001','guest')");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.execute(
                "INSERT INTO guest_credentials(principal_id,secret_hash) VALUES ('00000000-0000-0000-0000-000000000001','missing-expiry')"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.execute(
                "INSERT INTO guest_credentials(principal_id,secret_hash,expires_at) VALUES ('00000000-0000-0000-0000-000000000001','bad-expiry',CURRENT_TIMESTAMP - INTERVAL '1 second')"));
  }

  // 无访问摘要的旧格式只能保存为已撤销历史；新有效会话必须具有成对摘要及到期时间。
  @Test
  void activeSessionsRequireIndependentAccessCredentials() {
    flyway.migrate();
    jdbc.execute(
        "INSERT INTO accounts(id,email_normalized) VALUES ('00000000-0000-0000-0000-000000000001','constraint@example.test')");
    String base =
        "INSERT INTO sessions(account_id,refresh_hash,family_id,expires_at,platform,access_hash,access_expires_at,revoked_at) VALUES ('00000000-0000-0000-0000-000000000001',?,'00000000-0000-0000-0000-000000000002',CURRENT_TIMESTAMP + INTERVAL '30 days','android',?,?,?)";
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(base, "no-access", null, null, null));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(base, "unpaired", "access", null, null));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                base, "late-access", "access", java.time.OffsetDateTime.now().plusDays(31), null));
    assertEquals(1, jdbc.update(base, "legacy", null, null, java.time.OffsetDateTime.now()));
    assertEquals(
        1,
        jdbc.update(base, "valid", "access", java.time.OffsetDateTime.now().plusMinutes(15), null));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                base,
                "duplicate-access",
                "access",
                java.time.OffsetDateTime.now().plusMinutes(15),
                null));
  }
}

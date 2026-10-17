package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class GuestCredentialMapperIT extends DatabaseTestSupport {
  private static final String MAPPER = "jp.youren.database.mapper.GuestCredentialMapper.";

  // 参数化XML真实查询：到期瞬间、撤销、已接续主体均拒绝；注入文本不能变成SQL。
  @Test
  void filtersExpiredRevokedAndClaimedCredentialsAndBindsInput() throws Exception {
    flyway.migrate();
    var sql = new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource));
    var id = UUID.randomUUID();
    var principal = UUID.randomUUID();
    var created = OffsetDateTime.parse("2026-10-04T00:00:00Z");
    var expiry = created.plusDays(30);
    assertDoesNotThrow(
        () ->
            sql.insert(MAPPER + "insertPrincipal", Map.of("id", principal, "createdAt", created)));
    sql.insert(
        MAPPER + "insertCredential",
        Map.of(
            "id",
            id,
            "principalId",
            principal,
            "secretHash",
            "a".repeat(64),
            "createdAt",
            created,
            "expiresAt",
            expiry));
    assertNotNull(
        sql.selectOne(MAPPER + "findActive", Map.of("secretHash", "a".repeat(64), "now", created)));
    assertNull(
        sql.selectOne(MAPPER + "findActive", Map.of("secretHash", "a".repeat(64), "now", expiry)));
    assertNull(
        sql.selectOne(MAPPER + "findActive", Map.of("secretHash", "' OR 1=1 --", "now", created)));
    assertEquals(
        1,
        sql.update(
            MAPPER + "revoke", Map.of("secretHash", "a".repeat(64), "now", created.plusHours(1))));
    assertNull(
        sql.selectOne(MAPPER + "findActive", Map.of("secretHash", "a".repeat(64), "now", created)));
    assertEquals(
        0,
        sql.update(
            MAPPER + "revoke", Map.of("secretHash", "a".repeat(64), "now", created.plusHours(2))));
    jdbc.update("UPDATE guest_credentials SET revoked_at=NULL");
    jdbc.update("UPDATE principals SET claimed_at=? WHERE id=?", created, principal);
    assertNull(
        sql.selectOne(MAPPER + "findActive", Map.of("secretHash", "a".repeat(64), "now", created)));
  }

  // 凭证唯一摘要冲突时事务回滚新主体，不能遗留只有主体的半成品。
  @Test
  void duplicateCredentialFailureRollsBackAssociatedPrincipal() throws Exception {
    flyway.migrate();
    var sql = new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource));
    var created = OffsetDateTime.parse("2026-10-04T00:00:00Z");
    var first = UUID.randomUUID();
    assertDoesNotThrow(
        () -> sql.insert(MAPPER + "insertPrincipal", Map.of("id", first, "createdAt", created)));
    sql.insert(
        MAPPER + "insertCredential",
        Map.of(
            "id",
            UUID.randomUUID(),
            "principalId",
            first,
            "secretHash",
            "b".repeat(64),
            "createdAt",
            created,
            "expiresAt",
            created.plusDays(30)));
    var second = UUID.randomUUID();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            tx.executeWithoutResult(
                status -> {
                  sql.insert(
                      MAPPER + "insertPrincipal", Map.of("id", second, "createdAt", created));
                  sql.insert(
                      MAPPER + "insertCredential",
                      Map.of(
                          "id",
                          UUID.randomUUID(),
                          "principalId",
                          second,
                          "secretHash",
                          "b".repeat(64),
                          "createdAt",
                          created,
                          "expiresAt",
                          created.plusDays(30)));
                }));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM principals", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM principals WHERE id=?", Integer.class, second));
  }
}

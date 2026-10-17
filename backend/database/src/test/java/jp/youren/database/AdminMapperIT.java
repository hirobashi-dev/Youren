package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import jp.youren.database.mapper.AdminMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

class AdminMapperIT extends DatabaseTestSupport {
  // 验证一次性初始化可创建独立管理员账号，并通过挑战表持久化失败次数。
  @Test
  void persistsAdminAccountAndMfaChallengeFailures() throws Exception {
    flyway.migrate();
    var mapper =
        new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource))
            .getMapper(AdminMapper.class);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var adminId = UUID.randomUUID();
    var challengeId = UUID.randomUUID();
    assertTrue(mapper.adminReports(null, 50).isEmpty());

    assertEquals(
        1,
        mapper.insertAccount(
            adminId, "first-reviewer", "password-hash", "reviewer", "encrypted-seed", now));
    assertEquals(1, mapper.countAccounts());
    assertEquals("active", mapper.accountByLogin("first-reviewer").status());
    assertEquals(1, mapper.insertChallenge(challengeId, adminId, now, now.plusMinutes(5)));
    assertEquals(challengeId, mapper.lockChallenge(challengeId).id());
    assertEquals(1, mapper.failChallenge(challengeId, now));
    assertEquals(1, mapper.lockChallenge(challengeId).failedAttempts());
  }

  // 举报仅能针对当前公开对象，且审核动作须保存实际操作者、理由和查看版本。
  @Test
  void reportsVisiblePostAndStoresVersionedModerationAudit() throws Exception {
    flyway.migrate();
    var mapper =
        new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource))
            .getMapper(AdminMapper.class);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var owner = UUID.randomUUID();
    var admin = UUID.randomUUID();
    var post = UUID.randomUUID();
    var report = UUID.randomUUID();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO principals(id,kind,created_at,updated_at) VALUES ('"
              + owner
              + "','guest',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
      statement.execute(
          "INSERT INTO admin_accounts(id,login_name,password_hash,status,role,mfa_secret_ciphertext,created_at,updated_at) VALUES ('"
              + admin
              + "','reviewer','hash','active','reviewer','cipher',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
      statement.execute(
          "INSERT INTO posts(id,author_principal_id,display_mode,scope,category,title,body,status,published_at,expires_at,created_at,updated_at) VALUES ('"
              + post
              + "','"
              + owner
              + "','guest','public','general','标题','正文','visible','"
              + now
              + "','"
              + now.plusYears(1)
              + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    assertTrue(mapper.reportablePost(post, now));
    assertFalse(mapper.reportablePost(UUID.randomUUID(), now));
    assertEquals(1, mapper.insertReport(report, owner, "post", post, "spam", "说明", now));
    assertEquals("open", mapper.reportById(report).status());
    assertEquals(1, mapper.resolveReport(report, 1, now));
    assertEquals("resolved", mapper.reportById(report).status());
    var actionId = UUID.randomUUID();
    assertEquals(
        1,
        mapper.insertModerationAction(
            actionId, admin, report, "post", post, "hide", "人工核实", 1, now));
    assertEquals("hide", mapper.actionById(actionId).action());
    assertEquals(1, mapper.reviewPost(post, 1, "hidden", now));
    assertEquals("hidden", mapper.lockReviewPost(post).status());
    assertEquals(0, mapper.reviewPost(post, 1, "visible", now));
  }
}

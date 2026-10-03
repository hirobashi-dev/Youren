package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConstraintIT extends DatabaseTestSupport {
  @BeforeEach
  void migrate() {
    flyway.migrate();
  }

  private UUID account(String email) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO accounts(id,email_normalized) VALUES (?,?)", id, email);
    return id;
  }

  // 唯一、CHECK、FK和年龄自声明边界均由数据库真实拒绝。
  @Test
  void rejectsDuplicateEmailInvalidStateForeignKeyAndVerifiedAge() {
    var id = account("unique@example.test");
    assertThrows(DataIntegrityViolationException.class, () -> account("unique@example.test"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("INSERT INTO blocks(blocker_id,blocked_id) VALUES (?,?)", id, id));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO principals(kind,account_id) VALUES ('account',?)", UUID.randomUUID()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO age_declarations(account_id,status,statement_version) VALUES (?,'verified','v1')",
                id));
  }

  // 保留一年按UTC日历年计算，闰日到下一年二月底，编辑不可延长期限。
  @Test
  void preservesCalendarYearExpiryAcrossEdits() {
    var account = account("expiry@example.test");
    var principal = UUID.randomUUID();
    var post = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO principals(id,kind,account_id) VALUES (?,'account',?)", principal, account);
    jdbc.update(
        "INSERT INTO posts(id,author_principal_id,title,body,status,published_at) VALUES (?,?,'保留期','正文','visible',?)",
        post,
        principal,
        OffsetDateTime.parse("2028-02-29T08:00:00Z"));
    assertEquals(
        OffsetDateTime.parse("2029-02-28T08:00:00Z"),
        jdbc.queryForObject("SELECT expires_at FROM posts WHERE id=?", OffsetDateTime.class, post));
    jdbc.update("UPDATE posts SET body='修改', expires_at='2035-01-01T00:00:00Z' WHERE id=?", post);
    assertEquals(
        OffsetDateTime.parse("2029-02-28T08:00:00Z"),
        jdbc.queryForObject("SELECT expires_at FROM posts WHERE id=?", OffsetDateTime.class, post));
  }

  // 群主、有效人数及群会话必须同一事务提交；不完整群整体回滚。
  @Test
  void enforcesDeferredGroupIntegrityAtCommit() {
    var owner = account("owner@example.test");
    var group = UUID.randomUUID();
    var badGroup = UUID.randomUUID();
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO groups(id,creator_id,owner_id,name,active_count) VALUES (?,?,?,'测试群',1)",
              group,
              owner,
              owner);
          jdbc.update(
              "INSERT INTO group_members(group_id,account_id,role) VALUES (?,?,'owner')",
              group,
              owner);
          jdbc.update("INSERT INTO conversations(type,group_id) VALUES ('group',?)", group);
        });
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE groups SET active_count=101 WHERE id=?", group));
    assertThrows(
        RuntimeException.class,
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.update(
                        "INSERT INTO groups(id,creator_id,owner_id,name) VALUES (?,?,?,'不完整')",
                        badGroup,
                        owner,
                        owner)));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM groups WHERE id=?", Integer.class, badGroup));
  }

  // 普通私聊和恋爱会话可以独立存在，不能因参与者相同互相覆盖。
  @Test
  void permitsSeparateDirectAndDatingConversations() {
    var first = account("first@example.test");
    var second = account("second@example.test");
    var low = first.toString().compareTo(second.toString()) < 0 ? first : second;
    var high = low.equals(first) ? second : first;
    var match = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO matches(id,account_low_id,account_high_id) VALUES (?,?,?)", match, low, high);
    jdbc.update(
        "INSERT INTO conversations(type,account_low_id,account_high_id) VALUES ('direct',?,?)",
        low,
        high);
    jdbc.update(
        "INSERT INTO conversations(type,account_low_id,account_high_id,match_id) VALUES ('dating',?,?,?)",
        low,
        high,
        match);
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
  }
}

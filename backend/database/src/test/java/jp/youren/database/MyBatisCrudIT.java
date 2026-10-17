package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import jp.youren.database.mapper.AccountMapper;
import jp.youren.database.mapper.HealthMapper;
import jp.youren.database.mapper.MediaMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MyBatisCrudIT extends DatabaseTestSupport {
  // Mapper XML参数绑定和UUID结果映射必须在真实PostgreSQL运行。
  @Test
  void readsWritesUpdatesDeletesAndBindsInputLiterally() throws Exception {
    flyway.migrate();
    var session = new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource));
    assertEquals(1, session.getMapper(HealthMapper.class).ping());
    var mapper = session.getMapper(AccountMapper.class);
    var id = UUID.randomUUID();
    var email = "quote' OR 1=1 --@example.test";
    assertEquals(1, mapper.insert(id, email));
    assertEquals(email, mapper.find(id).email());
    assertEquals(id, mapper.find(id).id());
    assertEquals(1, mapper.updateVersion(id, 2));
    assertEquals(2, mapper.find(id).authVersion());
    assertEquals(1, mapper.delete(id));
    assertNull(mapper.find(id));
  }

  // 服务层事务与Mapper共用DataSource，业务失败后不能留下部分账号。
  @Test
  void rollsBackMapperWritesOnFailure() throws Exception {
    flyway.migrate();
    var mapper =
        new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource))
            .getMapper(AccountMapper.class);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var id = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.executeWithoutResult(
                status -> {
                  mapper.insert(id, "rollback@example.test");
                  throw new IllegalStateException("业务回滚");
                }));
    assertNull(mapper.find(id));
  }

  // PostgreSQL集成验证媒体状态只能按生命周期推进，未处理对象不会被标成技术ready。
  @Test
  void persistsOwnedMediaAndProcessingVariants() throws Exception {
    flyway.migrate();
    var session = new SqlSessionTemplate(new DatabaseConfiguration().sqlSessionFactory(dataSource));
    var owner = UUID.randomUUID();
    var mediaId = UUID.randomUUID();
    var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO principals(id,kind,created_at,updated_at) VALUES ('"
              + owner
              + "','guest',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    var mapper = session.getMapper(MediaMapper.class);
    String uploadDigest = "intent-digest";
    assertEquals(
        1,
        mapper.insertAsset(
            mediaId,
            owner,
            "board",
            UUID.randomUUID().toString(),
            "image/jpeg",
            128,
            uploadDigest,
            now.plusMinutes(10),
            now.plusHours(24),
            now));
    assertEquals("initiated", mapper.findForOwner(mediaId, owner).status());
    assertEquals(
        1,
        mapper.markUploaded(
            mediaId,
            owner,
            "image/jpeg",
            128,
            UUID.randomUUID().toString(),
            uploadDigest,
            "digest",
            now));
    assertEquals(1, mapper.markProcessing(mediaId, owner, now));
    assertEquals(
        1, mapper.completeProcessing(mediaId, owner, "image/jpeg", 64, 4, 4, "digest", now));
    assertEquals(
        1,
        mapper.insertVariant(
            mediaId,
            new MediaMapper.Variant("display", UUID.randomUUID().toString(), 32, 4, 4),
            now));
    assertEquals("approved", mapper.findForOwner(mediaId, owner).status());
    assertNull(mapper.findForOwner(mediaId, UUID.randomUUID()));
  }
}

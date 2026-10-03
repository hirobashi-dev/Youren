package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import jp.youren.database.mapper.AccountMapper;
import jp.youren.database.mapper.HealthMapper;
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
}

package jp.youren.database;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 每个测试类使用新容器，每项测试使用新schema；不接受外部数据库URL。
@Testcontainers(disabledWithoutDocker = false)
abstract class DatabaseTestSupport {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");
  HikariDataSource dataSource;
  JdbcTemplate jdbc;
  Flyway flyway;

  @BeforeEach
  void prepare() {
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .load();
  }

  @AfterEach
  void release() {
    if (dataSource != null) dataSource.close();
  }
}

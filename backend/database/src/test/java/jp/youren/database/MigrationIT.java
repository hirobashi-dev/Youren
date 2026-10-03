package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

class MigrationIT extends DatabaseTestSupport {
  // 先建立可信V1指纹，再核对旧迁移结构和历史，baseline不得重放已有业务表。
  @Test
  void adoptsAuditedLegacySchemaWithoutChangingData() throws Exception {
    flyway.migrate();
    String fingerprint = LegacyMigrationAdoption.fingerprint(dataSource);
    assertEquals(
        Files.readString(Path.of("src/main/resources/db/v1-schema.sha256")).trim(), fingerprint);
    System.out.println("V1_SCHEMA_SHA256=" + fingerprint);
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    String source =
        Files.readString(
            Path.of(
                "../../packages/database/prisma/migrations/202610030001_initial/migration.sql"));
    jdbc.execute(source);
    jdbc.execute(
        "CREATE TABLE _prisma_migrations(migration_name text, checksum text, finished_at timestamptz, rolled_back_at timestamptz)");
    String checksum =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    jdbc.update(
        "INSERT INTO _prisma_migrations VALUES ('202610030001_initial',?,CURRENT_TIMESTAMP,NULL)",
        checksum);
    jdbc.update("INSERT INTO accounts(email_normalized) VALUES ('preserved@example.test')");
    assertEquals(fingerprint, LegacyMigrationAdoption.fingerprint(dataSource));
    LegacyMigrationAdoption.adopt(dataSource, fingerprint, checksum);
    assertEquals(0, flyway.migrate().migrationsExecuted);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM accounts WHERE email_normalized='preserved@example.test'",
            Integer.class));
    assertEquals(
        "BASELINE", jdbc.queryForObject("SELECT type FROM flyway_schema_history", String.class));
  }

  // 结构漂移或不匹配历史必须在写入Flyway历史之前拒绝，不能降低校验门槛。
  @Test
  void refusesSchemaDriftBeforeBaseline() {
    flyway.migrate();
    String fingerprint = LegacyMigrationAdoption.fingerprint(dataSource);
    jdbc.execute("DROP TABLE flyway_schema_history");
    jdbc.execute(
        "CREATE TABLE _prisma_migrations(migration_name text, checksum text, finished_at timestamptz, rolled_back_at timestamptz)");
    jdbc.update(
        "INSERT INTO _prisma_migrations VALUES ('202610030001_initial',?,CURRENT_TIMESTAMP,NULL)",
        "a".repeat(64));
    jdbc.execute("ALTER TABLE accounts ADD COLUMN unexpected text");
    assertThrows(
        IllegalStateException.class,
        () -> LegacyMigrationAdoption.adopt(dataSource, fingerprint, "a".repeat(64)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history'",
            Integer.class));
  }

  // 结构相同但历史checksum未知也不能baseline，已有数据原样保留。
  @Test
  void refusesUnknownMigrationHistory() {
    flyway.migrate();
    String fingerprint = LegacyMigrationAdoption.fingerprint(dataSource);
    jdbc.execute("DROP TABLE flyway_schema_history");
    jdbc.execute(
        "CREATE TABLE _prisma_migrations(migration_name text,checksum text,finished_at timestamptz,rolled_back_at timestamptz)");
    jdbc.update(
        "INSERT INTO _prisma_migrations VALUES ('202610030001_initial',?,CURRENT_TIMESTAMP,NULL)",
        "b".repeat(64));
    assertThrows(
        IllegalStateException.class,
        () -> LegacyMigrationAdoption.adopt(dataSource, fingerprint, "a".repeat(64)));
  }

  // 新库只执行一次初始迁移，54张业务表齐全，重复部署无DDL。
  @Test
  void migratesEmptyDatabaseAndRepeatedDeploymentIsNoOp() {
    assertEquals(1, flyway.migrate().migrationsExecuted);
    assertEquals(
        54,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'",
            Integer.class));
    assertEquals(0, flyway.migrate().migrationsExecuted);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
    assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM policy_configs", Integer.class));
  }

  // 未审计的非空库拒绝自动baseline，clean禁止删除结构。
  @Test
  void refusesUnmanagedNonEmptyDatabaseAndClean() {
    jdbc.execute("CREATE TABLE unrelated_data(id integer)");
    assertThrows(FlywayException.class, () -> flyway.migrate());
    assertThrows(FlywayException.class, () -> flyway.clean());
    assertEquals(
        "unrelated_data",
        jdbc.queryForObject(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public'",
            String.class));
  }
}

package jp.youren.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

class MigrationIT extends DatabaseTestSupport {
  // 先建立可信V1指纹，再核对旧迁移结构和历史，baseline不得重放已有业务表。
  @Test
  void adoptsAuditedLegacySchemaWithoutChangingData() throws Exception {
    targetV1();
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
    targetV1();
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
    targetV1();
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

  // 新库执行V1/V2/V3，55张业务表齐全，重复部署无DDL；冻结基线仍为V1。
  @Test
  void migratesEmptyDatabaseAndRepeatedDeploymentIsNoOp() {
    assertEquals(3, flyway.migrate().migrationsExecuted);
    assertEquals(
        55,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'",
            Integer.class));
    assertEquals(0, flyway.migrate().migrationsExecuted);
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
    assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM policy_configs", Integer.class));
  }

  // V1升级保留原摘要，到期回填从创建时间计算；旧账号会话必须撤销，不能伪造访问秘密。
  @Test
  void upgradesExistingCredentialsAndInvalidatesLegacySessions() {
    targetV1();
    flyway.migrate();
    jdbc.execute(
        "INSERT INTO principals(id,kind) VALUES ('00000000-0000-0000-0000-000000000001','guest')");
    jdbc.execute(
        "INSERT INTO guest_credentials(principal_id,secret_hash,created_at) VALUES ('00000000-0000-0000-0000-000000000001','legacy-hash','2026-09-01T00:00:00Z')");
    jdbc.execute(
        "INSERT INTO accounts(id,email_normalized) VALUES ('00000000-0000-0000-0000-000000000002','upgrade@example.test')");
    jdbc.execute(
        "INSERT INTO sessions(account_id,refresh_hash,family_id,expires_at,platform) VALUES ('00000000-0000-0000-0000-000000000002','legacy-refresh','00000000-0000-0000-0000-000000000003',CURRENT_TIMESTAMP + INTERVAL '30 days','android')");
    flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .load();
    assertEquals(2, flyway.migrate().migrationsExecuted);
    assertEquals(
        "2026-10-01T00:00:00Z",
        jdbc.queryForObject(
                "SELECT expires_at FROM guest_credentials", java.time.OffsetDateTime.class)
            .toInstant()
            .toString());
    assertEquals(
        "legacy-hash",
        jdbc.queryForObject("SELECT secret_hash FROM guest_credentials", String.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM sessions WHERE revoked_at IS NOT NULL AND access_hash IS NULL AND access_expires_at IS NULL",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname='sessions_family_id_idx'",
            Integer.class));
  }

  // 指纹和历史接管固定在V1；后续迁移不能改变冻结基线的比较对象。
  private void targetV1() {
    flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("1")
            .cleanDisabled(true)
            .load();
  }

  // V2到V3只新增草稿与审核标记；旧资料没有批准证据，默认待审且原文本/version不变。
  @Test
  void upgradesProfilesWithoutPublishingUnreviewedLegacyValues() {
    var v2 =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("2")
            .load();
    v2.migrate();
    jdbc.execute(
        "INSERT INTO accounts(id,email_normalized) VALUES('00000000-0000-0000-0000-000000000004','profile-upgrade@example.test')");
    jdbc.execute(
        "INSERT INTO profiles(account_id,nickname,bio,version) VALUES('00000000-0000-0000-0000-000000000004','旧资料','原简介',7)");
    assertEquals(1, flyway.migrate().migrationsExecuted);
    assertEquals(
        "pending", jdbc.queryForObject("SELECT moderation_status FROM profiles", String.class));
    assertEquals("原简介", jdbc.queryForObject("SELECT bio FROM profiles", String.class));
    assertEquals(7, jdbc.queryForObject("SELECT version FROM profiles", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM profile_drafts", Integer.class));
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

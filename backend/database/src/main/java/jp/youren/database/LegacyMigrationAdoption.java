package jp.youren.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

// 接续仅新增Flyway历史，不修改业务表；结构和旧迁移历史必须同时一致。
public final class LegacyMigrationAdoption {
  private LegacyMigrationAdoption() {}

  public static String fingerprint(DataSource source) {
    var jdbc = new JdbcTemplate(source);
    // 使用逻辑定义而非OID比较；忽略两套工具自身的历史表。
    String sql =
        """
      WITH business AS (SELECT oid,relname FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='r' AND relname NOT IN ('flyway_schema_history','_prisma_migrations')),
      definitions AS (
        SELECT 'column:'||b.relname||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'')||':'||a.attidentity::text||':'||a.attgenerated::text AS definition
        FROM business b JOIN pg_attribute a ON a.attrelid=b.oid LEFT JOIN pg_attrdef d ON d.adrelid=b.oid AND d.adnum=a.attnum WHERE a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||b.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.condeferrable||':'||c.condeferred FROM business b JOIN pg_constraint c ON c.conrelid=b.oid
        UNION ALL SELECT 'index:'||b.relname||':'||pg_get_indexdef(i.indexrelid) FROM business b JOIN pg_index i ON i.indrelid=b.oid
        UNION ALL SELECT 'trigger:'||b.relname||':'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM business b JOIN pg_trigger t ON t.tgrelid=b.oid WHERE NOT t.tgisinternal
        UNION ALL SELECT 'table:'||relname FROM business
        UNION ALL SELECT 'function:'||pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.prokind='f'
        UNION ALL SELECT 'policy:'||key||':'||value::text FROM policy_configs
      ) SELECT definition FROM definitions ORDER BY definition COLLATE "C"
      """;
    try {
      String content = String.join("\n", jdbc.queryForList(sql, String.class));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256不可用");
    }
  }

  public static void adopt(DataSource source, String expectedSchema, String expectedMigration) {
    verifyLegacy(source, expectedSchema, expectedMigration);
    // 显式baseline版本1，之后migrate从V2开始；不开放自动baseline或clean。
    Flyway.configure()
        .dataSource(source)
        .baselineVersion("1")
        .baselineDescription("Audited Prisma initial schema")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .load()
        .baseline();
  }

  public static void verifyLegacy(
      DataSource source, String expectedSchema, String expectedMigration) {
    var jdbc = new JdbcTemplate(source);
    if (expectedSchema == null
        || !expectedSchema.matches("[0-9a-f]{64}")
        || expectedMigration == null
        || !expectedMigration.matches("[0-9a-f]{64}")
        || !expectedSchema.equals(fingerprint(source)))
      throw new IllegalStateException("旧库结构与V1不一致，拒绝baseline");
    Integer total = jdbc.queryForObject("SELECT count(*) FROM _prisma_migrations", Integer.class);
    Integer matching =
        jdbc.queryForObject(
            "SELECT count(*) FROM _prisma_migrations WHERE migration_name='202610030001_initial' AND checksum=? AND finished_at IS NOT NULL AND rolled_back_at IS NULL",
            Integer.class,
            expectedMigration);
    if (total == null || total != 1 || matching == null || matching != 1)
      throw new IllegalStateException("旧迁移历史不一致，拒绝baseline");
  }
}

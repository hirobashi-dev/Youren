package jp.youren.database;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import jp.youren.shared.config.RuntimeSettings;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

// 独立受控迁移入口，API和worker不自动改表；错误不回显配置或数据库异常。
public final class MigrationCommand {
  private MigrationCommand() {}

  public static void main(String[] args) {
    try {
      if (args.length != 1 || !Set.of("audit-legacy", "adopt-legacy", "migrate").contains(args[0]))
        throw new IllegalArgumentException();
      var settings = RuntimeSettings.from(System.getenv());
      try (var source = new HikariDataSource()) {
        source.setJdbcUrl(settings.databaseUrl());
        source.setUsername(settings.username());
        source.setPassword(settings.password());
        source.setMaximumPoolSize(2);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(2000);
        source.addDataSourceProperty("connectTimeout", 2);
        source.addDataSourceProperty("socketTimeout", 2);
        var flyway =
            Flyway.configure()
                .dataSource(source)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        if (args[0].equals("migrate")) {
          flyway.migrate();
        } else {
          String checksum =
              new JdbcTemplate(source)
                  .queryForObject(
                      "SELECT checksum FROM _prisma_migrations WHERE migration_name='202610030001_initial' AND finished_at IS NOT NULL AND rolled_back_at IS NULL",
                      String.class);
          String allowed = resource("/db/legacy-checksums.txt");
          if (checksum == null || !allowed.lines().anyMatch(checksum::equals))
            throw new IllegalStateException();
          String expected = resource("/db/v1-schema.sha256").trim();
          if (args[0].equals("audit-legacy"))
            LegacyMigrationAdoption.verifyLegacy(source, expected, checksum);
          else LegacyMigrationAdoption.adopt(source, expected, checksum);
        }
        System.out.println("{\"status\":\"ok\",\"operation\":\"" + args[0] + "\"}");
      }
    } catch (Exception error) {
      System.err.println("{\"status\":\"failed\",\"event\":\"migration_refused\"}");
      System.exit(1);
    }
  }

  private static String resource(String name) throws java.io.IOException {
    try (var input = MigrationCommand.class.getResourceAsStream(name)) {
      if (input == null) throw new IllegalStateException();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

package jp.youren.shared.config;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

// 启动前验证依赖配置；异常只包含字段名，禁止嵌套解析异常泄漏凭据。
public record RuntimeSettings(
    String databaseUrl,
    String username,
    String password,
    String redisUrl,
    int port,
    int readinessTimeoutMs,
    String logLevel) {
  public static RuntimeSettings from(Map<String, String> env) {
    String database = required(env, "SPRING_DATASOURCE_URL");
    String username = required(env, "SPRING_DATASOURCE_USERNAME");
    String password = required(env, "SPRING_DATASOURCE_PASSWORD");
    String redis = required(env, "SPRING_DATA_REDIS_URL");
    validateUrl(database, "SPRING_DATASOURCE_URL", true);
    validateUrl(redis, "SPRING_DATA_REDIS_URL", false);
    int port = integer(env, "SERVER_PORT", 3000, 65535);
    int timeout = integer(env, "READINESS_TIMEOUT_MS", 1500, 10000);
    String level = env.getOrDefault("LOG_LEVEL", "info");
    if (!java.util.Set.of("info", "warn", "error").contains(level)) {
      throw new IllegalArgumentException("LOG_LEVEL 不正确");
    }
    return new RuntimeSettings(database, username, password, redis, port, timeout, level);
  }

  private static String required(Map<String, String> env, String field) {
    String value = env.get(field);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 未配置");
    return value;
  }

  private static void validateUrl(String value, String field, boolean jdbc) {
    try {
      if (jdbc && !value.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
      URI uri = URI.create(jdbc ? value.substring(5) : value);
      String scheme = uri.getScheme();
      if (scheme == null
          || !(jdbc
              ? scheme.equals("postgresql")
              : java.util.Set.of("redis", "rediss").contains(scheme))
          || uri.getHost() == null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || uri.getFragment() != null) throw new IllegalArgumentException();
      // JDBC凭据只允许独立字段，避免URL成为误输出秘密的渠道。
      if (jdbc
          && (uri.getUserInfo() != null
              || uri.getPath() == null
              || uri.getPath().length() < 2
              || (uri.getRawQuery() != null
                  && uri.getRawQuery().toLowerCase(Locale.ROOT).matches(".*(password|user)=.*"))))
        throw new IllegalArgumentException();
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(field + " 格式不正确");
    }
  }

  private static int integer(Map<String, String> env, String field, int fallback, int maximum) {
    String raw = env.getOrDefault(field, Integer.toString(fallback));
    try {
      if (!raw.matches("[0-9]+")) throw new IllegalArgumentException();
      int number = Integer.parseInt(raw);
      if (number < 1 || number > maximum) throw new IllegalArgumentException();
      return number;
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(field + " 不正确");
    }
  }

  // record默认toString会包含所有字段，显式覆盖以保护数据库及Redis秘密。
  @Override
  public String toString() {
    return "RuntimeSettings[dependencies=redacted, port="
        + port
        + ", readinessTimeoutMs="
        + readinessTimeoutMs
        + ", logLevel="
        + logLevel
        + "]";
  }
}

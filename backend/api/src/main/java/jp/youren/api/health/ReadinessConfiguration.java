package jp.youren.api.health;

import jp.youren.database.mapper.HealthMapper;
import jp.youren.shared.config.RuntimeSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

// 两个依赖必须同时可用；只读取连通性状态，不读取用户内容。
@Configuration
public class ReadinessConfiguration {
  @Bean
  public ReadinessService readinessService(
      HealthMapper database, StringRedisTemplate redis, RuntimeSettings settings) {
    return new ReadinessService(
        () -> {
          if (database.ping() != 1) return false;
          try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping());
          }
        },
        settings.readinessTimeoutMs());
  }
}

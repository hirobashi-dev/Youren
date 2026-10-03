package jp.youren.shared.config;

import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// 两个服务共用受限连接配置；不读取Node .env，不在日志打印凭据。
@Configuration
public class RuntimeDependenciesConfiguration {
  @Bean
  public RuntimeSettings runtimeSettings(Environment environment) {
    var values = new HashMap<String, String>();
    for (String field :
        new String[] {
          "SPRING_DATASOURCE_URL",
          "SPRING_DATASOURCE_USERNAME",
          "SPRING_DATASOURCE_PASSWORD",
          "SPRING_DATA_REDIS_URL",
          "SERVER_PORT",
          "READINESS_TIMEOUT_MS",
          "LOG_LEVEL"
        }) {
      var value = environment.getProperty(field);
      if (value != null) values.put(field, value);
    }
    return RuntimeSettings.from(values);
  }

  @Bean(destroyMethod = "close")
  public HikariDataSource dataSource(RuntimeSettings settings) {
    var source = new HikariDataSource();
    source.setJdbcUrl(settings.databaseUrl());
    source.setUsername(settings.username());
    source.setPassword(settings.password());
    source.setMaximumPoolSize(4);
    source.setMinimumIdle(0);
    source.setConnectionTimeout(1500);
    source.setValidationTimeout(500);
    source.setConnectionInitSql("SET TIME ZONE 'UTC'");
    source.addDataSourceProperty("connectTimeout", 1);
    source.addDataSourceProperty("socketTimeout", 1);
    return source;
  }

  @Bean
  public LettuceConnectionFactory redisConnectionFactory(RuntimeSettings settings) {
    var uri = URI.create(settings.redisUrl());
    var server =
        new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort());
    if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
      server.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
    if (uri.getUserInfo() != null) {
      var credentials = uri.getUserInfo().split(":", 2);
      if (credentials.length == 2) {
        if (!credentials[0].isEmpty()) server.setUsername(credentials[0]);
        server.setPassword(RedisPassword.of(credentials[1]));
      } else server.setPassword(RedisPassword.of(credentials[0]));
    }
    // 断连命令拒绝排队；驱动自身超时和HTTP等待超时共同约束故障路径。
    var options =
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
            .build();
    var client =
        LettuceClientConfiguration.builder()
            .clientOptions(options)
            .commandTimeout(Duration.ofMillis(1000))
            .shutdownTimeout(Duration.ofMillis(100));
    if (uri.getScheme().equals("rediss")) client.useSsl();
    return new LettuceConnectionFactory(server, client.build());
  }

  @Bean
  public StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
    return new StringRedisTemplate(factory);
  }
}

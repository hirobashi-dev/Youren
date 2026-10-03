package jp.youren.worker;

import java.util.concurrent.CountDownLatch;
import jp.youren.database.DatabaseConfiguration;
import jp.youren.database.mapper.HealthMapper;
import jp.youren.shared.config.RuntimeDependenciesConfiguration;
import jp.youren.shared.config.RuntimeSettings;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

// 独立后台进程；首阶段没有任务消费，Spring管理依赖资源生命周期。
@SpringBootApplication
@Import({DatabaseConfiguration.class, RuntimeDependenciesConfiguration.class})
public class WorkerApplication {
  @Bean(initMethod = "start", destroyMethod = "close")
  public WorkerLifecycle workerLifecycle(
      HealthMapper database, StringRedisTemplate redis, RuntimeSettings settings) {
    return new WorkerLifecycle(
        () -> {
          if (database.ping() != 1) return false;
          try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping());
          }
        },
        settings.readinessTimeoutMs());
  }

  public static void main(String[] args) {
    var application = new SpringApplication(WorkerApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    application.setRegisterShutdownHook(false);
    try {
      var context = application.run(args);
      var stopped = new CountDownLatch(1);
      // 信号关闭必须释放Spring连接并输出一次安全事件，不只退出主线程。
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      context.close();
                      System.out.println("{\"event\":\"stopped\",\"service\":\"worker\"}");
                    } finally {
                      stopped.countDown();
                    }
                  },
                  "worker-shutdown"));
      System.out.println("{\"event\":\"started\",\"service\":\"worker\"}");
      stopped.await();
    } catch (Exception error) {
      System.err.println("{\"event\":\"startup_failed\",\"service\":\"worker\"}");
      System.exit(1);
    }
  }
}

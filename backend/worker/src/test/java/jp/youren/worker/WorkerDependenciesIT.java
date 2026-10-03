package jp.youren.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

// 在Linux Java21容器内测试真实JAR及OS信号，不能用Windows强杀冒称优雅退出。
@Testcontainers(disabledWithoutDocker = false)
class WorkerDependenciesIT {
  private static final String JRE =
      "eclipse-temurin@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5";
  private final String jar =
      Path.of("target/youren-worker-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString();

  private GenericContainer<?> worker(Network network, Map<String, String> environment) {
    return new GenericContainer<>(JRE)
        .withNetwork(network)
        .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app/worker.jar")
        .withEnv(environment)
        .withCommand("java", "-Dfile.encoding=UTF-8", "-jar", "/app/worker.jar");
  }

  // 缺配置或实际依赖不可用时进程非零退出，日志不能含测试密码。
  @Test
  void startupFailureExitsNonZeroWithoutSecretOutput() {
    try (var network = Network.newNetwork();
        var failed =
            worker(
                network,
                Map.of(
                    "SPRING_DATASOURCE_URL",
                    "jdbc:postgresql://127.0.0.1:1/missing",
                    "SPRING_DATASOURCE_USERNAME",
                    "missing",
                    "SPRING_DATASOURCE_PASSWORD",
                    "secret-value",
                    "SPRING_DATA_REDIS_URL",
                    "redis://127.0.0.1:1"))) {
      failed.withStartupCheckStrategy(
          // 测试需要观察预期非零退出；不能让框架把容器先删掉再读日志。
          new org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy() {
            @Override
            public StartupStatus checkStartupState(
                com.github.dockerjava.api.DockerClient client, String containerId) {
              var result = super.checkStartupState(client, containerId);
              return result == StartupStatus.FAILED ? StartupStatus.SUCCESSFUL : result;
            }
          }.withTimeout(Duration.ofSeconds(30)));
      failed.start();
      assertFalse(failed.getLogs().contains("secret-value"));
      assertTrue(failed.getLogs().contains("startup_failed"));
      assertNotEquals(
          0,
          failed
              .getDockerClient()
              .inspectContainerCmd(failed.getContainerId())
              .exec()
              .getState()
              .getExitCodeLong());
    }
  }

  // 实际SIGTERM及SIGINT均触发连接释放和一次stopped事件，不能依赖超时强杀。
  @Test
  void realDependenciesStartAndBothSignalsStopCleanly() throws Exception {
    try (var network = Network.newNetwork();
        var postgres =
            new PostgreSQLContainer("postgres:16.6")
                .withNetwork(network)
                .withNetworkAliases("postgres");
        var redis =
            new GenericContainer<>("redis:7.4.2-alpine")
                .withNetwork(network)
                .withNetworkAliases("redis")
                .withExposedPorts(6379)) {
      postgres.start();
      redis.start();
      var environment =
          Map.of(
              "SPRING_DATASOURCE_URL",
              "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName(),
              "SPRING_DATASOURCE_USERNAME",
              postgres.getUsername(),
              "SPRING_DATASOURCE_PASSWORD",
              postgres.getPassword(),
              "SPRING_DATA_REDIS_URL",
              "redis://redis:6379");
      for (String signal : new String[] {"SIGTERM", "SIGINT"}) {
        try (var process =
            worker(network, environment)
                .waitingFor(
                    Wait.forLogMessage(".*\\\"event\\\":\\\"started\\\".*", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)))) {
          process.start();
          var client = process.getDockerClient();
          client.killContainerCmd(process.getContainerId()).withSignal(signal).exec();
          boolean exited = false;
          for (int attempt = 0; attempt < 100; attempt++) {
            if (!Boolean.TRUE.equals(
                client
                    .inspectContainerCmd(process.getContainerId())
                    .exec()
                    .getState()
                    .getRunning())) {
              exited = true;
              break;
            }
            Thread.sleep(100);
          }
          assertTrue(exited, "信号后应在10秒内退出");
          var exit =
              client
                  .inspectContainerCmd(process.getContainerId())
                  .exec()
                  .getState()
                  .getExitCodeLong();
          assertNotEquals(137, exit);
          assertTrue(exit == 0 || exit == (signal.equals("SIGTERM") ? 143 : 130), "只能接受正常或对应信号退出码");
          var logs = process.getLogs();
          assertEquals(
              1, logs.lines().filter(line -> line.contains("\"event\":\"stopped\"")).count());
          assertFalse(logs.contains(postgres.getPassword()));
          // 进程退出后真实数据库不应保留worker的JDBC连接。
          var connections =
              postgres.execInContainer(
                  "psql",
                  "-U",
                  postgres.getUsername(),
                  "-d",
                  postgres.getDatabaseName(),
                  "-t",
                  "-c",
                  "SELECT count(*) FROM pg_stat_activity WHERE application_name='PostgreSQL JDBC Driver'");
          assertEquals("0", connections.getStdout().trim());
        }
      }
    }
  }
}

package jp.youren.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// HTTP真实访问随机端口，依赖均为本次测试临时容器，缺Docker直接失败。
@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadinessIT {
  // Docker重新启动会重新分配随机HostPort；先选空闲本机端口并固定容器绑定。
  private static final int REDIS_PORT = availablePort();

  private static int availablePort() {
    try (var socket =
        new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    } catch (java.io.IOException error) {
      throw new IllegalStateException("无法分配测试端口");
    }
  }

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.2-alpine")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(
              command ->
                  command
                      .getHostConfig()
                      .withPortBindings(
                          new com.github.dockerjava.api.model.PortBinding(
                              com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort(
                                  "127.0.0.1", REDIS_PORT),
                              new com.github.dockerjava.api.model.ExposedPort(6379))));

  @DynamicPropertySource
  static void dependencies(DynamicPropertyRegistry registry) {
    registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
    registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
    registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
    registry.add(
        "SPRING_DATA_REDIS_URL",
        () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
  }

  @LocalServerPort int port;

  private HttpResponse<String> request(HttpClient http, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(4))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // DB/Redis均正常200；停止本测试Redis后503，存活仍200，重启可恢复。
  @Test
  void realDependenciesFailAndRecoverWithoutExposingDetails() throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      assertEquals(200, request(http, "/health/ready").statusCode());
      REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
      try {
        long started = System.nanoTime();
        var failed = request(http, "/health/ready");
        assertEquals(503, failed.statusCode());
        assertEquals("{\"status\":\"unavailable\"}", failed.body());
        assertTrue(
            Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        assertEquals(200, request(http, "/health/live").statusCode());
      } finally {
        REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
      }
      boolean recovered = false;
      for (int attempt = 0; attempt < 30; attempt++) {
        if (request(http, "/health/ready").statusCode() == 200) {
          recovered = true;
          break;
        }
        Thread.sleep(200);
      }
      assertTrue(recovered, "Redis恢复后健康探测应重连");
    }
  }
}

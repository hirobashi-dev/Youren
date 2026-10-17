package jp.youren.api.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import jp.youren.api.ApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 无认证配置仍能启动健康接口；认证操作需安全失败，受保护路由不能匿名访问。
@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthConfigurationIT {
  @LocalServerPort int port;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry r) {
    r.add("SPRING_DATASOURCE_URL", () -> "jdbc:postgresql://127.0.0.1:1/test");
    r.add("SPRING_DATASOURCE_USERNAME", () -> "test");
    r.add("SPRING_DATASOURCE_PASSWORD", () -> "test");
    r.add("SPRING_DATA_REDIS_URL", () -> "redis://127.0.0.1:1");
  }

  @Test
  void missingMailAndHmacFailSafelyWithoutBreakingHealth() throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/auth/email-code"))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"email\":\"a@example.com\",\"purpose\":\"login\"}"))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
      assertTrue(response.body().contains("TEMPORARILY_UNAVAILABLE"));
      assertEquals(
          200,
          http.send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void anonymousMeRequiresPositiveAuthentication() throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      assertEquals(
          401,
          http.send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/me")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }
}

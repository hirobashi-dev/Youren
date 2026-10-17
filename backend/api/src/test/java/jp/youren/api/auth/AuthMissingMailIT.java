package jp.youren.api.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import jp.youren.api.ApiApplication;
import jp.youren.api.security.TokenSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

// HMAC已配置但生产邮件供应商缺失时，挑战消费也安全503，不触碰数据库或泄漏存在性。
@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthMissingMailIT {
  @LocalServerPort int port;
  static final String SECRET = TokenSecrets.generate();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("SPRING_DATASOURCE_URL", () -> "jdbc:postgresql://127.0.0.1:1/test");
    r.add("SPRING_DATASOURCE_USERNAME", () -> "test");
    r.add("SPRING_DATASOURCE_PASSWORD", () -> "test");
    r.add("SPRING_DATA_REDIS_URL", () -> "redis://127.0.0.1:1");
    r.add("youren.auth.challenge-secret", () -> SECRET);
  }

  @Test
  void loginWithoutConfiguredMailReturnsSafe503() throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/auth/login"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"challengeId\":\"aaaa0000-0000-0000-0000-000000000001\",\"code\":\"123456\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
      assertTrue(response.body().contains("TEMPORARILY_UNAVAILABLE"));
      assertFalse(response.body().contains("123456"));
    }
  }
}

package jp.youren.api.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import jp.youren.api.ApiApplication;
import jp.youren.api.email.EmailDelivery;
import jp.youren.api.security.TokenSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

// 隔离收件箱仅auth-test配置启用；独立密钥保护，主认证响应不返回验证码。
@ActiveProfiles("auth-test")
@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthTestInboxIT {
  static final String KEY = TokenSecrets.generate();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("SPRING_DATASOURCE_URL", () -> "jdbc:postgresql://127.0.0.1:1/test");
    r.add("SPRING_DATASOURCE_USERNAME", () -> "test");
    r.add("SPRING_DATASOURCE_PASSWORD", () -> "test");
    r.add("SPRING_DATA_REDIS_URL", () -> "redis://127.0.0.1:1");
    r.add("youren.auth.test-inbox-key", () -> KEY);
    r.add("youren.auth.challenge-secret", TokenSecrets::generate);
  }

  @Autowired EmailDelivery delivery;
  @Autowired jp.youren.api.email.TestInboxController controller;
  @LocalServerPort int port;

  HttpResponse<String> get(UUID id, String key) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__test/mail/" + id));
      if (key != null) request.header("X-Test-Inbox-Key", key);
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  // 正确配置才能发送到内存；无密钥和错误密钥都拒绝，正确密钥只读取相应挑战并禁缓存。
  @Test
  void testProfileOffersProtectedLoopbackInbox() throws Exception {
    assertTrue(delivery.configured());
    var id = UUID.randomUUID();
    delivery.deliver("inbox@example.com", "register", id, "654321", Instant.now().plusSeconds(600));
    var probe =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new jp.youren.api.http.ApiExceptionHandler())
            .build()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/__test/mail/" + id))
            .andReturn();
    assertTrue(
        probe.getResolvedException() instanceof AuthFailure,
        String.valueOf(probe.getResolvedException()));
    assertEquals(401, get(id, null).statusCode());
    assertEquals(401, get(id, TokenSecrets.generate()).statusCode());
    var response = get(id, KEY);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("654321"));
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
    assertEquals(404, get(UUID.randomUUID(), KEY).statusCode());
  }
}

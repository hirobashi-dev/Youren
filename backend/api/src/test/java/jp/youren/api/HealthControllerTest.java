package jp.youren.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import jp.youren.api.health.HealthController;
import jp.youren.api.health.ReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {
  // 存活探测不访问数据库；成功响应只暴露固定status字段。
  @Test
  void liveDoesNotProbeDependenciesAndHealthyReadyMatchesContract() throws Exception {
    var probes = new AtomicInteger();
    try (var readiness =
        new ReadinessService(
            () -> {
              probes.incrementAndGet();
              return true;
            },
            1500)) {
      var http = MockMvcBuilders.standaloneSetup(new HealthController(readiness)).build();
      http.perform(get("/health/live"))
          .andExpect(status().isOk())
          .andExpect(content().json("{\"status\":\"ok\"}", true));
      assertEquals(0, probes.get());
      http.perform(get("/health/ready"))
          .andExpect(status().isOk())
          .andExpect(content().json("{\"status\":\"ok\"}", true));
    }
  }

  // 依赖异常统一503，不能把密码或错误堆栈放到HTTP响应。
  @Test
  void dependencyErrorsAreSafeAndRetryCanRecover() throws Exception {
    var attempts = new AtomicInteger();
    try (var readiness =
        new ReadinessService(
            () -> {
              if (attempts.getAndIncrement() == 0) throw new IllegalStateException("secret-value");
              return true;
            },
            1500)) {
      var http = MockMvcBuilders.standaloneSetup(new HealthController(readiness)).build();
      http.perform(get("/health/ready"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(content().json("{\"status\":\"unavailable\"}", true));
      http.perform(get("/health/ready")).andExpect(status().isOk());
    }
  }

  // 超时返回后不叠加后台任务，关闭后拒绝新探测。
  @Test
  void timeoutKeepsSingleFlightAndShutdownRejectsNewWork() throws Exception {
    var attempts = new AtomicInteger();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var readiness =
        new ReadinessService(
            () -> {
              attempts.incrementAndGet();
              started.countDown();
              release.await();
              return true;
            },
            50)) {
      assertFalse(readiness.ready());
      assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(readiness.ready());
      assertEquals(1, attempts.get());
      release.countDown();
      readiness.close();
      assertFalse(readiness.ready());
    } finally {
      release.countDown();
    }
  }

  // 后台开发源允许CORS，其他来源不允许跨域读取。
  @Test
  void corsAllowsOnlyConfiguredDevelopmentOrigin() throws Exception {
    try (var readiness = new ReadinessService(() -> true, 1500)) {
      var http = MockMvcBuilders.standaloneSetup(new HealthController(readiness)).build();
      http.perform(get("/health/live").header("Origin", "http://localhost:5173"))
          .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
      // Vite及浏览器验收也使用127.0.0.1，须允许这个明确的本机源。
      http.perform(get("/health/live").header("Origin", "http://127.0.0.1:5173"))
          .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
      http.perform(get("/health/live").header("Origin", "https://untrusted.example"))
          .andExpect(status().isForbidden());
    }
  }
}

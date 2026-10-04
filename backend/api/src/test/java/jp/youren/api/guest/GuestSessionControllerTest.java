package jp.youren.api.guest;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.UUID;
import jp.youren.api.http.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GuestSessionControllerTest {
  // 含秘密的201响应禁止HTTP缓存，业务错误统一安全401，不带客户端提供的追踪头。
  @Test
  void secretResponseCannotBeCached() throws Exception {
    var service = mock(GuestSessionService.class);
    when(service.create())
        .thenReturn(
            new GuestSessionService.GuestSession(
                "secret-only-in-response",
                UUID.randomUUID(),
                Instant.parse("2026-11-03T00:00:00Z")));
    var http =
        MockMvcBuilders.standaloneSetup(new GuestSessionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    http.perform(post("/v1/guest-sessions"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  // 业务异常必须使用合同401错误码，不能落入500或包含请求头及原秘密。
  @Test
  void guestBusinessErrorUsesSafeAuthenticationShape() throws Exception {
    var service = mock(GuestSessionService.class);
    when(service.create()).thenThrow(new GuestCredentialException());
    var http =
        MockMvcBuilders.standaloneSetup(new GuestSessionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    http.perform(post("/v1/guest-sessions").header("X-Request-Id", "client-secret"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
        .andExpect(jsonPath("$.requestId").isString())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client-secret"))));
  }
}

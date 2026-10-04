package jp.youren.api.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import jp.youren.api.security.AccessTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 过滤器异常发生在MVC异常处理之前，也必须返回合同安全500，不能把秘密带进默认错误页。
class AccessTokenFilterTest {
  @Test
  void unexpectedVerifierErrorProducesSafeUncachedEnvelope() throws Exception {
    var verifier = mock(SessionService.class);
    when(verifier.requireAccess(anyString()))
        .thenThrow(new IllegalStateException("internal-token-secret"));
    var mapper = new ObjectMapper();
    var http =
        MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null))
            .addFilter(new AccessTokenFilter(verifier, mapper))
            .build();
    var result =
        assertDoesNotThrow(
            () ->
                http.perform(get("/v1/me").header("Authorization", "Bearer submitted-secret"))
                    .andReturn());
    assertEquals(500, result.getResponse().getStatus());
    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    var body = result.getResponse().getContentAsString();
    assertEquals("INTERNAL_ERROR", mapper.readTree(body).get("code").asText());
    assertFalse(body.contains("internal-token-secret"));
    assertFalse(body.contains("submitted-secret"));
  }
}

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

  @Test
  void guestOwnedContentRouteReachesControllerWithoutAccountToken() throws Exception {
    var verifier = mock(SessionService.class);
    var http =
        MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null))
            .addFilter(new AccessTokenFilter(verifier, new ObjectMapper()))
            .build();
    // 游客内容路由交由控制器验证游客凭证；过滤器不应先要求账号令牌。
    var result = http.perform(get("/v1/me/content").param("kind", "posts")).andReturn();
    assertEquals(404, result.getResponse().getStatus());
    verifyNoInteractions(verifier);

    var detail =
        http.perform(get("/v1/me/content/posts/123e4567-e89b-12d3-a456-426614174000")).andReturn();
    // 仅允许明确的帖子/回复UUID详情形状通过过滤器，控制器仍负责验证主体凭证。
    assertEquals(404, detail.getResponse().getStatus());
    verifyNoInteractions(verifier);
  }

  // 游客例外只能匹配完整posts/comments UUID详情；相邻本人路径仍要求账号认证。
  @Test
  void guestDetailExceptionCannotExpandToAdjacentMeRoutes() throws Exception {
    var verifier = mock(SessionService.class);
    var http =
        MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null))
            .addFilter(new AccessTokenFilter(verifier, new ObjectMapper()))
            .build();
    for (String path :
        java.util.List.of(
            "/v1/me/content/posts/not-uuid",
            "/v1/me/content/accounts/123e4567-e89b-12d3-a456-426614174000",
            "/v1/me/content/comments/123e4567-e89b-12d3-a456-426614174000/deeper",
            "/v1/me/settings"))
      assertEquals(401, http.perform(get(path)).andReturn().getResponse().getStatus());
    assertEquals(
        404,
        http.perform(get("/v1/me/content/comments/123e4567-e89b-12d3-a456-426614174000"))
            .andReturn()
            .getResponse()
            .getStatus());
    verifyNoInteractions(verifier);
  }
}

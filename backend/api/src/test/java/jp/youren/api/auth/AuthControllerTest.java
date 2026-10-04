package jp.youren.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jp.youren.api.http.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 只替代不会被非法输入调用的业务依赖，真实控制器负责严格字段校验和安全错误封装。
class AuthControllerTest {
  @Test
  void malformedJsonDoesNotEchoOrCacheSubmittedSecret() throws Exception {
    var controller = new AuthController(null, null, null, AuthSettings.from(new MockEnvironment()));
    var http =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    http.perform(
            post("/v1/auth/login")
                .contentType("application/json")
                .content("{\"code\":\"sensitive-code\""))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-code"))));
  }

  // 未知字段不能偷偷进入业务服务，安全422不得包含原秘密或客户端追踪ID。
  @Test
  void unknownFieldsAndNullValuesAreRejectedBeforeBusinessOperations() throws Exception {
    var http =
        MockMvcBuilders.standaloneSetup(
                new AuthController(null, null, null, AuthSettings.from(new MockEnvironment())))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    for (String body :
        new String[] {
          "{\"refreshToken\":\"submitted-secret\",\"accountId\":\"forged\"}",
          "{\"refreshToken\":null}",
          "{\"refreshToken\":\"\"}",
          "[]"
        })
      http.perform(
              post("/v1/auth/refresh")
                  .header("X-Request-Id", "client-secret")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.requestId").isString())
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("submitted-secret"))));
  }
}

package jp.youren.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jp.youren.api.http.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ApiErrorTest {
  // 错误DTO使用合同中的平铺code/message/requestId，不回显客户端输入。
  @Test
  void invalidInputReturnsContractErrorWithServerRequestId() throws Exception {
    var http =
        MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    var result =
        http.perform(get("/input").param("count", "secret-value"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.requestId").isString())
            .andReturn();
    var body = result.getResponse().getContentAsString();
    assertFalse(body.contains("secret-value"));
    assertEquals(3, new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).size());
  }

  // 原始运行时异常统一500，仅返回中文说明及新生成UUID，不输出内部堆栈。
  @Test
  void unexpectedExceptionIsSafeAndMatchesErrorShape() throws Exception {
    var http =
        MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    var result =
        http.perform(get("/failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn();
    var body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.getResponse().getContentAsString());
    assertFalse(body.toString().contains("secret-value"));
    assertNotNull(java.util.UUID.fromString(body.get("requestId").asText()));
  }

  @RestController
  static class FailingController {
    @GetMapping("/input")
    String input(@RequestParam("count") int count) {
      return "ok";
    }

    @GetMapping("/failure")
    String fail() {
      throw new IllegalStateException("secret-value");
    }
  }
}

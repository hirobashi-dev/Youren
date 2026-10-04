package jp.youren.api.profile;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import jp.youren.api.http.ApiExceptionHandler;
import jp.youren.api.security.CurrentPrincipal;
import jp.youren.database.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 控制器和校验真实运行；单元层只替换持久端口，集成测试另行验证真实事务和SQL。
class ProfileControllerTest {
  final UUID id = UUID.randomUUID();

  // 若资格检查之后公开行变为待审，绝不能将待审行内容当公开DTO返回。
  @Test
  void publicMappingRejectsUnapprovedSnapshotEvenAfterEligibilityCheck() {
    var mapper = mock(ProfileMapper.class);
    when(mapper.isPublic(id)).thenReturn(true);
    when(mapper.published(id))
        .thenReturn(
            new ProfileMapper.Snapshot(
                "{\"nickname\":\"未审\",\"bio\":\"私有\",\"regionCode\":null,\"interestIds\":[]}",
                2,
                "pending"));
    var service = new ProfileService(mapper, null, new ObjectMapper(), Clock.systemUTC(), 30, 500);
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            jp.youren.api.auth.AuthFailure.class, () -> service.publicProfile(id, null));
    org.junit.jupiter.api.Assertions.assertEquals(404, failure.status());
  }

  MockMvc http(int nicknameMax, int bioMax) {
    var accounts = mock(IdentityAccountMapper.class);
    when(accounts.lock(id))
        .thenReturn(new IdentityAccountMapper.IdentityRow(id, "private@example.com", "active"));
    var service =
        new ProfileService(
            mock(ProfileMapper.class),
            accounts,
            new ObjectMapper(),
            Clock.systemUTC(),
            nicknameMax,
            bioMax);
    return MockMvcBuilders.standaloneSetup(new ProfileController(service, null))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  // NBSP和全角空格组成的昵称应视为留白，不能用Unicode空白绕过首次基本资料要求。
  @Test
  void unicodeWhitespaceCannotCreateBlankNickname() throws Exception {
    http(30, 500)
        .perform(
            patch("/v1/me/profile")
                .requestAttr(
                    CurrentPrincipal.ATTRIBUTE,
                    new CurrentPrincipal(id, UUID.randomUUID(), UUID.randomUUID()))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"nickname\":\" 　\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // 暂定默认值不是不可变产品上限；配置增大后超过30/500的合法资料必须可以保存。
  @Test
  void configuredLimitsAllowLargerGraphemeInputs() throws Exception {
    var body =
        new ObjectMapper()
            .writeValueAsString(
                java.util.Map.of("nickname", "字".repeat(40), "bio", "字".repeat(700)));
    http(40, 700)
        .perform(
            patch("/v1/me/profile")
                .requestAttr(
                    CurrentPrincipal.ATTRIBUTE,
                    new CurrentPrincipal(id, UUID.randomUUID(), UUID.randomUUID()))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1))
        .andExpect(jsonPath("$.data.profile.moderationStatus").value("pending"));
  }

  // 没有服务器身份即使知道账号ID也不能操作；畸形JSON响应不缓存或回显私有正文。
  @Test
  void ownerIdentityAndMalformedJsonStaySafe() throws Exception {
    var http = http(30, 500);
    http.perform(
            patch("/v1/me/profile")
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"nickname\":\"姓名\"}"))
        .andExpect(status().isUnauthorized());
    http.perform(
            patch("/v1/me/profile")
                .contentType("application/json")
                .content("{\"bio\":\"private-secret\""))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-secret"))));
  }
}

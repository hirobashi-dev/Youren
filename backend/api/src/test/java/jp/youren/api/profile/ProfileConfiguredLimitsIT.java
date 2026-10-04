package jp.youren.api.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

// 真实Spring配置绑定和HTTP验证证明部署设置是权威，不能被暂定30/500默认值截断。
@TestPropertySource(
    properties = {"youren.profile.nickname-graphemes=40", "youren.profile.bio-graphemes=700"})
class ProfileConfiguredLimitsIT extends ProfileTestSupport {
  @Test
  void deploymentCanOverrideProvisionalDefaults() throws Exception {
    var u = user();
    assertEquals(
        200,
        patch(u, Map.of("nickname", "👩🏽‍💻".repeat(40), "bio", "e\u0301".repeat(700)), "\"0\"")
            .statusCode());
    assertEquals(422, patch(u, Map.of("nickname", "字".repeat(41)), "\"1\"").statusCode());
    assertEquals(422, patch(u, Map.of("bio", "字".repeat(701)), "\"1\"").statusCode());
  }
}

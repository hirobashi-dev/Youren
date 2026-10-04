package jp.youren.api.guest;

import java.time.Clock;
import java.time.Duration;
import jp.youren.database.mapper.GuestCredentialMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 游客有效期用ISO-8601时长配置；不依赖后续验证码密钥，健康接口仍可独立启动。
@Configuration
public class GuestConfiguration {
  @Bean
  public GuestSessionService guestSessionService(
      GuestCredentialMapper mapper, @Value("${YOUREN_GUEST_CREDENTIAL_TTL:P30D}") String lifetime) {
    return new GuestSessionService(mapper, Clock.systemUTC(), Duration.parse(lifetime));
  }
}

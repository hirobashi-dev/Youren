package jp.youren.api.auth;

import java.time.Clock;
import java.util.*;
import jp.youren.api.email.EmailDelivery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

// 默认值对应已批准工程配置；缺少HMAC和邮件不妨碍健康启动，但认证操作拒绝服务。
@Configuration
public class AuthConfiguration {
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock authClock() {
    return Clock.systemUTC();
  }

  @Bean
  public AuthSettings authSettings(Environment env) {
    return AuthSettings.from(env);
  }

  @Bean
  @ConditionalOnMissingBean(EmailDelivery.class)
  public EmailDelivery emailDelivery() {
    return new EmailDelivery() {
      public boolean configured() {
        return false;
      }

      public void deliver(
          String email, String purpose, UUID id, String code, java.time.Instant expiresAt) {
        throw AuthFailure.unavailable();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(GuestClaimHandler.class)
  public GuestClaimHandler guestClaimHandler(jp.youren.api.guest.GuestSessionService guests) {
    return (account, token) -> {
      guests.requirePrincipal(token);
      return false;
    };
  }
}

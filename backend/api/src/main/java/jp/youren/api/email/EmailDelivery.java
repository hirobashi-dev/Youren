package jp.youren.api.email;

import java.util.UUID;

// 邮件端口只向受信供应商传递验证码；生产未配置时必须拒绝，禁止返回或记录秘密。
public interface EmailDelivery {
  void deliver(
      String email, String purpose, UUID challengeId, String code, java.time.Instant expiresAt);

  default boolean configured() {
    return true;
  }
}

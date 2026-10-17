package jp.youren.api.auth;

import java.util.UUID;

// 接续在认证事务成功后执行，处理器验证原游客秘密并负责重复接续的幂等规则。
@FunctionalInterface
public interface GuestClaimHandler {
  boolean claim(UUID accountId, String guestToken);
}

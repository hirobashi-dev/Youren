package jp.youren.api.security;

import java.util.UUID;

// 该请求属性只能由服务器过滤器写入；控制器不能接收客户端accountId替代身份。
public record CurrentPrincipal(UUID accountId, UUID sessionId, UUID familyId) {
  public static final String ATTRIBUTE = CurrentPrincipal.class.getName();
}

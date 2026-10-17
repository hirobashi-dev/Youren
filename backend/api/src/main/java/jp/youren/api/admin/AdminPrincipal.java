package jp.youren.api.admin;

import java.util.UUID;

// 管理身份由独立过滤器产生，普通账号认证属性不能伪造成管理员角色。
public record AdminPrincipal(UUID adminId, String role) {
  public static final String ATTRIBUTE = AdminPrincipal.class.getName();
}

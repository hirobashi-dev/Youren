package jp.youren.api.guest;

// 无效、到期、撤销统一失败；异常不保留输入秘密或底层详情。
public final class GuestCredentialException extends RuntimeException {
  public GuestCredentialException() {
    super("游客凭证不可用");
  }
}

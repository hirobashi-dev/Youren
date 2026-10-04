package jp.youren.api.auth;

// 安全业务错误不保存输入、数据库详情或底层异常；状态和错误码由服务端确定。
public final class AuthFailure extends RuntimeException {
  private final int status;
  private final String code;
  private final long retryAfter;

  public AuthFailure(int status, String code) {
    this(status, code, 0);
  }

  public AuthFailure(int status, String code, long retryAfter) {
    super("认证操作不可用");
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public long retryAfter() {
    return retryAfter;
  }

  public static AuthFailure unavailable() {
    return new AuthFailure(503, "TEMPORARILY_UNAVAILABLE");
  }

  public static AuthFailure unauthorized() {
    return new AuthFailure(401, "AUTH_REQUIRED");
  }

  public static AuthFailure invalid() {
    return new AuthFailure(422, "VALIDATION_FAILED");
  }
}

package jp.youren.api.http;

import jp.youren.api.guest.GuestCredentialException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// 错误响应与OpenAPI Error一致；不引用原始异常、字段值或请求头中的追踪ID。
@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  public record ApiError(String code, String message, String requestId) {}

  // 游客凭证业务失败不区分未知、过期或撤销，也不回显原凭证。
  @ExceptionHandler(GuestCredentialException.class)
  public ResponseEntity<Object> guestCredentialUnavailable(GuestCredentialException error) {
    return ResponseEntity.status(401)
        .body(new ApiError("AUTH_REQUIRED", "身份凭证不可用", java.util.UUID.randomUUID().toString()));
  }

  // 媒体业务边界失败返回稳定客户端错误，不将实现细节或存储路径带回响应。
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Object> invalidRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest()
        .header("Cache-Control", "no-store")
        .body(
            new ApiError(
                "INVALID_REQUEST", "请求参数或资源状态不正确", java.util.UUID.randomUUID().toString()));
  }

  // 认证失败保持稳定合同字段；限流等待由服务端计算。
  @ExceptionHandler(jp.youren.api.auth.AuthFailure.class)
  public ResponseEntity<Object> authentication(jp.youren.api.auth.AuthFailure error) {
    var builder = ResponseEntity.status(error.status()).header("Cache-Control", "no-store");
    if (error.status() == 429) builder.header("Retry-After", Long.toString(error.retryAfter()));
    return builder.body(
        new ApiError(error.code(), "认证操作不可用", java.util.UUID.randomUUID().toString()));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception error,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    headers = new HttpHeaders(headers);
    headers.setCacheControl("no-store");
    String code = status.value() == 404 ? "CONTENT_UNAVAILABLE" : "INVALID_REQUEST";
    String message = status.value() == 404 ? "内容不可用" : "请求参数不正确";
    return new ResponseEntity<>(
        new ApiError(code, message, java.util.UUID.randomUUID().toString()), headers, status);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpected(Exception error) {
    return ResponseEntity.status(500)
        .header("Cache-Control", "no-store")
        .body(new ApiError("INTERNAL_ERROR", "服务暂时不可用", java.util.UUID.randomUUID().toString()));
  }
}

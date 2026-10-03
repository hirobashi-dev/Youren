package jp.youren.api.http;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// 错误响应与OpenAPI Error一致；不引用原始异常、字段值或请求头中的追踪ID。
@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  public record ApiError(String code, String message, String requestId) {}

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception error,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String code = status.value() == 404 ? "CONTENT_UNAVAILABLE" : "INVALID_REQUEST";
    String message = status.value() == 404 ? "内容不可用" : "请求参数不正确";
    return new ResponseEntity<>(
        new ApiError(code, message, java.util.UUID.randomUUID().toString()), headers, status);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpected(Exception error) {
    return ResponseEntity.status(500)
        .body(new ApiError("INTERNAL_ERROR", "服务暂时不可用", java.util.UUID.randomUUID().toString()));
  }
}

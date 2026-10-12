package jp.youren.api.media;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.auth.AuthController;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.CurrentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 上传接口仅返回媒体状态；原图和对象键不进入HTTP响应或静态文件服务。
@RestController
public class MediaController {
  private final MediaService service;

  public MediaController(MediaService service) {
    this.service = service;
  }

  @PostMapping("/v1/media/upload-intents")
  public ResponseEntity<AuthController.Envelope> intent(
      HttpServletRequest request,
      @RequestBody JsonNode input,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    fields(input, Set.of("purpose", "fileName", "mime", "sizeBytes"));
    String purpose = text(input, "purpose", 16);
    String mime = text(input, "mime", 32);
    long size = number(input, "sizeBytes");
    UUID owner = owner(request, guestToken);
    String base = request.getRequestURL().toString().replaceFirst("/upload-intents$", "");
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(
            new AuthController.Envelope(
                service.createIntent(owner, purpose, mime, size, base),
                UUID.randomUUID().toString()));
  }

  // 显式绑定路径变量名，避免构建器未保留Java参数名时请求在进入控制器前失败。
  @PutMapping("/v1/media/{mediaId}/content")
  public ResponseEntity<AuthController.Envelope> upload(
      HttpServletRequest request,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader("X-Upload-Token") String uploadToken,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken)
      throws IOException {
    UUID owner = owner(request, guestToken);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            new AuthController.Envelope(
                service.upload(owner, mediaId, uploadToken, request.getInputStream()),
                UUID.randomUUID().toString()));
  }

  @PostMapping("/v1/media/{mediaId}/complete")
  public ResponseEntity<AuthController.Envelope> complete(
      HttpServletRequest request,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken)
      throws IOException {
    return ResponseEntity.accepted()
        .header("Cache-Control", "no-store")
        .body(
            new AuthController.Envelope(
                service.complete(owner(request, guestToken), mediaId),
                UUID.randomUUID().toString()));
  }

  @GetMapping("/v1/media/{mediaId}")
  public ResponseEntity<AuthController.Envelope> status(
      HttpServletRequest request,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            new AuthController.Envelope(
                service.status(owner(request, guestToken), mediaId), UUID.randomUUID().toString()));
  }

  @DeleteMapping("/v1/media/{mediaId}")
  public ResponseEntity<Void> delete(
      HttpServletRequest request,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken)
      throws IOException {
    service.delete(owner(request, guestToken), mediaId);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  private UUID owner(HttpServletRequest request, String guestToken) {
    var principal = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    try {
      return service.resolveOwner(principal == null ? null : principal.accountId(), guestToken);
    } catch (RuntimeException invalid) {
      throw AuthFailure.unauthorized();
    }
  }

  private static void fields(JsonNode input, Set<String> allowed) {
    if (input == null || !input.isObject()) {
      throw AuthFailure.invalid();
    }
    var provided = new java.util.HashSet<String>();
    input.fieldNames().forEachRemaining(provided::add);
    if (!allowed.containsAll(provided)) throw AuthFailure.invalid();
    for (String required : Set.of("purpose", "mime", "sizeBytes")) {
      if (!input.has(required)) throw AuthFailure.invalid();
    }
  }

  private static String text(JsonNode input, String field, int max) {
    JsonNode value = input.get(field);
    if (value == null
        || !value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > max) {
      throw AuthFailure.invalid();
    }
    return value.asText();
  }

  private static long number(JsonNode input, String field) {
    JsonNode value = input.get(field);
    if (value == null || !value.canConvertToLong()) throw AuthFailure.invalid();
    return value.asLong();
  }
}

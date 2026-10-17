package jp.youren.api.guest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import jp.youren.api.auth.*;
import jp.youren.api.security.CurrentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 重试入口只从服务器请求身份取账号，主体ID不接受为所有权证据。
@RestController
public class GuestClaimController {
  private final GuestClaimService service;

  public GuestClaimController(GuestClaimService service) {
    this.service = service;
  }

  @PostMapping("/v1/me/guest-claims")
  public ResponseEntity<AuthController.Envelope> claim(
      HttpServletRequest request,
      @RequestBody JsonNode input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    var principal = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    if (principal == null) throw AuthFailure.unauthorized();
    if (input == null
        || !input.isObject()
        || input.size() != 1
        || !input.has("guestToken")
        || !input.get("guestToken").isTextual()) throw AuthFailure.invalid();
    UUID id;
    try {
      id = UUID.fromString(key);
      if (!id.toString().equalsIgnoreCase(key)) throw AuthFailure.invalid();
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw AuthFailure.invalid();
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            new AuthController.Envelope(
                service.claim(principal.accountId(), input.get("guestToken").asText(), id),
                UUID.randomUUID().toString()));
  }
}

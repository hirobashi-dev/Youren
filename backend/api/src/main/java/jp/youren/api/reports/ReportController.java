package jp.youren.api.reports;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.BoardService;
import jp.youren.api.auth.AuthController;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.CurrentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// 举报身份由账号会话或游客凭证解析；请求体不能指定举报人主体。
@RestController
public class ReportController {
  private final ReportService reports;
  private final BoardService board;

  public ReportController(ReportService reports, BoardService board) {
    this.reports = reports;
    this.board = board;
  }

  @PostMapping("/v1/reports")
  public ResponseEntity<AuthController.Envelope> submit(
      HttpServletRequest request,
      @RequestBody JsonNode input,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    fields(
        input,
        Set.of("targetType", "targetId", "reasonCode", "detail"),
        Set.of("targetType", "targetId", "reasonCode"));
    UUID reporter = owner(request, guestToken);
    var result =
        reports.submit(
            reporter,
            text(input, "targetType", 20),
            uuid(input, "targetId"),
            text(input, "reasonCode", 40),
            optionalText(input, "detail"),
            idempotencyKey(key),
            input.toString());
    return ResponseEntity.status(201).header("Cache-Control", "no-store").body(envelope(result));
  }

  @GetMapping("/v1/me/reports")
  public ResponseEntity<AuthController.Envelope> mine(
      HttpServletRequest request,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(envelope(reports.mine(owner(request, guestToken))));
  }

  private UUID owner(HttpServletRequest request, String guestToken) {
    var current = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    return board.resolveOwner(current == null ? null : current.accountId(), guestToken);
  }

  private static void fields(JsonNode input, Set<String> allowed, Set<String> required) {
    if (input == null || !input.isObject()) throw AuthFailure.invalid();
    Set<String> provided = new java.util.HashSet<>();
    input.fieldNames().forEachRemaining(provided::add);
    if (!allowed.containsAll(provided) || !provided.containsAll(required))
      throw AuthFailure.invalid();
  }

  private static String text(JsonNode input, String field, int max) {
    var value = input.get(field);
    if (value == null
        || !value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > max) throw AuthFailure.invalid();
    return value.asText();
  }

  private static String optionalText(JsonNode input, String field) {
    var value = input.get(field);
    if (value == null || value.isNull()) return "";
    if (!value.isTextual()) throw AuthFailure.invalid();
    return value.asText();
  }

  private static UUID uuid(JsonNode input, String field) {
    try {
      return UUID.fromString(text(input, field, 36));
    } catch (RuntimeException error) {
      throw AuthFailure.invalid();
    }
  }

  private static String idempotencyKey(String value) {
    if (value == null || value.length() > 128 || value.isBlank()) throw AuthFailure.invalid();
    return value;
  }

  private static AuthController.Envelope envelope(Object data) {
    return new AuthController.Envelope(data, UUID.randomUUID().toString());
  }
}

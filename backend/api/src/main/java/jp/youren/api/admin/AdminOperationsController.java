package jp.youren.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.auth.AuthController;
import jp.youren.api.auth.AuthFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 管理查询需经独立token过滤器；每个操作再次检查读写角色，不能依赖界面隐藏按钮。
@RestController
public class AdminOperationsController {
  private final AdminOperationsService operations;
  private final ReportServiceFacade reports;

  public AdminOperationsController(
      AdminOperationsService operations, jp.youren.api.reports.ReportService reports) {
    this.operations = operations;
    this.reports = new ReportServiceFacade(reports);
  }

  @GetMapping("/v1/admin/content")
  public ResponseEntity<AuthController.Envelope> content(
      HttpServletRequest request,
      @RequestParam(name = "status") String status,
      @RequestParam(name = "targetType", required = false) String targetType,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    String baseUrl =
        org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
            .build()
            .toUriString();
    return ok(operations.content(principal(request), status, targetType, limit, baseUrl));
  }

  @GetMapping("/v1/admin/reports")
  public ResponseEntity<AuthController.Envelope> reports(
      HttpServletRequest request,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    return ok(reports.list(principal(request), status, limit));
  }

  @GetMapping("/v1/admin/reports/{reportId}")
  public ResponseEntity<AuthController.Envelope> report(
      HttpServletRequest request, @PathVariable("reportId") UUID reportId) {
    return ok(reports.detail(principal(request), reportId));
  }

  @PostMapping("/v1/admin/reports/{reportId}/resolve")
  public ResponseEntity<AuthController.Envelope> resolveReport(
      HttpServletRequest request,
      @PathVariable("reportId") UUID reportId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode input) {
    fields(input, Set.of("resolution", "reason", "expectedVersion"));
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(
            envelope(
                operations.resolveReport(
                    principal(request),
                    reportId,
                    text(input, "resolution", 32),
                    text(input, "reason", 1000),
                    integer(input, "expectedVersion"),
                    key,
                    input.toString())));
  }

  @PostMapping("/v1/admin/moderation-actions")
  public ResponseEntity<AuthController.Envelope> moderate(
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode input) {
    fields(input, Set.of("targetType", "targetId", "action", "reason", "expectedVersion"));
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(
            envelope(
                operations.moderate(
                    principal(request),
                    text(input, "targetType", 20),
                    uuid(input, "targetId"),
                    text(input, "action", 20),
                    text(input, "reason", 1000),
                    integer(input, "expectedVersion"),
                    key,
                    input.toString())));
  }

  private static AdminPrincipal principal(HttpServletRequest request) {
    var principal = (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTRIBUTE);
    if (principal == null) throw AuthFailure.unauthorized();
    return principal;
  }

  private static ResponseEntity<AuthController.Envelope> ok(Object data) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(envelope(data));
  }

  private static AuthController.Envelope envelope(Object data) {
    return new AuthController.Envelope(data, UUID.randomUUID().toString());
  }

  private static void fields(JsonNode input, Set<String> fields) {
    if (input == null || !input.isObject()) throw AuthFailure.invalid();
    Set<String> provided = new java.util.HashSet<>();
    input.fieldNames().forEachRemaining(provided::add);
    if (!provided.equals(fields)) throw AuthFailure.invalid();
  }

  private static String text(JsonNode input, String field, int max) {
    var value = input.get(field);
    if (value == null || !value.isTextual() || value.asText().length() > max)
      throw AuthFailure.invalid();
    return value.asText();
  }

  private static int integer(JsonNode input, String field) {
    var value = input.get(field);
    if (value == null || !value.canConvertToInt()) throw AuthFailure.invalid();
    return value.asInt();
  }

  private static UUID uuid(JsonNode input, String field) {
    try {
      return UUID.fromString(text(input, field, 36));
    } catch (RuntimeException error) {
      throw AuthFailure.invalid();
    }
  }

  private record ReportServiceFacade(jp.youren.api.reports.ReportService service) {
    Object list(AdminPrincipal principal, String status, int limit) {
      readable(principal);
      return service.adminList(status, limit);
    }

    Object detail(AdminPrincipal principal, UUID id) {
      readable(principal);
      return service.adminDetail(id);
    }

    private static void readable(AdminPrincipal principal) {
      if (principal == null
          || !Set.of("reviewer", "operator", "auditor").contains(principal.role()))
        throw AuthFailure.unauthorized();
    }
  }
}

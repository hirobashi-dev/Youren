package jp.youren.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.auth.AuthController;
import jp.youren.api.auth.AuthFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 管理登录只接收口令并换取短期MFA挑战；MFA成功前不颁发管理访问权限。
@RestController
public class AdminAuthController {
  private final AdminAuthService authentication;

  public AdminAuthController(AdminAuthService authentication) {
    this.authentication = authentication;
  }

  @PostMapping("/v1/admin/auth/login")
  public ResponseEntity<AuthController.Envelope> login(@RequestBody JsonNode input) {
    fields(input, Set.of("loginName", "password"));
    String name = text(input, "loginName", 64);
    char[] password = text(input, "password", 256).toCharArray();
    return success(authentication.login(name, password));
  }

  @PostMapping("/v1/admin/auth/mfa")
  public ResponseEntity<AuthController.Envelope> mfa(@RequestBody JsonNode input) {
    fields(input, Set.of("challengeId", "code"));
    String id = text(input, "challengeId", 36);
    if (!id.matches("[0-9a-fA-F-]{36}")) throw AuthFailure.invalid();
    String code = text(input, "code", 6);
    return success(authentication.complete(UUID.fromString(id), code));
  }

  private static ResponseEntity<AuthController.Envelope> success(Object result) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(new AuthController.Envelope(result, UUID.randomUUID().toString()));
  }

  private static void fields(JsonNode input, Set<String> allowed) {
    if (input == null || !input.isObject()) throw AuthFailure.invalid();
    Set<String> provided = new java.util.HashSet<>();
    input.fieldNames().forEachRemaining(provided::add);
    if (!allowed.equals(provided)) throw AuthFailure.invalid();
  }

  private static String text(JsonNode input, String field, int max) {
    var value = input.get(field);
    if (value == null
        || !value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > max) throw AuthFailure.invalid();
    return value.asText();
  }
}

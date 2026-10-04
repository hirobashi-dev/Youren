package jp.youren.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import jp.youren.api.security.CurrentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 严格验证合同字段后才调用业务服务；不将请求原文或秘密保存在异常中。
@RestController
public class AuthController {
  private final EmailChallengeService challenges;
  private final AuthenticationService authentication;
  private final SessionService sessions;
  private final AuthSettings settings;

  public record Envelope(Object data, String requestId) {}

  public record Policy(String termsVersion, String privacyVersion) {}

  public AuthController(
      EmailChallengeService challenges,
      AuthenticationService authentication,
      SessionService sessions,
      AuthSettings settings) {
    this.challenges = challenges;
    this.authentication = authentication;
    this.sessions = sessions;
    this.settings = settings;
  }

  @GetMapping("/v1/auth/policy")
  public ResponseEntity<Envelope> policy() {
    return success(200, new Policy(settings.termsVersion(), settings.privacyVersion()));
  }

  @PostMapping("/v1/auth/email-code")
  public ResponseEntity<Envelope> challenge(
      @RequestBody JsonNode input, HttpServletRequest request) {
    fields(input, Set.of("email", "purpose"));
    String email = text(input, "email", 254).trim().toLowerCase(Locale.ROOT);
    String purpose = text(input, "purpose", 16);
    if (!email.matches(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")
        || email.startsWith(".")
        || email.contains("..")
        || email.contains(".@")
        || !Set.of("register", "login").contains(purpose)) throw AuthFailure.invalid();
    // 默认不信任代理头，只用服务器连接的远端地址参与IP限流。
    return success(202, challenges.send(email, purpose, request.getRemoteAddr()));
  }

  @PostMapping("/v1/auth/register")
  public ResponseEntity<Envelope> register(@RequestBody JsonNode input) {
    fields(input, Set.of("challengeId", "code", "termsVersion", "privacyVersion", "guestToken"));
    UUID id = id(input);
    String code = code(input);
    String terms = text(input, "termsVersion", 128);
    String privacy = text(input, "privacyVersion", 128);
    String guest = optionalToken(input, "guestToken");
    var result = authentication.authenticate(id, code, "register", terms, privacy, guest);
    return success(result.created() ? 201 : 200, result.session());
  }

  @PostMapping("/v1/auth/login")
  public ResponseEntity<Envelope> login(@RequestBody JsonNode input) {
    fields(input, Set.of("challengeId", "code", "guestToken"));
    var result =
        authentication.authenticate(
            id(input), code(input), "login", null, null, optionalToken(input, "guestToken"));
    return success(200, result.session());
  }

  @PostMapping("/v1/auth/refresh")
  public ResponseEntity<Envelope> refresh(@RequestBody JsonNode input) {
    fields(input, Set.of("refreshToken"));
    String token = text(input, "refreshToken", 1024);
    if (!SessionService.validToken(token)) throw AuthFailure.invalid();
    return success(200, sessions.refresh(token));
  }

  @PostMapping("/v1/auth/logout")
  public ResponseEntity<Void> logout(
      HttpServletRequest request, @RequestBody(required = false) JsonNode input) {
    if (input != null) fields(input, Set.of());
    var principal = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    if (principal == null) throw AuthFailure.unauthorized();
    sessions.logout(principal);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  private static ResponseEntity<Envelope> success(int status, Object data) {
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(new Envelope(data, UUID.randomUUID().toString()));
  }

  private static void fields(JsonNode input, Set<String> allowed) {
    if (input == null || !input.isObject()) throw AuthFailure.invalid();
    input
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw AuthFailure.invalid();
            });
  }

  private static String text(JsonNode input, String name, int max) {
    var value = input.get(name);
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > max) throw AuthFailure.invalid();
    return value.textValue();
  }

  private static UUID id(JsonNode input) {
    String value = text(input, "challengeId", 36);
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw AuthFailure.invalid();
      return id;
    } catch (IllegalArgumentException ex) {
      throw AuthFailure.invalid();
    }
  }

  private static String code(JsonNode input) {
    String value = text(input, "code", 6);
    if (!value.matches("[0-9]{6}")) throw AuthFailure.invalid();
    return value;
  }

  private static String optionalToken(JsonNode input, String name) {
    if (!input.has(name)) return null;
    String token = text(input, name, 1024);
    if (!SessionService.validToken(token)) throw AuthFailure.invalid();
    return token;
  }
}

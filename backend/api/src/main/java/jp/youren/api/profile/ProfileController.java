package jp.youren.api.profile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import jp.youren.api.auth.*;
import jp.youren.api.security.CurrentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 本人路由不接收账号ID，公开账号ID只用于读取独立公开DTO。
@RestController
public class ProfileController {
  private final ProfileService profiles;
  private final AccountMeQuery me;

  public ProfileController(ProfileService profiles, AccountMeQuery me) {
    this.profiles = profiles;
    this.me = me;
  }

  @GetMapping("/v1/me")
  public ResponseEntity<AuthController.Envelope> me(HttpServletRequest request) {
    return success(me.read(owner(request)));
  }

  @PatchMapping("/v1/me/profile")
  public ResponseEntity<AuthController.Envelope> update(
      HttpServletRequest request,
      @RequestBody JsonNode input,
      @RequestHeader(value = "If-Match", required = false) String version) {
    return success(profiles.update(owner(request), input, version));
  }

  @PatchMapping("/v1/me/settings")
  public ResponseEntity<AuthController.Envelope> settings(
      HttpServletRequest request, @RequestBody JsonNode input) {
    return success(profiles.settings(owner(request), input));
  }

  @GetMapping("/v1/profiles/{accountId}")
  public ResponseEntity<AuthController.Envelope> publicProfile(
      HttpServletRequest request, @PathVariable("accountId") String id) {
    var viewer = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    return success(
        profiles.publicProfile(
            ProfileService.uuid(com.fasterxml.jackson.databind.node.TextNode.valueOf(id)),
            viewer == null ? null : viewer.accountId()));
  }

  private static UUID owner(HttpServletRequest request) {
    var principal = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    if (principal == null) throw AuthFailure.unauthorized();
    return principal.accountId();
  }

  private static ResponseEntity<AuthController.Envelope> success(Object data) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(new AuthController.Envelope(data, UUID.randomUUID().toString()));
  }
}

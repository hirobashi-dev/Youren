package jp.youren.api.profile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

  // 公开字典带稳定ETag并采用分页外壳，允许客户端安全缓存且与普通私有响应隔离。
  @GetMapping("/v1/regions")
  public ResponseEntity<CatalogPage<ProfileService.Region>> regions(
      @RequestParam(value = "parentCode", required = false) String parentCode) {
    var data = profiles.regions(parentCode);
    return catalog(data);
  }

  @GetMapping("/v1/interests")
  public ResponseEntity<CatalogPage<ProfileService.Interest>> interests() {
    return catalog(profiles.interests());
  }

  public record CatalogPage<T>(java.util.List<T> data, CatalogPageInfo page, String requestId) {}

  public record CatalogPageInfo(String nextCursor, boolean hasMore) {}

  private static <T> ResponseEntity<CatalogPage<T>> catalog(java.util.List<T> data) {
    String etag;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(data.toString().getBytes(StandardCharsets.UTF_8));
      // 响应包含逐次变化的追踪ID，使用弱验证器表达目录数据本身语义相同即可复用。
      etag = "W/\"" + HexFormat.of().formatHex(digest) + "\"";
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .header("Cache-Control", "public, max-age=300")
        .body(
            new CatalogPage<>(
                data, new CatalogPageInfo(null, false), UUID.randomUUID().toString()));
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

package jp.youren.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import jp.youren.api.auth.AuthController;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.CurrentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 请求严格接受合同字段；作者、公开状态、发布时间和到期时间只由服务端决定。
@RestController
public class BoardController {
  private final BoardService board;
  private final jp.youren.database.mapper.BoardMapper mapper;
  private final jp.youren.api.media.StoragePort storage;
  private final java.time.Clock clock;

  public BoardController(
      BoardService board,
      jp.youren.database.mapper.BoardMapper mapper,
      jp.youren.api.media.StoragePort storage,
      java.time.Clock clock) {
    this.board = board;
    this.mapper = mapper;
    this.storage = storage;
    this.clock = clock;
  }

  @PostMapping("/v1/posts")
  public ResponseEntity<AuthController.Envelope> submitPost(
      HttpServletRequest request,
      @RequestBody JsonNode input,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    fields(
        input,
        Set.of("title", "body", "category", "regionCode", "interestIds", "mediaIds"),
        Set.of("title", "body", "category"));
    UUID owner = owner(request, guestToken);
    var result =
        board.submitPost(
            owner,
            text(input, "title"),
            text(input, "body"),
            text(input, "category"),
            nullableText(input, "regionCode"),
            ids(input, "interestIds"),
            ids(input, "mediaIds"),
            requireIdempotencyKey(idempotencyKey),
            input.toString());
    return ResponseEntity.status(201).header("Cache-Control", "no-store").body(envelope(result));
  }

  @PatchMapping("/v1/posts/{postId}")
  public ResponseEntity<AuthController.Envelope> editPost(
      HttpServletRequest request,
      @PathVariable("postId") UUID postId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode input,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    fields(
        input,
        Set.of("title", "body", "category", "regionCode", "interestIds", "mediaIds"),
        Set.of("title", "body", "category"));
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            envelope(
                board.editPost(
                    owner(request, guestToken),
                    postId,
                    expectedVersion(ifMatch),
                    text(input, "title"),
                    text(input, "body"),
                    text(input, "category"),
                    nullableText(input, "regionCode"),
                    ids(input, "interestIds"),
                    ids(input, "mediaIds"))));
  }

  @DeleteMapping("/v1/posts/{postId}")
  public ResponseEntity<Void> deletePost(
      HttpServletRequest request,
      @PathVariable("postId") UUID postId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    requireIdempotencyKey(idempotencyKey);
    board.deletePost(owner(request, guestToken), postId);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  @GetMapping("/v1/posts")
  public ResponseEntity<BoardService.Page<?>> publicPosts(
      @RequestParam(name = "regionCode", required = false) String regionCode,
      @RequestParam(name = "interestId", required = false) UUID interestId,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "sort", defaultValue = "latest") String sort,
      @RequestParam(required = false, name = "q") String query,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    if (!"latest".equals(sort)) throw AuthFailure.invalid();
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(board.publicPosts(regionCode, interestId, category, query, cursor, limit));
  }

  @GetMapping("/v1/posts/{postId}")
  public ResponseEntity<AuthController.Envelope> publicPost(@PathVariable("postId") UUID postId) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(envelope(board.publicPost(postId)));
  }

  @GetMapping("/v1/me/content")
  public ResponseEntity<BoardService.Page<?>> ownPosts(
      HttpServletRequest request,
      @RequestParam(name = "kind") String kind,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(board.ownContent(owner(request, guestToken), kind, status, cursor, limit));
  }

  @GetMapping("/v1/me/content/{kind}/{contentId}")
  public ResponseEntity<AuthController.Envelope> ownPost(
      HttpServletRequest request,
      @PathVariable("kind") String kind,
      @PathVariable("contentId") UUID contentId,
      @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    if (!Set.of("posts", "comments").contains(kind))
      throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            envelope(
                "posts".equals(kind)
                    ? board.ownPost(owner(request, guestToken), contentId)
                    : board.ownComment(owner(request, guestToken), contentId)));
  }

  // 回复字段严格按合同接收；父帖、作者和生命周期不可由客户端伪造。
  @PostMapping("/v1/posts/{postId}/comments")
  public ResponseEntity<AuthController.Envelope> submitComment(
      HttpServletRequest request,
      @PathVariable("postId") UUID postId,
      @RequestBody JsonNode input,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader(value = "X-Guest-Token", required = false) String token) {
    fields(input, Set.of("body", "mediaIds", "replyToId"), Set.of());
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(
            envelope(
                board.submitComment(
                    owner(request, token),
                    postId,
                    nullableId(input, "replyToId"),
                    replyText(input),
                    ids(input, "mediaIds"),
                    requireIdempotencyKey(key),
                    input.toString())));
  }

  @PatchMapping("/v1/comments/{commentId}")
  public ResponseEntity<AuthController.Envelope> editComment(
      HttpServletRequest request,
      @PathVariable("commentId") UUID id,
      @RequestBody JsonNode input,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "X-Guest-Token", required = false) String token) {
    fields(input, Set.of("body", "mediaIds", "replyToId"), Set.of());
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            envelope(
                board.editComment(
                    owner(request, token),
                    id,
                    expectedVersion(version),
                    nullableId(input, "replyToId"),
                    replyText(input),
                    ids(input, "mediaIds"))));
  }

  @DeleteMapping("/v1/comments/{commentId}")
  public ResponseEntity<Void> deleteComment(
      HttpServletRequest request,
      @PathVariable("commentId") UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader(value = "X-Guest-Token", required = false) String token) {
    requireIdempotencyKey(key);
    board.deleteComment(owner(request, token), id);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  @GetMapping("/v1/posts/{postId}/comments")
  public ResponseEntity<BoardService.Page<?>> comments(
      @PathVariable("postId") UUID id,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(board.publicComments(id, cursor, limit));
  }

  private static String replyText(JsonNode input) {
    return input.has("body") ? text(input, "body") : "";
  }

  private static UUID nullableId(JsonNode input, String key) {
    String value = nullableText(input, key);
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw AuthFailure.invalid();
    }
  }

  // 原图从未提供路由；访问描述与实际下载均重新核验公开状态及到期，不缓存历史授权。
  @GetMapping("/v1/media/{mediaId}/access")
  public ResponseEntity<AuthController.Envelope> mediaAccess(
      @PathVariable("mediaId") UUID id,
      @RequestParam(name = "variant", defaultValue = "display") String variant) {
    requireVariant(variant);
    var now = java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
    if (mapper.publicImageKey(id, variant, now) == null)
      throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    var expiry = mapper.publicImageExpiry(id, now);
    if (expiry.isAfter(now.plusMinutes(5))) expiry = now.plusMinutes(5);
    String url =
        org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/v1/media/" + id + "/" + variant)
            .toUriString();
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(envelope(Map.of("url", url, "expiresAt", expiry)));
  }

  @GetMapping({"/v1/media/{mediaId}/display", "/v1/media/{mediaId}/thumbnail"})
  public ResponseEntity<byte[]> image(HttpServletRequest request, @PathVariable("mediaId") UUID id)
      throws java.io.IOException {
    String variant = request.getRequestURI().endsWith("/thumbnail") ? "thumbnail" : "display";
    String key =
        mapper.publicImageKey(
            id,
            variant,
            java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
    if (key == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    try (var input =
        storage.open(
            "display".equals(variant)
                ? jp.youren.api.media.StoragePort.Area.DISPLAY
                : jp.youren.api.media.StoragePort.Area.THUMBNAIL,
            key)) {
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .header("X-Content-Type-Options", "nosniff")
          .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
          .body(input.readAllBytes());
    }
  }

  private static void requireVariant(String variant) {
    if (!Set.of("display", "thumbnail").contains(variant)) throw AuthFailure.invalid();
  }

  private UUID owner(HttpServletRequest request, String guestToken) {
    var current = (CurrentPrincipal) request.getAttribute(CurrentPrincipal.ATTRIBUTE);
    return board.resolveOwner(current == null ? null : current.accountId(), guestToken);
  }

  private static void fields(JsonNode input, Set<String> allowed, Set<String> required) {
    if (input == null || !input.isObject()) throw AuthFailure.invalid();
    Set<String> provided = new HashSet<>();
    input.fieldNames().forEachRemaining(provided::add);
    if (!allowed.containsAll(provided) || !provided.containsAll(required))
      throw AuthFailure.invalid();
  }

  private static String text(JsonNode input, String key) {
    JsonNode v = input.get(key);
    if (v == null || !v.isTextual()) throw AuthFailure.invalid();
    return v.asText();
  }

  private static String nullableText(JsonNode input, String key) {
    JsonNode v = input.get(key);
    if (v == null || v.isNull()) return null;
    if (!v.isTextual()) throw AuthFailure.invalid();
    return v.asText();
  }

  private static List<UUID> ids(JsonNode input, String key) {
    JsonNode v = input.get(key);
    if (v == null || v.isNull()) return List.of();
    if (!v.isArray()) throw AuthFailure.invalid();
    List<UUID> result = new ArrayList<>();
    for (JsonNode item : v) {
      if (!item.isTextual()) throw AuthFailure.invalid();
      try {
        result.add(UUID.fromString(item.asText()));
      } catch (IllegalArgumentException invalid) {
        throw AuthFailure.invalid();
      }
    }
    return result;
  }

  private static AuthController.Envelope envelope(Object data) {
    return new AuthController.Envelope(data, UUID.randomUUID().toString());
  }

  // If-Match只接受合同规定的双引号正版本，避免缺失或通配符绕过乐观锁。
  private static int expectedVersion(String value) {
    if (value == null) throw new AuthFailure(428, "VERSION_REQUIRED");
    if (!value.matches("\\\"[1-9][0-9]{0,8}\\\"")) throw AuthFailure.invalid();
    try {
      return Integer.parseInt(value.substring(1, value.length() - 1));
    } catch (NumberFormatException invalid) {
      throw AuthFailure.invalid();
    }
  }

  // 写请求只接受 UUID 幂等键，避免空值或任意超长字符串进入后续幂等记录。
  private static UUID requireIdempotencyKey(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value))
        throw AuthFailure.invalid();
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw AuthFailure.invalid();
    }
  }
}

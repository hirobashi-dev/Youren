package jp.youren.api;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.guest.GuestSessionService;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.BoardMapper;
import jp.youren.database.mapper.MediaMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 留言板帖子首次提交进入待审；只有管理员决定后才可进入公开查询。
@Service
public class BoardService {
  public record Submit(
      UUID id, String status, OffsetDateTime publishedAt, OffsetDateTime expiresAt, int version) {}

  public record Page<T>(List<T> data, Map<String, Object> page, String requestId) {}

  public record PublicPost(
      UUID id,
      String title,
      String body,
      Map<String, Object> authorDisplay,
      Object region,
      List<Object> interests,
      List<Object> images,
      int replyCount,
      OffsetDateTime publishedAt,
      OffsetDateTime expiresAt,
      Map<String, Boolean> capabilities) {}

  public record OwnPost(
      String kind,
      UUID id,
      String status,
      OffsetDateTime publishedAt,
      OffsetDateTime expiresAt,
      int version,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String title,
      String body,
      List<Object> images,
      boolean parentUnavailable) {}

  private static final Pattern GRAPHEME = Pattern.compile("\\X");
  private static final Set<String> CATEGORIES = Set.of("interest", "city", "general");
  private final BoardMapper mapper;
  private final MediaMapper media;
  private final GuestSessionService guests;
  private final Clock clock;
  private final long receiptSeconds;

  public BoardService(
      BoardMapper mapper,
      MediaMapper media,
      GuestSessionService guests,
      Clock clock,
      @Value("${youren.guest.claim-receipt-seconds:86400}") long receiptSeconds) {
    if (receiptSeconds <= 0) throw new IllegalArgumentException("幂等回执窗口必须为正数");
    this.mapper = mapper;
    this.media = media;
    this.guests = guests;
    this.clock = clock;
    this.receiptSeconds = receiptSeconds;
  }

  // 主体来自账号过滤器或有效游客秘密；客户端不能提交principalId作为作者身份。
  public UUID resolveOwner(UUID accountId, String guestToken) {
    if (accountId != null && guestToken != null) throw AuthFailure.invalid();
    if (accountId != null) {
      UUID principal = media.principalForAccount(accountId);
      if (principal == null) throw AuthFailure.unauthorized();
      return principal;
    }
    if (guestToken == null || guestToken.isBlank()) throw AuthFailure.unauthorized();
    try {
      return guests.requirePrincipal(guestToken);
    } catch (RuntimeException invalid) {
      throw AuthFailure.unauthorized();
    }
  }

  // 文本和分类先由服务端规范化、限长；帖子及关联在单一事务中全部成功或全部回滚。
  @Transactional
  public Submit submitPost(
      UUID owner,
      String title,
      String body,
      String category,
      String regionCode,
      List<UUID> interestIds,
      List<UUID> mediaIds,
      UUID idempotencyKey,
      String requestBody) {
    String key = idempotencyKey.toString();
    String actor = owner.toString();
    String requestHash = TokenSecrets.digest(requestBody);
    OffsetDateTime now = now();
    var prior = mapper.postReceipt(actor, key);
    if (prior != null) return replay(prior, requestHash, now);
    String normalizedTitle = text(title, 60, true);
    String normalizedBody = text(body, 2000, true);
    if (!CATEGORIES.contains(category)) throw AuthFailure.invalid();
    if (regionCode != null && !mapper.regionActive(regionCode)) throw AuthFailure.invalid();
    List<UUID> interests = uniqueIds(interestIds, 20);
    List<UUID> images = uniqueIds(mediaIds, 9);
    if (!interests.isEmpty() && mapper.countActiveInterests(interests) != interests.size())
      throw AuthFailure.invalid();
    if (!images.isEmpty()) {
      if (mapper.lockMedia(owner, images) != images.size()) throw AuthFailure.invalid();
      // 等待媒体锁后重读回执，重复提交不能被首个事务已完成的绑定误判为非法。
      prior = mapper.postReceipt(actor, key);
      if (prior != null) return replay(prior, requestHash, now());
      if (mapper.countBindableMedia(owner, images) != images.size()) throw AuthFailure.invalid();
    }
    String mode = mapper.displayMode(owner);
    if (mode == null) throw AuthFailure.unauthorized();
    UUID id = UUID.randomUUID();
    if (mapper.insertPostReceipt(actor, key, requestHash, id, now, now.plusSeconds(receiptSeconds))
        != 1) {
      prior = mapper.postReceipt(actor, key);
      if (prior == null) throw AuthFailure.unavailable();
      return replay(prior, requestHash, now);
    }
    if (mapper.insertPost(
            id, owner, mode, normalizedTitle, normalizedBody, category, regionCode, now)
        != 1) throw AuthFailure.unavailable();
    for (UUID interest : interests) mapper.insertPostInterest(id, interest);
    for (int i = 0; i < images.size(); i++)
      if (mapper.insertPostMedia(id, images.get(i), i, owner) != 1) throw AuthFailure.invalid();
    if (mapper.completePostReceipt(actor, key, now()) != 1) throw AuthFailure.unavailable();
    return new Submit(id, "pending", null, null, 1);
  }

  // 同键同请求返回原资源；摘要不匹配或回执过期时拒绝复用，绝不复制内容。
  private static Submit replay(
      BoardMapper.PostReceipt receipt, String requestHash, OffsetDateTime now) {
    if (!receipt.requestHash().equals(requestHash))
      throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
    if (!receipt.expiresAt().isAfter(now)) throw new AuthFailure(409, "RESULT_EXPIRED");
    return new Submit(receipt.resourceId(), "pending", null, null, 1);
  }

  // 只有本人待审或驳回内容可编辑；重提后重新进入人工审核且版本递增。
  @Transactional
  public Submit editPost(
      UUID owner,
      UUID id,
      int expectedVersion,
      String title,
      String body,
      String category,
      String regionCode,
      List<UUID> interestIds,
      List<UUID> mediaIds) {
    String normalizedTitle = text(title, 60, true);
    String normalizedBody = text(body, 2000, true);
    if (!CATEGORIES.contains(category)) throw AuthFailure.invalid();
    if (regionCode != null && !mapper.regionActive(regionCode)) throw AuthFailure.invalid();
    List<UUID> interests = uniqueIds(interestIds, 20);
    List<UUID> images = uniqueIds(mediaIds, 9);
    if (!interests.isEmpty() && mapper.countActiveInterests(interests) != interests.size())
      throw AuthFailure.invalid();
    if (!images.isEmpty()
        && (mapper.lockMedia(owner, images) != images.size()
            || mapper.countReusableMedia(owner, images, id) != images.size()))
      throw AuthFailure.invalid();
    OffsetDateTime now = now();
    if (mapper.updateOwnPost(
            id, owner, expectedVersion, normalizedTitle, normalizedBody, category, regionCode, now)
        != 1) {
      var existing = mapper.findOwnPost(id, owner);
      if (existing == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
      if (existing.version() != expectedVersion) throw new AuthFailure(412, "VERSION_CONFLICT");
      if (unavailable(existing.status(), existing.expiresAt()))
        throw new AuthFailure(409, "RESULT_EXPIRED");
      throw AuthFailure.invalid();
    }
    mapper.deletePostMedia(id);
    mapper.deletePostInterests(id);
    for (UUID interest : interests) mapper.insertPostInterest(id, interest);
    for (int i = 0; i < images.size(); i++)
      if (mapper.insertPostMedia(id, images.get(i), i, owner) != 1) throw AuthFailure.invalid();
    // 已公开内容重提后仍返回数据库保留的首发时间与期限，避免客户端误以为一年期限被清空。
    var updated = mapper.findOwnPost(id, owner);
    return new Submit(
        id, updated.status(), updated.publishedAt(), updated.expiresAt(), updated.version());
  }

  // 所有权过滤与隐藏在数据库内原子完成；重复删除只确认已达成的终态。
  @Transactional
  public void deletePost(UUID owner, UUID id) {
    if (mapper.deleteOwnPost(id, owner, now()) == 1) return;
    var existing = mapper.findOwnPost(id, owner);
    if (existing == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    if (!"deleted".equals(existing.status())) throw new AuthFailure(409, "VERSION_CONFLICT");
  }

  // 公开读取只查询已审核且未到期的项目；时间边界为expiresAt大于当前UTC时间。
  public Page<PublicPost> publicPosts(
      String region, UUID interest, String category, String query, String cursor, int limit) {
    if (limit < 1 || limit > 50 || (category != null && !CATEGORIES.contains(category)))
      throw AuthFailure.invalid();
    if (region != null && !mapper.regionActive(region)) throw AuthFailure.invalid();
    if (interest != null && mapper.countActiveInterests(List.of(interest)) != 1)
      throw AuthFailure.invalid();
    OffsetDateTime cursorTime = null;
    UUID cursorId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String decoded =
            new String(
                Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
        int split = decoded.lastIndexOf('|');
        cursorTime = OffsetDateTime.parse(decoded.substring(0, split));
        cursorId = UUID.fromString(decoded.substring(split + 1));
      } catch (RuntimeException invalid) {
        throw new AuthFailure(400, "INVALID_CURSOR");
      }
    }
    String safeQuery = query == null || query.isBlank() ? null : text(query, 100, false);
    var rows =
        mapper.publicPosts(
            now(), region, interest, category, safeQuery, cursorTime, cursorId, limit + 1);
    boolean more = rows.size() > limit;
    var selected = more ? rows.subList(0, limit) : rows;
    var data = selected.stream().map(this::publicDto).toList();
    String next = null;
    if (more && !selected.isEmpty()) {
      var last = selected.get(selected.size() - 1);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.publishedAt() + "|" + last.id())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    Map<String, Object> page = new HashMap<>();
    page.put("nextCursor", next);
    page.put("hasMore", more);
    return new Page<>(data, page, UUID.randomUUID().toString());
  }

  public Page<Object> ownPosts(UUID owner, int limit) {
    if (limit < 1 || limit > 50) throw AuthFailure.invalid();
    var items = mapper.ownPosts(owner, now(), limit).stream().map(row -> ownDto(row)).toList();
    Map<String, Object> page = new HashMap<>();
    page.put("nextCursor", null);
    page.put("hasMore", false);
    return new Page<>(items, page, UUID.randomUUID().toString());
  }

  // 详情先按所有者过滤；终态内容只返回占位，避免从历史链接恢复正文。
  public Object ownPost(UUID owner, UUID id) {
    var row = mapper.findOwnPost(id, owner);
    if (row == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    if ("deleted".equals(row.status())
        || "expired".equals(row.status())
        || (row.expiresAt() != null && !row.expiresAt().isAfter(now()))) {
      return Map.of(
          "kind",
          "posts",
          "id",
          id,
          "status",
          "deleted".equals(row.status()) ? "deleted" : "expired",
          "unavailable",
          true);
    }
    return ownDto(row);
  }

  public PublicPost publicPost(UUID id) {
    var row = mapper.findPost(id, now());
    if (row == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    return publicDto(row);
  }

  // 展示字段从字典及已处理变体取得；URL每次请求仍按内容状态重新鉴权。
  private PublicPost publicDto(BoardMapper.PostRow row) {
    return new PublicPost(
        row.id(),
        row.title(),
        row.body(),
        authorDisplay(row),
        mapper.region(row.regionCode()),
        new ArrayList<>(mapper.interests(row.id())),
        images(row.id(), "posts", row.expiresAt()),
        mapper.replyCount(row.id(), now()),
        row.publishedAt(),
        row.expiresAt(),
        capabilities());
  }

  private static Map<String, Boolean> capabilities() {
    return Map.of(
        "canRead",
        true,
        "canSend",
        true,
        "canEdit",
        false,
        "canDelete",
        false,
        "canJoin",
        false,
        "canMessage",
        false);
  }

  private boolean unavailable(String status, OffsetDateTime expiry) {
    return "deleted".equals(status)
        || "expired".equals(status)
        || (expiry != null && !expiry.isAfter(now()));
  }

  private Object ownDto(BoardMapper.PostRow row) {
    if (unavailable(row.status(), row.expiresAt()))
      return placeholder("posts", row.id(), row.status());
    return new OwnPost(
        "posts",
        row.id(),
        row.status(),
        row.publishedAt(),
        row.expiresAt(),
        row.version(),
        row.title(),
        row.body(),
        "visible".equals(row.status()) ? images(row.id(), "posts", row.expiresAt()) : List.of(),
        false);
  }

  private static Object placeholder(String kind, UUID id, String status) {
    return Map.of(
        "kind",
        kind,
        "id",
        id,
        "status",
        "deleted".equals(status) ? "deleted" : "expired",
        "unavailable",
        true);
  }

  private List<Object> images(UUID id, String kind, OffsetDateTime expiry) {
    return mapper.images(id, kind).stream()
        .map(
            r ->
                (Object)
                    new Image(
                        r.id(),
                        org.springframework.web.servlet.support.ServletUriComponentsBuilder
                            .fromCurrentContextPath()
                            .path("/v1/media/" + r.id() + "/display")
                            .toUriString(),
                        r.width(),
                        r.height(),
                        expiry))
        .toList();
  }

  public record Image(UUID id, String url, int width, int height, OffsetDateTime expiresAt) {}

  public record Reply(
      UUID id,
      UUID postId,
      String body,
      Map<String, Object> authorDisplay,
      List<Object> images,
      UUID replyToId,
      OffsetDateTime publishedAt,
      OffsetDateTime expiresAt,
      Map<String, Boolean> capabilities) {}

  // 父帖必须当前公开且未到期才能新建或重提；回复引用也必须同帖且当前可见。
  private void requireReplyTarget(UUID postId, UUID replyToId) {
    if (mapper.lockReplyPost(postId) == null) throw AuthFailure.invalid();
    publicPost(postId);
    if (replyToId != null) {
      var reply = mapper.lockReplyComment(replyToId);
      if (reply == null
          || !reply.postId().equals(postId)
          || !"visible".equals(reply.status())
          || unavailable(reply.status(), reply.expiresAt())) throw AuthFailure.invalid();
    }
  }

  private String replyBody(String body, List<UUID> images) {
    String result = text(body, 1000, false);
    if (result.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))
        && images.isEmpty()) throw AuthFailure.invalid();
    return result;
  }

  @Transactional
  public Submit submitComment(
      UUID owner,
      UUID postId,
      UUID replyToId,
      String body,
      List<UUID> mediaIds,
      UUID key,
      String requestBody) {
    String actor = owner.toString(),
        route = "/v1/posts/" + postId + "/comments",
        hash = TokenSecrets.digest(requestBody);
    var receipt = mapper.commentReceipt(actor, key.toString(), route);
    if (receipt != null) return replay(receipt, hash, now());
    requireReplyTarget(postId, replyToId);
    var images = uniqueIds(mediaIds, 3);
    String normalized = replyBody(body, images);
    UUID id = UUID.randomUUID();
    if (!images.isEmpty()) {
      if (mapper.lockMedia(owner, images) != images.size()) throw AuthFailure.invalid();
      // 并发同键在媒体锁后重读回执，返回原内容而不重新绑定。
      receipt = mapper.commentReceipt(actor, key.toString(), route);
      if (receipt != null) return replay(receipt, hash, now());
      if (mapper.countBindableMedia(owner, images) != images.size()) throw AuthFailure.invalid();
      // 媒体锁可能等待较久；重新读服务端时钟确认帖子在写入瞬间仍可回复。
      requireReplyTarget(postId, replyToId);
    }
    var now = now();
    if (mapper.insertCommentReceipt(
            actor, key.toString(), route, hash, id, now, now.plusSeconds(receiptSeconds))
        != 1) return replay(mapper.commentReceipt(actor, key.toString(), route), hash, now);
    String mode = mapper.displayMode(owner);
    if (mode == null) throw AuthFailure.unauthorized();
    if (mapper.insertComment(id, postId, replyToId, owner, mode, normalized, now) != 1)
      throw AuthFailure.unavailable();
    for (int i = 0; i < images.size(); i++)
      if (mapper.insertCommentMedia(id, images.get(i), i, owner) != 1) throw AuthFailure.invalid();
    return new Submit(id, "pending", null, null, 1);
  }

  private BoardMapper.CommentRow requireOwnComment(UUID owner, UUID id) {
    var row = mapper.findComment(id);
    if (row == null || !mapper.ownsPrincipal(owner, row.authorPrincipalId()))
      throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    return row;
  }

  @Transactional
  public Submit editComment(
      UUID owner, UUID id, int version, UUID replyToId, String body, List<UUID> mediaIds) {
    var row = requireOwnComment(owner, id);
    requireReplyTarget(row.postId(), replyToId);
    var images = uniqueIds(mediaIds, 3);
    String normalized = replyBody(body, images);
    if (!images.isEmpty()
        && (mapper.lockMedia(owner, images) != images.size()
            || mapper.countReusableMedia(owner, images, id) != images.size()))
      throw AuthFailure.invalid();
    // 复用或绑定图片等待期间也必须再次检查父帖与引用回复的有效期。
    requireReplyTarget(row.postId(), replyToId);
    if (mapper.editComment(id, owner, version, replyToId, normalized, now()) != 1) {
      var current = requireOwnComment(owner, id);
      if (current.version() != version) throw new AuthFailure(412, "VERSION_CONFLICT");
      else if (unavailable(current.status(), current.expiresAt()))
        throw new AuthFailure(409, "RESULT_EXPIRED");
      else throw AuthFailure.invalid();
    }
    mapper.deleteCommentMedia(id);
    for (int i = 0; i < images.size(); i++)
      if (mapper.insertCommentMedia(id, images.get(i), i, owner) != 1) throw AuthFailure.invalid();
    return new Submit(id, "pending", row.publishedAt(), row.expiresAt(), version + 1);
  }

  @Transactional
  public void deleteComment(UUID owner, UUID id) {
    var row = requireOwnComment(owner, id);
    if ("deleted".equals(row.status())) return;
    if (mapper.deleteComment(id, owner, now()) != 1) throw new AuthFailure(409, "VERSION_CONFLICT");
  }

  public Object ownComment(UUID owner, UUID id) {
    return ownCommentDto(requireOwnComment(owner, id));
  }

  private Object ownCommentDto(BoardMapper.CommentRow row) {
    if (unavailable(row.status(), row.expiresAt()))
      return placeholder("comments", row.id(), row.status());
    return new OwnPost(
        "comments",
        row.id(),
        row.status(),
        row.publishedAt(),
        row.expiresAt(),
        row.version(),
        null,
        row.body(),
        "visible".equals(row.status()) ? images(row.id(), "comments", row.expiresAt()) : List.of(),
        mapper.findPost(row.postId(), now()) == null);
  }

  // 到期父帖只保留回复入口；隐藏、删除父帖不授予公开读取权限。
  public Page<Reply> publicComments(UUID postId, String cursor, int limit) {
    if (!mapper.replyParentAvailable(postId, now()))
      throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    if (limit < 1 || limit > 50) throw AuthFailure.invalid();
    var c = decodeCursor(cursor);
    var rows = mapper.comments(postId, now(), c.time(), c.id(), limit + 1);
    boolean more = rows.size() > limit;
    var selected = more ? rows.subList(0, limit) : rows;
    var data =
        selected.stream()
            .map(
                row ->
                    new Reply(
                        row.id(),
                        row.postId(),
                        row.body(),
                        commentAuthor(row),
                        images(row.id(), "comments", row.expiresAt()),
                        row.replyToId(),
                        row.publishedAt(),
                        row.expiresAt(),
                        capabilities()))
            .toList();
    var page = new HashMap<String, Object>();
    page.put("hasMore", more);
    page.put(
        "nextCursor",
        more
            ? encodeCursor(
                selected.get(selected.size() - 1).publishedAt(),
                selected.get(selected.size() - 1).id())
            : null);
    return new Page<>(data, page, UUID.randomUUID().toString());
  }

  private static Map<String, Object> commentAuthor(BoardMapper.CommentRow r) {
    return authorDisplay(
        new BoardMapper.PostRow(
            r.id(),
            r.authorPrincipalId(),
            r.displayMode(),
            r.authorAccountId(),
            r.authorNickname(),
            null,
            r.body(),
            null,
            null,
            r.status(),
            r.publishedAt(),
            r.expiresAt(),
            r.version()));
  }

  private record Cursor(OffsetDateTime time, UUID id) {}

  private static Cursor decodeCursor(String cursor) {
    if (cursor == null) return new Cursor(null, null);
    try {
      String d =
          new String(
              Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
      int i = d.lastIndexOf('|');
      return new Cursor(
          OffsetDateTime.parse(d.substring(0, i)), UUID.fromString(d.substring(i + 1)));
    } catch (RuntimeException e) {
      throw new AuthFailure(400, "INVALID_CURSOR");
    }
  }

  private static String encodeCursor(OffsetDateTime time, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((time + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  // 本人列表含终态占位；创建时间和UUID形成稳定游标，状态筛选不改变权限范围。
  public Page<Object> ownContent(UUID owner, String kind, String status, String cursor, int limit) {
    if (!Set.of("posts", "comments").contains(kind) || limit < 1 || limit > 50)
      throw AuthFailure.invalid();
    if (status != null
        && !Set.of("pending", "visible", "rejected", "hidden", "deleted", "expired")
            .contains(status)) throw AuthFailure.invalid();
    var c = decodeCursor(cursor);
    var rows = mapper.ownContentIds(owner, kind, status, now(), c.time(), c.id(), limit + 1);
    boolean more = rows.size() > limit;
    var selected = more ? rows.subList(0, limit) : rows;
    var data =
        selected.stream()
            .map(r -> "posts".equals(kind) ? ownPost(owner, r.id()) : ownComment(owner, r.id()))
            .toList();
    var page = new HashMap<String, Object>();
    page.put("hasMore", more);
    page.put(
        "nextCursor",
        more
            ? encodeCursor(
                selected.get(selected.size() - 1).createdAt(),
                selected.get(selected.size() - 1).id())
            : null);
    return new Page<>(data, page, UUID.randomUUID().toString());
  }

  private static String text(String value, int max, boolean required) {
    if (value == null) throw AuthFailure.invalid();
    String result = Normalizer.normalize(value, Normalizer.Form.NFC);
    if (result.codePoints().anyMatch(cp -> cp == 0)
        || (required
            && result
                .codePoints()
                .allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))))
      throw AuthFailure.invalid();
    var matcher = GRAPHEME.matcher(result);
    int count = 0;
    while (matcher.find()) if (++count > max) throw AuthFailure.invalid();
    return result;
  }

  // 按发布时固定的展示模式返回允许公开的字段，不输出游客主体或账号邮箱。
  private static Map<String, Object> authorDisplay(BoardMapper.PostRow row) {
    if (!"account".equals(row.displayMode()) || row.authorAccountId() == null)
      return Map.of("kind", "guest", "label", "游客");
    Map<String, Object> account = new LinkedHashMap<>();
    account.put("kind", "account");
    account.put("accountId", row.authorAccountId());
    account.put("nickname", row.authorNickname() == null ? "Youren用户" : row.authorNickname());
    account.put("avatar", null);
    return account;
  }

  private static List<UUID> uniqueIds(List<UUID> ids, int max) {
    if (ids == null) return List.of();
    if (ids.size() > max
        || ids.stream().anyMatch(Objects::isNull)
        || new HashSet<>(ids).size() != ids.size()) throw AuthFailure.invalid();
    return List.copyOf(ids);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }
}

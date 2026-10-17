package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 留言板查询只暴露公开 DTO 所需字段，写操作使用当前服务端主体与状态。
public interface BoardMapper {
  record PostReceipt(
      String requestHash, String status, UUID resourceId, OffsetDateTime expiresAt) {}

  record PostRow(
      UUID id,
      UUID authorPrincipalId,
      String displayMode,
      UUID authorAccountId,
      String authorNickname,
      String title,
      String body,
      String category,
      String regionCode,
      String status,
      OffsetDateTime publishedAt,
      OffsetDateTime expiresAt,
      int version) {}

  String displayMode(@Param("principalId") UUID principalId);

  boolean regionActive(@Param("code") String code);

  int countActiveInterests(@Param("ids") List<UUID> ids);

  int countBindableMedia(@Param("owner") UUID owner, @Param("ids") List<UUID> ids);

  int insertPost(
      @Param("id") UUID id,
      @Param("owner") UUID owner,
      @Param("displayMode") String displayMode,
      @Param("title") String title,
      @Param("body") String body,
      @Param("category") String category,
      @Param("regionCode") String regionCode,
      @Param("now") OffsetDateTime now);

  int insertPostInterest(@Param("postId") UUID postId, @Param("interestId") UUID interestId);

  int insertPostMedia(
      @Param("postId") UUID postId,
      @Param("mediaId") UUID mediaId,
      @Param("position") int position,
      @Param("owner") UUID owner);

  int updateOwnPost(
      @Param("id") UUID id,
      @Param("owner") UUID owner,
      @Param("version") int version,
      @Param("title") String title,
      @Param("body") String body,
      @Param("category") String category,
      @Param("regionCode") String regionCode,
      @Param("now") OffsetDateTime now);

  PostRow findOwnPost(@Param("id") UUID id, @Param("owner") UUID owner);

  int deletePostMedia(@Param("postId") UUID postId);

  int deletePostInterests(@Param("postId") UUID postId);

  int deleteOwnPost(
      @Param("id") UUID id, @Param("owner") UUID owner, @Param("now") OffsetDateTime now);

  PostReceipt postReceipt(@Param("actor") String actor, @Param("key") String key);

  int insertPostReceipt(
      @Param("actor") String actor,
      @Param("key") String key,
      @Param("requestHash") String requestHash,
      @Param("resourceId") UUID resourceId,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  int completePostReceipt(
      @Param("actor") String actor, @Param("key") String key, @Param("now") OffsetDateTime now);

  PostRow findPost(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  List<PostRow> publicPosts(
      @Param("now") OffsetDateTime now,
      @Param("regionCode") String regionCode,
      @Param("interestId") UUID interestId,
      @Param("category") String category,
      @Param("query") String query,
      @Param("cursorTime") OffsetDateTime cursorTime,
      @Param("cursorId") UUID cursorId,
      @Param("limit") int limit);

  List<PostRow> ownPosts(
      @Param("owner") UUID owner, @Param("now") OffsetDateTime now, @Param("limit") int limit);

  // 回复复用现有comments字段；作者与父帖关系只由服务端写入。
  record CommentRow(
      UUID id,
      UUID postId,
      UUID replyToId,
      UUID authorPrincipalId,
      String displayMode,
      UUID authorAccountId,
      String authorNickname,
      String body,
      String status,
      OffsetDateTime publishedAt,
      OffsetDateTime expiresAt,
      int version) {}

  CommentRow findComment(@Param("id") UUID id);

  UUID lockReplyPost(@Param("id") UUID id);

  CommentRow lockReplyComment(@Param("id") UUID id);

  List<CommentRow> comments(
      @Param("postId") UUID postId,
      @Param("now") OffsetDateTime now,
      @Param("cursorTime") OffsetDateTime cursorTime,
      @Param("cursorId") UUID cursorId,
      @Param("limit") int limit);

  List<CommentRow> ownComments(@Param("owner") UUID owner);

  int insertComment(
      @Param("id") UUID id,
      @Param("postId") UUID postId,
      @Param("replyToId") UUID replyToId,
      @Param("owner") UUID owner,
      @Param("mode") String mode,
      @Param("body") String body,
      @Param("now") OffsetDateTime now);

  int editComment(
      @Param("id") UUID id,
      @Param("owner") UUID owner,
      @Param("version") int version,
      @Param("replyToId") UUID replyToId,
      @Param("body") String body,
      @Param("now") OffsetDateTime now);

  int deleteComment(
      @Param("id") UUID id, @Param("owner") UUID owner, @Param("now") OffsetDateTime now);

  int deleteCommentMedia(@Param("id") UUID id);

  int insertCommentMedia(
      @Param("id") UUID id,
      @Param("mediaId") UUID mediaId,
      @Param("position") int position,
      @Param("owner") UUID owner);

  int lockMedia(@Param("owner") UUID owner, @Param("ids") List<UUID> ids);

  int countReusableMedia(
      @Param("owner") UUID owner, @Param("ids") List<UUID> ids, @Param("id") UUID id);

  PostReceipt commentReceipt(
      @Param("actor") String actor, @Param("key") String key, @Param("route") String route);

  int insertCommentReceipt(
      @Param("actor") String actor,
      @Param("key") String key,
      @Param("route") String route,
      @Param("requestHash") String requestHash,
      @Param("resourceId") UUID resourceId,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  int completeCommentReceipt(
      @Param("actor") String actor,
      @Param("key") String key,
      @Param("route") String route,
      @Param("now") OffsetDateTime now);

  int replyCount(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  record Region(String code, String parentCode, String level, String nameJa, String nameZh) {}

  record Interest(UUID id, String code, String nameZh) {}

  Region region(@Param("code") String code);

  List<Interest> interests(@Param("id") UUID id);

  record ImageRow(UUID id, int width, int height) {}

  List<ImageRow> images(@Param("id") UUID id, @Param("kind") String kind);

  String publicImageKey(
      @Param("id") UUID id, @Param("variant") String variant, @Param("now") OffsetDateTime now);

  OffsetDateTime publicImageExpiry(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  // 仅供后续人工审核服务在验证管理员、理由、版本和审计事务后调用；不会由公开控制器暴露。
  int publishPost(
      @Param("id") UUID id, @Param("version") int version, @Param("now") OffsetDateTime now);

  int publishComment(
      @Param("id") UUID id, @Param("version") int version, @Param("now") OffsetDateTime now);

  boolean replyParentAvailable(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  record ContentId(UUID id, OffsetDateTime createdAt) {}

  List<ContentId> ownContentIds(
      @Param("owner") UUID owner,
      @Param("kind") String kind,
      @Param("status") String status,
      @Param("now") OffsetDateTime now,
      @Param("cursorTime") OffsetDateTime cursorTime,
      @Param("cursorId") UUID cursorId,
      @Param("limit") int limit);

  // 账号仅能管理数据库证明已接续的主体；游客仍严格按自己的主体授权。
  boolean ownsPrincipal(@Param("owner") UUID owner, @Param("principal") UUID principal);
}

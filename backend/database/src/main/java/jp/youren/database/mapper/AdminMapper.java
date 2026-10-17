package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 管理认证只返回MFA流程必要字段；口令摘要和密钥密文不映射到响应DTO。
public interface AdminMapper {
  record Account(
      UUID id,
      String loginName,
      String passwordHash,
      String status,
      String role,
      String mfaCiphertext,
      int authVersion) {}

  record Challenge(
      UUID id,
      UUID adminId,
      OffsetDateTime expiresAt,
      OffsetDateTime consumedAt,
      int failedAttempts) {}

  record Session(
      UUID adminId,
      String role,
      int authVersion,
      OffsetDateTime expiresAt,
      OffsetDateTime revokedAt) {}

  record Report(
      UUID id,
      UUID reporterPrincipalId,
      String targetType,
      UUID targetId,
      String reasonCode,
      String detail,
      String status,
      int version,
      OffsetDateTime createdAt) {}

  record ReportReceipt(String requestHash, UUID resourceId, OffsetDateTime expiresAt) {}

  record Action(
      UUID id,
      UUID adminId,
      String targetType,
      UUID targetId,
      String action,
      String reason,
      OffsetDateTime createdAt) {}

  record ReviewContent(
      String targetType,
      UUID targetId,
      String status,
      int version,
      OffsetDateTime pendingSince,
      String title,
      String body) {}

  record ReviewImage(UUID id, int width, int height) {}

  Account accountByLogin(@Param("loginName") String loginName);

  Account accountById(@Param("id") UUID id);

  int countAccounts();

  int insertAccount(
      @Param("id") UUID id,
      @Param("loginName") String loginName,
      @Param("passwordHash") String passwordHash,
      @Param("role") String role,
      @Param("mfaCiphertext") String mfaCiphertext,
      @Param("now") OffsetDateTime now);

  int insertChallenge(
      @Param("id") UUID id,
      @Param("adminId") UUID adminId,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  Challenge lockChallenge(@Param("id") UUID id);

  Account accountForChallenge(@Param("id") UUID challengeId);

  int failChallenge(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  int consumeChallenge(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  int insertSession(
      @Param("id") UUID id,
      @Param("adminId") UUID adminId,
      @Param("tokenHash") String tokenHash,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  Session sessionByToken(@Param("tokenHash") String tokenHash, @Param("now") OffsetDateTime now);

  boolean reportablePost(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  boolean reportableComment(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  ReportReceipt reportReceipt(@Param("actor") String actor, @Param("key") String key);

  int insertReportReceipt(
      @Param("actor") String actor,
      @Param("key") String key,
      @Param("hash") String hash,
      @Param("id") UUID id,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  int completeReportReceipt(
      @Param("actor") String actor, @Param("key") String key, @Param("now") OffsetDateTime now);

  int insertReport(
      @Param("id") UUID id,
      @Param("reporter") UUID reporter,
      @Param("targetType") String targetType,
      @Param("targetId") UUID targetId,
      @Param("reasonCode") String reasonCode,
      @Param("detail") String detail,
      @Param("now") OffsetDateTime now);

  Report reportById(@Param("id") UUID id);

  Report ownedReport(@Param("id") UUID id, @Param("reporter") UUID reporter);

  java.util.List<Report> ownedReports(@Param("reporter") UUID reporter);

  java.util.List<Report> adminReports(@Param("status") String status, @Param("limit") int limit);

  Report lockReport(@Param("id") UUID id);

  int resolveReport(
      @Param("id") UUID id, @Param("version") int version, @Param("now") OffsetDateTime now);

  java.util.List<ReviewContent> reviewQueue(
      @Param("status") String status, @Param("limit") int limit);

  java.util.List<ReviewImage> reviewImages(
      @Param("targetType") String targetType,
      @Param("targetId") UUID targetId,
      @Param("now") OffsetDateTime now);

  String adminReviewImageKey(@Param("mediaId") UUID mediaId);

  BoardMapper.PostRow lockReviewPost(@Param("id") UUID id);

  BoardMapper.CommentRow lockReviewComment(@Param("id") UUID id);

  int reviewPost(
      @Param("id") UUID id,
      @Param("version") int version,
      @Param("status") String status,
      @Param("now") OffsetDateTime now);

  int reviewComment(
      @Param("id") UUID id,
      @Param("version") int version,
      @Param("status") String status,
      @Param("now") OffsetDateTime now);

  int insertModerationAction(
      @Param("id") UUID id,
      @Param("adminId") UUID adminId,
      @Param("reportId") UUID reportId,
      @Param("targetType") String targetType,
      @Param("targetId") UUID targetId,
      @Param("action") String action,
      @Param("reason") String reason,
      @Param("expectedVersion") int expectedVersion,
      @Param("now") OffsetDateTime now);

  Action actionById(@Param("id") UUID id);

  int insertActionReceipt(
      @Param("actor") String actor,
      @Param("route") String route,
      @Param("key") String key,
      @Param("hash") String hash,
      @Param("id") UUID id,
      @Param("now") OffsetDateTime now,
      @Param("expiresAt") OffsetDateTime expiresAt);

  ReportReceipt actionReceipt(
      @Param("actor") String actor, @Param("route") String route, @Param("key") String key);

  boolean receiptExists(
      @Param("actor") String actor, @Param("route") String route, @Param("key") String key);
}

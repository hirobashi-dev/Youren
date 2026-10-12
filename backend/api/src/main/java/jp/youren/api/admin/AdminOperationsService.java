package jp.youren.api.admin;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.AdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 管理审核由人工显式决定；所有写操作检查角色、目标状态、版本并在同一事务写入审计。
@Service
public class AdminOperationsService {
  private static final Set<String> REVIEW_TYPES = Set.of("post", "comment");
  private static final Set<String> ACTIONS =
      Set.of("approve", "reject", "hide", "restore", "delete");
  private static final int IDEMPOTENCY_SECONDS = 86_400;
  private final AdminMapper mapper;
  private final Clock clock;

  public record Content(
      String targetType,
      UUID targetId,
      String status,
      int version,
      OffsetDateTime pendingSince,
      boolean reviewOverdue,
      String title,
      String body,
      List<Image> images) {}

  public record Image(UUID id, String url, int width, int height, OffsetDateTime expiresAt) {}

  public record AuditAction(
      UUID id,
      UUID adminId,
      String targetType,
      UUID targetId,
      String action,
      String reason,
      OffsetDateTime createdAt) {}

  public AdminOperationsService(AdminMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  public List<Content> content(
      AdminPrincipal principal, String status, String type, int limit, String baseUrl) {
    requireRead(principal);
    if (!Set.of("pending", "rejected").contains(status)
        || (type != null && !REVIEW_TYPES.contains(type))
        || limit < 1
        || limit > 100) throw AuthFailure.invalid();
    var now = now();
    return mapper.reviewQueue(status, limit).stream()
        .filter(item -> type == null || type.equals(item.targetType()))
        .map(
            item ->
                new Content(
                    item.targetType(),
                    item.targetId(),
                    item.status(),
                    item.version(),
                    item.pendingSince(),
                    !item.pendingSince().plusHours(72).isAfter(now),
                    item.title(),
                    item.body(),
                    mapper.reviewImages(item.targetType(), item.targetId(), now).stream()
                        .map(
                            image -> {
                              var expiresAt = now.plusMinutes(5);
                              String url =
                                  org.springframework.web.util.UriComponentsBuilder.fromUriString(
                                          baseUrl)
                                      .path("/v1/admin/media/{id}/display")
                                      .queryParam("expiresAt", expiresAt.toEpochSecond())
                                      .buildAndExpand(image.id())
                                      .toUriString();
                              return new Image(
                                  image.id(), url, image.width(), image.height(), expiresAt);
                            })
                        .toList()))
        .toList();
  }

  @Transactional
  public AuditAction moderate(
      AdminPrincipal principal,
      String targetType,
      UUID targetId,
      String action,
      String reason,
      int expectedVersion,
      String key,
      String request) {
    requireWrite(principal);
    if (!REVIEW_TYPES.contains(targetType)
        || !ACTIONS.contains(action)
        || targetId == null
        || expectedVersion < 1) throw AuthFailure.invalid();
    String normalizedReason = reason(reason);
    String route = "/v1/admin/moderation-actions";
    String hash = TokenSecrets.digest(request);
    var replay = replay(principal.adminId(), route, key, hash);
    if (replay != null) return replay;
    var post = "post".equals(targetType) ? mapper.lockReviewPost(targetId) : null;
    var comment = "comment".equals(targetType) ? mapper.lockReviewComment(targetId) : null;
    String currentStatus = post != null ? post.status() : comment == null ? null : comment.status();
    int currentVersion = post != null ? post.version() : comment == null ? -1 : comment.version();
    if (currentStatus == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    if (currentVersion != expectedVersion) throw new AuthFailure(412, "VERSION_CONFLICT");
    String nextStatus = nextStatus(action, currentStatus);
    var now = now();
    int changed =
        post != null
            ? mapper.reviewPost(targetId, expectedVersion, nextStatus, now)
            : mapper.reviewComment(targetId, expectedVersion, nextStatus, now);
    if (changed != 1) throw new AuthFailure(412, "VERSION_CONFLICT");
    UUID id = UUID.randomUUID();
    if (mapper.insertModerationAction(
            id,
            principal.adminId(),
            null,
            targetType,
            targetId,
            action,
            normalizedReason,
            expectedVersion,
            now)
        != 1) throw AuthFailure.unavailable();
    persistReceipt(principal.adminId(), route, key, hash, id, now);
    var saved = mapper.actionById(id);
    if (saved == null) throw AuthFailure.unavailable();
    return dto(saved);
  }

  @Transactional
  public AuditAction resolveReport(
      AdminPrincipal principal,
      UUID reportId,
      String resolution,
      String reason,
      int expectedVersion,
      String key,
      String request) {
    requireWrite(principal);
    if (!Set.of("action_taken", "no_action").contains(resolution) || expectedVersion < 1)
      throw AuthFailure.invalid();
    String normalizedReason = reason(reason);
    String route = "/v1/admin/reports/" + reportId + "/resolve";
    String hash = TokenSecrets.digest(request);
    var replay = replay(principal.adminId(), route, key, hash);
    if (replay != null) return replay;
    var report = mapper.lockReport(reportId);
    if (report == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    if (report.version() != expectedVersion) throw new AuthFailure(412, "VERSION_CONFLICT");
    if (!Set.of("open", "reviewing").contains(report.status()))
      throw new AuthFailure(409, "RESULT_ALREADY_RESOLVED");
    UUID actionId = UUID.randomUUID();
    var now = now();
    if (mapper.insertModerationAction(
            actionId,
            principal.adminId(),
            reportId,
            report.targetType(),
            report.targetId(),
            "report_" + resolution,
            normalizedReason,
            expectedVersion,
            now)
        != 1) throw AuthFailure.unavailable();
    if (mapper.resolveReport(reportId, expectedVersion, now) != 1)
      throw new AuthFailure(412, "VERSION_CONFLICT");
    persistReceipt(principal.adminId(), route, key, hash, actionId, now);
    var saved = mapper.actionById(actionId);
    if (saved == null) throw AuthFailure.unavailable();
    return dto(saved);
  }

  private AuditAction replay(UUID adminId, String route, String key, String hash) {
    var receipt = mapper.actionReceipt(adminId.toString(), route, key);
    if (receipt == null) return null;
    if (!receipt.requestHash().equals(hash)) throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
    var action = mapper.actionById(receipt.resourceId());
    if (action == null) throw AuthFailure.unavailable();
    return dto(action);
  }

  private void persistReceipt(
      UUID adminId, String route, String key, String hash, UUID id, OffsetDateTime now) {
    if (mapper.insertActionReceipt(
            adminId.toString(), route, key, hash, id, now, now.plusSeconds(IDEMPOTENCY_SECONDS))
        != 1) throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
  }

  private static String nextStatus(String action, String current) {
    boolean valid =
        switch (action) {
          case "approve" -> "pending".equals(current);
          case "reject" -> "pending".equals(current);
          case "hide" -> "visible".equals(current);
          case "restore" -> "hidden".equals(current);
          case "delete" -> Set.of("pending", "rejected", "visible", "hidden").contains(current);
          default -> false;
        };
    if (!valid) throw new AuthFailure(409, "INVALID_CONTENT_TRANSITION");
    return switch (action) {
      case "approve", "restore" -> "visible";
      case "reject" -> "rejected";
      case "hide" -> "hidden";
      default -> "deleted";
    };
  }

  private static String reason(String value) {
    if (value == null) throw AuthFailure.invalid();
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    if (normalized.isEmpty()
        || normalized.codePointCount(0, normalized.length()) > 500
        || normalized.codePoints().anyMatch(cp -> cp == 0)) throw AuthFailure.invalid();
    return normalized;
  }

  private static void requireRead(AdminPrincipal principal) {
    if (principal == null || !Set.of("reviewer", "operator", "auditor").contains(principal.role()))
      throw AuthFailure.unauthorized();
  }

  private static void requireWrite(AdminPrincipal principal) {
    requireRead(principal);
    if ("auditor".equals(principal.role())) throw new AuthFailure(403, "FORBIDDEN");
  }

  private static AuditAction dto(AdminMapper.Action action) {
    return new AuditAction(
        action.id(),
        action.adminId(),
        action.targetType(),
        action.targetId(),
        action.action(),
        action.reason(),
        action.createdAt());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }
}

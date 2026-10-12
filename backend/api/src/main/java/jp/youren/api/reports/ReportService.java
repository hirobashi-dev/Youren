package jp.youren.api.reports;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.AdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 举报只接收当前公开可读的帖子或回复；结案流程与被举报内容状态相互独立。
@Service
public class ReportService {
  private static final Set<String> REASONS = Set.of("spam", "abuse", "illegal", "privacy", "other");
  private final AdminMapper mapper;
  private final Clock clock;

  public record Report(UUID id, String status, OffsetDateTime createdAt, int version) {}

  public record AdminReport(
      Report report, String targetType, UUID targetId, String reasonCode, String detail) {}

  public ReportService(AdminMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional
  public Report submit(
      UUID reporter,
      String targetType,
      UUID targetId,
      String reasonCode,
      String detail,
      String key,
      String request) {
    if (reporter == null
        || targetId == null
        || !Set.of("post", "comment").contains(targetType)
        || !REASONS.contains(reasonCode)) throw AuthFailure.invalid();
    String normalizedDetail =
        detail == null ? "" : Normalizer.normalize(detail, Normalizer.Form.NFC);
    if (normalizedDetail.codePointCount(0, normalizedDetail.length()) > 500
        || normalizedDetail.codePoints().anyMatch(cp -> cp == 0)) throw AuthFailure.invalid();
    String hash = TokenSecrets.digest(request);
    var existing = mapper.reportReceipt(reporter.toString(), key);
    if (existing != null) {
      if (!existing.requestHash().equals(hash)) throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
      var previous = mapper.reportById(existing.resourceId());
      if (previous == null) throw AuthFailure.unavailable();
      return dto(previous);
    }
    var now = now();
    boolean visible =
        "post".equals(targetType)
            ? mapper.reportablePost(targetId, now)
            : mapper.reportableComment(targetId, now);
    if (!visible) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    UUID id = UUID.randomUUID();
    int inserted =
        mapper.insertReportReceipt(reporter.toString(), key, hash, id, now, now.plusHours(24));
    if (inserted != 1) {
      var raced = mapper.reportReceipt(reporter.toString(), key);
      if (raced == null || !raced.requestHash().equals(hash))
        throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
      var prior = mapper.reportById(raced.resourceId());
      if (prior == null) throw AuthFailure.unavailable();
      return dto(prior);
    }
    if (mapper.insertReport(id, reporter, targetType, targetId, reasonCode, normalizedDetail, now)
        != 1) throw AuthFailure.unavailable();
    var created = mapper.reportById(id);
    if (created == null) throw AuthFailure.unavailable();
    return dto(created);
  }

  public java.util.List<Report> mine(UUID reporter) {
    return mapper.ownedReports(reporter).stream().map(ReportService::dto).toList();
  }

  public java.util.List<AdminReport> adminList(String status, int limit) {
    if (status != null && !Set.of("open", "reviewing", "resolved").contains(status))
      throw AuthFailure.invalid();
    if (limit < 1 || limit > 100) throw AuthFailure.invalid();
    return mapper.adminReports(status, limit).stream()
        .map(
            report ->
                new AdminReport(
                    dto(report),
                    report.targetType(),
                    report.targetId(),
                    report.reasonCode(),
                    report.detail()))
        .toList();
  }

  public AdminReport adminDetail(UUID id) {
    var report = mapper.reportById(id);
    if (report == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    return new AdminReport(
        dto(report), report.targetType(), report.targetId(), report.reasonCode(), report.detail());
  }

  private static Report dto(AdminMapper.Report report) {
    return new Report(report.id(), report.status(), report.createdAt(), report.version());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }
}

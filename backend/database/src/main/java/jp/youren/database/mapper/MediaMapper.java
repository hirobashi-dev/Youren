package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 媒体内部对象只提供给服务层；公开访问仍需重新检查内容审核和归属状态。
public interface MediaMapper {
  record Asset(
      UUID id,
      UUID ownerPrincipalId,
      String purpose,
      String objectKey,
      String status,
      String mime,
      long sizeBytes,
      Integer width,
      Integer height,
      OffsetDateTime uploadExpiresAt,
      OffsetDateTime cleanupAfter,
      String checksum) {}

  record Variant(String kind, String objectKey, long sizeBytes, int width, int height) {}

  int insertAsset(
      @Param("id") UUID id,
      @Param("owner") UUID owner,
      @Param("purpose") String purpose,
      @Param("objectKey") String objectKey,
      @Param("mime") String mime,
      @Param("size") long size,
      @Param("tokenDigest") String tokenDigest,
      @Param("expires") OffsetDateTime expires,
      @Param("cleanup") OffsetDateTime cleanup,
      @Param("now") OffsetDateTime now);

  Asset findForOwner(@Param("id") UUID id, @Param("owner") UUID owner);

  UUID principalForAccount(@Param("accountId") UUID accountId);

  List<Variant> variants(@Param("mediaId") UUID mediaId);

  int markUploaded(
      @Param("id") UUID id,
      @Param("owner") UUID owner,
      @Param("mime") String mime,
      @Param("size") long size,
      @Param("objectKey") String objectKey,
      @Param("tokenDigest") String tokenDigest,
      @Param("checksum") String checksum,
      @Param("now") OffsetDateTime now);

  int markProcessing(
      @Param("id") UUID id, @Param("owner") UUID owner, @Param("now") OffsetDateTime now);

  int completeProcessing(
      @Param("id") UUID id,
      @Param("owner") UUID owner,
      @Param("mime") String mime,
      @Param("size") long size,
      @Param("width") int width,
      @Param("height") int height,
      @Param("checksum") String checksum,
      @Param("now") OffsetDateTime now);

  int insertVariant(
      @Param("mediaId") UUID mediaId,
      @Param("variant") Variant variant,
      @Param("now") OffsetDateTime now);

  int deleteVariants(@Param("mediaId") UUID mediaId);

  int deleteAsset(
      @Param("id") UUID id, @Param("owner") UUID owner, @Param("now") OffsetDateTime now);
}

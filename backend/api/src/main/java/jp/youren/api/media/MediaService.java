package jp.youren.api.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import jp.youren.api.guest.GuestSessionService;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.MediaMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 上传意向、原图和派生图始终归属同一服务器主体；ready只表示技术处理完成。
@Service
public class MediaService {
  public record UploadIntent(
      UUID mediaId,
      String uploadUrl,
      java.util.Map<String, String> uploadHeaders,
      java.time.Instant expiresAt) {}

  public record Media(UUID id, String status, String purpose, long sizeBytes) {}

  private static final Set<String> PURPOSES = Set.of("board", "profile", "dating", "event");
  private static final Set<String> MIMES = Set.of("image/jpeg", "image/png", "image/webp");
  private final MediaMapper mapper;
  private final StoragePort storage;
  private final ImageProcessor processor;
  private final GuestSessionService guests;
  private final Clock clock;

  public MediaService(
      MediaMapper mapper,
      StoragePort storage,
      ImageProcessor processor,
      GuestSessionService guests,
      Clock clock) {
    this.mapper = mapper;
    this.storage = storage;
    this.processor = processor;
    this.guests = guests;
    this.clock = clock;
  }

  // 账号身份由过滤器注入；游客必须出示独立凭证，客户端不能提交主体ID。
  public UUID resolveOwner(UUID accountId, String guestToken) {
    if (accountId != null && guestToken != null) throw new IllegalArgumentException("不能同时使用两种身份");
    if (accountId != null) {
      UUID principal = mapper.principalForAccount(accountId);
      if (principal == null) throw new IllegalArgumentException("身份不可用");
      return principal;
    }
    if (guestToken == null || guestToken.isBlank()) throw new IllegalArgumentException("需要有效身份凭证");
    return guests.requirePrincipal(guestToken);
  }

  // 先登记短期意向并只保存上传秘密摘要；对象键由服务生成，签名和存储路径互不复用。
  @Transactional
  public UploadIntent createIntent(
      UUID owner, String purpose, String mime, long size, String uploadUrlBase) {
    if (!PURPOSES.contains(purpose) || !MIMES.contains(mime) || size < 1 || size > 5_000_000) {
      throw new IllegalArgumentException("上传用途、格式或大小不符合要求");
    }
    UUID id = UUID.randomUUID();
    String token = TokenSecrets.generate();
    OffsetDateTime now = now();
    OffsetDateTime expiry = now.plusMinutes(10);
    int inserted =
        mapper.insertAsset(
            id,
            owner,
            purpose,
            UUID.randomUUID().toString(),
            mime,
            size,
            TokenSecrets.digest(token),
            expiry,
            now.plusHours(24),
            now);
    if (inserted != 1) throw new IllegalStateException("无法创建上传意向");
    return new UploadIntent(
        id,
        uploadUrlBase + "/" + id + "/content",
        java.util.Map.of("X-Upload-Token", token),
        expiry.toInstant());
  }

  // 临时上传票据只允许写入一次，流大小在写磁盘时再次硬限制。
  @Transactional
  public Media upload(UUID owner, UUID mediaId, String token, InputStream content)
      throws IOException {
    var asset = requireAsset(owner, mediaId);
    if (!"initiated".equals(asset.status()) || !TokenSecrets.matches(token, asset.checksum())) {
      throw new IllegalArgumentException("上传意向不可用");
    }
    if (content == null) {
      throw new IllegalArgumentException("上传内容缺失");
    }
    var stored = storage.store(StoragePort.Area.ORIGINAL, content);
    if (stored.sizeBytes() != asset.sizeBytes()) {
      storage.delete(StoragePort.Area.ORIGINAL, stored.objectKey());
      throw new IllegalArgumentException("上传文件大小与意向不一致");
    }
    String checksum = HexFormat.of().formatHex(sha256(readOriginal(stored.objectKey())));
    int updated =
        mapper.markUploaded(
            mediaId,
            owner,
            asset.mime(),
            stored.sizeBytes(),
            stored.objectKey(),
            TokenSecrets.digest(token),
            checksum,
            now());
    if (updated != 1) {
      storage.delete(StoragePort.Area.ORIGINAL, stored.objectKey());
      throw new IllegalArgumentException("上传意向已过期或已使用");
    }
    return new Media(mediaId, "uploaded", asset.purpose(), stored.sizeBytes());
  }

  // complete可安全重试；只有技术校验、重编码和变体落盘全部成功才写ready状态。
  @Transactional(rollbackFor = Exception.class)
  public Media complete(UUID owner, UUID mediaId) throws IOException {
    var asset = requireAsset(owner, mediaId);
    if ("approved".equals(asset.status())) return media(asset);
    if (!"uploaded".equals(asset.status()) || mapper.markProcessing(mediaId, owner, now()) != 1) {
      throw new IllegalArgumentException("媒体尚未上传或无法处理");
    }
    StoragePort.StoredObject display = null;
    StoragePort.StoredObject thumbnail = null;
    try (var input = storage.open(StoragePort.Area.ORIGINAL, asset.objectKey())) {
      var images = processor.process(input.readAllBytes(), asset.mime());
      display =
          storage.store(StoragePort.Area.DISPLAY, new ByteArrayInputStream(images.displayJpeg()));
      thumbnail =
          storage.store(
              StoragePort.Area.THUMBNAIL, new ByteArrayInputStream(images.thumbnailJpeg()));
      OffsetDateTime now = now();
      mapper.insertVariant(
          mediaId,
          new MediaMapper.Variant(
              "display",
              display.objectKey(),
              display.sizeBytes(),
              images.displayWidth(),
              images.displayHeight()),
          now);
      mapper.insertVariant(
          mediaId,
          new MediaMapper.Variant(
              "thumbnail",
              thumbnail.objectKey(),
              thumbnail.sizeBytes(),
              images.thumbnailWidth(),
              images.thumbnailHeight()),
          now);
      String originalChecksum = HexFormat.of().formatHex(sha256(readOriginal(asset.objectKey())));
      if (mapper.completeProcessing(
              mediaId,
              owner,
              "image/jpeg",
              asset.sizeBytes(),
              images.displayWidth(),
              images.displayHeight(),
              originalChecksum,
              now)
          != 1) throw new IllegalStateException("媒体处理状态冲突");
      return new Media(mediaId, "approved", asset.purpose(), asset.sizeBytes());
    } catch (IOException | RuntimeException failure) {
      if (display != null) storage.delete(StoragePort.Area.DISPLAY, display.objectKey());
      if (thumbnail != null) storage.delete(StoragePort.Area.THUMBNAIL, thumbnail.objectKey());
      throw failure;
    }
  }

  public Media status(UUID owner, UUID mediaId) {
    return media(requireAsset(owner, mediaId));
  }

  // 仅删除本人且尚未绑定内容的媒体；数据库先封锁访问，再逐个清理私有对象。
  @Transactional(rollbackFor = Exception.class)
  public void delete(UUID owner, UUID mediaId) throws IOException {
    var asset = requireAsset(owner, mediaId);
    var variants = mapper.variants(mediaId);
    if (mapper.deleteAsset(mediaId, owner, now()) != 1) {
      throw new IllegalArgumentException("已绑定或不可删除的媒体资源");
    }
    for (var variant : variants) {
      storage.delete(
          variant.kind().equals("display") ? StoragePort.Area.DISPLAY : StoragePort.Area.THUMBNAIL,
          variant.objectKey());
    }
    if (!asset.objectKey().isBlank()) storage.delete(StoragePort.Area.ORIGINAL, asset.objectKey());
  }

  private byte[] readOriginal(String objectKey) throws IOException {
    try (var input = storage.open(StoragePort.Area.ORIGINAL, objectKey)) {
      return input.readAllBytes();
    }
  }

  private MediaMapper.Asset requireAsset(UUID owner, UUID id) {
    var asset = mapper.findForOwner(id, owner);
    if (asset == null) throw new IllegalArgumentException("媒体资源不可用");
    return asset;
  }

  private static Media media(MediaMapper.Asset asset) {
    return new Media(asset.id(), asset.status(), asset.purpose(), asset.sizeBytes());
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("摘要算法不可用");
    }
  }
}

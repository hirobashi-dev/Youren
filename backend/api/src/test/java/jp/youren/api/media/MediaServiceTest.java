package jp.youren.api.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import javax.imageio.ImageIO;
import jp.youren.api.security.TokenSecrets;
import jp.youren.database.mapper.MediaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaServiceTest {
  @TempDir Path temporaryDirectory;

  // 上传票据仅以摘要落库；错误主体或错误票据不能创建任何文件。
  @Test
  void bindsUploadIntentToOwnerAndSingleUseSecret() throws Exception {
    var mapper = mock(MediaMapper.class);
    var storage = mock(StoragePort.class);
    var service = service(mapper, storage);
    UUID owner = UUID.randomUUID();
    byte[] png = png();
    when(mapper.insertAsset(
            any(),
            eq(owner),
            eq("board"),
            anyString(),
            eq("image/png"),
            eq((long) png.length),
            anyString(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    var intent =
        service.createIntent(owner, "board", "image/png", png.length, "http://localhost/v1/media");
    UUID id = intent.mediaId();
    String token = intent.uploadHeaders().get("X-Upload-Token");
    verify(mapper)
        .insertAsset(
            eq(id),
            eq(owner),
            eq("board"),
            anyString(),
            eq("image/png"),
            eq((long) png.length),
            eq(TokenSecrets.digest(token)),
            any(),
            any(),
            any());
    assertTrue(intent.uploadUrl().endsWith("/" + id + "/content"));

    var asset =
        new MediaMapper.Asset(
            id,
            owner,
            "board",
            UUID.randomUUID().toString(),
            "initiated",
            "image/png",
            png.length,
            null,
            null,
            java.time.OffsetDateTime.now().plusMinutes(5),
            java.time.OffsetDateTime.now().plusHours(24),
            TokenSecrets.digest(token));
    when(mapper.findForOwner(id, owner)).thenReturn(asset);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.upload(owner, id, "wrong-token", new java.io.ByteArrayInputStream(png)));
    verifyNoInteractions(storage);
    when(storage.store(eq(StoragePort.Area.ORIGINAL), any()))
        .thenReturn(new StoragePort.StoredObject("stored-key", png.length));
    when(storage.open(StoragePort.Area.ORIGINAL, "stored-key"))
        .thenReturn(new java.io.ByteArrayInputStream(png));
    when(mapper.markUploaded(
            eq(id),
            eq(owner),
            eq("image/png"),
            eq((long) png.length),
            eq("stored-key"),
            eq(TokenSecrets.digest(token)),
            anyString(),
            any()))
        .thenReturn(1);

    var uploaded = service.upload(owner, id, token, new java.io.ByteArrayInputStream(png));

    assertEquals("uploaded", uploaded.status());
    verify(storage).store(eq(StoragePort.Area.ORIGINAL), any());
  }

  // 内容无法处理时不创建ready媒体；完成状态与图片源格式及审核状态分离。
  @Test
  void rejectsWrongOwnerAndInvalidMediaBeforeCompletion() {
    var mapper = mock(MediaMapper.class);
    var service = service(mapper, mock(StoragePort.class));
    UUID owner = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    when(mapper.findForOwner(mediaId, owner)).thenReturn(null);

    assertThrows(IllegalArgumentException.class, () -> service.status(owner, mediaId));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.createIntent(owner, "board", "image/gif", 10, "http://localhost"));
    verify(mapper, never()).markProcessing(any(), any(), any());
  }

  private MediaService service(MediaMapper mapper, StoragePort storage) {
    return new MediaService(mapper, storage, new ImageProcessor(), null, Clock.systemUTC());
  }

  private static byte[] png() throws Exception {
    var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", output));
    return output.toByteArray();
  }
}

package jp.youren.api.media;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {
  private final ImageProcessor processor = new ImageProcessor();

  // JPEG EXIF方向必须先应用再重编码；展示图为无EXIF的JPEG且尺寸受控。
  @Test
  void appliesExifOrientationAndReencodesWithoutMetadata() throws Exception {
    byte[] source = jpegWithOrientation(4, 2, 6);

    var processed = processor.process(source, "image/jpeg");
    BufferedImage display = ImageIO.read(new ByteArrayInputStream(processed.displayJpeg()));

    assertEquals(2, display.getWidth());
    assertEquals(4, display.getHeight());
    assertTrue(processed.displayJpeg().length <= 1_000_000);
    assertTrue(processed.thumbnailJpeg().length <= 1_000_000);
    assertFalse(
        new String(processed.displayJpeg(), java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("Exif"));
  }

  // MIME必须与解码器识别的静态格式一致；GIF及损坏图片不能借扩展名绕过检查。
  @Test
  void rejectsMimeSpoofingUnsupportedAndCorruptImages() throws Exception {
    byte[] png = image(3, 2, "png");

    assertThrows(IllegalArgumentException.class, () -> processor.process(png, "image/jpeg"));
    assertThrows(
        IllegalArgumentException.class, () -> processor.process(new byte[] {1, 2, 3}, "image/png"));
    assertThrows(
        IllegalArgumentException.class, () -> processor.process(new byte[5_000_001], "image/png"));
  }

  // 像素数在解码前验证，20,000,001像素的PNG即使文件很小也必须拒绝。
  @Test
  void rejectsPixelBombBeforeDecodingPixels() throws Exception {
    byte[] png = image(1, 1, "png");
    ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN).putInt(16, 20_000_001).putInt(20, 1);
    CRC32 crc = new CRC32();
    crc.update(png, 12, 17);
    ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN).putInt(29, (int) crc.getValue());

    assertThrows(IllegalArgumentException.class, () -> processor.process(png, "image/png"));
  }

  // APNG的动画控制块即使声明PNG MIME也必须拒绝，不能只处理并展示首帧。
  @Test
  void rejectsAnimatedPng() throws Exception {
    byte[] png = image(1, 1, "png");
    byte[] animated = insertBeforeEnd(png, "acTL", new byte[] {0, 0, 0, 2, 0, 0, 0, 0});

    assertThrows(IllegalArgumentException.class, () -> processor.process(animated, "image/png"));
  }

  // 静态WebP接受并转换为通用展示JPEG，避免客户端依赖服务器原始编码器。
  @Test
  void acceptsStaticWebpAndCreatesBoundedJpeg() {
    byte[] webp =
        Base64.getDecoder()
            .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEALmk0mk0iIiIiIgBoSygABc6zbAAA");

    var processed = processor.process(webp, "image/webp");

    assertTrue(processed.displayWidth() > 0);
    assertTrue(processed.displayHeight() > 0);
    assertTrue(Math.max(processed.displayWidth(), processed.displayHeight()) <= 1920);
    assertTrue(processed.displayJpeg().length <= 1_000_000);
  }

  // 动画WebP的ANIM/ANMF标记必须在解码前拒绝，不能只保存首帧。
  @Test
  void rejectsAnimatedWebp() {
    byte[] webp =
        Base64.getDecoder()
            .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEALmk0mk0iIiIiIgBoSygABc6zbAAA");
    byte[] animated = insertBeforeWebpPayload(webp, "ANIM", new byte[0]);

    assertThrows(IllegalArgumentException.class, () -> processor.process(animated, "image/webp"));
  }

  private static byte[] image(int width, int height, String format) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, Color.BLUE.getRGB());
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, format, output));
    return output.toByteArray();
  }

  private static byte[] jpegWithOrientation(int width, int height, int orientation)
      throws Exception {
    byte[] jpeg = image(width, height, "jpeg");
    byte[] tiff = {
      'I',
      'I',
      42,
      0,
      8,
      0,
      0,
      0,
      1,
      0,
      0x12,
      0x01,
      3,
      0,
      1,
      0,
      0,
      0,
      (byte) orientation,
      0,
      0,
      0,
      0,
      0,
      0,
      0
    };
    byte[] exif = new byte[6 + tiff.length];
    System.arraycopy(
        "Exif\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, exif, 0, 6);
    System.arraycopy(tiff, 0, exif, 6, tiff.length);
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    result.write(jpeg, 0, 2);
    result.write(0xff);
    result.write(0xe1);
    int segmentLength = exif.length + 2;
    result.write(segmentLength >>> 8);
    result.write(segmentLength);
    result.write(exif);
    result.write(jpeg, 2, jpeg.length - 2);
    return result.toByteArray();
  }

  private static byte[] insertBeforeEnd(byte[] png, String type, byte[] data) throws Exception {
    int insert = png.length - 12;
    byte[] chunk = pngChunk(type, data);
    byte[] result = new byte[png.length + chunk.length];
    System.arraycopy(png, 0, result, 0, insert);
    System.arraycopy(chunk, 0, result, insert, chunk.length);
    System.arraycopy(png, insert, result, insert + chunk.length, 12);
    return result;
  }

  private static byte[] pngChunk(String type, byte[] data) throws Exception {
    ByteArrayOutputStream chunk = new ByteArrayOutputStream();
    var header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length).array();
    chunk.write(header);
    byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    chunk.write(typeBytes);
    chunk.write(data);
    CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    chunk.write(
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue()).array());
    return chunk.toByteArray();
  }

  private static byte[] insertBeforeWebpPayload(byte[] webp, String type, byte[] data) {
    int chunkLength = 8 + data.length + (data.length & 1);
    byte[] result = new byte[webp.length + chunkLength];
    System.arraycopy(webp, 0, result, 0, 12);
    System.arraycopy(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, result, 12, 4);
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(16, data.length);
    System.arraycopy(data, 0, result, 20, data.length);
    System.arraycopy(webp, 12, result, 12 + chunkLength, webp.length - 12);
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length - 8);
    return result;
  }
}

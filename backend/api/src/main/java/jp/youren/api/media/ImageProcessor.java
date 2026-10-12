package jp.youren.api.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

// 所有不可信图片串行限时解码，避免多个高像素文件同时占满API内存。
@Component
public final class ImageProcessor {
  private static final long MAX_INPUT_BYTES = 5_000_000;
  private static final long MAX_PIXELS = 20_000_000;
  private static final int MAX_DISPLAY_EDGE = 1920;
  private static final int MAX_THUMBNAIL_EDGE = 480;
  private static final int MAX_OUTPUT_BYTES = 1_000_000;
  private static final Duration DECODE_TIMEOUT = Duration.ofSeconds(10);
  private static final ThreadPoolExecutor DECODER =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(1),
          task -> {
            var thread = new Thread(task, "youren-image-decoder");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public ProcessedImages process(byte[] source, String expectedMime) {
    if (source == null || source.length == 0 || source.length > MAX_INPUT_BYTES) {
      throw new IllegalArgumentException("图片文件大小无效");
    }
    if (expectedMime == null) {
      throw new IllegalArgumentException("图片声明格式无效");
    }
    byte[] isolated = source.clone();
    Future<ProcessedImages> task;
    try {
      task = DECODER.submit(() -> decode(isolated, expectedMime));
    } catch (RuntimeException rejected) {
      throw new IllegalArgumentException("图片处理资源繁忙", rejected);
    }
    try {
      return task.get(DECODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      task.cancel(true);
      throw new IllegalArgumentException("图片处理超过时间限制", timeout);
    } catch (InterruptedException interrupted) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("图片处理已中断", interrupted);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof IllegalArgumentException invalid) {
        throw invalid;
      }
      if (cause instanceof IOException io) {
        throw new IllegalArgumentException("图片无法安全解码", io);
      }
      throw new IllegalArgumentException("图片处理失败", cause);
    }
  }

  private ProcessedImages decode(byte[] source, String expectedMime) throws IOException {
    try (ImageInputStream input =
        ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
      if (input == null) {
        throw new IllegalArgumentException("图片格式无效");
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new IllegalArgumentException("仅支持JPEG、PNG和WebP静态图片");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, false, false);
        String format = format(reader.getFormatName());
        String actualMime = mime(format);
        if (!actualMime.equals(expectedMime)) {
          throw new IllegalArgumentException("图片实际格式与声明格式不一致");
        }
        if (isAnimated(source, format)) {
          throw new IllegalArgumentException("不支持动画图片");
        }
        if (format.equals("jpeg") && reader.getNumImages(true) != 1) {
          throw new IllegalArgumentException("不支持多帧JPEG图片");
        }
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
          throw new IllegalArgumentException("图片像素数超过限制");
        }
        int subsampling = Math.max(1, (Math.max(width, height) + 3839) / 3840);
        ImageReadParam parameters = reader.getDefaultReadParam();
        parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
        BufferedImage decoded = reader.read(0, parameters);
        if (decoded == null) {
          throw new IllegalArgumentException("图片内容无法解码");
        }
        BufferedImage oriented = orient(decoded, ExifOrientation.read(source, format));
        BufferedImage normalized = rgbOnWhite(oriented);
        BufferedImage display = fit(normalized, MAX_DISPLAY_EDGE);
        BufferedImage thumbnail = fit(normalized, MAX_THUMBNAIL_EDGE);
        EncodedImage displayEncoded = encodeBounded(display);
        EncodedImage thumbnailEncoded = encodeBounded(thumbnail);
        return new ProcessedImages(
            displayEncoded.bytes(),
            displayEncoded.image().getWidth(),
            displayEncoded.image().getHeight(),
            thumbnailEncoded.bytes(),
            thumbnailEncoded.image().getWidth(),
            thumbnailEncoded.image().getHeight());
      } finally {
        reader.dispose();
      }
    }
  }

  private static String format(String reported) {
    String normalized = reported.toLowerCase(java.util.Locale.ROOT);
    if (normalized.equals("jpg") || normalized.equals("jpeg")) {
      return "jpeg";
    }
    if (normalized.equals("png")) {
      return "png";
    }
    if (normalized.equals("webp")) {
      return "webp";
    }
    throw new IllegalArgumentException("仅支持JPEG、PNG和WebP静态图片");
  }

  private static String mime(String format) {
    return switch (format) {
      case "jpeg" -> "image/jpeg";
      case "png" -> "image/png";
      case "webp" -> "image/webp";
      default -> throw new IllegalArgumentException("图片格式不支持");
    };
  }

  // PNG/APNG和WebP动画标志均在读取像素前检查，避免只保留首帧掩盖动画内容。
  private static boolean isAnimated(byte[] bytes, String format) {
    if (format.equals("png")) {
      return containsPngAnimationChunk(bytes);
    }
    if (format.equals("webp")) {
      return containsWebpAnimationChunk(bytes);
    }
    return false;
  }

  private static boolean containsPngAnimationChunk(byte[] bytes) {
    if (bytes.length < 8) {
      return false;
    }
    long offset = 8;
    while (offset + 12 <= bytes.length) {
      long size = unsignedIntBigEndian(bytes, (int) offset);
      if (size > bytes.length || offset + 12 + size > bytes.length) {
        return false;
      }
      String type = new String(bytes, (int) offset + 4, 4, StandardCharsets.US_ASCII);
      if (type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) {
        return true;
      }
      offset += 12 + size;
      if (type.equals("IEND")) {
        return false;
      }
    }
    return false;
  }

  private static boolean containsWebpAnimationChunk(byte[] bytes) {
    if (bytes.length < 12 || !ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WEBP")) {
      return false;
    }
    long end = Math.min(bytes.length, 8 + unsignedIntLittleEndian(bytes, 4));
    long offset = 12;
    while (offset + 8 <= end) {
      String type = new String(bytes, (int) offset, 4, StandardCharsets.US_ASCII);
      long size = unsignedIntLittleEndian(bytes, (int) offset + 4);
      long data = offset + 8;
      if (data + size > end) {
        return false;
      }
      if (type.equals("ANIM") || type.equals("ANMF")) {
        return true;
      }
      if (type.equals("VP8X") && size > 0 && (bytes[(int) data] & 0x02) != 0) {
        return true;
      }
      offset = data + size + (size & 1);
    }
    return false;
  }

  private static boolean ascii(byte[] bytes, int offset, String expected) {
    if (offset + expected.length() > bytes.length) {
      return false;
    }
    for (int index = 0; index < expected.length(); index++) {
      if ((bytes[offset + index] & 0xff) != expected.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private static long unsignedIntBigEndian(byte[] bytes, int offset) {
    return Integer.toUnsignedLong(
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
  }

  private static long unsignedIntLittleEndian(byte[] bytes, int offset) {
    return Integer.toUnsignedLong(
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
  }

  // EXIF方向先作用于像素；转换后的字节不携带源图metadata。
  private static BufferedImage orient(BufferedImage source, int orientation) {
    if (orientation < 2 || orientation > 8) {
      return source;
    }
    int width = source.getWidth();
    int height = source.getHeight();
    boolean swap = orientation >= 5;
    BufferedImage result =
        new BufferedImage(
            swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int targetX;
        int targetY;
        switch (orientation) {
          case 2 -> {
            targetX = width - 1 - x;
            targetY = y;
          }
          case 3 -> {
            targetX = width - 1 - x;
            targetY = height - 1 - y;
          }
          case 4 -> {
            targetX = x;
            targetY = height - 1 - y;
          }
          case 5 -> {
            targetX = y;
            targetY = x;
          }
          case 6 -> {
            targetX = height - 1 - y;
            targetY = x;
          }
          case 7 -> {
            targetX = height - 1 - y;
            targetY = width - 1 - x;
          }
          case 8 -> {
            targetX = y;
            targetY = width - 1 - x;
          }
          default -> {
            return source;
          }
        }
        result.setRGB(targetX, targetY, source.getRGB(x, y));
      }
    }
    return result;
  }

  private static BufferedImage rgbOnWhite(BufferedImage source) {
    BufferedImage result =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = result.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
      graphics.drawImage(source, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return result;
  }

  private static BufferedImage fit(BufferedImage source, int maximumEdge) {
    int longest = Math.max(source.getWidth(), source.getHeight());
    double scale = Math.min(1.0, (double) maximumEdge / longest);
    int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = result.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return result;
  }

  // 逐步降低质量和尺寸直到满足1MB硬上限；无法满足时拒绝，不产生半成功媒体状态。
  private static EncodedImage encodeBounded(BufferedImage initial) throws IOException {
    BufferedImage candidate = initial;
    float[] qualities = {0.82f, 0.72f, 0.62f, 0.52f, 0.42f, 0.35f};
    while (true) {
      for (float quality : qualities) {
        byte[] encoded = encodeJpeg(candidate, quality);
        if (encoded.length <= MAX_OUTPUT_BYTES) {
          return new EncodedImage(candidate, encoded);
        }
      }
      int longest = Math.max(candidate.getWidth(), candidate.getHeight());
      if (longest <= 320) {
        throw new IllegalArgumentException("展示图片无法压缩到大小限制");
      }
      int next = Math.max(320, (int) Math.floor(longest * 0.85));
      candidate = fit(candidate, next);
    }
  }

  private record EncodedImage(BufferedImage image, byte[] bytes) {}

  private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IOException("JPEG编码器不可用");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      ImageWriteParam parameters = writer.getDefaultWriteParam();
      parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      parameters.setCompressionQuality(quality);
      writer.write(null, new IIOImage(image, null, null), parameters);
      output.flush();
      return bytes.toByteArray();
    } finally {
      writer.dispose();
    }
  }

  public record ProcessedImages(
      byte[] displayJpeg,
      int displayWidth,
      int displayHeight,
      byte[] thumbnailJpeg,
      int thumbnailWidth,
      int thumbnailHeight) {
    public ProcessedImages {
      displayJpeg = displayJpeg.clone();
      thumbnailJpeg = thumbnailJpeg.clone();
    }

    @Override
    public byte[] displayJpeg() {
      return displayJpeg.clone();
    }

    @Override
    public byte[] thumbnailJpeg() {
      return thumbnailJpeg.clone();
    }
  }

  private static final class ExifOrientation {
    private ExifOrientation() {}

    static int read(byte[] bytes, String format) {
      if (!format.equals("jpeg") || bytes.length < 4 || !ascii(bytes, 0, "\u00ff\u00d8")) {
        return 1;
      }
      int offset = 2;
      while (offset + 4 <= bytes.length && (bytes[offset] & 0xff) == 0xff) {
        int marker = bytes[offset + 1] & 0xff;
        if (marker == 0xda || marker == 0xd9) {
          return 1;
        }
        int length = ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
        int data = offset + 4;
        if (length < 2 || data + length - 2 > bytes.length) {
          return 1;
        }
        if (marker == 0xe1 && length >= 8 && ascii(bytes, data, "Exif\0\0")) {
          return readTiffOrientation(bytes, data + 6, data + length - 2);
        }
        offset = data + length - 2;
      }
      return 1;
    }

    private static int readTiffOrientation(byte[] bytes, int base, int end) {
      if (base + 8 > end) {
        return 1;
      }
      ByteOrder order;
      if (bytes[base] == 'I' && bytes[base + 1] == 'I') {
        order = ByteOrder.LITTLE_ENDIAN;
      } else if (bytes[base] == 'M' && bytes[base + 1] == 'M') {
        order = ByteOrder.BIG_ENDIAN;
      } else {
        return 1;
      }
      if (readUnsignedShort(bytes, base + 2, order) != 42) {
        return 1;
      }
      long relativeIfd = readUnsignedInt(bytes, base + 4, order);
      if (relativeIfd > end - base - 2) {
        return 1;
      }
      int ifd = base + (int) relativeIfd;
      int entries = readUnsignedShort(bytes, ifd, order);
      for (int index = 0; index < entries; index++) {
        int entry = ifd + 2 + index * 12;
        if (entry + 12 > end) {
          return 1;
        }
        int tag = readUnsignedShort(bytes, entry, order);
        int type = readUnsignedShort(bytes, entry + 2, order);
        long count = readUnsignedInt(bytes, entry + 4, order);
        if (tag == 0x0112 && type == 3 && count >= 1) {
          int value = readUnsignedShort(bytes, entry + 8, order);
          return value >= 1 && value <= 8 ? value : 1;
        }
      }
      return 1;
    }

    private static int readUnsignedShort(byte[] bytes, int offset, ByteOrder order) {
      return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(order).getShort());
    }

    private static long readUnsignedInt(byte[] bytes, int offset, ByteOrder order) {
      return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(order).getInt());
    }
  }
}

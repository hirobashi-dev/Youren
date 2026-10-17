package jp.youren.api.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {
  @TempDir Path temporaryDirectory;

  // 配置必须指向绝对目录，避免工作目录变化时把用户文件写进未知位置。
  @Test
  void rejectsRelativeStorageRoot() {
    assertThrows(
        IllegalArgumentException.class, () -> new LocalFileStorage(Path.of("relative-media")));
  }

  // 原图和展示图使用随机键并分目录保存，存储实现不创建公开静态目录。
  @Test
  void storesObjectsPrivatelyWithRandomKeys() throws Exception {
    Path root = temporaryDirectory.resolve("private-media");
    var storage = new LocalFileStorage(root);
    var original =
        storage.store(StoragePort.Area.ORIGINAL, new ByteArrayInputStream(new byte[] {1, 2, 3}));
    var display =
        storage.store(StoragePort.Area.DISPLAY, new ByteArrayInputStream(new byte[] {4, 5}));

    assertNotEquals(original.objectKey(), display.objectKey());
    assertDoesNotThrow(() -> UUID.fromString(original.objectKey()));
    assertTrue(Files.isRegularFile(root.resolve("originals").resolve(original.objectKey())));
    assertTrue(Files.isRegularFile(root.resolve("display").resolve(display.objectKey())));
    assertFalse(Files.exists(root.resolve("public")));
    try (var input = storage.open(StoragePort.Area.DISPLAY, display.objectKey())) {
      assertArrayEquals(new byte[] {4, 5}, input.readAllBytes());
    }
  }

  // 即使调用方伪造相对路径，也只能拒绝读取和删除，不能触碰存储根目录之外的文件。
  @Test
  void rejectsTraversalKeysWithoutTouchingOutsideFiles() throws Exception {
    Path root = temporaryDirectory.resolve("private-media");
    Path outside = temporaryDirectory.resolve("outside.txt");
    Files.writeString(outside, "保留文件");
    var storage = new LocalFileStorage(root);

    assertThrows(
        IllegalArgumentException.class,
        () -> storage.open(StoragePort.Area.ORIGINAL, "../../outside.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> storage.delete(StoragePort.Area.ORIGINAL, "../outside.txt"));
    assertEquals("保留文件", Files.readString(outside));
  }

  // 上传流超过格式上限时立即失败，不留下可被后续流程误绑定的半成品对象。
  @Test
  void rejectsOversizedInputAndRemovesTemporaryFile() throws Exception {
    Path root = temporaryDirectory.resolve("private-media");
    var storage = new LocalFileStorage(root);
    byte[] oversized = new byte[Math.toIntExact(StoragePort.Area.ORIGINAL.maxBytes() + 1)];

    assertThrows(
        IllegalArgumentException.class,
        () -> storage.store(StoragePort.Area.ORIGINAL, new ByteArrayInputStream(oversized)));
    try (var files = Files.walk(root)) {
      assertEquals(0, files.filter(Files::isRegularFile).count());
    }
  }
}

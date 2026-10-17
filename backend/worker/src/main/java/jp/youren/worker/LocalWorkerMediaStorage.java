package jp.youren.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

// 清理器只接受 UUID 对象键和固定媒体分区，拒绝符号链接及根目录外路径。
public final class LocalWorkerMediaStorage implements WorkerMediaStorage {
  private static final Map<String, String> AREAS =
      Map.of(
          "original", "originals",
          "display", "display",
          "thumbnail", "thumbnails",
          "temporary", "temporary");
  private final Path root;

  public LocalWorkerMediaStorage(Path configuredRoot) {
    if (configuredRoot == null || !configuredRoot.isAbsolute()) {
      throw new IllegalArgumentException("YOUREN_MEDIA_ROOT必须是绝对路径");
    }
    try {
      if (Files.isSymbolicLink(configuredRoot)) throw new IOException("媒体根目录不能是符号链接");
      Files.createDirectories(configuredRoot);
      root = configuredRoot.toRealPath();
    } catch (IOException failure) {
      throw new IllegalArgumentException("媒体根目录不可用", failure);
    }
  }

  // 对象不存在时视为已经清理，重复执行不会破坏其他对象或分区。
  @Override
  public void delete(String area, String objectKey) throws IOException {
    String directory = AREAS.get(area);
    if (directory == null || !UUID.fromString(objectKey).toString().equals(objectKey)) {
      throw new IllegalArgumentException("媒体对象键无效");
    }
    Path folder = root.resolve(directory).normalize();
    if (!folder.startsWith(root)) throw new IOException("媒体分区越界");
    if (Files.isSymbolicLink(folder)) throw new IOException("拒绝跟随媒体目录链接");
    Files.createDirectories(folder);
    Path realFolder = folder.toRealPath();
    if (!realFolder.startsWith(root)) throw new IOException("媒体分区越界");
    Path file = realFolder.resolve(objectKey).normalize();
    if (!file.startsWith(realFolder) || Files.isSymbolicLink(file)) {
      throw new IOException("拒绝访问异常媒体对象");
    }
    Files.deleteIfExists(file);
  }
}

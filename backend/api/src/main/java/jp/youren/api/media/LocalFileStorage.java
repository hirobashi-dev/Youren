package jp.youren.api.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// 本机目录不映射到静态资源；对象键仅由服务端生成，并按用途分目录隔离。
public final class LocalFileStorage implements StoragePort {
  private final Path root;
  private final Map<Area, Path> directories = new EnumMap<>(Area.class);

  public LocalFileStorage(Path configuredRoot) {
    Objects.requireNonNull(configuredRoot, "媒体根目录不能为空");
    if (!configuredRoot.isAbsolute()) {
      throw new IllegalArgumentException("YOUREN_MEDIA_ROOT必须是绝对路径");
    }
    try {
      Path candidate = configuredRoot.toAbsolutePath().normalize();
      if (Files.isSymbolicLink(candidate)) {
        throw new IllegalArgumentException("媒体根目录不能是符号链接");
      }
      Files.createDirectories(candidate);
      if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("媒体根目录必须是普通目录");
      }
      this.root = candidate.toRealPath();
      for (Area area : Area.values()) {
        Path directory = this.root.resolve(area.directory()).normalize();
        if (!directory.startsWith(this.root)) {
          throw new IllegalArgumentException("媒体分区超出根目录");
        }
        if (Files.isSymbolicLink(directory)) {
          throw new IllegalArgumentException("媒体分区不能是符号链接");
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            || !directory.toRealPath().startsWith(this.root)) {
          throw new IllegalArgumentException("媒体分区必须位于根目录内");
        }
        directories.put(area, directory.toRealPath());
      }
    } catch (IOException error) {
      throw new IllegalArgumentException("无法初始化本机媒体目录", error);
    }
  }

  // 上传写入临时文件，校验完整大小后再原子发布随机键，失败时清除半成品。
  @Override
  public StoredObject store(Area area, InputStream content) throws IOException {
    Objects.requireNonNull(area, "媒体分区不能为空");
    Objects.requireNonNull(content, "媒体内容不能为空");
    Path directory = safeDirectory(area);
    Path temporary = Files.createTempFile(directory, ".incoming-", ".tmp");
    long size = 0;
    try {
      try (var output =
          Files.newOutputStream(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = content.read(buffer)) != -1) {
          size += read;
          if (size > area.maxBytes()) {
            throw new IllegalArgumentException("媒体文件超过允许大小");
          }
          output.write(buffer, 0, read);
        }
      }
      if (size == 0) {
        throw new IllegalArgumentException("媒体文件不能为空");
      }
      String objectKey = UUID.randomUUID().toString();
      Path destination = resolve(area, objectKey);
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, destination);
      }
      return new StoredObject(objectKey, size);
    } catch (IOException | RuntimeException failure) {
      Files.deleteIfExists(temporary);
      throw failure;
    }
  }

  // 读取前验证分区、键和文件类型，避免跟随符号链接逃出私有根目录。
  @Override
  public InputStream open(Area area, String objectKey) throws IOException {
    Path file = resolve(area, objectKey);
    if (Files.isSymbolicLink(file)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || !file.toRealPath().startsWith(safeDirectory(area))) {
      throw new IOException("媒体对象不可用");
    }
    return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  }

  // 重试删除对已不存在对象保持幂等；不会递归删除目录或其他媒体对象。
  @Override
  public void delete(Area area, String objectKey) throws IOException {
    Path file = resolve(area, objectKey);
    if (Files.isSymbolicLink(file)) {
      throw new IOException("拒绝删除异常媒体链接");
    }
    Files.deleteIfExists(file);
  }

  private Path resolve(Area area, String objectKey) throws IOException {
    Objects.requireNonNull(area, "媒体分区不能为空");
    try {
      if (!UUID.fromString(objectKey).toString().equals(objectKey)) {
        throw new IllegalArgumentException("媒体对象键格式无效");
      }
    } catch (NullPointerException | IllegalArgumentException invalid) {
      throw new IllegalArgumentException("媒体对象键格式无效", invalid);
    }
    Path directory = safeDirectory(area);
    Path file = directory.resolve(objectKey).normalize();
    if (!file.startsWith(directory) || file.equals(directory)) {
      throw new IllegalArgumentException("媒体对象键越界");
    }
    return file;
  }

  private Path safeDirectory(Area area) throws IOException {
    Path directory = directories.get(Objects.requireNonNull(area, "媒体分区不能为空"));
    if (directory == null
        || Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || !directory.toRealPath().startsWith(root)) {
      throw new IOException("媒体分区不可用");
    }
    return directory;
  }
}

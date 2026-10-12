package jp.youren.api.media;

import java.io.IOException;
import java.io.InputStream;

// 业务层只使用随机对象键，不感知本机目录或未来对象存储供应商。
public interface StoragePort {
  StoredObject store(Area area, InputStream content) throws IOException;

  InputStream open(Area area, String objectKey) throws IOException;

  void delete(Area area, String objectKey) throws IOException;

  record StoredObject(String objectKey, long sizeBytes) {}

  enum Area {
    ORIGINAL("originals", 5_000_000),
    DISPLAY("display", 1_000_000),
    THUMBNAIL("thumbnails", 1_000_000),
    TEMPORARY("temporary", 5_000_000);

    private final String directory;
    private final long maxBytes;

    Area(String directory, long maxBytes) {
      this.directory = directory;
      this.maxBytes = maxBytes;
    }

    String directory() {
      return directory;
    }

    public long maxBytes() {
      return maxBytes;
    }
  }
}

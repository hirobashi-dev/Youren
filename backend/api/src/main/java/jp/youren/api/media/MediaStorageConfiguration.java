package jp.youren.api.media;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 媒体根目录必须由本机环境显式提供，避免回退到公开资源目录或意外位置。
@Configuration
public class MediaStorageConfiguration {
  @Bean
  public StoragePort storagePort(@Value("${youren.media.root:}") String configuredRoot) {
    if (configuredRoot == null || configuredRoot.isBlank()) {
      throw new IllegalStateException("必须设置YOUREN_MEDIA_ROOT为本机私有绝对路径");
    }
    Path root;
    try {
      root = Path.of(configuredRoot);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("YOUREN_MEDIA_ROOT路径无效", invalid);
    }
    if (!root.isAbsolute()) {
      throw new IllegalStateException("YOUREN_MEDIA_ROOT必须是绝对路径");
    }
    return new LocalFileStorage(root);
  }
}

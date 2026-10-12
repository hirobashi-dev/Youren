package jp.youren.api.media;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaStorageConfigurationTest {
  @TempDir Path temporaryDirectory;

  // 缺少或使用相对路径时启动配置失败，防止媒体落入仓库或不确定工作目录。
  @Test
  void requiresExplicitPrivateAbsoluteRoot() {
    var configuration = new MediaStorageConfiguration();

    assertThrows(IllegalStateException.class, () -> configuration.storagePort(""));
    assertThrows(IllegalStateException.class, () -> configuration.storagePort("relative"));
  }

  // 正确的本机绝对目录创建受控存储分区供媒体服务使用。
  @Test
  void createsLocalStorageAtConfiguredRoot() {
    var storage = new MediaStorageConfiguration().storagePort(temporaryDirectory.toString());

    assertInstanceOf(LocalFileStorage.class, storage);
    assertTrue(temporaryDirectory.resolve("originals").toFile().isDirectory());
    assertTrue(temporaryDirectory.resolve("display").toFile().isDirectory());
    assertTrue(temporaryDirectory.resolve("thumbnails").toFile().isDirectory());
  }
}

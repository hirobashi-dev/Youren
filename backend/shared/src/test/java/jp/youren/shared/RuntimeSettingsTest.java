package jp.youren.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import jp.youren.shared.config.RuntimeSettings;
import org.junit.jupiter.api.Test;

class RuntimeSettingsTest {
  private Map<String, String> valid() {
    return new HashMap<>(
        Map.of(
            "SPRING_DATASOURCE_URL",
            "jdbc:postgresql://127.0.0.1:5442/youren_test",
            "SPRING_DATASOURCE_USERNAME",
            "youren_test",
            "SPRING_DATASOURCE_PASSWORD",
            "secret-value",
            "SPRING_DATA_REDIS_URL",
            "redis://127.0.0.1:6382"));
  }

  // 正常配置使用独立数据库密码，端口和超时提供安全默认值。
  @Test
  void acceptsValidConfigurationAndDefaults() {
    var settings = RuntimeSettings.from(valid());
    assertEquals(3000, settings.port());
    assertEquals(1500, settings.readinessTimeoutMs());
    assertEquals("info", settings.logLevel());
    assertFalse(settings.toString().contains("secret-value"));
  }

  // 每个必要字段缺失都应拒绝启动，不通过默认外部地址隐藏配置问题。
  @Test
  void rejectsEachMissingDependencySetting() {
    for (var field : valid().keySet()) {
      var env = valid();
      env.remove(field);
      var error = assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
      assertTrue(error.getMessage().contains(field));
    }
  }

  // URL只接受目标协议和有效主机；错误不能泄漏输入或嵌套异常中的凭据。
  @Test
  void rejectsInvalidUrlsWithoutLeakingSecrets() {
    for (var field : new String[] {"SPRING_DATASOURCE_URL", "SPRING_DATA_REDIS_URL"}) {
      for (var value :
          new String[] {
            "https://secret-value@host",
            "jdbc:postgresql://user:secret-value@host/db",
            "redis:///",
            "garbage-secret-value"
          }) {
        var env = valid();
        env.put(field, value);
        var error = assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
        assertTrue(error.getMessage().contains(field));
        assertFalse(error.toString().contains("secret-value"));
        assertNull(error.getCause());
      }
    }
  }

  // 端口及超时必须有限且为十进制整数，拒绝空值、越界和溢出。
  @Test
  void rejectsInvalidNumericSettings() {
    for (var field : new String[] {"SERVER_PORT", "READINESS_TIMEOUT_MS"}) {
      for (var value : new String[] {"", "0", "-1", "1.5", "65536", "999999999999999", " 3000"}) {
        var env = valid();
        env.put(field, value);
        assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
      }
    }
  }

  // 显式边界合法；未知日志级别应立即失败。
  @Test
  void acceptsBoundariesAndRejectsUnknownLogLevel() {
    var env = valid();
    env.put("SERVER_PORT", "65535");
    env.put("READINESS_TIMEOUT_MS", "10000");
    assertEquals(65535, RuntimeSettings.from(env).port());
    assertEquals(10000, RuntimeSettings.from(env).readinessTimeoutMs());
    env.put("LOG_LEVEL", "secret-value");
    assertEquals(
        "LOG_LEVEL 不正确",
        assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env)).getMessage());
  }
}

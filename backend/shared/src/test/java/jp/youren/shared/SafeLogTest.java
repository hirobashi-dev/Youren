package jp.youren.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import jp.youren.shared.logging.SafeLog;
import org.junit.jupiter.api.Test;

class SafeLogTest {
  // 事件、状态、耗时允许输出，正文、密码和异常不进入日志字段。
  @Test
  void retainsSafeMetadataAndDropsUnknownFields() {
    var input =
        Map.<String, Object>of(
            "event",
            "ready",
            "service",
            "api",
            "statusCode",
            200,
            "durationMs",
            1.5,
            "password",
            "secret-value",
            "error",
            new RuntimeException("secret-value"));
    assertEquals(
        Map.of("event", "ready", "service", "api", "statusCode", 200, "durationMs", 1.5),
        SafeLog.redact(input));
  }

  // 白名单字段也不能承载任意文本，数值必须有限且非负。
  @Test
  void rejectsUnsafeValuesEvenInAllowedFields() {
    assertEquals(
        Map.of(),
        SafeLog.redact(
            Map.of(
                "event",
                "password=secret-value",
                "requestId",
                "x".repeat(81),
                "statusCode",
                -1,
                "durationMs",
                Double.NaN)));
    assertEquals(Map.of(), SafeLog.redact(null));
  }
}

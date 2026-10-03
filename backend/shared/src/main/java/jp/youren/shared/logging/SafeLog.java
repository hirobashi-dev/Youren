package jp.youren.shared.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// 字段和值双白名单；禁止直接传出正文、异常和连接字符串。
public final class SafeLog {
  private SafeLog() {}

  public static Map<String, Object> redact(Map<String, ?> input) {
    Map<String, Object> output = new LinkedHashMap<>();
    if (input == null) return output;
    input.forEach(
        (key, value) -> {
          if (Set.of("event", "service", "requestId", "status").contains(key)
              && value instanceof String text
              && text.matches("[a-zA-Z0-9_-]{1,80}")) output.put(key, text);
          if (Set.of("statusCode", "durationMs").contains(key)
              && value instanceof Number number
              && Double.isFinite(number.doubleValue())
              && number.doubleValue() >= 0) output.put(key, value);
        });
    return output;
  }
}

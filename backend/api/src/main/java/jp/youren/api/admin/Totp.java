package jp.youren.api.admin;

import java.nio.ByteBuffer;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 仅接受RFC 6238的六位TOTP，在当前30秒窗口前后各容忍一个窗口时钟偏差。
final class Totp {
  private Totp() {}

  static String generateSecret() {
    byte[] bytes = new byte[20];
    new java.security.SecureRandom().nextBytes(bytes);
    return base32(bytes);
  }

  static boolean verify(String secret, String code, Instant now) {
    if (code == null || !code.matches("[0-9]{6}")) return false;
    long current = now.getEpochSecond() / 30;
    for (long step = current - 1; step <= current + 1; step++)
      if (java.security.MessageDigest.isEqual(
          code.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          code(secret, step).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return true;
    return false;
  }

  static String code(String secret, long step) {
    try {
      byte[] key = unbase32(secret);
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = digest[digest.length - 1] & 0x0f;
      int binary =
          ((digest[offset] & 0x7f) << 24)
              | ((digest[offset + 1] & 0xff) << 16)
              | ((digest[offset + 2] & 0xff) << 8)
              | (digest[offset + 3] & 0xff);
      return "%06d".formatted(binary % 1_000_000);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException error) {
      throw new IllegalStateException("管理员MFA验证不可用");
    }
  }

  private static String base32(byte[] bytes) {
    final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    StringBuilder output = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        output.append(alphabet[(buffer >> (bits - 5)) & 31]);
        bits -= 5;
      }
    }
    if (bits > 0) output.append(alphabet[(buffer << (5 - bits)) & 31]);
    return output.toString();
  }

  private static byte[] unbase32(String value) {
    String normalized = value.replace("=", "").toUpperCase(java.util.Locale.ROOT);
    byte[] output = new byte[normalized.length() * 5 / 8];
    int buffer = 0, bits = 0, index = 0;
    for (char current : normalized.toCharArray()) {
      int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(current);
      if (digit < 0) throw new IllegalArgumentException("MFA密钥格式无效");
      buffer = (buffer << 5) | digit;
      bits += 5;
      if (bits >= 8) {
        output[index++] = (byte) (buffer >> (bits - 8));
        bits -= 8;
      }
    }
    return output;
  }
}

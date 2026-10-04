package jp.youren.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// 高熵不透明秘密无需认证密钥；摘要只针对至少256位随机秘密，不用于六位验证码。
public final class TokenSecrets {
  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenSecrets() {}

  public static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String digest(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("安全摘要不可用");
    }
  }

  // 摘要比较不提前退出，不在异常或日志中携带原秘密。
  public static boolean matches(String token, String storedHash) {
    return token != null
        && storedHash != null
        && MessageDigest.isEqual(
            digest(token).getBytes(StandardCharsets.US_ASCII),
            storedHash.getBytes(StandardCharsets.US_ASCII));
  }
}

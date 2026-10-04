package jp.youren.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 验证码低熵，必须用外部注入的HMAC密钥；消息绑定挑战、用途和邮箱防止跨场景复用。
public final class ChallengeSecrets {
  private static final SecureRandom RANDOM = new SecureRandom();

  private ChallengeSecrets() {}

  public static String code() {
    return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
  }

  public static String hmac(
      AuthSettings settings, UUID id, String email, String purpose, String code) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(settings.hmacKey(), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(
                  (id + "\n" + email + "\n" + purpose + "\n" + code)
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw AuthFailure.unavailable();
    }
  }

  public static boolean matches(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
  }
}

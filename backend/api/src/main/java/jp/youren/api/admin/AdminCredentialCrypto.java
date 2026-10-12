package jp.youren.api.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

// 管理口令用独立加盐PBKDF2摘要；MFA密钥使用部署注入的256位AES密钥加密。
final class AdminCredentialCrypto {
  private static final int ITERATIONS = 310_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private AdminCredentialCrypto() {}

  static String passwordHash(char[] password) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] derived = derive(password, salt);
    return "pbkdf2-sha256$" + ITERATIONS + "$" + encode(salt) + "$" + encode(derived);
  }

  static boolean verifyPassword(char[] password, String encoded) {
    try {
      String[] parts = encoded.split("\\$", -1);
      if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 100_000 || iterations > 1_000_000) return false;
      byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
      byte[] actual = derive(password, Base64.getUrlDecoder().decode(parts[2]), iterations);
      return java.security.MessageDigest.isEqual(expected, actual);
    } catch (RuntimeException error) {
      return false;
    }
  }

  static String encryptTotpSecret(String secret, String base64Key) {
    try {
      byte[] key = Base64.getDecoder().decode(base64Key);
      if (key.length != 32) throw new IllegalArgumentException("管理员MFA密钥配置无效");
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          javax.crypto.Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new javax.crypto.spec.GCMParameterSpec(128, nonce));
      byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      byte[] packed = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, packed, 0, nonce.length);
      System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(packed);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException error) {
      throw new IllegalStateException("管理员MFA密钥不可用");
    }
  }

  static String decryptTotpSecret(String encoded, String base64Key) {
    try {
      byte[] packed = Base64.getDecoder().decode(encoded);
      byte[] key = Base64.getDecoder().decode(base64Key);
      if (key.length != 32 || packed.length < 29)
        throw new IllegalArgumentException("管理员MFA密钥配置无效");
      var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          javax.crypto.Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new javax.crypto.spec.GCMParameterSpec(128, packed, 0, 12));
      return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException error) {
      throw new IllegalStateException("管理员MFA密钥不可用");
    }
  }

  private static byte[] derive(char[] password, byte[] salt) {
    return derive(password, salt, ITERATIONS);
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    try {
      var spec = new PBEKeySpec(password, salt, iterations, 256);
      try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .getEncoded();
      } finally {
        spec.clearPassword();
      }
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("管理员口令摘要不可用");
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}

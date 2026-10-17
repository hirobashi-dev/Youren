package jp.youren.api.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

// 验证口令摘要不可逆比较、MFA密文认证及RFC TOTP标准向量与时间窗口边界。
class AdminCredentialCryptoTest {
  @Test
  void passwordHashUsesUniqueSaltAndChecksTheEntireSecret() {
    char[] password = "long-admin-passphrase".toCharArray();
    String first = AdminCredentialCrypto.passwordHash(password);
    String second = AdminCredentialCrypto.passwordHash(password);
    assertNotEquals(first, second);
    assertTrue(AdminCredentialCrypto.verifyPassword(password, first));
    assertFalse(AdminCredentialCrypto.verifyPassword("different".toCharArray(), first));
  }

  @Test
  void encryptedTotpSecretRequiresTheConfiguredKey() {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    String encrypted = AdminCredentialCrypto.encryptTotpSecret("private-seed", key);
    assertNotEquals("private-seed", encrypted);
    assertEquals("private-seed", AdminCredentialCrypto.decryptTotpSecret(encrypted, key));
    byte[] otherBytes = new byte[32];
    otherBytes[0] = 1;
    String other = Base64.getEncoder().encodeToString(otherBytes);
    assertThrows(
        IllegalStateException.class,
        () -> AdminCredentialCrypto.decryptTotpSecret(encrypted, other));
  }

  @Test
  void totpMatchesRfc6238VectorAndAcceptsOneAdjacentWindow() {
    String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    assertEquals("287082", Totp.code(secret, 1));
    assertTrue(Totp.verify(secret, "287082", Instant.ofEpochSecond(59)));
    assertTrue(Totp.verify(secret, "287082", Instant.ofEpochSecond(60)));
    assertFalse(Totp.verify(secret, "287082", Instant.ofEpochSecond(120)));
    assertFalse(Totp.verify(secret, "28782", Instant.ofEpochSecond(59)));
  }
}

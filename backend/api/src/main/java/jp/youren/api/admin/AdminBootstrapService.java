package jp.youren.api.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import jp.youren.api.auth.AuthFailure;
import jp.youren.database.mapper.AdminMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 首名管理员创建持有表级互斥锁，并在同一事务中确认空库，防止并行命令创建多个首管。
@Service
public class AdminBootstrapService {
  private final AdminMapper mapper;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String encryptionKey;
  private final TransactionTemplate transaction;

  public AdminBootstrapService(
      AdminMapper mapper,
      DataSource dataSource,
      Clock clock,
      PlatformTransactionManager manager,
      @Value("${YOUREN_ADMIN_MFA_ENCRYPTION_KEY:}") String encryptionKey) {
    this.mapper = mapper;
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
    this.encryptionKey = encryptionKey;
    this.transaction = new TransactionTemplate(manager);
  }

  public void createFirstReviewer(String loginName, char[] password, String secret, String code) {
    String login = loginName == null ? "" : loginName.trim().toLowerCase(java.util.Locale.ROOT);
    if (!login.matches("[a-z0-9][a-z0-9._-]{2,63}") || password == null || password.length < 16)
      throw AuthFailure.invalid();
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (!Totp.verify(secret, code, now.toInstant())) throw AuthFailure.unauthorized();
    if (encryptionKey == null || encryptionKey.isBlank()) throw AuthFailure.unavailable();
    String passwordHash = AdminCredentialCrypto.passwordHash(password);
    Arrays.fill(password, '\0');
    String encryptedSecret = AdminCredentialCrypto.encryptTotpSecret(secret, encryptionKey);
    transaction.executeWithoutResult(
        status -> {
          jdbc.execute("LOCK TABLE admin_accounts IN EXCLUSIVE MODE");
          if (mapper.countAccounts() != 0) throw AuthFailure.invalid();
          mapper.insertAccount(
              UUID.randomUUID(), login, passwordHash, "reviewer", encryptedSecret, now);
        });
  }
}

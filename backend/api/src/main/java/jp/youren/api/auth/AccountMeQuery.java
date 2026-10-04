package jp.youren.api.auth;

import java.time.Instant;
import java.util.UUID;
import jp.youren.database.mapper.IdentityAccountMapper;
import org.springframework.stereotype.Service;

// 本人DTO与公开账号行隔离；任务4在同一查询入口接入普通资料，认证不实现恋爱意愿。
@Service
public class AccountMeQuery {
  private final IdentityAccountMapper accounts;

  public record Settings(boolean allowStrangerDm, boolean pushEnabled, boolean lockscreenPreview) {}

  public record AgeDeclaration(String status, Instant declaredAt, Instant withdrawnAt) {}

  public record Me(
      UUID id,
      String email,
      String status,
      Object profile,
      Settings settings,
      AgeDeclaration ageDeclaration,
      boolean onboardingRequired,
      Integer profileVersion) {
    @Override
    public String toString() {
      return "Me[id=" + id + ", personalData=REDACTED]";
    }
  }

  public AccountMeQuery(IdentityAccountMapper accounts) {
    this.accounts = accounts;
  }

  public Me read(UUID id) {
    var account = accounts.find(id);
    SessionService.requireActive(account);
    var settings = accounts.settings(id);
    if (settings == null) throw new IllegalStateException("账号设置不可用");
    return new Me(
        id,
        account.email(),
        account.status(),
        null,
        new Settings(
            settings.allowStrangerDm(), settings.pushEnabled(), settings.lockscreenPreview()),
        new AgeDeclaration("not_declared", null, null),
        true,
        null);
  }
}

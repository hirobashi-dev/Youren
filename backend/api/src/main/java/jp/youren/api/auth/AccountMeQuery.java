package jp.youren.api.auth;

import java.time.Instant;
import java.util.UUID;
import jp.youren.api.profile.ProfileService;
import jp.youren.database.mapper.IdentityAccountMapper;
import jp.youren.database.mapper.ProfileMapper;
import org.springframework.stereotype.Service;

// 本人DTO与公开资料隔离；登录快照与GET /me共用最新编辑资料，不实现恋爱意愿写入。
@Service
public class AccountMeQuery {
  private final IdentityAccountMapper accounts;
  private final ProfileService profiles;
  private final ProfileMapper mapper;
  private final java.time.Clock clock;

  public record Settings(boolean allowStrangerDm, boolean pushEnabled, boolean lockscreenPreview) {}

  public record AgeDeclaration(String status, Instant declaredAt, Instant withdrawnAt) {}

  public record Me(
      UUID id,
      String email,
      String status,
      ProfileService.OwnerProfile profile,
      Settings settings,
      AgeDeclaration ageDeclaration,
      boolean onboardingRequired,
      Integer profileVersion) {
    @Override
    public String toString() {
      return "Me[id=" + id + ", personalData=REDACTED]";
    }
  }

  public AccountMeQuery(
      IdentityAccountMapper accounts,
      ProfileService profiles,
      ProfileMapper mapper,
      java.time.Clock clock) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Me read(UUID id) {
    var account = accounts.find(id);
    SessionService.requireActive(account);
    var settings = accounts.settings(id);
    if (settings == null) throw new IllegalStateException("账号设置不可用");
    var state = profiles.own(id);
    var age =
        mapper.age(
            id, java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
    return new Me(
        id,
        account.email(),
        account.status(),
        state.profile(),
        new Settings(
            settings.allowStrangerDm(), settings.pushEnabled(), settings.lockscreenPreview()),
        age == null
            ? new AgeDeclaration("not_declared", null, null)
            : new AgeDeclaration(
                age.status(),
                age.declaredAt().toInstant(),
                age.withdrawnAt() == null ? null : age.withdrawnAt().toInstant()),
        state.profile() == null,
        state.version());
  }
}

package jp.youren.api.profile;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import jp.youren.api.auth.*;
import jp.youren.database.mapper.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 编辑最新草稿与读取公开快照分离；每个DTO显式映射，内部JSON和数据库实体不会直接公开。
@Service
public class ProfileService {
  private static final Pattern GRAPHEME = Pattern.compile("\\X");
  private static final Set<String> FIELDS =
      Set.of("nickname", "bio", "regionCode", "interestIds", "avatarMediaId");
  private final ProfileMapper mapper;
  private final IdentityAccountMapper accounts;
  private final ObjectMapper json;
  private final Clock clock;
  private final int nicknameMax, bioMax;

  public record Region(
      String code, String parentCode, String level, String nameJa, String nameZh) {}

  public record Interest(UUID id, String code, String nameZh) {}

  public record Capabilities(boolean canRead, boolean canEdit, boolean canMessage) {}

  public record PublicProfile(
      UUID id,
      String nickname,
      String bio,
      Object avatar,
      Region region,
      List<Interest> interests,
      Capabilities capabilities) {}

  public record OwnerProfile(
      UUID id,
      String nickname,
      String bio,
      Object avatar,
      Region region,
      List<Interest> interests,
      Capabilities capabilities,
      String moderationStatus) {
    @Override
    public String toString() {
      return "OwnerProfile[personalData=REDACTED]";
    }
  }

  public record OwnerState(OwnerProfile profile, Integer version) {}

  public record ProfileVersion(OwnerProfile profile, int version) {}

  public ProfileService(
      ProfileMapper mapper,
      IdentityAccountMapper accounts,
      ObjectMapper json,
      Clock clock,
      @Value("${youren.profile.nickname-graphemes:30}") int nicknameMax,
      @Value("${youren.profile.bio-graphemes:500}") int bioMax) {
    if (nicknameMax < 1 || bioMax < 1) throw new IllegalArgumentException("暂定资料限额必须为正数");
    this.mapper = mapper;
    this.accounts = accounts;
    this.json = json;
    this.clock = clock;
    this.nicknameMax = nicknameMax;
    this.bioMax = bioMax;
  }

  // 当前编辑版本包括未发布草稿，完成基本资料引导不要求资料已公开。
  public OwnerState own(UUID id) {
    var snapshot = current(id);
    return snapshot == null
        ? new OwnerState(null, null)
        : new OwnerState(owner(id, snapshot), snapshot.version());
  }

  // 资格与快照在同一数据库快照读取，防止审核/隐藏并发变更期间混用不同版本。
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PublicProfile publicProfile(UUID id, UUID viewer) {
    if (!mapper.isPublic(id)) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    var row = mapper.published(id);
    if (row == null || !"approved".equals(row.moderationStatus()))
      throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    var fields = parse(row.fields());
    return new PublicProfile(
        id,
        fields.get("nickname").asText(),
        fields.get("bio").asText(),
        null,
        region(fields),
        interests(fields),
        new Capabilities(true, false, viewer != null && mapper.canMessage(viewer, id)));
  }

  @Transactional
  public ProfileVersion update(UUID id, JsonNode input, String ifMatch) {
    int expected = version(ifMatch);
    fields(input, FIELDS);
    SessionService.requireActive(accounts.lock(id));
    var old = current(id);
    int current = old == null ? 0 : old.version();
    if (expected != current) throw new AuthFailure(412, "VERSION_CONFLICT");
    if (current == Integer.MAX_VALUE) throw new AuthFailure(409, "QUOTA_EXCEEDED");
    ObjectNode result = old == null ? json.createObjectNode() : parse(old.fields()).deepCopy();
    if (old == null) {
      result.put("bio", "");
      result.putNull("regionCode");
      result.putNull("avatarMediaId");
      result.putArray("interestIds");
    }
    if (input.has("nickname"))
      result.put("nickname", text(input.get("nickname"), nicknameMax, true));
    if (!result.hasNonNull("nickname")) throw AuthFailure.invalid();
    if (input.has("bio")) result.put("bio", text(input.get("bio"), bioMax, false));
    if (input.has("regionCode")) {
      var value = input.get("regionCode");
      if (value.isNull()) result.putNull("regionCode");
      else if (!value.isTextual() || mapper.region(value.asText()) == null)
        throw AuthFailure.invalid();
      else result.put("regionCode", value.asText());
    }
    if (input.has("interestIds")) {
      var value = input.get("interestIds");
      if (!value.isArray()) throw AuthFailure.invalid();
      Set<UUID> unique = new HashSet<>();
      var ids = json.createArrayNode();
      for (var entry : value) {
        UUID interest = uuid(entry);
        if (!unique.add(interest) || mapper.interest(interest) == null) throw AuthFailure.invalid();
        ids.add(interest.toString());
      }
      result.set("interestIds", ids);
    }
    if (input.has("avatarMediaId")) {
      var value = input.get("avatarMediaId");
      if (value.isNull()) result.putNull("avatarMediaId");
      else {
        uuid(value);
        // 本阶段没有受控图片展示绑定，全部非空请求安全拒绝，不泄漏资源归属或签名URL。
        throw new AuthFailure(409, "MEDIA_NOT_READY");
      }
    }
    mapper.saveDraft(id, result.toString(), current + 1, now());
    return new ProfileVersion(
        owner(id, new ProfileMapper.Snapshot(result.toString(), current + 1, "pending")),
        current + 1);
  }

  @Transactional
  public AccountMeQuery.Settings settings(UUID id, JsonNode input) {
    fields(input, Set.of("allowStrangerDm", "pushEnabled", "lockscreenPreview"));
    input
        .elements()
        .forEachRemaining(
            value -> {
              if (!value.isBoolean()) throw AuthFailure.invalid();
            });
    SessionService.requireActive(accounts.lock(id));
    if (mapper.settings(
            id,
            bool(input, "allowStrangerDm"),
            bool(input, "pushEnabled"),
            bool(input, "lockscreenPreview"),
            now())
        != 1) throw new IllegalStateException("账号设置不可用");
    var result = accounts.settings(id);
    return new AccountMeQuery.Settings(
        result.allowStrangerDm(), result.pushEnabled(), result.lockscreenPreview());
  }

  private ProfileMapper.Snapshot current(UUID id) {
    var draft = mapper.draft(id);
    var published = mapper.published(id);
    return draft != null && (published == null || draft.version() >= published.version())
        ? draft
        : published;
  }

  private OwnerProfile owner(UUID id, ProfileMapper.Snapshot snapshot) {
    var fields = parse(snapshot.fields());
    return new OwnerProfile(
        id,
        fields.get("nickname").asText(),
        fields.get("bio").asText(),
        null,
        region(fields),
        interests(fields),
        new Capabilities(true, true, false),
        snapshot.moderationStatus());
  }

  private Region region(JsonNode fields) {
    if (!fields.hasNonNull("regionCode")) return null;
    var r = mapper.region(fields.get("regionCode").asText());
    return r == null
        ? null
        : new Region(r.code(), r.parentCode(), r.level(), r.nameJa(), r.nameZh());
  }

  private List<Interest> interests(JsonNode fields) {
    var result = new ArrayList<Interest>();
    for (var id : fields.get("interestIds")) {
      var item = mapper.interest(uuid(id));
      if (item != null) result.add(new Interest(item.id(), item.code(), item.nameZh()));
    }
    return List.copyOf(result);
  }

  private ObjectNode parse(String fields) {
    try {
      return (ObjectNode) json.readTree(fields);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("资料快照不可用");
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private static Boolean bool(JsonNode input, String key) {
    return input.has(key) ? input.get(key).booleanValue() : null;
  }

  public static UUID uuid(JsonNode value) {
    if (value == null || !value.isTextual()) throw AuthFailure.invalid();
    try {
      UUID id = UUID.fromString(value.asText());
      if (!id.toString().equalsIgnoreCase(value.asText())) throw AuthFailure.invalid();
      return id;
    } catch (IllegalArgumentException ex) {
      throw AuthFailure.invalid();
    }
  }

  private static void fields(JsonNode input, Set<String> allowed) {
    if (input == null || !input.isObject() || input.isEmpty()) throw AuthFailure.invalid();
    input
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw AuthFailure.invalid();
            });
  }

  private static int version(String value) {
    if (value == null) throw new AuthFailure(428, "VERSION_REQUIRED");
    if (!value.matches("\"(0|[1-9][0-9]*)\"")) throw AuthFailure.invalid();
    try {
      return Integer.parseInt(value.substring(1, value.length() - 1));
    } catch (NumberFormatException ex) {
      throw AuthFailure.invalid();
    }
  }

  private static String text(JsonNode value, int max, boolean nonblank) {
    if (!value.isTextual()) throw AuthFailure.invalid();
    String text = value.asText();
    // Unicode空白包括NBSP，Java isBlank单独使用会遗漏这种可见空格。
    if (nonblank
        && text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
      throw AuthFailure.invalid();
    // PostgreSQL文本不接受NUL或孤立代理项；先返回安全验证错误而不是数据库500。
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == 0) throw AuthFailure.invalid();
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i)))
          throw AuthFailure.invalid();
      } else if (Character.isLowSurrogate(c)) throw AuthFailure.invalid();
    }
    var matcher = GRAPHEME.matcher(text);
    int count = 0;
    while (matcher.find()) if (++count > max) throw AuthFailure.invalid();
    return text;
  }
}

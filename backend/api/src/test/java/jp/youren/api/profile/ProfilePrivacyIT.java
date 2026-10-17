package jp.youren.api.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ProfilePrivacyIT extends ProfileTestSupport {
  // 未认证不能读取本人资料；本人DTO只使用令牌身份，首次草稿不成为公开主页。
  @Test
  void meRequiresUserAndFirstDraftRemainsPrivate() throws Exception {
    var u = user();
    assertEquals(401, request("GET", "/v1/me", null, null, null, null).statusCode());
    assertEquals(401, request("GET", "/v1/me", null, "invalid", null, null).statusCode());
    var initial = request("GET", "/v1/me", null, u.token(), null, null);
    assertEquals(200, initial.statusCode());
    assertEquals(8, data(initial).size());
    assertTrue(data(initial).get("profile").isNull());
    assertTrue(data(initial).get("profileVersion").isNull());
    assertEquals(
        200,
        patch(u, Map.of("nickname", "小林", "bio", "<script>纯文本</script>"), "\"0\"").statusCode());
    var me = data(request("GET", "/v1/me", null, u.token(), null, null));
    assertEquals(u.email(), me.get("email").asText());
    assertEquals(1, me.get("profileVersion").asInt());
    assertEquals("pending", me.get("profile").get("moderationStatus").asText());
    assertFalse(me.get("onboardingRequired").asBoolean());
    assertEquals(
        404, request("GET", "/v1/profiles/" + u.id(), null, null, null, null).statusCode());
    assertTrue(
        data(request("GET", "/v1/me", null, user().token(), null, null)).get("profile").isNull());
  }

  // 乐观锁缺失/格式/陈旧版本分别拒绝；两个并发编辑只有一个胜出，不丢失未提交的字段。
  @Test
  void optimisticVersionsValidateCreationAndConcurrentUpdates() throws Exception {
    var u = user();
    assertEquals(428, patch(u, Map.of("nickname", "创建"), null).statusCode());
    assertEquals(422, patch(u, Map.of("nickname", "创建"), "0").statusCode());
    for (String invalid : List.of("\"01\"", "W/\"0\"", "\"-1\"", "\"2147483648\""))
      assertEquals(422, patch(u, Map.of("nickname", "创建"), invalid).statusCode());
    assertEquals(422, patch(u, Map.of("bio", "仅简介"), "\"0\"").statusCode());
    assertEquals(412, patch(u, Map.of("nickname", "创建"), "\"1\"").statusCode());
    assertEquals(200, patch(u, Map.of("nickname", "创建", "bio", "原简介"), "\"0\"").statusCode());
    assertEquals(412, patch(u, Map.of("nickname", "重建"), "\"0\"").statusCode());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> patch(u, Map.of("nickname", "甲"), "\"1\"").statusCode());
      var b = pool.submit(() -> patch(u, Map.of("nickname", "乙"), "\"1\"").statusCode());
      var statuses = new ArrayList<>(List.of(a.get(), b.get()));
      Collections.sort(statuses);
      assertEquals(List.of(200, 412), statuses);
    }
    var me = data(request("GET", "/v1/me", null, u.token(), null, null));
    assertEquals(2, me.get("profileVersion").asInt());
    assertEquals("原简介", me.get("profile").get("bio").asText());
  }

  // 已批准版本独立保存；编辑新草稿不替换公开值，公开DTO永远不含私人或关联域信息。
  @Test
  void publicReadsOnlyApprovedSnapshotAndNeverPrivateData() throws Exception {
    var u = user();
    var viewer = user();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name='profiles' AND column_name='moderation_status'",
            Integer.class));
    jdbc.update(
        "INSERT INTO profiles(account_id,nickname,bio,version,moderation_status) VALUES(?,?,?,?,?)",
        u.id(),
        "已审核",
        "公开旧简介",
        4,
        "approved");
    assertEquals(200, patch(u, Map.of("nickname", "待审新名字"), "\"4\"").statusCode());
    var r = request("GET", "/v1/profiles/" + u.id(), null, viewer.token(), null, null);
    assertEquals(200, r.statusCode());
    var profile = data(r);
    assertEquals(7, profile.size());
    assertEquals(u.id().toString(), profile.get("id").asText());
    assertEquals("已审核", profile.get("nickname").asText());
    assertTrue(profile.get("capabilities").get("canMessage").asBoolean());
    for (String secret : List.of("email", "principal", "dating", "events", "moderationStatus"))
      assertFalse(r.body().contains(secret));
    assertEquals(
        "待审新名字",
        data(request("GET", "/v1/me", null, u.token(), null, null))
            .get("profile")
            .get("nickname")
            .asText());
    assertFalse(
        data(request("GET", "/v1/profiles/" + u.id(), null, null, null, null))
            .get("capabilities")
            .get("canMessage")
            .asBoolean());
    assertEquals(
        401, request("GET", "/v1/profiles/" + u.id(), null, "invalid", null, null).statusCode());
    jdbc.update("UPDATE profiles SET visibility='hidden' WHERE account_id=?", u.id());
    assertEquals(
        404, request("GET", "/v1/profiles/" + u.id(), null, null, null, null).statusCode());
  }

  // 设置只修改本人，部分更新保留其他默认值；类型、空对象与身份注入均拒绝。
  @Test
  void settingsUseOwnerAndStrictPartialBooleans() throws Exception {
    var u = user();
    var other = user();
    var response = request("GET", "/v1/me", null, u.token(), null, null);
    assertEquals(200, response.statusCode());
    var me = data(response);
    var defaults = me.get("settings");
    assertTrue(defaults.get("allowStrangerDm").asBoolean());
    assertTrue(defaults.get("pushEnabled").asBoolean());
    assertFalse(defaults.get("lockscreenPreview").asBoolean());
    var r =
        request(
            "PATCH", "/v1/me/settings", Map.of("allowStrangerDm", false), u.token(), null, null);
    assertEquals(200, r.statusCode());
    assertTrue(data(r).get("pushEnabled").asBoolean());
    assertFalse(data(r).get("allowStrangerDm").asBoolean());
    assertTrue(
        data(request("GET", "/v1/me", null, other.token(), null, null))
            .get("settings")
            .get("allowStrangerDm")
            .asBoolean());
    for (Object input :
        List.of(
            Map.of(),
            Map.of("pushEnabled", "true"),
            Map.of("accountId", other.id()),
            Map.of("pushEnabled", 1)))
      assertEquals(
          422, request("PATCH", "/v1/me/settings", input, u.token(), null, null).statusCode());
  }

  // 活跃字典必须存在且无重复；失败整笔回滚，未配置图片展示时任何非空头像安全拒绝。
  @Test
  void dictionariesAndAvatarValidationAreAtomic() throws Exception {
    var u = user();
    UUID interest = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO regions(code,level,name_ja,name_zh) VALUES('13','prefecture','東京','东京')");
    jdbc.update("INSERT INTO interests(id,code,name_zh) VALUES(?,?,?)", interest, "walk", "散步");
    assertEquals(
        200,
        patch(
                u,
                Map.of("nickname", "字典", "regionCode", "13", "interestIds", List.of(interest)),
                "\"0\"")
            .statusCode());
    for (Object input :
        List.of(
            Map.of("nickname", "错误", "regionCode", "missing"),
            Map.of("interestIds", List.of(interest, interest)),
            Map.of("interestIds", List.of(UUID.randomUUID())),
            Map.of("nickname", " "),
            Map.of("email", "secret"),
            Map.of("bio", false))) assertEquals(422, patch(u, input, "\"1\"").statusCode());
    assertEquals(409, patch(u, Map.of("avatarMediaId", UUID.randomUUID()), "\"1\"").statusCode());
    var me = data(request("GET", "/v1/me", null, u.token(), null, null));
    assertEquals(1, me.get("profileVersion").asInt());
    assertEquals("字典", me.get("profile").get("nickname").asText());
    assertEquals("13", me.get("profile").get("region").get("code").asText());
    assertEquals("散步", me.get("profile").get("interests").get(0).get("nameZh").asText());
    var remove = new HashMap<String, Object>();
    remove.put("regionCode", null);
    remove.put("avatarMediaId", null);
    remove.put("interestIds", List.of());
    assertEquals(200, patch(u, remove, "\"1\"").statusCode());
  }

  // UAX29可见字符边界覆盖组合音标、ZWJ家庭、国旗及肤色；第31个可见字符才拒绝。
  @Test
  void unicodeLimitsCountGraphemesRatherThanCodepoints() throws Exception {
    var u = user();
    String cluster = "👩🏽‍💻";
    String nickname = cluster.repeat(30);
    assertEquals(
        200,
        patch(u, Map.of("nickname", nickname, "bio", "e\u0301".repeat(500)), "\"0\"").statusCode());
    assertEquals(422, patch(u, Map.of("nickname", nickname + "🇯🇵"), "\"1\"").statusCode());
    assertEquals(422, patch(u, Map.of("bio", "👨‍👩‍👧‍👦".repeat(501)), "\"1\"").statusCode());
    assertEquals(
        200,
        patch(u, Map.of("nickname", "🇯🇵".repeat(30), "bio", "👨‍👩‍👧‍👦".repeat(500)), "\"1\"")
            .statusCode());
  }

  // 普通私聊能力只受本人身份、陌生联系设置和双向拉黑控制，不读取恋爱或年龄资格。
  @Test
  void publicMessagingCapabilitiesRespectSettingsAndBothBlockDirections() throws Exception {
    var owner = user();
    var viewer = user();
    jdbc.update(
        "INSERT INTO profiles(account_id,nickname,moderation_status) VALUES(?,?,'approved')",
        owner.id(),
        "公开账号");
    String path = "/v1/profiles/" + owner.id();
    assertTrue(
        data(request("GET", path, null, viewer.token(), null, null))
            .get("capabilities")
            .get("canMessage")
            .asBoolean());
    assertFalse(
        data(request("GET", path, null, owner.token(), null, null))
            .get("capabilities")
            .get("canMessage")
            .asBoolean());
    request(
        "PATCH", "/v1/me/settings", Map.of("allowStrangerDm", false), owner.token(), null, null);
    assertFalse(
        data(request("GET", path, null, viewer.token(), null, null))
            .get("capabilities")
            .get("canMessage")
            .asBoolean());
    UUID low =
        owner.id().toString().compareTo(viewer.id().toString()) < 0 ? owner.id() : viewer.id();
    UUID high = low.equals(owner.id()) ? viewer.id() : owner.id();
    jdbc.update(
        "INSERT INTO direct_contact_states(account_low_id,account_high_id,low_sent_count,high_sent_count) VALUES(?,?,1,1)",
        low,
        high);
    assertTrue(
        data(request("GET", path, null, viewer.token(), null, null))
            .get("capabilities")
            .get("canMessage")
            .asBoolean());
    for (var pair : List.of(List.of(owner.id(), viewer.id()), List.of(viewer.id(), owner.id()))) {
      jdbc.update("DELETE FROM blocks");
      jdbc.update(
          "INSERT INTO blocks(blocker_id,blocked_id) VALUES(?,?)", pair.get(0), pair.get(1));
      assertFalse(
          data(request("GET", path, null, viewer.token(), null, null))
              .get("capabilities")
              .get("canMessage")
              .asBoolean());
    }
  }

  // 没有受控图片展示能力时，包括他人、错误用途、未就绪或批准资源都不能产生头像绑定。
  @Test
  void allAvatarResourcesFailSafelyWithoutDeliveryBinding() throws Exception {
    var u = user();
    var another = user();
    assertEquals(200, patch(u, Map.of("nickname", "头像"), "\"0\"").statusCode());
    for (var input :
        List.of(
            List.of(u.id(), "profile", "approved"),
            List.of(another.id(), "profile", "approved"),
            List.of(u.id(), "dating", "approved"),
            List.of(u.id(), "board", "uploaded"),
            List.of(u.id(), "profile", "processing"))) {
      UUID asset = UUID.randomUUID();
      UUID principal =
          jdbc.queryForObject(
              "SELECT id FROM principals WHERE account_id=? AND kind='account'",
              UUID.class,
              input.get(0));
      jdbc.update(
          "INSERT INTO media_assets(id,owner_principal_id,purpose,object_key,status,mime,size_bytes,upload_expires_at) VALUES(?,?,?,?,?,'image/png',100,CURRENT_TIMESTAMP+INTERVAL '1 hour')",
          asset,
          principal,
          input.get(1),
          asset.toString(),
          input.get(2));
      var response = patch(u, Map.of("avatarMediaId", asset), "\"1\"");
      assertEquals(409, response.statusCode());
      assertEquals("MEDIA_NOT_READY", json.readTree(response.body()).get("code").asText());
      assertFalse(response.body().contains(asset.toString()));
    }
    assertEquals(
        1,
        data(request("GET", "/v1/me", null, u.token(), null, null)).get("profileVersion").asInt());
  }
}

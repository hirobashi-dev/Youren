package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.*;
import org.apache.ibatis.annotations.Param;

// 私有快照与公开行均为内部模型；HTTP服务明确映射允许字段而非序列化内部行。
public interface ProfileMapper {
  record Snapshot(String fields, int version, String moderationStatus) {}

  record Region(String code, String parentCode, String level, String nameJa, String nameZh) {}

  record Interest(UUID id, String code, String nameZh) {}

  record Age(String status, OffsetDateTime declaredAt, OffsetDateTime withdrawnAt) {}

  Snapshot draft(@Param("id") UUID id);

  Snapshot published(@Param("id") UUID id);

  boolean isPublic(@Param("id") UUID id);

  int saveDraft(
      @Param("id") UUID id,
      @Param("fields") String fields,
      @Param("version") int version,
      @Param("now") OffsetDateTime now);

  Region region(@Param("code") String code);

  // 地区字典只返回启用项；无父级时由SQL限定为都道府县，避免一次加载全部市区町村。
  List<Region> regions(@Param("parentCode") String parentCode);

  Interest interest(@Param("id") UUID id);

  // 兴趣字典按稳定顺序返回启用项，客户端无需持有数据库内部排序规则。
  List<Interest> interests();

  boolean canMessage(@Param("viewer") UUID viewer, @Param("target") UUID target);

  int settings(
      @Param("id") UUID id,
      @Param("dm") Boolean dm,
      @Param("push") Boolean push,
      @Param("preview") Boolean preview,
      @Param("now") OffsetDateTime now);

  Age age(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}

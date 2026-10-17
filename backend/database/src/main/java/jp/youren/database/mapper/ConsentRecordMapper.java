package jp.youren.database.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

// 接受记录由认证服务与新账号同事务写入，已有账号重复注册不伪造新的同意。
public interface ConsentRecordMapper {
  int insert(
      @Param("id") UUID id,
      @Param("accountId") UUID accountId,
      @Param("terms") String terms,
      @Param("privacy") String privacy,
      @Param("now") OffsetDateTime now);
}

package jp.youren.database.mapper;

// 健康检查只读，不访问用户内容。
public interface HealthMapper {
  int ping();
}

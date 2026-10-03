# 共享服务端运行时

配置来自环境，启动前校验数据库与Redis URL、端口和日志级别。日志仅允许event、requestId、status、durationMs和service等工程字段，不记录请求/异常正文和秘密；不向手机端导出。JSON配置固定Jest/ts-jest转换器及严格TypeScript编译。

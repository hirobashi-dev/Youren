# 本机隔离依赖

根目录复制`infra/.env.example`为`infra/.env`，设置仅本机使用的密码。执行`docker compose --env-file infra/.env -f infra/compose.yaml -p youren-stage1 up -d --wait`。开发DB5441/Redis6381，测试DB5442/Redis6382；用户和数据库分别为youren_dev/youren_test，只绑定loopback，独立命名卷。

应用环境使用DATABASE_URL、REDIS_URL、PORT、LOG_LEVEL；测试必须显式使用youren_test数据库。根`.env`放开发URL，测试URL通过当前进程设置，不从外部DATABASE_URL推断；不打印连接串。关闭只操作`youren-stage1`项目，不删除其他容器或卷。PostgreSQL16.6沿用既有验证版本，Redis7.4.2为本机兼容基线；生产版本/安全维护在发布前另行评审。

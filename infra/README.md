# 本机隔离依赖

根目录复制`infra/.env.example`为`infra/.env`，设置仅本机使用的密码。执行`docker compose --env-file infra/.env -f infra/compose.yaml -p youren-stage1 up -d --wait`。开发DB5441/Redis6381，测试DB5442/Redis6382；用户和数据库分别为youren_dev/youren_test，只绑定loopback，独立命名卷。

Java应用环境使用SPRING_DATASOURCE_URL（JDBC URL）、SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD、SPRING_DATA_REDIS_URL、SERVER_PORT、LOG_LEVEL。Java不读取Node .env，密码不放JDBC URL或命令行。测试须显式使用youren_test；关闭只操作youren-stage1，不删除其他容器或卷。测试库已审计接续到Flyway baseline1，不能再对该库运行Prisma migrate；开发库尚未切换。API/worker不自动改表，迁移按backend/database/README受控执行。PostgreSQL16.6/Redis7.4.2为本机基线，发布前另评生产维护版本。

# Java 后端

Java 21、Spring Boot 3.5.16、MyBatis Starter 3.0.5、Maven 3.9.9。
`shared` 提供配置验证与日志白名单；`database` 已实现 MyBatis/Flyway 基础，`api`、`worker` 随后续任务实现。
四模块使用同一 BOM，Surefire 发现 `*Test`，Failsafe 在 `verify` 验收 `*IT`。
Spotless 在 `verify` 检查 Java 格式。数据库有11项临时PostgreSQL集成测试，HTTP尚待实现。

## Windows 本地构建

```powershell
.\backend\build.ps1 clean verify
.\backend\build.ps1 -pl shared -am test
.\backend\build.ps1 spotless:apply
```

脚本默认使用仓库同级 `.youren-tools/jdk-21.0.12.1+1`，不改系统 `JAVA_HOME`。
其他电脑先安装 JDK 21，用 `YOUREN_JDK_HOME` 或 `-JdkHome` 指定路径。
直接使用 Wrapper 时，需在当前终端设置 `JAVA_HOME`；Linux/macOS 使用 `./backend/mvnw -f backend/pom.xml verify`。
Maven Wrapper 固定 3.9.9，并校验下载的 SHA-256；首次构建需联网下载依赖。

## 配置与安全

必要字段：`SPRING_DATASOURCE_URL`（`jdbc:postgresql://127.0.0.1:5442/youren_test`）、
`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`SPRING_DATA_REDIS_URL`。
Java 不自动读取 Node `.env`。数据库用户名/密码使用独立字段，不放进 JDBC URL。
可选字段：`SERVER_PORT` 默认 3000、`READINESS_TIMEOUT_MS` 默认 1500（1–10000）、
`LOG_LEVEL` 默认 `info`，支持 `info/warn/error`。
不要把配置对象、原始异常或用户正文直接写入日志；使用 `SafeLog.redact` 元数据白名单。

## 测试报告

单元报告：各模块 `target/surefire-reports/`；集成报告：`target/failsafe-reports/`。
报告须检查实际执行数量；空模块当前没有业务测试，不能当作业务验收通过。
集成测试加入后必须具备隔离依赖环境，不通过跳过测试取得验收结果。

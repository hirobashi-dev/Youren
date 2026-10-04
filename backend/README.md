# Java 后端

Java 21、Spring Boot 3.5.16、MyBatis Starter 3.0.5、Maven 3.9.9。
`shared` 提供配置验证与日志白名单；`database` 已实现 MyBatis/Flyway 基础，`api` 已实现健康、游客凭证和邮箱认证，`worker` 已通过生命周期及真实JAR信号测试。
四模块使用同一 BOM，Surefire 发现 `*Test`，Failsafe 在 `verify` 验收 `*IT`。
Spotless 在 `verify` 检查 Java 格式。数据库和HTTP使用临时PostgreSQL/Redis进行集成验证；实际测试数量及结果以各模块报告为准。

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

## 邮箱认证

API增加邮箱挑战、注册、登录、公开策略读取、刷新轮换及退出；数据库只保存HMAC/SHA256摘要。
`GET /v1/auth/policy` 返回当前 `termsVersion` / `privacyVersion`，客户端先读取并让用户接受，不能硬编码版本。
`AccountMeQuery` 是任务4接入本人资料的共享查询入口；本任务的新账号返回空资料、`not_declared` 年龄声明及初始化设置。
认证事务按规范邮箱、账号、挑战顺序加锁；错误尝试提交后才返回401。刷新按账号、会话锁顺序处理，旧令牌重放提交整个族的撤销后返回401。
邮件发送不持有数据库锁，失败挑战不可消费；Redis故障返回503，不放宽配额。IP取连接远端地址，默认不信任转发头。

| 环境字段 | 默认值/要求 |
|---|---|
| `YOUREN_AUTH_CHALLENGE_SECRET` | 必须为至少32个随机字节的无填充Base64URL；无默认秘密 |
| `YOUREN_AUTH_CODE_TTL` / `YOUREN_AUTH_MAX_ATTEMPTS` | 600秒 / 5次错误 |
| `YOUREN_AUTH_COOLDOWN` | 60秒 |
| `YOUREN_AUTH_EMAIL_HOURLY` / `YOUREN_AUTH_EMAIL_DAILY` | 5 / 20 |
| `YOUREN_AUTH_IP_HOURLY` / `YOUREN_AUTH_IP_DAILY` | 30 / 100 |
| `YOUREN_AUTH_ACCESS_TTL` / `YOUREN_AUTH_REFRESH_TTL` | 900 / 2592000秒；访问有效期不得大于刷新有效期 |
| `YOUREN_TERMS_VERSION` / `YOUREN_PRIVACY_VERSION` | 开发默认2026-10-04，非法律政策发布版本 |
| `YOUREN_AUTH_TEST_INBOX_KEY` | 仅隔离测试配置，独立高熵随机秘密，不能和HMAC密钥共用 |

缺少HMAC或邮件端口时发送返回503，但健康接口仍可启动。生产 `EmailDelivery` 供应商暂缓，默认实现安全拒绝发送。
正式启用前须配置供应商、发布政策正文及版本、注入独立密钥，并在部署检查中确认认证功能可用。
正常认证响应及日志从不含验证码；访问、刷新和测试收件箱响应均禁止缓存。

### Android 隔离测试收件箱

仅显式 `SPRING_PROFILES_ACTIVE=auth-test` 启用本机调试入口；与 `prod` / `production` 任一组合直接拒绝启动。
缺少或低熵测试密钥也拒绝启动。收件箱只留内存、最多1000条、按挑战实际到期时间清理，重启后全部丢弃。
此路径不是生产邮件渠道验收，也不是普通API合同的一部分。

在独立本地终端生成两个不同秘密，仅存在当前进程环境，不打印或保存到Git：

```powershell
function New-LocalAuthSecret {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
$env:YOUREN_AUTH_CHALLENGE_SECRET = New-LocalAuthSecret
$env:YOUREN_AUTH_TEST_INBOX_KEY = New-LocalAuthSecret
$env:SPRING_PROFILES_ACTIVE = 'auth-test'
# 数据库与Redis按上方配置；先运行独立迁移再启动构建好的API JAR。
& "$env:YOUREN_JDK_HOME/bin/java.exe" -jar backend/api/target/youren-api-0.1.0-SNAPSHOT.jar
```

手机仍只调用正常 `/v1/auth/email-code`。取得响应 `challengeId` 后，测试人员在API主机的本机终端读取：

```powershell
$challengeId = '从正常响应取得的UUID'
$inbox = Invoke-RestMethod -Uri "http://127.0.0.1:3000/__test/mail/$challengeId" `
  -Headers @{ 'X-Test-Inbox-Key' = $env:YOUREN_AUTH_TEST_INBOX_KEY }
# 将$inbox.code用于手机输入；不要把结果写入日志或终端历史文件。
```

调试入口只接受连接地址127.0.0.1或IPv6本机地址及请求头密钥；Android的10.0.2.2直连读取会被拒绝。
手机API可用 `adb reverse tcp:3000 tcp:3000` 与本机3000映射；验证码由上述主机读取步骤取得。
任务4通过 `GuestClaimHandler.claim(accountId, guestToken)` 接入实际接续；当前默认验证游客凭证后返回失败，认证会话已提交且不丢失，不能据此宣称已接续。

### 本任务验证

```powershell
.\backend\build.ps1 -pl api -am verify
```

MockMvc验证严格字段、畸形JSON及安全错误；临时PostgreSQL/Redis与真实HTTP验证单次消费、并发注册、条款事务、发送故障、各窗口配额、到期、账号状态、刷新重放及退出。
无需实际邮件供应商。独立测试收件箱的配置、权限、容量与到期另有测试；Android实际操作由阶段最终验收完成。

# Java 后端

Java 21、Spring Boot 3.5.16、MyBatis Starter 3.0.5、Maven 3.9.9。
`shared` 提供配置验证与日志白名单；`database` 已实现 MyBatis/Flyway 基础，`api` 已实现健康、游客凭证/接续、邮箱认证与普通资料，`worker` 已通过生命周期及真实JAR信号测试。
四模块使用同一 BOM，Surefire 发现 `*Test`，Failsafe 在 `verify` 验收 `*IT`。
Spotless 在 `verify` 检查 Java 格式。数据库和HTTP使用临时PostgreSQL/Redis进行集成验证；实际测试数量及结果以各模块报告为准。

## Windows 本地构建

本机数据库初始化、JDK/Android环境变量、开发API与模拟器联调手顺见[本机开发环境手册](../docs/development/local-environment.md)。

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
`AccountMeQuery` 返回本人资料草稿、资料版本及设置；新账号返回空资料、`not_declared` 年龄声明及初始化设置。
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
`GuestClaimHandler.claim(accountId, guestToken)` 已接入真实接续事务；认证会话先提交，接续领域失败只返回failed，不丢失成功登录的会话。接续权限与幂等规则见下方资料说明。

### 本任务验证

```powershell
.\backend\build.ps1 -pl api -am verify
```

MockMvc验证严格字段、畸形JSON及安全错误；临时PostgreSQL/Redis与真实HTTP验证单次消费、并发注册、条款事务、发送故障、各窗口配额、到期、账号状态、刷新重放及退出。
无需实际邮件供应商。独立测试收件箱的配置、权限、容量与到期另有测试；Android实际操作由阶段最终验收完成。
# 游客接续与普通资料

`POST /v1/me/guest-claims`只接受本人UserBearer、UUID `Idempotency-Key`和`guestToken`。
账号→主体→凭证按固定顺序锁定，绑定、撤销全部同主体凭证和回执写入同一事务。
同账号仍有效的已接续凭证可幂等重试；其他账号、过期/未接续已撤销秘密统一403，
同键不同秘密409。回执不保存秘密或响应，只保存摘要和内部资源标识；重放重新检查
归属并统计未删除、未到期、非deleted/expired状态的本人原主体帖子和回复。
历史作者外键、kind及display_mode保持不变。登录/注册在认证提交后调用同一真实服务，
预期领域失败只令claimStatus=failed，登录会话仍成功，游客可独立重试。

`GET /v1/me`与AuthSession.account共用精确本人快照，资料版本以最新草稿为准。
`PATCH /v1/me/profile`首次要求`If-Match: "0"`和非空白昵称；之后正版本，缺失428、
格式422、不匹配412；并发只能一个成功。部分更新保留未提供字段，字典必须活跃存在
且兴趣不可重复。`PATCH /v1/me/settings`只接受非空部分布尔更新，不接受账号ID；
默认allowStrangerDm=true、pushEnabled=true、lockscreenPreview=false。

V3独立`profile_drafts`只存本人最新版本，编辑不覆盖`profiles`已批准公开快照。
OwnerProfile有moderationStatus=pending/approved/rejected；PublicProfile没有审核状态或
email、principal、恋爱及活动字段，id始终为账号ID。初始草稿不公开；公开主页只读取
active账号的public/approved快照；已有批准版本在新草稿编辑期间保持旧值。历史资料
没有审核证据，V3默认pending，安全拒绝公开。本人基本资料存在即可完成引导，与
审核发布分开。V1/V2没有修改；本阶段无审核、发布或清理worker。

不用强制expires_at的moderation_revisions存普通资料草稿，因为其保留期尚未批准；
latest-draft存储不虚构一年或短期清理规则。图片展示/安全绑定尚未实现，所有非空
avatarMediaId都返回409 MEDIA_NOT_READY，包括未知、他人、错误用途和未就绪资源；
null可清除草稿头像。本阶段所有资料DTO的avatar为null，不签发存储URL。

昵称30、简介500**仅为可配置暂定工程默认值，尚未获用户确认**，不同于已批准认证
政策。服务端使用Java21正则`\X`扩展字素计数；测试覆盖组合音标、ZWJ家庭、肤色与
国旗边界，以及配置增大后40/700合法输入。客户端不得把合同建议元数据作为固定上限，
实际服务器配置才是验证权威。

| 环境变量 | 暂定默认 | 作用 |
|---|---:|---|
| YOUREN_PROFILE_NICKNAME_GRAPHEMES | 30 | 昵称可见字素上限，正整数 |
| YOUREN_PROFILE_BIO_GRAPHEMES | 500 | 简介可见字素上限，正整数 |
| YOUREN_GUEST_CLAIM_RECEIPT_SECONDS | 86400 | 回执重试窗口秒数，正整数；过期同键RESULT_EXPIRED |

公开canMessage只由实际账号身份、非本人、双向拉黑及普通陌生私聊设置决定，
关闭陌生联系不撤销已互相发送过消息的联系；不读取年龄/恋爱资格。
能力字段仅指导界面，未来私聊写入仍须重新授权和检查额度。

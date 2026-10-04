# Java API 工程基础

当前实现工程健康接口、游客凭证创建及统一错误格式，尚未开发注册、聊天等业务。

```powershell
.\backend\build.ps1 clean verify
.\backend\run.ps1 -Service api
```

启动前在当前终端设置后端README中的SPRING_DATASOURCE及Redis字段。
默认端口3000；配置缺失或启动失败返回非零，错误事件不含秘密。
Java不会读取Node `.env`。API不自动迁移，先用独立迁移入口准备数据库。

- `GET /health/live`：200及`{"status":"ok"}`，不访问依赖。
- `GET /health/ready`：依赖正常200，失败/超时503及`{"status":"unavailable"}`。
- `POST /v1/guest-sessions`：无需请求体，返回201及`data/requestId`。
  `data`包含`guestToken/principalId/expiresAt`，响应使用`Cache-Control: no-store`。
  原凭证含32随机字节，不记录日志，数据库仅保存SHA256摘要；重复创建不复用主体或秘密。
- `YOUREN_GUEST_CREDENTIAL_TTL`采用ISO-8601时长，默认`P30D`；例如`PT2H`表示2小时。
  到期与撤销立即拒绝，游客鉴权不依赖验证码HMAC密钥；原凭证仅保存在安全客户端存储。
- 同时只有一项在途探测，默认等待1500ms，驱动自身也有连接/命令超时。
- CORS仅允许`http://localhost:5173`及`http://127.0.0.1:5173`的GET。
- 普通错误DTO使用合同中的平铺`code/message/requestId`；ID由服务器新生成，
  不回显原异常、请求参数、客户端追踪值或内部连接信息。

11项单元测试及5项真实HTTP/依赖集成测试；后者创建临时PostgreSQL/Redis，
实际停止并重启本测试Redis，验证200→503→200和故障期间存活200。
游客测试验证真实摘要持久化、30天到期、重复创建、撤销与到期拒绝、失败时事务回滚。
Redis主机端口在本次测试固定为动态选出的空闲本机端口，避免Docker重启更换映射。
JUnit、Failsafe报告分别位于`target/surefire-reports`、`target/failsafe-reports`。
Failsafe明确使用`target/classes`，避免Boot可执行JAR布局影响加载。

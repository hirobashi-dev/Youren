# 第二阶段：身份与普通资料实施计划

> **实施提示：** 开发时逐任务执行并勾选；每个任务先写失败测试，再实现、验证、差分审查并提交。不得将本计划或现有健康检查当作业务功能已完成。

**目标：** 建成游客凭证、邮箱注册与登录、会话安全、游客内容接续、普通个人资料和设置的可测试端到端基础。

**架构：** Java API 负责身份、权限、事务与资料过滤；MyBatis 访问现有 PostgreSQL/Flyway 数据结构，Redis 承担短期验证码和限流。Expo 手机端封装认证 API，并将秘密凭证存于 SecureStore；EmailDelivery 作为后端端口，开发测试使用可断言的替身，生产邮件供应商由上线配置决定。

**技术栈：** Java 21、Spring Boot 3.5、Maven、MyBatis、PostgreSQL、Redis、JUnit/MockMvc/Testcontainers；React Native、Expo、TypeScript、Jest、React Native Testing Library。

**依据：** [阶段总计划](2026-10-03-phased-development-acceptance.md)阶段2、[技术方案](../specs/2026-10-03-social-app-technical-design.md)第4节、[数据库与API详细设计](../specs/2026-10-03-social-app-database-api-design.md)第7.1节、[OpenAPI合同](../../../packages/contracts/openapi.json)。

## 全局约束

- 只实现普通账号与普通资料，不提前实现恋爱资料、年龄声明、留言板写入、群聊、活动或管理后台。
- 现有 `V1__initial.sql` 已冻结；复用 `accounts`、`principals`、`guest_credentials`、`sessions`、`email_challenges`、`profiles`、`account_settings`。若验证发现结构无法满足合同，只能新增带中文注释的 Flyway V2，禁止改写 V1。
- 所有代码与测试就近写简明中文注释，说明业务规则、权限、事务、边界及场景期望；不得记录验证码、令牌、邮箱正文或认证头。
- 注册成功必须先有邮箱验证及服务端条款/隐私版本接受记录；资料公开响应不得包含邮箱、游客主体、恋爱字段或活动记录。
- Token 必须高熵、只存摘要、刷新单次轮换；重放撤销同一会话族。游客凭证仅证明其持有者，接续失败不得影响登录或关联他人内容。
- iOS 原生构建按用户此前决定延期；阶段2照常完成契约、单元测试、API集成及Android可运行流程，iOS不记为通过。

## 文件地图

后端精确新增文件：backend/api/src/main/java/jp/youren/api/auth/{AuthController,AuthService,EmailChallengeService,TokenService,AuthDtos}.java；email/EmailDelivery.java；guest/{GuestSessionController,GuestSessionService,GuestClaimController,GuestClaimService}.java；profile/{ProfileController,ProfileService,ProfileDtos}.java；security/{AccessTokenFilter,CurrentPrincipal}.java。数据库新增 backend/database/src/main/java/jp/youren/database/mapper/{PrincipalMapper,GuestCredentialMapper,SessionMapper,EmailChallengeMapper,ProfileMapper,AccountSettingsMapper}.java 及 backend/database/src/main/resources/mapper/ 同名 XML；AccountMapper 已有，直接复用。后端测试分别新增于 backend/api/src/test/java/jp/youren/api/{auth,guest,profile,security}/ 和 backend/database/src/test/java/jp/youren/database/。仅当 V1 结构不满足合同才创建 backend/database/src/main/resources/db/migration/V2__stage2_identity_support.sql 与 MigrationIT.java，绝不改 V1。

手机端精确新增 apps/mobile/src/api/{auth,profile}.ts、src/auth/sessionStore.ts、src/screens/{AuthScreen,ProfileEditScreen,SettingsScreen,PublicProfileScreen}.tsx；新增 tests/{auth,profile}.test.tsx 与 sessionStore.test.ts。先核对 Expo SDK 对应 SecureStore 版本，再更新 package 与 lockfile、中文说明和配置；不得用 AsyncStorage 保存令牌。

合同源维护于 `packages/contracts/src/operations.cjs`、`schemas.cjs`；改动后重建 `openapi.json` 并运行合同测试。端到端运行说明更新 `backend/api/README.md`、`apps/mobile/README.md` 及 `docs/development/stage-2-acceptance.md`。

## 编码前决策门槛

在改动业务代码前确认以下建议；它们目前是设计建议，不是已批准规则：验证码用于注册和登录、六位数字、有效10分钟、最多错误5次、同邮箱60秒冷却、响应不暴露邮箱是否已注册；访问令牌15分钟、刷新令牌30天（技术方案建议）；邮件服务供应商及密钥注入方式。默认验证码参数可配置。若服务端暂不具备真实发信配置，以 EmailDelivery 测试替身完成本地自动化，但不得宣称真实邮箱验收通过。注册条款版本从服务端配置读取，前端记录用户主动勾选，不得代替服务端校验。

## 任务与验收

### 任务1：锁定合同、参数与错误语义

**文件：** 修改 `packages/contracts/src/operations.cjs`、`schemas.cjs`、对应合同测试与 `openapi.json`；创建 `docs/development/stage-2-acceptance.md`。

- [ ] 先为验证码用途、过期/尝试/冷却、匿名响应、注册条款版本、登录结果、游客接续失败状态、资料字段和设置字段补合同断言。
- [ ] 核实 POST `/guest-sessions`、`/auth/email-code`、`/auth/register`、`/auth/login`、`/auth/refresh`、`/auth/logout`、`/me/guest-claims`、`/me`、PATCH `/me/profile`、PATCH `/me/settings`、GET `/profiles/{accountId}` 全部有请求/响应及权限约束。
- [ ] 运行 `npm test -w @youren/contracts` 与 `npm run check:generated -w @youren/contracts`；预期合同测试通过、生成文件无差异。
- [ ] 记录上述参数决定及邮件供应商状态；接口变更只由源生成 JSON。审查差分后提交 `docs: define stage 2 identity contract`。

### 任务2：持久化与游客凭证

**文件：** 扩充/新增 database Mapper、XML 与行模型；必要时新增 V2 和 `MigrationIT`；新增 `GuestSessionServiceTest`、Mapper 集成测试。

- [ ] 测试游客凭证只保存安全摘要、凭证过期/撤销后拒绝、重复创建不复用秘密；验证主体与凭证关联事务。
- [ ] 实现创建游客 principal/secret、摘要比较与撤销/过期查询；禁止日志回显原凭证。逐条使用参数化 MyBatis XML。
- [ ] 运行 `.\backend\build.ps1 -pl database -am verify`；预期 Flyway、约束、Mapper真实PostgreSQL测试通过，迁移仅新增版本。
- [ ] 审核迁移及中文注释后提交 `feat: persist guest credentials`。

### 任务3：邮箱挑战、注册、登录和令牌

**文件：** 新增 `api/auth/`、`api/email/`、`api/security/` 类；数据库 challenge/session Mapper；`EmailChallengeServiceTest`、`AuthControllerTest`、`AuthFlowIT`。

- [ ] 先测正确/错误用途、过期、超限、冷却、重复消费、账号存在与否响应一致、未验证注册被拒绝、已注册邮箱不覆盖账号、条款版本由服务端要求。
- [ ] 实现 EmailDelivery 端口及测试替身，Redis 限速、challenge 单次消费、账号事务创建、短期 access/rotation refresh、退出撤销；登录先成功签发会话，游客接续作为独立可重试结果。
- [ ] 测刷新轮换与并发重放：旧 refresh 再用必须撤销该会话族；退出后 access/refresh 均不可继续访问。错误响应遵守 OpenAPI 且日志无秘密。
- [ ] 运行 `.\backend\build.ps1 -pl api -am verify`；预期 MockMvc 和 PostgreSQL/Redis 集成通过。
- [ ] 审查事务、中文测试说明和脱敏后提交 `feat: implement email authentication`。

### 任务4：游客接续与普通资料 API

**文件：** 新增 `api/guest/`、`api/profile/`、相关 Mapper；新增 `GuestClaimIT`、`ProfileControllerTest`、`ProfilePrivacyIT`。

- [ ] 测本人接续成功且幂等、他人凭证拒绝、无效凭证仅报告 claim 失败而登录成功；游客 principal 与公开游客身份不泄漏。
- [ ] 测账号可读写本人 `/me`、资料版本冲突拒绝、设置仅改本人；公开主页不含 email、principal、dating、events，未发布资料不公开；普通私聊设置按合同输出。
- [ ] 实现服务端所有权检查和事务绑定；游客原有内容作者显示身份保持不变；公开 DTO 单独映射，严禁直接序列化数据库实体。
- [ ] 运行 `.\backend\build.ps1 -pl api -am verify`；预期越权、隐私和DB集成用例通过。
- [ ] 审查权限与DTO字段后提交 `feat: add guest claims and profiles`。

### 任务5：手机认证与资料流程

**文件：** 更新 mobile `package.json`/lockfile；新增 `apps/mobile/src/api/auth.ts`、`profile.ts`、认证状态/SecureStore 封装和相关 screens/components；新增 `tests/auth.test.tsx`、`profile.test.tsx`、`sessionStore.test.ts`；更新 mobile README。

- [ ] 先测试邮箱格式/验证码提交、注册条款主动勾选、错误与限流信息、游客浏览进入登录、登录后接续失败可重试、资料编辑/加载/冲突、退出清理会话。
- [ ] 实现 API 封装、请求超时与安全错误映射、SecureStore 持久化；启动时刷新会话，失败清空秘密并回游客浏览。普通主页不得呈现恋爱字段。
- [ ] 运行 `npm test -w @youren/mobile -- --runInBand`、`npm run typecheck -w @youren/mobile`、`npm run build:js -w @youren/mobile`；均应通过。
- [ ] 在 Android 模拟器实际操作注册/登录（测试邮件替身或已配置测试邮箱）、资料读取编辑、重新启动恢复、退出及失败重试；记录设备、截图和结果。iOS实机/原生构建列为延期，不通过模拟截图替代。
- [ ] 审查交互与中文注释后提交 `feat: add mobile account flows`。

### 任务6：全阶段验收与记录

**文件：** 完成 `docs/development/stage-2-acceptance.md`、更新 `apps/mobile/README.md` 和 `backend/api/README.md`。

- [ ] 执行 `npm run check`、`npm run check:engineering`、`.\backend\build.ps1 clean verify`、`npm test -w @youren/contracts`、`npm test -w @youren/mobile -- --runInBand`、`npm run typecheck -w @youren/mobile`。
- [ ] 对照清单验证：游客可匿名浏览并获得独立凭证；邮箱注册/登录及退出完整；验证码单次消费及限流；刷新轮换/重放撤销；本人内容接续幂等且不能跨账号认领；登录不被接续失败阻断；资料和设置只读写本人；公开主页无私密字段；Android界面真实运行；iOS验收明确标记延期。
- [ ] 记录每条命令、JDK/Node、数据库/Redis环境、测试数量、Android设备、截图及未完成原因；`git diff --check` 和手工差分复审通过后，以单独文档提交收尾。不得将未配置真实邮件渠道或延期iOS伪报为通过。

## 阶段退出条件

任务1的验证码/邮件决策记录完成、任务2至6测试均通过、Android实际流程通过、记录完整且所有失败已解决，才可把阶段2标为完成。其余未决定的生产邮件供应商、iOS构建与真机验收须明确列为上线前事项；它们不允许被虚构为成功证据。
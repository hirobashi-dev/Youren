# 工程结构与模块组织计划

**目标：** 明确四个应用工程、共享模块及各自目录，作为后续开发的结构依据。

**架构：** 同一个Git仓库管理四个可独立运行的应用：手机端、管理后台、API和worker。手机端一套代码生成iOS与Android应用；Java共享模块不独立运行。

**技术：** 前端TypeScript、React Native/Expo、React/Vite；后端Java/Spring Boot、Maven、MyBatis、Flyway；PostgreSQL、Redis。

**依据：** [技术方案](../specs/2026-10-03-social-app-technical-design.md)、[第1阶段实施计划](2026-10-03-stage-1-engineering-foundation.md)、[数据库/API设计](../specs/2026-10-03-social-app-database-api-design.md)。本文件说明目录与职责；任务进度只在第1阶段实施计划维护，避免重复计划。

## 1. 工程清单

第1阶段主体工程已建立，第2阶段身份与普通资料任务1至6按已约定范围完成（2026-10-10）；Android模拟器的会话恢复和API断线重试已有记录，Android实机与iOS原生验收仍按用户决定延期。第3阶段设计与实施计划已记录，当前尚未开始实施。阶段进度以[阶段总计划](2026-10-03-phased-development-acceptance.md)和各阶段验收文档为准；本文件只说明实际目录和后续结构约定。

| 工程 | 根目录 | 职责 | 当前状态 |
|---|---|---|---|
| 手机端 | apps/mobile/ | 普通用户使用，支持iOS与Android | 已有游客、账号认证、普通资料和设置；Android模拟器证据已记录，原生iOS验收延期 |
| 管理后台 | apps/admin/ | 管理员工作台 | 目前仍是API连接状态基础页；业务审核和举报界面待第3阶段 |
| API服务 | backend/api/ | HTTP接口、权限、业务事务 | 已有健康、身份认证、游客凭证及普通资料 API |
| worker服务 | backend/worker/ | 到期清理、媒体处理和其他异步任务 | 目前仍只有启动、依赖检查和关闭骨架，业务任务未实现 |

backend/shared/、backend/database/是两个Java共享模块，不增加应用工程数量。PostgreSQL与Redis是基础设施服务，不是本仓库应用源码工程。

## 2. 仓库总目录

下列目录树说明当前跟踪的源码/文档和明确标注的规划项；依赖、构建产物、被忽略的本机环境与工具状态不列出。`[规划]`表示尚未实现，业务目录随对应阶段创建，不提前生成空模块。

```text
Youren/
├─ apps/
│  ├─ admin/                  管理后台
│  └─ mobile/                 iOS/Android共用手机工程
├─ app.json                   根目录EAS项目ID参考，需与手机动态配置保持一致
├─ backend/
│  ├─ pom.xml                 Maven聚合：shared/database/api/worker
│  ├─ mvnw、mvnw.cmd          固定Maven Wrapper
│  ├─ .mvn/wrapper/           Wrapper版本和校验配置
│  ├─ build.ps1、run.ps1      独立JDK构建与服务启动
│  ├─ shared/                 公共配置、连接和安全日志
│  ├─ database/               MyBatis和Flyway
│  ├─ api/                    Java API应用
│  └─ worker/                 Java worker应用
├─ packages/
│  ├─ contracts/              OpenAPI合同、生成源和检查
│  └─ database/               原数据库规范源、历史Prisma工具
├─ infra/                     Compose、环境示例和本地依赖说明
├─ tools/                     统一检查、Java报告门槛、Docker及API联调辅助脚本
├─ tests/                     engineering.test.cjs、check.test.cjs、java-reports.test.cjs
├─ docs/
│  ├─ superpowers/specs/      产品/技术规格；含第三阶段留言板与图片审核设计
│  ├─ superpowers/plans/      开发路线、阶段实施计划与目录组织说明；含第三阶段实施计划
│  ├─ development/            验收证据和恢复记录
│  └─ design/                 线框图、视觉稿及相关工具
├─ .github/workflows/ci.yml    前端、Java、浏览器三个任务；修正后的云端验收已通过
├─ AGENTS.md                  贡献与代理工作规范（日文）
├─ .gitignore、.gitattributes  忽略规则；文本LF、Windows cmd保留CRLF
├─ .node-version              固定Node运行时版本
├─ eslint.config.mjs           ESLint配置
├─ .prettierrc.json            Prettier配置
├─ .prettierignore             格式化排除规则
├─ tsconfig.base.json          TypeScript共享配置
├─ package.json               npm工作区，仅前端/合同及历史工具
├─ package-lock.json          根npm依赖锁
└─ README.md                  安装、启动和文档入口
```

旧TypeScript API曾在本地留下被`.gitignore`排除的`apps/api/dist/`编译产物；已按用户确认清理该目录。它不属于当前Git源码结构或活动工程，当前API实现位于`backend/api/`。

## 3. 手机端目录（第1、2阶段已实现，后续业务模块按需增加）

以下列出当前源码和主要测试；不包括依赖、构建产物及被忽略的本机 IDE 文件。

    apps/mobile/
    ├─ App.tsx、index.ts                  应用入口
    ├─ app.config.ts、eas.json            动态 Expo 与开发构建配置
    ├─ package.json、tsconfig.json、jest.config.cjs
    ├─ src/
    │  ├─ api/                            API 客户端、认证、资料和健康请求
    │  ├─ auth/                           SecureStore 和会话状态
    │  ├─ components/Form.tsx             共享表单组件
    │  ├─ screens/                        基础页、登录注册、账号导航、资料和设置
    │  └─ theme.ts                        主题色
    ├─ tests/                             认证、会话、资料、API 和基础页面测试
    ├─ .env.example                       公开 API 地址示例
    └─ README.md                          本机运行、设备地址与构建验收

认证及资料已放在 src/auth、src/api 和 src/screens；旧提议的 src/features/auth、src/features/profile、src/navigation 目前并未创建，后续按阶段计划增量组织，不为了目录完整而创建空目录。第3阶段具体文件路径以[第三阶段实施计划](2026-10-12-stage-3-content-board.md)为准。

根目录 app.json 只保留 EAS 项目 ID 参考，须与 apps/mobile/app.config.ts 中的 extra.eas.projectId 一致。android/、ios/由 Expo 原生生成流程产生并忽略；Android开发构建/模拟器有验收记录，iOS原生验收延期。手机端不包含服务器密码或数据库访问代码。

## 4. 管理后台目录

```text
apps/admin/
├─ index.html
├─ package.json、tsconfig.json、vite.config.ts
├─ playwright.config.ts                  模拟浏览器检查
├─ playwright.integration.config.ts      真实Java API检查
├─ src/
│  ├─ main.tsx、App.tsx、styles.css       入口和连接状态页
│  ├─ App.test.tsx、test-setup.ts         组件测试及测试环境
│  ├─ api/health.ts、health.test.ts       健康请求与客户端测试
│  ├─ components/                        [规划] 后台复用组件
│  ├─ routes/                            [规划] 管理页面路由
│  └─ features/                          [规划] 管理业务
├─ tests/
│  ├─ smoke.spec.ts                      模拟状态与重试
│  └─ java-api.spec.ts                   真实依赖故障与恢复
└─ README.md
```

后续业务模块包括管理身份`auth/`、内容审核`moderation/`、举报`reports/`、用户管理`users/`、运营配置`policies/`及审计`audit/`。后台仅调用授权API，不直连数据库；测试文件优先与对应组件/客户端就近放置。

## 5. API服务目录

API 已有健康、邮箱认证、游客凭证/接续和普通资料实现。业务代码采用按职责分开的顶层 package，而不是尚未建立的 features/ 目录。

    backend/api/
    ├─ pom.xml、README.md
    └─ src/
       ├─ main/java/jp/youren/api/
       │  ├─ ApiApplication.java
       │  ├─ auth/                         邮箱挑战、登录、会话与账号查询
       │  ├─ email/                        邮件发送端口及隔离测试收件箱
       │  ├─ guest/                        游客凭证及游客内容接续
       │  ├─ health/                       健康和就绪检查
       │  ├─ http/                         安全 HTTP 错误处理
       │  ├─ profile/                      普通资料服务与 API
       │  └─ security/                     令牌、请求身份与认证过滤器
       ├─ main/resources/                  application.yml、日志配置
       └─ test/                            健康、认证、游客及资料测试

第3阶段计划在同一职责分组下增量增加 board、media、moderation、reports、admin 等 package，具体边界见阶段计划。控制器处理HTTP，服务层协调权限与事务，DTO维护公开合同；Mapper和SQL放database模块。API不直接依赖worker启动代码。

## 6. worker服务目录

```text
backend/worker/
├─ pom.xml、README.md
└─ src/
   ├─ main/
   │  ├─ java/jp/youren/worker/
   │  │  ├─ WorkerApplication.java
   │  │  ├─ WorkerLifecycle.java
   │  │  ├─ jobs/                        [规划] 清理、审核、推送任务
   │  │  └─ dispatch/                    [规划] 任务领取、重试和幂等
   │  └─ resources/application.yml、logback-spring.xml
   └─ test/
      ├─ java/jp/youren/worker/
      │  ├─ WorkerLifecycleTest.java
      │  └─ WorkerDependenciesIT.java
      └─ resources/logback-test.xml
```

worker独立运行，不依赖API控制器。业务任务随对应阶段加入，领取、重试及幂等规则必须有测试；不得将周期任务直接堆进启动方法。

## 7. Java共享模块

    backend/shared/
    ├─ pom.xml
    └─ src/main/java/jp/youren/shared/
       ├─ config/                         运行配置校验与依赖连接工厂
       └─ logging/SafeLog.java            白名单安全日志

    backend/database/
    ├─ pom.xml、README.md
    ├─ tools/sync-initial.cjs             初始 SQL 来源一致性检查
    └─ src/
       ├─ main/java/jp/youren/database/   配置、迁移命令、Mapper 与类型处理
       ├─ main/resources/mapper/          当前账号、同意、认证、游客和资料 Mapper XML
       ├─ main/resources/db/migration/    V1 初始迁移、V2 身份、V3 资料草稿
       └─ test/                           迁移、读写、事务和约束测试

第3阶段开始前须核对 V1-V3 与现有 Mapper；只在现有结构确有缺口时新增后续迁移，不能修改已使用的旧迁移或重复建表。

### 模块依赖约定

    手机端 ──HTTP──┐
                  ├── API ──→ database ──→ shared
    管理后台 ─HTTP─┘      └───────────────→ shared
                      worker ─→ database
                             └→ shared

HTTP是运行时通信，箭头表示Java代码依赖。api与worker不能互相导入代码或依赖对方启动；database依赖shared，shared不反向依赖database或应用。API业务服务协调权限与事务；Mapper和迁移留在database。前端只调用授权API，合同以packages/contracts/openapi.json为准，不以数据库行模型替代DTO。

## 8. API到worker的任务流（后续业务实现）

当前worker仅具备启动、依赖检查和关闭骨架；以下是已有数据库/API设计的落地约定，并非已实现任务消费。

```text
用户请求 → API业务服务 → 同一事务：业务数据 + outbox任务 → 提交响应
                                          ↓
worker短事务领取任务并设置租约 → 事务外执行审核/推送等外部调用
                                          ↓
                        保存业务结果及任务状态 / 安排失败重试
                                          ↓
                         手机端或后台通过API读取最新业务结果
```

- PostgreSQL的`outbox`是持久任务来源；Redis不作为任务是否成功的唯一凭据。删除流程还使用`deletion_jobs`与`deletion_ledger`，遵循数据库/API设计规定的清理顺序。
- API任务生产逻辑放对应业务package的service层；worker处理器放`jobs/`，领取、租约、重试和幂等协调放`dispatch/`。任务Mapper/XML在database模块，后续按业务归组，接口和XML保持对应。
- 业务写入和outbox入队使用同一数据库事务；外部调用在领取事务提交后执行，不长时间持锁。API响应表示已提交的业务状态，不能将“已入队”描述成“审核或推送已成功”。
- 沿用`pending → processing → succeeded/dead`状态；通过`available_at`安排执行，`lease_until`支持崩溃后重新领取，`attempts`记录尝试次数。具体租约时长、重试间隔和上限在对应业务实施前固定并测试。
- 允许任务重复执行，不承诺恰好一次；用`dedupe_key`防止重复入队，以eventId及业务唯一约束实现消费幂等。提供方支持幂等键时传递同一键；不支持时记录外部结果不确定的处理策略，避免无条件重复发送。
- 完成或失败回写须校验当前领取资格，避免过期执行者覆盖新结果。不可重试错误或达到上限进入`dead`，管理后台查询及受控重试通过API执行，不直接修改任务表。
- payload只保留完成任务所需的对象ID、版本等元信息；不存令牌、验证码或长期正文副本。执行前重新检查权限、版本和到期状态，清理或推送任务不得使过期内容重新可见。
- 对应测试覆盖事务回滚不入队、重复执行、租约过期恢复、旧执行者回写拒绝、失败重试及到期/注销后不再推送。

## 9. 配置与生成文件约定

### 配置位置和用途

| 工程/环境 | 配置位置与字段 | 加载约定 |
|---|---|---|
| 本地依赖 | 已有`infra/.env.example`，实际`infra/.env` | Compose通过`--env-file`显式加载；只使用开发/测试隔离服务 |
| Java API/worker | 已有各模块`src/main/resources/application.yml`；进程环境变量 | 必需`SPRING_DATASOURCE_URL`、独立用户名/密码、`SPRING_DATA_REDIS_URL`；Java不自动读取`.env` |
| Java可选配置 | `SERVER_PORT`、`READINESS_TIMEOUT_MS`、`LOG_LEVEL` | 当前默认3000、1500ms、info；字段边界以shared验证和后端README为准 |
| 管理后台 | 规划`apps/admin/.env.example`；Vite实际环境文件 | `VITE_API_BASE_URL`是公开地址；现有客户端缺省为`http://127.0.0.1:3000` |
| 图片存储 | 第3阶段规划`YOUREN_MEDIA_ROOT`本机私有目录 | 由API/worker配置访问；不映射为静态公开目录，不将本机图片纳入Git；S3适配留待后续 |
| 手机端 | 已有`apps/mobile/.env.example`和`app.config.ts` | `EXPO_PUBLIC_API_BASE_URL`是公开地址；模拟器/真机地址分别记录，不能照搬本机localhost |
| 云构建/CI | 平台环境变量和秘密管理 | 仅注入该任务需要的配置，不提交密钥文件或账号凭据 |

环境示例和README可提交，实际`.env`及秘密不得提交；`VITE_*`、`EXPO_PUBLIC_*`会进入客户端产物，禁止放密码、签名密钥或管理员凭据。新增Java配置示例放`backend/.env.example`（规划），仅作为变量说明，不能暗示Java会自动加载。

开发/测试配置必须分别指向隔离数据库和Redis；测试配置由测试代码或明确编排传入，拒绝无意回退到开发/生产目标。数据库迁移为独立受控操作，不由API/worker启动自动执行。完整配置规则见[后端说明](../../../backend/README.md)和[基础设施说明](../../../infra/README.md)。

### 测试配置和产物

- Java测试配置就近放`src/test/resources/`；前端测试配置及模拟环境放对应应用，不把测试凭证写入生产入口。
- 提交源码、根`package-lock.json`、Maven Wrapper及校验配置、Flyway版本迁移和OpenAPI规范。生成合同由生成源维护，重新生成后检查一致性。
- 忽略`node_modules/`、`target/`、`dist/`、`.expo/`、覆盖率/测试报告和截图等生成物；截图记录在忽略的`docs/development/artifacts/`，验收文档记载复现步骤。
- 当前`.gitignore`已忽略`apps/mobile/android/`、`apps/mobile/ios/`，默认沿用Expo生成策略。若以后需要持久维护原生改动，先明确生成/维护方式，再同步忽略规则、构建说明及干净检出验证。
- 新增API客户端生成产物的路径、提交策略和生成命令须随引入步骤明确；目前未建立自动生成客户端，不把规划描述为已可用。

## 10. 落地顺序与验证

1. 第1阶段工程基础已交付；iOS原生验收延期。
2. 第2阶段身份与普通资料任务1至6已按确认范围完成；Android实机和iOS原生验收延期，证据见 docs/development/stage-2-acceptance.md。
3. 第3阶段设计与实施计划已建立但尚未开工；设计见 specs/2026-10-12-stage-3-content-board-design.md，任务见 plans/2026-10-12-stage-3-content-board.md。
4. 后续业务目录随各阶段实现，不提前生成空模块；每步核对中文注释、依赖边界、对应测试和差分，完成后本地提交并更新 README。

本结构计划描述实际目录和明确标注的规划项，不代表规划目录已创建，也不替代阶段验收。文档变更需核对目录存在、路径引用和差分；代码变更执行对应测试，界面变更实际渲染并操作。

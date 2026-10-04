# 工程结构与模块组织计划

**目标：** 明确四个应用工程、共享模块及各自目录，作为后续开发的结构依据。

**架构：** 同一个Git仓库管理四个可独立运行的应用：手机端、管理后台、API和worker。手机端一套代码生成iOS与Android应用；Java共享模块不独立运行。

**技术：** 前端TypeScript、React Native/Expo、React/Vite；后端Java/Spring Boot、Maven、MyBatis、Flyway；PostgreSQL、Redis。

**依据：** [技术方案](../specs/2026-10-03-social-app-technical-design.md)、[第1阶段实施计划](2026-10-03-stage-1-engineering-foundation.md)、[数据库/API设计](../specs/2026-10-03-social-app-database-api-design.md)。本文件说明目录与职责；任务进度只在第1阶段实施计划维护，避免重复计划。

## 1. 工程清单

第1阶段同步（2026-10-04）：四个应用基础均已建立，Java四模块共30项测试，统一检查本地共78项测试及构建通过；Android模拟器有原生验收证据，iOS按用户决定延期。已推送到GitHub的`develop/stage-1-foundation`；CI配置已上传，云端修正后运行37168830294的前端、后端、浏览器均成功。产品显示名称为`Youren`，重要更改先与用户确认。

| 工程 | 根目录 | 职责 | 当前状态 |
|---|---|---|---|
| 手机端 | `apps/mobile/` | 普通用户使用，支持iOS与Android | 已有基础页及Android模拟器证据，iOS尚未验收 |
| 管理后台 | `apps/admin/` | 审核、举报和用户管理界面 | 已有连接状态页，业务后续开发 |
| API服务 | `backend/api/` | HTTP接口、权限、业务事务与后续实时通信 | 已有健康接口及错误处理 |
| worker服务 | `backend/worker/` | 到期清理、审核、推送等异步任务 | 已有启动/关闭骨架，尚无业务消费 |

`backend/shared/`、`backend/database/`是两个Java共享模块，不增加应用工程数量。PostgreSQL与Redis是基础设施服务，不是本仓库应用源码工程。

## 2. 仓库总目录

下列目录树中，`[规划]`表示尚未实现；未标注的路径已有。业务目录按对应阶段创建，不提前生成空模块。

```text
Youren/
├─ apps/
│  ├─ admin/                  管理后台
│  └─ mobile/                 iOS/Android共用手机工程
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
├─ tools/                     check.cjs统一检查、check-java-reports.cjs报告门槛
│                            check-engineering.cjs、docker.cjs、浏览器联调启动/编排
├─ tests/                     engineering.test.cjs、check.test.cjs、java-reports.test.cjs
├─ docs/
│  ├─ superpowers/specs/      产品、技术、页面、数据库/API方案
│  ├─ superpowers/plans/      开发路线与实施计划
│  ├─ development/            验收证据和恢复记录
│  └─ design/                 线框图、视觉稿及相关工具
├─ .github/workflows/ci.yml    前端、Java、浏览器三个任务；修正后的云端验收已通过
├─ .gitattributes             文本LF、Windows cmd保留CRLF
├─ package.json               npm工作区，仅前端/合同及历史工具
├─ package-lock.json          根npm依赖锁
└─ README.md                  安装、启动和文档入口
```

## 3. 手机端目录（基础已有，业务目录为规划）

```text
apps/mobile/
├─ App.tsx、index.ts          应用入口
├─ app.config.ts             应用标识、Expo平台配置
├─ eas.json                  Android/iOS开发构建配置
├─ package.json、tsconfig.json
├─ jest.config.cjs           组件测试配置
├─ assets/                   [规划] 图标、启动图和静态资源
├─ src/
│  ├─ screens/
│  │  └─ FoundationScreen.tsx  第1阶段连接状态页
│  ├─ api/health.ts           第1阶段健康请求、超时与取消
│  ├─ theme.ts               绿色主色、粉色恋爱辅助色
│  ├─ components/            [规划] 后续复用按钮、卡片及状态提示
│  ├─ navigation/            [规划] 后续导航和页面路由
│  └─ features/              [规划] 后续业务模块，见下方约定
├─ tests/
│  ├─ FoundationScreen.test.tsx  页面状态与重试
│  ├─ health.test.ts             请求、超时、取消与响应边界
│  └─ setup.ts                  原生测试环境
├─ .env.example                 公开API地址示例
└─ README.md                 本机运行、设备地址与构建验收
```

后续`features/`按`auth/`、`profile/`、`board/`、`chat/`、`activities/`、`dating/`、`settings/`组织，每个模块按需包含`screens/`、`components/`、`api.ts`、`types.ts`和测试。第1阶段只建立入口、状态页和连接测试。

`android/`、`ios/`由Expo原生生成流程产生，当前均忽略、不提交；Android已用prebuild/Gradle构建并安装模拟器，iOS原生测试延期。若未来需要长期维护原生改动，先确认策略再同步忽略规则。手机端不包含服务器密码或数据库访问代码。

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

```text
backend/api/
├─ pom.xml、README.md
└─ src/
   ├─ main/
   │  ├─ java/jp/youren/api/
   │  │  ├─ ApiApplication.java
   │  │  ├─ health/                      已有健康控制器和就绪检查
   │  │  ├─ http/ApiExceptionHandler.java 已有安全HTTP错误处理
   │  │  ├─ security/                    [规划] 身份和权限入口
   │  │  └─ features/                    [规划] 按业务组织
   │  └─ resources/
   │     ├─ application.yml
   │     └─ logback-spring.xml
   └─ test/
      ├─ java/jp/youren/api/
      │  ├─ HealthControllerTest.java
      │  ├─ ApiErrorTest.java
      │  └─ ReadinessIT.java
      └─ resources/logback-test.xml
```

后续`features/`包含`auth/`、`profile/`、`board/`、`chat/`、`activities/`、`dating/`、`moderation/`，各模块按需建立`controller/`、`service/`、`dto/`。控制器处理HTTP，服务层协调权限与事务，DTO维护公开合同；Mapper和SQL放数据库模块。实时协议确认后再创建对应通信目录。

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

```text
backend/shared/
├─ pom.xml
└─ src/
   ├─ main/java/jp/youren/shared/
   │  ├─ config/                         配置校验、依赖连接工厂
   │  └─ logging/SafeLog.java             白名单安全日志
   └─ test/java/jp/youren/shared/         配置与日志单元测试

backend/database/
├─ pom.xml、README.md
├─ tools/sync-initial.cjs                初始SQL来源一致性检查
└─ src/
   ├─ main/
   │  ├─ java/jp/youren/database/
   │  │  ├─ DatabaseConfiguration.java
   │  │  ├─ MigrationCommand.java
   │  │  ├─ LegacyMigrationAdoption.java
   │  │  ├─ mapper/                      MyBatis接口，按业务逐步扩展
   │  │  └─ type/UuidTypeHandler.java
   │  └─ resources/
   │     ├─ mapper/                      XML与接口对应
   │     ├─ db/migration/V1__initial.sql
   │     ├─ db/v1-schema.sha256
   │     ├─ db/legacy-checksums.txt
   │     └─ logback.xml
   └─ test/                              迁移、读写、事务和约束测试
```

### 模块依赖约定

```text
手机端 ──HTTP──┐
              ├── API ──→ database ──→ shared
管理后台 ─HTTP─┘      └───────────────→ shared
                  worker ─→ database
                         └→ shared
```

HTTP是运行时通信，箭头`→`是Java代码依赖；以上Java依赖已由当前`pom.xml`声明。

- `api`与`worker`不能互相导入代码或依赖对方启动；通过持久任务记录衔接异步工作。
- `database`依赖`shared`，`shared`不反向依赖database或应用。database负责Mapper、类型映射和迁移，不承担HTTP控制器或推送调用。
- API的业务服务协调权限与事务；跨业务模块通过明确的服务接口调用，控制器不绕过服务直接写Mapper。避免模块循环依赖，新的共享业务逻辑出现后再按职责提取。
- 前端仅调用公开或授权API，不能导入Java实现、服务器配置及旧Prisma访问包。合同以`packages/contracts/openapi.json`为准，不能以共享数据库行模型替代DTO。

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
- API任务生产逻辑放对应`features/<业务>/service/`；worker处理器放`jobs/`，领取、租约、重试和幂等协调放`dispatch/`。任务Mapper/XML在database模块，后续按业务归组，接口和XML保持对应。
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


1. 第1阶段任务6：手机基础、组件、类型、两端JS导出及Android模拟器已有证据；用户决定延期iOS原生验收。
2. 第1阶段任务7：已有CI与统一检查；Maven `clean verify`和npm前端/合同检查分别执行，子命令失败立即停止，JUnit数量与结果独立检查。本地完整检查已通过，已推送GitHub，修正后的云端验收已通过；操作见`docs/development/ci.md`。
3. 阶段2至7：随业务新增上述规划目录，明确模块接口后开发，避免为目录完整性生成空代码。
4. 每步核对中文注释、依赖边界、对应测试及差分，保存一次本地提交；更新README说明实际新增目录。

本结构计划不表示规划目录已经创建，也不替代阶段验收。文档变更核对目录存在性、路径和引用；代码变更执行对应单元/集成测试，界面变更实际渲染和操作。

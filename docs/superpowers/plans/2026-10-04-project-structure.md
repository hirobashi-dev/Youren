# 工程结构与模块组织计划

**目标：** 明确四个应用工程、共享模块及各自目录，作为后续开发的结构依据。

**架构：** 同一个Git仓库管理四个可独立运行的应用：手机端、管理后台、API和worker。手机端一套代码生成iOS与Android应用；Java共享模块不独立运行。

**技术：** 前端TypeScript、React Native/Expo、React/Vite；后端Java/Spring Boot、Maven、MyBatis、Flyway；PostgreSQL、Redis。

**依据：** [技术方案](../specs/2026-10-03-social-app-technical-design.md)、[第1阶段实施计划](2026-10-03-stage-1-engineering-foundation.md)、[数据库/API设计](../specs/2026-10-03-social-app-database-api-design.md)。本文件说明目录与职责；任务进度只在第1阶段实施计划维护，避免重复计划。

## 1. 工程清单

| 工程 | 根目录 | 职责 | 当前状态 |
|---|---|---|---|
| 手机端 | `apps/mobile/` | 普通用户使用，支持iOS与Android | 尚未建立，第1阶段任务6 |
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
│  └─ mobile/                 [规划] iOS/Android共用手机工程
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
├─ tools/                     工程检查、浏览器集成测试编排
├─ tests/                     仓库级工程测试
├─ docs/
│  ├─ superpowers/specs/      产品、技术、页面、数据库/API方案
│  ├─ superpowers/plans/      开发路线与实施计划
│  ├─ development/            验收证据和恢复记录
│  └─ design/                 线框图、视觉稿及相关工具
├─ .github/workflows/         [规划] 双工具链CI
├─ package.json               npm工作区，仅前端/合同及历史工具
├─ package-lock.json          根npm依赖锁
└─ README.md                  安装、启动和文档入口
```

## 3. 手机端目录（全部为规划）

```text
apps/mobile/
├─ App.tsx、index.ts          应用入口
├─ app.config.ts             应用标识、Expo平台配置
├─ eas.json                  Android/iOS开发构建配置
├─ package.json、tsconfig.json
├─ jest.config.cjs           组件测试配置
├─ assets/                   图标、启动图和静态资源
├─ src/
│  ├─ screens/
│  │  └─ FoundationScreen.tsx  第1阶段连接状态页
│  ├─ api/health.ts           第1阶段健康请求、超时与取消
│  ├─ theme.ts               绿色主色、粉色恋爱辅助色
│  ├─ components/            后续复用按钮、卡片及状态提示
│  ├─ navigation/            后续导航和页面路由
│  └─ features/              后续业务模块，见下方约定
├─ tests/FoundationScreen.test.tsx
└─ README.md                 本机运行、设备地址与构建验收
```

后续`features/`按`auth/`、`profile/`、`board/`、`chat/`、`activities/`、`dating/`、`settings/`组织，每个模块按需包含`screens/`、`components/`、`api.ts`、`types.ts`和测试。第1阶段只建立入口、状态页和连接测试。

`android/`、`ios/`可能由Expo原生生成流程产生；是否提交根据实际构建策略确定并记录，不作为手工维护两套业务工程。手机端不包含服务器密码或数据库访问代码。

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

依赖方向：`api`、`worker` → `shared`、`database`；具体构建依赖以各`pom.xml`为准。共享模块不反向依赖应用，不让手机端或后台导入Java实现。跨进程重复业务逻辑出现时再按职责提取，不提前建立空的领域模块。

## 8. 落地顺序与验证

1. 第1阶段任务6：建立手机基础目录，先测试状态和重试，再实现；运行类型、组件、JS导出及Android/iOS开发构建验收。
2. 第1阶段任务7：建立CI与统一检查；Maven `verify`和npm前端/合同检查分别执行，子命令失败必须传递非零状态。
3. 阶段2至7：随业务新增上述规划目录，明确模块接口后开发，避免为目录完整性生成空代码。
4. 每步核对中文注释、依赖边界、对应测试及差分，保存一次本地提交；更新README说明实际新增目录。

本结构计划不表示规划目录已经创建，也不替代阶段验收。文档变更核对目录存在性、路径和引用；代码变更执行对应单元/集成测试，界面变更实际渲染和操作。

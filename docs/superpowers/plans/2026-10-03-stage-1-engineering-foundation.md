# 第1阶段：工程基础实施计划

状态（2026-10-04）：开发已恢复，任务1至5已完成、测试并本地提交；任务6手机端和任务7双工具链CI尚未开始。第1阶段尚未整体验收通过。

本文件是第1阶段唯一实施计划，已合并Java/Maven改修内容；[验收记录](../../development/stage-1-acceptance.md)保存当前验证和历史证据。原TypeScript后端及独立改修计划可通过Git历史查阅，当前不再执行旧NestJS/runtime命令。

## 目标、架构与技术组合

建立可复现、可测试的手机端、API、后台及worker工程，完成隔离依赖、自动检查和Android/iOS开发构建验收。账号、留言板、聊天、活动及恋爱业务属于后续阶段。

- 后端：Java 21、Spring Boot、Maven Wrapper、MyBatis、Flyway、PostgreSQL和Redis；JUnit、MockMvc、Testcontainers。
- 前端：TypeScript；手机端React Native/Expo，后台React/Vite；后台Vitest、Testing Library、Playwright，手机端拟采用jest-expo及React Native Testing Library。
- 已固定JDK21.0.12.1+1、Maven3.9.9、Spring Boot3.5.16、MyBatis Starter3.0.5、Node22.23.3；手机端依赖随任务6核实并固定。
- 模块化单体，API/worker共用Java模块；手机端和后台通过HTTP访问API，不直接访问数据库。npm负责前端和合同，Maven负责Java后端。

依据：[八阶段路线](2026-10-03-phased-development-acceptance.md)、[技术方案](../specs/2026-10-03-social-app-technical-design.md)、[数据库/API设计](../specs/2026-10-03-social-app-database-api-design.md)、[合同说明](../../../packages/contracts/README.md)。

## 文件布局与职责

各应用和共享模块的详细目录见[工程结构计划](2026-10-04-project-structure.md)。该文件只维护结构与职责，开发进度以本实施计划为准。

| 路径 | 职责与状态 |
|---|---|
| 根`package.json`、锁文件、`.node-version`、ESLint/Prettier配置 | 已有：前端工作区、固定安装和静态检查 |
| `backend/pom.xml`、`mvnw*`、`build.ps1`、`run.ps1` | 已有：Java四模块构建、版本约束、独立JDK启动 |
| `backend/shared/` | 已有：配置校验、连接工厂、安全日志和测试 |
| `backend/database/` | 已有：MyBatis接口/XML、Flyway SQL、迁移接续与集成测试 |
| `backend/api/`、`backend/worker/` | 已有：HTTP健康接口、worker生命周期和真实依赖测试 |
| `infra/` | 已有：隔离开发/测试数据库和Redis、环境示例 |
| `apps/admin/`、`tools/test-admin-java.cjs` | 已有：中文状态页、组件和真实Java浏览器测试 |
| `packages/contracts/`、`packages/database/` | 已有：OpenAPI合同、旧数据库规范源和历史回归工具 |
| `apps/mobile/` | 待新增：Expo工程、状态页、API客户端、主题、测试和构建配置 |
| `.github/workflows/ci.yml`、`tools/check.cjs` | 待建立或调整：双工具链CI、统一检查和失败传播 |

旧数据库包保留只读参考；同一库禁止同时由Prisma和Flyway修改结构。新增DDL由Flyway管理，不生成54套空Mapper。

## 全局执行要求

按executing-plans逐任务实施、记录验证与提交；当前下一步为任务6。

- 在`develop/stage-1-foundation`开发；每步先写对应失败测试，再实现、验证、差分审查并本地提交，未要求不推送。
- 代码及测试加入中文注释；JSON使用邻近中文README说明。界面中文，服务时间UTC；页面实际渲染和操作。
- 工具放独立目录，不改系统默认JDK；修改系统设置前确认，其他已授权项目操作直接执行。
- 缺失脚本、跳过测试、旧后端证据不计为当前通过；必要条件不足时记录原因，保留未验收状态。

## Java数据库与迁移规则

- 使用与Boot版本匹配的官方MyBatis Starter，不自动引入MyBatis-Plus或JPA。Mapper接口和XML namespace/方法一致，显式resultMap；数据库行不直接作为公开DTO。
- 输入使用`#{...}`参数绑定，禁止用户输入进入`${...}`；排序列等标识使用服务端枚举白名单。复杂事务和锁查询放XML便于审查。
- Spring服务层与Mapper共用DataSource/事务管理器，不手动commit，不在持锁事务等待邮件、审核或推送；遵循业务锁序，outbox与业务写入同事务。
- UUID、timestamptz、bigint、JSONB及枚举显式映射；UTC使用Instant，消息sequence对外仍是字符串。新增JSONB TypeHandler时增加测试。
- Flyway V1保持原54表、函数、索引、触发器、默认配置及中文说明，由Flyway管理事务。既有库须先核对结构指纹及Prisma迁移版本/checksum，再显式baseline；不重放V1、不删除业务数据，结构不匹配则停止。
- 关闭自动baseline和clean，API/worker关闭自动改表；新增DDL使用后续Flyway版本。SQL来源和规范源调整完成前保留旧包只读参考。

详细操作见[数据库模块说明](../../../backend/database/README.md)；参数绑定及事务遵循项目Mapper实现与测试。

## 后端配置与测试发现

Java使用`SPRING_DATASOURCE_URL`（JDBC URL）、独立`SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD`、`SPRING_DATA_REDIS_URL`、`SERVER_PORT`。不假设自动加载Node `.env`，不把原Prisma URL直接作为JDBC URL，不回显凭证或连接串。

Surefire执行`*Test`，Failsafe绑定integration-test和verify执行`*IT`；检查实际测试数量，禁止用`-DskipTests`验收。`test`仅执行单元测试，`verify`包含集成、格式及打包；仅执行integration-test不代表完整verify通过。

Windows使用`backend/build.ps1`临时选择独立JDK，退出后恢复环境；Linux/macOS使用`./backend/mvnw -f backend/pom.xml verify`。模块命令可使用`-pl api -am test`或`-pl worker -am verify`；启动使用已打包JAR及`backend/run.ps1`，详见[后端说明](../../../backend/README.md)。

## 任务1：工程与构建基础 — 已完成

- [x] 根工作区、锁文件、类型/格式与工程检查；Maven四模块、Wrapper及工具版本约束。
- [x] 独立JDK下载校验、配置与安全日志；7项JUnit测试和干净构建通过，已本地提交。

## 任务2：隔离依赖、MyBatis与迁移 — 已完成

- [x] 开发/测试数据库和Redis隔离；保留54表和约束，建立Flyway V1、参数绑定、UUID映射及事务。
- [x] 11项数据库集成测试通过：重复迁移、CRUD、回滚、约束和旧库接续。
- [x] 现有测试库审计后baseline到V1，业务数据不变；开发库尚未接续，不宣称全部环境已迁移。

## 任务3：Java API基础 — 已完成

- [x] `/health/live`返回200；`/health/ready`依赖正常200、故障503，有限超时并可恢复。
- [x] 安全错误响应、脱敏及受限CORS；6项HTTP单元和1项真实依赖集成测试通过，合同9项及生成一致性通过。

## 任务4：Java worker基础 — 已完成

- [x] 独立worker无业务消费，启动失败非零退出、超时及重复关闭安全。
- [x] 3项单元和2项实际JAR进程集成测试通过；Linux容器验证SIGINT/SIGTERM与JDBC连接释放，不冒称Windows原生信号验证。
- [x] Java验证后移除旧TS API/worker/runtime，已本地提交。

## 任务5：管理后台兼容 — 已完成

- [x] 中文连接状态页调用Java API；5项组件/客户端测试覆盖超时、取消和状态。
- [x] 模拟及真实Java浏览器测试各1项通过；Redis故障→重试→恢复、375px窄屏截图复核及类型/构建/静态检查通过。

## 任务6：手机端与两端开发构建 — 待开发

接口：独立`getReadiness(signal?: AbortSignal)`返回`ready`或`unavailable`；`FoundationScreen`展示中文状态与重试。`EXPO_PUBLIC_API_BASE_URL`仅放公开地址，真机不能使用自身localhost。

- [ ] 先写成功、失败、超时、取消及重试测试，确认失败后实现最小页面。
- [ ] 新增`App.tsx`、`src/screens/FoundationScreen.tsx`、`src/api/health.ts`、`src/theme.ts`、测试及README；沿用绿色主色和粉色恋爱辅助色，不实现业务页面。
- [ ] 固定Expo配套依赖和EAS CLI；新增`app.config.ts`、`eas.json`，配置开发客户端/internal与iOS模拟器构建。
- [ ] 实时协议仍待确认，建议Spring WebSocket＋JSON，在阶段4前确认；手机骨架不提前绑定Socket.IO。
- [ ] 定义并执行`test`、`typecheck`、`build:js`，运行`expo install --check`。JS导出不等于原生构建通过。
- [ ] Android执行`expo run:android`构建安装；iOS使用EAS开发构建及登记真机，或macOS模拟器路径。
- [ ] 两端分别验证启动、重载、API成功/故障/恢复、中文和安全区；记录OS、设备、SDK、构建ID、截图及结果。
- [ ] 缺账号/设备时平台保持未验收；Expo Go不代替开发构建。仅配置完成时提交记录明确缺少平台证据。

Windows不运行Xcode；iOS账号、Apple开发者资格及设备条件在构建前确认。[Expo开发构建说明](https://docs.expo.dev/develop/development-builds/introduction/)。

## 任务7：双工具链CI与整体验收 — 待开发

- [ ] 编排测试先验证失败传播，再实现`tools/check.cjs`和根`check`；必要命令失败立即非零退出，不忽略缺失脚本。
- [ ] CI固定JDK、Maven和Node/npm；后端Wrapper执行`verify`，前端执行固定安装、类型、格式、测试、构建和合同检查。
- [ ] 使用Testcontainers隔离依赖，缺Docker时报失败、不跳过IT；核对报告数量和容器清理。
- [ ] 干净检出复现README及后台浏览器检查、手机JS导出；云构建和人工设备证据单独记录。
- [ ] 汇总结果、平台证据和未实施原因；核对中文注释、秘密、锁文件和差分，保存验收提交。

## 命令矩阵

从仓库根执行，先按README选择固定Node；Windows后端推荐独立JDK脚本。

| 命令 | 状态与用途 |
|---|---|
| `npm ci` | 已有：根锁文件安装 |
| `npm run test:engineering`、`npm run lint`、`npm run format:check` | 已有：工程和静态检查 |
| `.\backend\build.ps1 clean verify` | 已有：Java构建、格式、单元和集成测试，需要Docker |
| `npm test -w @youren/admin -- --run`、`npm run build -w @youren/admin` | 已有：后台测试、类型和构建 |
| `npm run test:e2e -w @youren/admin` | 已有：模拟浏览器检查 |
| `npm run test:e2e:java -w @youren/admin` | 已有：真实Java浏览器检查 |
| `npm test -w @youren/contracts`、`npm run check:generated -w @youren/contracts` | 已有：合同及生成一致性 |
| `npm test -w @youren/mobile -- --runInBand` | 待任务6定义：手机组件测试 |
| `npm run build:js -w @youren/mobile`、`npm run android -w @youren/mobile` | 待任务6定义：JS导出、Android开发构建 |
| `npm run check` | 待任务7定义：整仓编排，当前不能执行 |
| `git diff --check` | 每步完成前：差分格式检查 |

## 第1阶段整体验收清单

以下12项是最终门槛，尚未整体签核；已完成的局部验证见任务记录。

- [ ] 全新检出按README固定安装、检查、启动成功。
- [ ] 前后端依赖边界清晰，手机端不导入服务端秘密或数据库实现。
- [ ] 重复迁移无副作用，54表、MyBatis读写/事务及迁移接续验证完整。
- [ ] API存活/就绪正常，数据库或Redis故障503并可恢复。
- [ ] worker失败可检测、重复关闭安全，无遗留连接或业务副作用。
- [ ] 日志脱敏、格式、类型、合同和迁移源一致性通过。
- [ ] 后台实际渲染，失败/恢复/重试操作通过。
- [ ] Android开发构建安装并连接API，设备与截图证据完整。
- [ ] iOS开发构建安装并连接API，设备与截图证据完整。
- [ ] 统一检查传播真实失败，CI集成和浏览器检查通过。
- [ ] 开发/测试数据隔离，秘密和构建产物未提交。
- [ ] 每步测试和本地提交记录完整；必要验收缺失时阶段保持未完成。

下一步执行任务6，不重复实施已通过的Java基础任务；全部门槛通过后进入阶段2。

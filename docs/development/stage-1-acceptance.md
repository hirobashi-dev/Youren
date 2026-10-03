# 第1阶段进度与恢复入口

## 当前状态

开发已恢复，Java改修任务1已验收：Maven四模块、Wrapper、shared配置验证及安全日志通过。7项JUnit测试、clean verify和格式检查成功；Java8被Enforcer拒绝、JAVA_HOME退出后恢复。MyBatis/Flyway新增11项数据库集成测试通过，现有测试库已审计baseline且业务数据不变；Java API新增6项HTTP单元及1项真实依赖IT通过，合同9项及生成一致性通过；worker新增3项单元及2项真实JAR进程IT通过，SIGINT/SIGTERM及JDBC连接释放验证成功。旧TS后端移除。前端Java兼容回归已通过：5项组件/客户端、模拟浏览器1项、真实Java浏览器1项，窄屏375px截图已复核；下一步手机端及CI，以下暂停说明保留为历史记录。

历史（恢复前）2026-10-04补充决策：后端目标已改为Java/Spring Boot、Maven、MyBatis，当前代码尚未迁移。下方已执行验证均为原TypeScript/Prisma证据，不能视为Java/MyBatis通过。恢复执行依据为[第1阶段实施计划](../superpowers/plans/2026-10-03-stage-1-engineering-foundation.md)。

2026-10-04按用户要求暂停。开发分支为`develop/stage-1-foundation`，未合并master、未推送。第1阶段尚未整体验收完成。

已实现任务1至5的工程基础：根工作区与锁文件、配置/日志、隔离依赖、API健康检查、worker生命周期和后台连接页。任务6手机端和任务7完整自动检查/CI尚未开始。

## 已执行验证

| 内容 | 结果 |
|---|---|
| 基线数据库/合同 | 10项及9项通过；生成一致性通过 |
| 根工程 | 2项测试通过；固定Node运行、干净npm ci通过 |
| runtime/DB工厂 | 2项配置脱敏、1项工厂边界通过；runtime构建通过 |
| Docker | 项目独立开发/测试4服务健康；测试DB首次迁移、再次部署无待迁移 |
| API | 2项HTTP、1项真实依赖集成通过；编译通过；真实Redis停止503、恢复200 |
| worker | 2项生命周期、1项真实连接集成通过；编译通过；OS信号演练尚未验收 |
| admin | 2项MSW组件测试、1项Chrome操作测试通过；类型/构建、ESLint及格式检查通过 |

后台测试实际演练失败→点击重试→成功，375px窄屏无水平溢出。已视觉复核本地截图`artifacts/admin-foundation.png`，截图是忽略的生成物，干净检出可通过浏览器测试重新生成。

## 当前继续顺序

1. 阅读[详细计划](../superpowers/plans/2026-10-03-stage-1-engineering-foundation.md)、本文件及根README，确认分支和工作区状态；不要从头重建项目。
2. Java改修任务1至5已有当前证据和提交，不重复开发。接下来执行第1阶段任务6：手机端、两端开发构建和设备操作；账号或设备缺失时记录平台未验收。
3. 完成任务7双工具链CI和干净检出验收。根完整check尚未定义；Java用Maven verify、前端/合同用npm，不能声称整仓已通过。

## 环境与重启

下方为历史TypeScript环境重现命令，需先检出3448582对应版本；当前已移除旧后端，不能在当前分支执行这些历史工作区命令。Java恢复前检查JDK/JAVA_HOME，按新计划创建Wrapper/pom；Java配置用JDBC URL和独立用户名/密码，不能直接复用Prisma URL或假设自动读取Node `.env`。Java未实现前不尝试执行拟新增Maven命令。

固定Node22.23.3在`D:/MyWork/01_developer/Ai/.youren-tools/node_modules/node/bin/node.exe`，系统默认仍为20.12.1。系统npm.ps1可能优先旧Node，因此本会话直接用固定Node运行系统npm-cli：

```powershell
$env:PATH='D:\MyWork\01_developer\Ai\.youren-tools\node_modules\node\bin;'+$env:PATH
node 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js' ci
node 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js' run generate:client -w @youren/database
node 'C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js' run build -w @youren/runtime
docker compose --env-file infra/.env -f infra/compose.yaml -p youren-stage1 up -d --wait
```

`infra/.env`已在本机创建并忽略；根`.env`尚未创建。启动API/worker前按infra/README配置根`.env`，或仅当前进程显式设置DATABASE_URL/REDIS_URL后运行编译入口。开发DB5441/Redis6381，测试DB5442/Redis6382；不得把测试写入指向开发或外部数据库。Prisma CLI通过`npm exec -w @youren/database -- prisma ...`解析，不假设位于根node_modules。

本次暂停已停止自身API进程和`youren-stage1`四服务，保留数据卷。没有上传云构建、安装手机端依赖或修改系统Node。Android SDK目录存在，但设备/JDK/原生构建兼容尚未验证；iOS账号/设备及云构建尚未验证。

## 已知实施调整

- TypeScript6搭配typescript-eslint8.71.0；先前旧适配器peer范围冲突已解决。
- SWC Windows原生缓存权限校验失败，已改为兼容TS6的ts-jest，没有修改本机缓存权限。
- 新应用随任务创建，未用空脚本伪造工作区检查。统一根锁后移除既有包锁。
- 旧文档中逐包`npm ci`需更新为根安装；根README版本说明及全部命令将在任务7统一核对。新依赖的生产维护/安全版本评估仍需完成，不把当前开发基线当生产认证。
- 下一步不再直接继续手机任务6，而是先改修Java/Maven/MyBatis基础。此句为恢复前决策；现在已恢复并完成Java改修任务1至4。

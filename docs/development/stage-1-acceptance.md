# 第1阶段进度与恢复入口

## GitHub CI实际核验（2026-10-04）

最新已推送提交`5b9200d`的[运行37168061562](https://github.com/hirobashi-dev/Youren/actions/runs/37168061562)已结束，结论failure；前端success、后端failure、浏览器skipped。前次`5c1704a`运行也失败。

GitHub后端check-run注释明确：`21.0.12.1+1`不是setup-java接受的SemVer格式，失败于安装JDK步骤；Maven及集成测试未执行，JUnit报告未生成，不能计为云端后端通过。浏览器因needs依赖未满足跳过。核验通过GitHub REST API读取runs、jobs、check-run annotations，未修改工作流、重跑或推送修复。

本地最新计划同步提交`c2df803`尚未推送，不属于该运行覆盖范围；本次仅记录核验结果。iOS延期决定保持。

## 当前状态

最新（任务7）：统一检查、JUnit报告门槛及三个GitHub CI任务已实现；本地结果见下方记录。手机基础及Android已有证据，用户决定延期iOS原生测试，保持未验收；GitHub hosted runner结果已核验：前端成功、后端JDK安装失败、浏览器跳过。不宣称第1阶段所有平台通过。

### 统一检查与CI验证证据（2026-10-04）

| 检查 | 实际命令/步骤与结果 |
|---|---|
| 测试驱动 | 新增9项编排/报告测试，实现前7项失败（2项拒绝输入已成立）；实现后9项通过，连同工程2项共11项 |
| 前端组 | `npm run check:frontend`退出0：工程11、历史数据库11、合同9、后台5、手机10项，共46项；类型、lint/格式、生成一致性、后台构建、Expo兼容及两端JS导出通过 |
| Java组 | `npm run check:backend`退出0：Docker必需检查、Flyway来源、Wrapper clean verify、JUnit六组共30项；无失败/错误/跳过 |
| 浏览器组 | `npm run check:browser`退出0：Chrome模拟操作1项及真实Java故障/恢复1项；375px与宽屏操作通过 |
| Docker缺失 | 当前进程DOCKER_BIN指向不存在工具，后端组退出非零，未执行Flyway或Maven；真实daemon和其他终端不受影响 |
| 工作流 | 官方actionlint1.7.12校验下载SHA256后检查`ci.yml`退出0；Prettier检查包含工作流；官方Actions固定提交 |
| 干净源码 | Git暂存树导出到新目录，排除旧node_modules/target/dist，`npm ci --no-audit --no-fund`固定安装1407包；修正路径配置后`npm run check`完整退出0，共78项测试，包含重新Java clean verify和两项浏览器检查 |
| 原目录回归 | 最终`npm run format:check`及手机10项测试退出0，检查源码/配置中文注释和差分 |

干净源码复验发现并修正两项问题：系统Git的core.autocrlf使导出文本变为CRLF，Prettier拒绝44个文件；仓库`.gitattributes`统一文本LF、cmd保留CRLF，未修改系统Git设置。Jest将含`.local`的Windows绝对路径拼入glob后找不到测试；改为相对匹配，原路径失败、新路径两套10项通过，随后完整复验成功。干净目录仅更新这份已验证配置，未复制旧构建产物。

这是全新源码目录的本地验证，不称为GitHub实际检出运行。使用既有独立Node/JDK、Docker、Chrome及隔离测试环境配置；固定安装、各组及完整日志保留于忽略的`.local/stage7-*.log`。GitHub配置已推送，报告和API产物传递的云端已核验但未通过（后端JDK安装失败）；iOS原生测试按用户决定延期，两端JS导出不计平台验收。

测试只停止本次启动的redis-test，保留原有postgres-test和卷；Testcontainers临时实例自行清理。未改变系统默认JDK/PATH，不提交本地环境、依赖、JAR、截图或日志。操作与JSON配置说明见[CI说明](ci.md)。

### 手机端验证证据（2026-10-04）

| 检查 | 实际命令/步骤与结果 |
|---|---|
| 测试驱动 | 初始两个实现缺失；补充边界占位后10项测试均因功能缺失失败。实现后`npm test -w @youren/mobile -- --runInBand`：2套、10项通过 |
| 类型/兼容 | `npm run typecheck -w @youren/mobile`及`npm exec -w @youren/mobile -- expo install --check`退出0；React类型按Expo要求修正至19.2.4 |
| 两端JS | `npm run build:js -w @youren/mobile`退出0，生成Android和iOS各1个Hermes包；config限定手机两平台，不扩展Web依赖 |
| 静态/回归 | 根lint、format:check通过；工程2项、后台5项、合同9项通过，后台build通过 |
| 固定安装复验 | 更新根锁后`npm ci --no-audit --no-fund`退出0；重新运行手机10项、类型及根格式检查全部通过，生成物和截图保持忽略 |
| 原生构建 | Expo prebuild android no-install，生成目录运行Gradle `assembleDebug -PreactNativeArchitectures=x86_64 --no-daemon --console=plain`；BUILD SUCCESSFUL，447任务，5m35s |
| 原生环境 | 独立JDK21、Gradle9.3.1、SDK36/BuildTools36、NDK27.1.12297006；构建另自动安装已许可的BuildTools35及CMake3.22.1，未修改系统默认设置 |
| 安装/设备 | `adb -s emulator-5556 install .../app-debug.apk`成功；Pixel_3a_API_34只读、无快照模式，Android14，1080×2220；包名jp.youren.app.dev，Expo57.0.26、RN0.86.3 |
| API真实验证 | 独立Java端口3008、测试PG5442/Redis6382；手机地址10.0.2.2:3008。初始200/连接成功 → 停止仅测试Redis → API503/手机重试连接失败 → 恢复Redis并有限等待Java重连 → 再重试连接成功 |
| 重载/显示 | 开发菜单Reload重新加载Metro包后恢复成功、重试可操作；成功、失败、恢复、重载截图均保存且视觉复核中文、按钮和安全区 |
| iOS限制 | 固定EAS CLI24.10.0，whoami为Not logged in；未上传、未云构建、未安装iOS设备，不计平台通过 |

APK SHA256：`19e09ba7dbaaa07dfd0fcb95f712d5eb58b279c55a3600055343f7f10c1609af`。截图为忽略产物：`artifacts/mobile-android-ready.png`、`mobile-android-unavailable.png`、`mobile-android-recovered.png`、`mobile-android-reloaded.png`。

Metro首次localhost绑定IPv6导致模拟器加载空白；检查端口和Expo日志定位后，仅当前Metro进程使用`NODE_OPTIONS=--dns-result-order=ipv4first`，IPv4状态端点200并成功加载原生页面。未修改系统网络配置。Redis刚健康时Java可能暂未重连，验收采用有限轮询，不立即假定恢复完成。

验证后核对进程命令与独立端口，停止本次Metro/API及只读模拟器，停止本次新启动的测试Redis；原有postgres-test继续运行，数据卷未删除，其他项目进程未操作。

构建出现第三方Kotlin/Gradle弃用提示、SDK工具XML版本提示，以及外部NO_COLOR/FORCE_COLOR提示；实际构建和检查未失败。不把开发APK作为商店发布包或生产依赖认证。

### 任务1至5及早期历史记录

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
2. Java任务1至5及手机基础/Android模拟器已有证据，不重复开发。iOS开发构建与设备操作已由用户决定延期，保持平台未验收。
3. 使用`npm run check`复现本地验收；查看已推送GitHub的三个任务和报告，当前不计云端通过。下一阶段开发需要先细化身份与普通资料计划。

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

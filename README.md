# 友缘

四个应用及共享模块的目录规划见[工程结构计划](docs/superpowers/plans/2026-10-04-project-structure.md)。

手机端基础已建立，运行、构建及设备地址见[手机端说明](apps/mobile/README.md)。10项测试、两端JS导出和Android14模拟器操作已验证；用户决定延期iOS原生验收，不计为通过。

后端采用Java/Spring Boot、Maven、MyBatis；开发已恢复，Maven四模块和shared配置/日志基础已建立。MyBatis/Flyway数据库基础已通过，Java API健康接口及错误映射已通过，Java worker启动及信号关闭已通过，旧TypeScript后端已移除并保留Git历史。当前执行[第1阶段实施计划](docs/superpowers/plans/2026-10-03-stage-1-engineering-foundation.md)。手机端与管理后台继续TypeScript；前端与合同继续Node/npm。

Java构建执行 `.\backend\build.ps1 clean verify`，使用独立JDK21，不改变系统默认Java。详见[后端说明](backend/README.md)。

面向日本中国用户的交友应用。第一阶段工程建设进行中；产品与数据库/API设计见`docs/superpowers/specs/`，验收计划见`docs/superpowers/plans/2026-10-03-stage-1-engineering-foundation.md`。

## 开发环境

使用Node **22.23.3**及npm **10.5.0**，根目录执行`npm ci`。Windows如使用专用Node安装，将其bin目录加入当前PowerShell PATH；不改变系统默认版本。根package-lock.json为唯一安装依据，所有依赖由npm生成锁定。

```powershell
npm run test:engineering
npm run check:engineering
npm test -w @youren/database
npm test -w @youren/contracts
```

四个应用的工程基础已建立，业务按后续阶段添加。工程JSON配置使用本说明解释：tsconfig启用严格类型（包含后台两份Playwright配置），ESLint/Prettier统一新增源码和CI格式，既有生成文件保持原格式。每项开发对应测试通过后才提交本地版本。

## 统一检查与CI

```powershell
Copy-Item infra/.env.example infra/.env # 首次配置；已有本地文件不要覆盖
$env:YOUREN_JDK_HOME = '独立JDK21的绝对路径' # Windows；仅当前终端
npm run check
```

完整检查需要Docker daemon、JDK21和Chrome，固定npm安装后执行。Windows需要PowerShell 7；Linux使用`JAVA_HOME`和Maven Wrapper。浏览器联调仅使用本项目测试服务，保留原有服务和卷；不要与其他测试并行操作同一Compose项目或5173端口。

| 命令                         | 内容                                                                |
| ---------------------------- | ------------------------------------------------------------------- |
| `npm run check:frontend`     | 工程、lint/格式、类型、数据库规范/合同、测试、后台构建、两端JS导出  |
| `npm run check:backend`      | Docker必需检查、Flyway来源、Maven clean verify、JUnit实际数量与结果 |
| `npm run check:browser`      | 模拟浏览器及真实Java故障恢复；需先构建后端并准备infra/.env          |
| `npm run check:java-reports` | 核对现有JUnit报告；不能代替重新运行测试                             |

新环境安装Chrome可运行`npm exec -w @youren/admin -- playwright install --with-deps chrome`。所有子检查失败立即退出；报告缺失、零用例、失败或跳过均拒绝验收。CI分为前端、Java后端和浏览器三个任务，见[CI与验收说明](docs/development/ci.md)。本地检查不替代GitHub实际运行或iOS原生验收。

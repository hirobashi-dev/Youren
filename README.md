# 友缘

后端方向已于2026-10-04确认改为Java/Spring Boot、Maven、MyBatis；当前仓库仍保留暂停的TypeScript后端基础，Java尚未实现。恢复先执行[改修计划](docs/superpowers/plans/2026-10-04-java-maven-transition.md)。前端与合同继续Node/npm；以下命令说明当前代码，不代表目标Java构建已经可用。

面向日本中国用户的交友应用。第一阶段工程建设进行中；产品与数据库/API设计见`docs/superpowers/specs/`，验收计划见`docs/superpowers/plans/2026-10-03-stage-1-engineering-foundation.md`。

## 开发环境

使用Node **22.23.3**及npm **10.5.0**，根目录执行`npm ci`。Windows如使用专用Node安装，将其bin目录加入当前PowerShell PATH；不改变系统默认版本。根package-lock.json为唯一安装依据，所有依赖由npm生成锁定。

```powershell
npm run test:engineering
npm run check:engineering
npm test -w @youren/database
npm test -w @youren/contracts
```

数据库、合同已实现；apps应用会按阶段逐一添加。工程JSON配置使用本说明解释：tsconfig启用严格类型，ESLint/Prettier统一新增源码风格，既有生成文件保持原格式。每项开发对应测试通过后才提交本地版本。

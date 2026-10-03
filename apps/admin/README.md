# 管理后台工程

根目录执行`npm run dev -w @youren/admin`（5173）；VITE_API_BASE_URL默认本机3000，仅公开地址。中文工程状态页验证连接和重试，管理员登录/审核业务尚未实现。JSON继承严格TypeScript/DOM类型，Vitest+MSW测试失败与恢复，Playwright独立Chrome实际渲染和操作。开发机需安装Chrome；CI安装对应浏览器后通过配置覆盖channel或安装Chrome。构建输出dist忽略。

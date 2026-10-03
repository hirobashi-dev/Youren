# 管理后台工程

根目录执行`npm run dev -w @youren/admin`（5173）；VITE_API_BASE_URL默认本机3000，仅公开地址。中文工程状态页验证连接和重试，管理员登录/审核业务尚未实现。JSON继承严格TypeScript/DOM类型，Vitest+MSW测试失败与恢复，Playwright独立Chrome实际渲染和操作。开发机需安装Chrome；CI安装对应浏览器后通过配置覆盖channel或安装Chrome。构建输出dist忽略。

`npm test -w @youren/admin`执行5项组件/客户端测试，包含两秒超时、主动/预先取消及无效响应。
`npm run test:e2e -w @youren/admin`验证浏览器响应模拟场景；
`npm run test:e2e:java -w @youren/admin`启动本项目测试依赖、独立Java API和Vite，
真实停止/恢复测试Redis并核对页面实际请求的Java端口、中文及375px无横向溢出。
API端口为本次随机分配，VITE_API_BASE_URL只在子进程中设置；不复用已有3000服务。
后者需JDK21、已打包API、Docker及infra/.env；优先YOUREN_JDK_HOME，
本机独立工具目录或CI JAVA_HOME，不改系统默认。只停止本次启动的服务、不删除卷。
截图生成于忽略的docs/development/artifacts/admin-java.png。

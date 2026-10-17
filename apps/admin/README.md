# 管理后台工程

根目录执行 `npm run dev -w @youren/admin` 启动本机管理台（5173）；`VITE_API_BASE_URL` 默认指向本机 3000。管理员使用独立账号密码与 MFA 登录，令牌只保存在当前页面内存中；可查看待审、待修改、已公开、已隐藏与举报队列。审核和举报结案均要求理由、确认、版本号及幂等键；审计员只读。超过 72 小时的待审内容会提示并继续保留。图片经管理员鉴权接口短时读取展示图，不加载原图。

JSON继承严格TypeScript/DOM类型。Vitest+MSW覆盖登录、MFA、队列、角色和连接状态；Playwright验证浏览器真实渲染和窄屏布局。开发机需安装Chrome；CI安装对应浏览器后通过配置覆盖channel或安装Chrome。

`npm test -w @youren/admin`运行管理端单测；`npm run typecheck -w @youren/admin`执行类型检查；`npm run build -w @youren/admin`执行生产构建。
`npm run test:e2e -w @youren/admin`验证浏览器响应模拟场景；
`npm run test:e2e:java -w @youren/admin`启动本项目测试依赖、独立Java API和Vite，
真实停止/恢复测试Redis并核对页面实际请求的Java端口、中文及375px无横向溢出。
API端口为本次随机分配，VITE_API_BASE_URL只在子进程中设置；不复用已有3000服务。
后者需JDK21、已打包API、Docker及infra/.env；优先YOUREN_JDK_HOME，
本机独立工具目录或CI JAVA_HOME，不改系统默认。只停止本次启动的服务、不删除卷。
截图生成于忽略的docs/development/artifacts/admin-java.png。

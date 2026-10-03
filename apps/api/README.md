# API工程

根目录构建runtime和本包后，用根`.env`配置DATABASE_URL、REDIS_URL、PORT，执行`npm run dev -w @youren/api`。`/health/live`检查进程，`/health/ready`检查真实数据库和Redis，失败503且不暴露细节；业务/v1接口尚未实现。CORS只允许本机后台5173，手机原生不受浏览器CORS限制。监听全网卡用于手机局域网连接，依赖DB/Redis仍仅loopback。

`npm run test:e2e -w @youren/api`使用注入探针；`test:integration`必须显式TEST_DATABASE_URL、TEST_REDIS_URL，拒绝非youren_test数据库。TypeScript启用Nest装饰器元信息；dev运行编译产物，修改后重新build。

# Worker工程

仅建立依赖连接和进程生命周期，不消费审核、删除或推送任务。根目录build后执行`npm run dev -w @youren/worker`，SIGINT/SIGTERM关闭，启动失败非零退出。JSON编译配置继承根类型基线；测试通过Jest和ts-jest执行，真实集成需显式TEST_DATABASE_URL/TEST_REDIS_URL。

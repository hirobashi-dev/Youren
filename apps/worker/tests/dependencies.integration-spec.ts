// 显式测试数据库/Redis校验真实连接及释放，不消费业务数据。
import { test, expect } from '@jest/globals';
import { startWorker } from '../src/bootstrap';
test('隔离真实依赖可以启动和关闭', async () => {
  const databaseUrl = process.env.TEST_DATABASE_URL,
    redisUrl = process.env.TEST_REDIS_URL;
  if (
    !databaseUrl ||
    new URL(databaseUrl).pathname != '/youren_test' ||
    !redisUrl
  )
    throw Error('必须配置隔离测试依赖');
  const worker = await startWorker({
    databaseUrl,
    redisUrl,
    port: 3000,
    logLevel: 'info',
  });
  await worker.stop();
  await expect(worker.stop()).resolves.toBeUndefined();
});

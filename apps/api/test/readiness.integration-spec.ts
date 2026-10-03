// 集成测试只能连接显式测试目标；不自动使用外部DATABASE_URL。
import { test, expect } from '@jest/globals';
import request from 'supertest';
import { createDependencies } from '@youren/runtime';
import { createApp } from '../src/app.module';
test('真实隔离数据库及Redis就绪，关闭重复调用安全', async () => {
  const databaseUrl = process.env.TEST_DATABASE_URL,
    redisUrl = process.env.TEST_REDIS_URL;
  if (
    !databaseUrl ||
    new URL(databaseUrl).pathname != '/youren_test' ||
    !redisUrl
  )
    throw Error('必须明确配置隔离测试依赖');
  const probe = createDependencies({
    databaseUrl,
    redisUrl,
    port: 3000,
    logLevel: 'info',
  });
  const app = await createApp(probe);
  try {
    await request(app.getHttpServer())
      .get('/health/ready')
      .expect(200, { status: 'ok' });
  } finally {
    await app.close();
    await expect(probe.close()).resolves.toBeUndefined();
  }
});

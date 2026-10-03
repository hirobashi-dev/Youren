// 真实HTTP校验存活、依赖失败、恢复和检查超时，不接受假就绪。
import { test, expect } from '@jest/globals';
import request from 'supertest';
import { createApp } from '../src/app.module';
test('存活正常；依赖失败503，恢复后200', async () => {
  let failed = true;
  const app = await createApp(
    {
      check: async () => {
        if (failed) throw Error('secret');
      },
      close: async () => {},
    },
    50,
  );
  try {
    await request(app.getHttpServer())
      .get('/health/live')
      .expect(200, { status: 'ok' });
    const result = await request(app.getHttpServer())
      .get('/health/ready')
      .expect(503);
    expect(JSON.stringify(result.body)).not.toContain('secret');
    failed = false;
    await request(app.getHttpServer())
      .get('/health/ready')
      .expect(200, { status: 'ok' });
  } finally {
    await app.close();
  }
});
test('超时依赖在有限时间内返回503', async () => {
  const app = await createApp(
    { check: () => new Promise(() => {}), close: async () => {} },
    30,
  );
  try {
    await request(app.getHttpServer()).get('/health/ready').expect(503);
  } finally {
    await app.close();
  }
});

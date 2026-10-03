// 启动配置和日志必须拒绝危险输入，并保留安全追踪信息。
import { loadConfig, redactLog } from '../src';
import { test, expect } from '@jest/globals';
test('缺失配置、错误协议及端口必须拒绝', () => {
  expect(() => loadConfig({})).toThrow();
  expect(() =>
    loadConfig({
      DATABASE_URL: 'https://example.test',
      REDIS_URL: 'redis://localhost',
      PORT: '-1',
    }),
  ).toThrow();
  expect(
    loadConfig({
      DATABASE_URL: 'postgresql://localhost/youren_test',
      REDIS_URL: 'redis://localhost',
      PORT: '3000',
    }).port,
  ).toBe(3000);
});
test('嵌套凭证、任意异常正文及邮箱不进入日志', () => {
  const safe = redactLog({
    event: 'startup',
    requestId: 'abc-123',
    authorization: 'Bearer secret',
    nested: { password: 'secret' },
    message: 'password=secret person@example.test',
    databaseUrl: 'postgresql://secret',
  });
  expect(safe).toEqual({ event: 'startup', requestId: 'abc-123' });
  expect(JSON.stringify(safe)).not.toContain('secret');
});

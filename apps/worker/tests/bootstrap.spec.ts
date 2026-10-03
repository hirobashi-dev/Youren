// 启动失败必须释放依赖；正常关闭可重复并只释放一次。
import { test, expect, jest } from '@jest/globals';
import { startWorker } from '../src/bootstrap';
const config = {
  databaseUrl: 'postgresql://localhost/youren_test',
  redisUrl: 'redis://localhost',
  port: 3000,
  logLevel: 'info' as const,
};
test('重复关闭只释放依赖一次', async () => {
  const probe = {
    check: jest.fn(async () => {}),
    close: jest.fn(async () => {}),
  };
  const worker = await startWorker(config, probe);
  await worker.stop();
  await worker.stop();
  expect(probe.close).toHaveBeenCalledTimes(1);
});
test('启动依赖失败不返回worker并释放连接', async () => {
  const probe = {
    check: async () => {
      throw Error('故障');
    },
    close: jest.fn(async () => {}),
  };
  await expect(startWorker(config, probe)).rejects.toThrow('故障');
  expect(probe.close).toHaveBeenCalledTimes(1);
});

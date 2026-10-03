// API/worker共用工程依赖，懒连接、有限超时和关闭；不实现业务写入。
import Redis from 'ioredis';
import { createDatabaseClient } from '@youren/database';
import type { RuntimeConfig } from './config';
export interface DependencyProbe {
  check(): Promise<void>;
  close(): Promise<void>;
}
export function createDependencies(config: RuntimeConfig): DependencyProbe {
  const database = createDatabaseClient(config.databaseUrl);
  const redis = new Redis(config.redisUrl, {
    lazyConnect: true,
    connectTimeout: 1000,
    commandTimeout: 1000,
    maxRetriesPerRequest: 0,
    retryStrategy: () => null,
  });
  // Redis默认错误事件不能写未脱敏stderr；由就绪状态显式报告失败。
  redis.on('error', () => {});
  let closed = false;
  return {
    async check() {
      if (closed) throw new Error('依赖已关闭');
      if (redis.status === 'end') await redis.connect();
      await Promise.all([database.$queryRawUnsafe('SELECT 1'), redis.ping()]);
    },
    async close() {
      if (closed) return;
      closed = true;
      redis.disconnect();
      await database.$disconnect();
    },
  };
}

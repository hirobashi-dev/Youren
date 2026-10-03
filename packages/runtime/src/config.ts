// 启动前拒绝非法配置，错误文案只包含字段名，不包含秘密值。
export interface RuntimeConfig {
  databaseUrl: string;
  redisUrl: string;
  port: number;
  logLevel: 'info' | 'warn' | 'error';
}
export function loadConfig(env: NodeJS.ProcessEnv): RuntimeConfig {
  const validateUrl = (field: string, protocols: string[]) => {
    const value = env[field];
    if (!value) throw new Error(`${field} 未配置`);
    try {
      if (!protocols.includes(new URL(value).protocol)) throw new Error();
    } catch {
      throw new Error(`${field} 格式不正确`);
    }
    return value;
  };
  const databaseUrl = validateUrl('DATABASE_URL', ['postgres:', 'postgresql:']);
  const redisUrl = validateUrl('REDIS_URL', ['redis:', 'rediss:']);
  const rawPort = env.PORT ?? '3000';
  const port = Number(rawPort);
  if (
    !/^\d+$/.test(rawPort) ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  )
    throw new Error('PORT 不正确');
  const level = env.LOG_LEVEL ?? 'info';
  if (!['info', 'warn', 'error'].includes(level))
    throw new Error('LOG_LEVEL 不正确');
  return {
    databaseUrl,
    redisUrl,
    port,
    logLevel: level as RuntimeConfig['logLevel'],
  };
}

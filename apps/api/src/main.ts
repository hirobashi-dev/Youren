// 启动错误只记录稳定事件码，不输出携带秘密的异常堆栈。
import { loadConfig, createDependencies, logEvent } from '@youren/runtime';
import { createApp } from './app.module';
async function main() {
  const config = loadConfig(process.env);
  const app = await createApp(createDependencies(config));
  await app.listen(config.port, '0.0.0.0');
  logEvent({ event: 'ready', service: 'api' });
}
main().catch(() => {
  logEvent({ event: 'startup_failed', service: 'api' });
  process.exitCode = 1;
});

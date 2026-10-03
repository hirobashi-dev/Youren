// 仅自身SIGINT/SIGTERM触发关闭，失败退出码非零，日志不包含内部异常。
import { loadConfig, logEvent } from '@youren/runtime';
import { startWorker } from './bootstrap';
async function main() {
  const worker = await startWorker(loadConfig(process.env));
  const stop = () => {
    worker.stop().catch(() => {
      logEvent({ event: 'shutdown_failed', service: 'worker' });
      process.exitCode = 1;
    });
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  logEvent({ event: 'ready', service: 'worker' });
}
main().catch(() => {
  logEvent({ event: 'startup_failed', service: 'worker' });
  process.exitCode = 1;
});

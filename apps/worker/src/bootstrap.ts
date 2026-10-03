// 无业务消费的worker骨架：就绪后维持生命周期，关闭幂等且释放所有连接。
import {
  createDependencies,
  type DependencyProbe,
  type RuntimeConfig,
} from '@youren/runtime';
export async function startWorker(
  config: RuntimeConfig,
  probe: DependencyProbe = createDependencies(config),
) {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      probe.check(),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => reject(new Error('依赖就绪超时')), 1500);
      }),
    ]);
  } catch (error) {
    await probe.close();
    throw error;
  } finally {
    if (timeout) clearTimeout(timeout);
  }
  const keepAlive = setInterval(() => {}, 60000);
  let stopping: Promise<void> | undefined;
  return {
    stop(): Promise<void> {
      if (!stopping) {
        clearInterval(keepAlive);
        stopping = probe.close();
      }
      return stopping;
    },
  };
}

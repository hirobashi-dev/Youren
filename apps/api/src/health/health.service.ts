// 依赖检查有限时返回，不暴露内部错误；共享同一次检查避免并发探测风暴。
import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import type { DependencyProbe } from '@youren/runtime';
export const PROBE = 'DEPENDENCY_PROBE',
  TIMEOUT = 'HEALTH_TIMEOUT';
@Injectable()
export class HealthService implements OnApplicationShutdown {
  private pending?: Promise<boolean>;
  constructor(
    @Inject(PROBE) private readonly probe: DependencyProbe,
    @Inject(TIMEOUT) private readonly timeout: number,
  ) {}
  async isReady(): Promise<boolean> {
    if (this.pending) return this.pending;
    this.pending = new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => resolve(false), this.timeout);
      this.probe.check().then(
        () => {
          clearTimeout(timer);
          resolve(true);
        },
        () => {
          clearTimeout(timer);
          resolve(false);
        },
      );
    });
    try {
      return await this.pending;
    } finally {
      this.pending = undefined;
    }
  }
  async onApplicationShutdown() {
    await this.probe.close();
  }
}

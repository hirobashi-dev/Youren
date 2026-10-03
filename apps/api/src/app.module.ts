// 依赖探针显式注入，HTTP测试不读取本机开发数据库。
import 'reflect-metadata';
import { Module } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import type { DependencyProbe } from '@youren/runtime';
import { HealthService, PROBE, TIMEOUT } from './health/health.service';
import { HealthController } from './health/health.controller';
export async function createApp(probe: DependencyProbe, timeout = 1500) {
  @Module({
    controllers: [HealthController],
    providers: [
      HealthService,
      { provide: PROBE, useValue: probe },
      { provide: TIMEOUT, useValue: timeout },
    ],
  })
  class AppModule {}
  const app = await NestFactory.create(AppModule, { logger: false });
  app.enableCors({
    origin: ['http://localhost:5173', 'http://127.0.0.1:5173'],
    credentials: false,
  });
  app.enableShutdownHooks();
  await app.init();
  return app;
}

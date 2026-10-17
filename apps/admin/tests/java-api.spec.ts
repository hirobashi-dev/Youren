// 浏览器不拦截HTTP，直接连接本次Java API，演练真实Redis故障与恢复。
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { test, expect } from '@playwright/test';
const require = createRequire(import.meta.url);
const { dockerExecutable } = require('../../../tools/docker.cjs') as {
  dockerExecutable: () => string;
};
test('Java API连接、真实失败及恢复在宽窄屏可操作', async ({ page }) => {
  test.setTimeout(45000);
  const docker = dockerExecutable();
  const redis = 'youren-stage1-redis-test-1';
  // 必须确认页面访问本次Java端口，不能误连端口3000已有旧服务而得到假绿。
  const apiBase = process.env.VITE_API_BASE_URL;
  expect(apiBase).toBeTruthy();
  const connected = page.waitForResponse(`${apiBase}/health/ready`);
  await page.goto('/');
  expect((await connected).status()).toBe(200);
  await expect(page.getByRole('status')).toHaveText('连接成功');
  execFileSync(docker, ['stop', '--time', '1', redis]);
  try {
    await page.getByRole('button', { name: '重试' }).click();
    await expect(page.getByRole('status')).toHaveText('连接失败');
  } finally {
    execFileSync(docker, ['start', redis]);
  }
  await expect(async () => {
    await page.getByRole('button', { name: '重试' }).click();
    await expect(page.getByRole('status')).toHaveText('连接成功');
  }).toPass({ timeout: 10000 });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
  await page.screenshot({
    path: '../../docs/development/artifacts/admin-java.png',
    fullPage: true,
  });
});

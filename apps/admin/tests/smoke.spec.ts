// 真实浏览器中演练失败→重试→恢复，并检查窄屏没有水平溢出。
import { test, expect } from '@playwright/test';
test('连接失败可重试恢复，宽窄屏可读', async ({ page }) => {
  let attempts = 0;
  await page.route('**/health/ready', (route) =>
    route.fulfill({
      status: ++attempts === 1 ? 503 : 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: attempts === 1 ? 'unavailable' : 'ok' }),
    }),
  );
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('连接失败');
  await page.getByRole('button', { name: '重试' }).click();
  await expect(page.getByRole('status')).toHaveText('连接成功');
  await page.screenshot({
    path: '../../docs/development/artifacts/admin-foundation.png',
    fullPage: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
});

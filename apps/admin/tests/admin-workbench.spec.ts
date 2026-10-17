// 浏览器真实操作覆盖管理员双重验证与带版本的人工审核提交。
import { test, expect } from '@playwright/test';

test('管理员完成MFA后通过待审留言并刷新队列', async ({ page }, testInfo) => {
  let removed = false;
  let actionBody: Record<string, unknown> | undefined;
  let actionKey = '';
  await page.route('**/health/ready', (route) => route.fulfill({ json: { status: 'ok' } }));
  await page.route('**/v1/admin/auth/login', (route) => route.fulfill({ json: { data: { challengeId: 'challenge-e2e', expiresAt: '2099-01-01T00:00:00Z' } } }));
  await page.route('**/v1/admin/auth/mfa', (route) => route.fulfill({ json: { data: { accessToken: 'temporary-admin-token', role: 'reviewer', expiresAt: '2099-01-01T00:00:00Z' } } }));
  await page.route('**/v1/admin/content*', (route) => route.fulfill({ json: { data: removed ? [] : [{ targetType: 'post', targetId: 'post-e2e', status: 'pending', version: 2, pendingSince: '2026-10-17T10:00:00Z', reviewOverdue: false, title: '周末散步同行', body: '欢迎一起参加。', images: [] }] } }));
  await page.route('**/v1/admin/moderation-actions', async (route) => {
    actionBody = route.request().postDataJSON();
    actionKey = route.request().headers()['idempotency-key'] ?? '';
    removed = true;
    await route.fulfill({ status: 201, json: { data: { id: 'action-e2e' } } });
  });
  page.on('dialog', (dialog) => dialog.accept());
  await page.goto('/');
  await page.getByLabel('管理员邮箱').fill('reviewer@example.jp');
  await page.getByLabel('登录密码').fill('local-password');
  await page.getByLabel('二次验证代码').fill('123456');
  await page.getByRole('button', { name: '验证并登录' }).click();
  await expect(page.getByText('周末散步同行')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('workbench.png'), fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.locator('.table-scroll').evaluate((node) => node.scrollWidth <= node.clientWidth)).toBeTruthy();
  await expect(page.getByRole('button', { name: '查看详情' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('workbench-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole('button', { name: '查看详情' }).click();
  await page.screenshot({ path: testInfo.outputPath('review-detail.png'), fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.screenshot({ path: testInfo.outputPath('review-detail-mobile.png'), fullPage: true });
  await page.getByLabel('处理理由').fill('人工复核通过');
  await page.getByRole('button', { name: '通过并公开' }).click();
  await expect(page.getByText('当前没有待审核内容。')).toBeVisible();
  expect(actionBody).toMatchObject({ targetType: 'post', targetId: 'post-e2e', action: 'approve', reason: '人工复核通过', expectedVersion: 2 });
  expect(actionKey).toMatch(/^[0-9a-f-]{36}$/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});

test('举报详情同时显示理由和被举报内容', async ({ page }, testInfo) => {
  // 场景：审核员打开举报详情；预期：举报依据与精确匹配的内容一同展示。
  await page.route('**/health/ready', (route) => route.fulfill({ json: { status: 'ok' } }));
  await page.route('**/v1/admin/auth/login', (route) => route.fulfill({ json: { data: { challengeId: 'report-challenge' } } }));
  await page.route('**/v1/admin/auth/mfa', (route) => route.fulfill({ json: { data: { accessToken: 'report-token', role: 'reviewer', expiresAt: '2099-01-01T00:00:00Z' } } }));
  await page.route('**/v1/admin/reports*', (route) => route.fulfill({ json: { data: [{ report: { id: 'report-e2e', status: 'open', createdAt: '2026-10-17T10:00:00Z', version: 1 }, targetType: 'post', targetId: 'reported-post', reasonCode: 'privacy', detail: '内容包含私人联系方式' }] } }));
  await page.route('**/v1/admin/content*', (route) => {
    const status = new URL(route.request().url()).searchParams.get('status');
    return route.fulfill({ json: { data: status === 'visible' ? [{ targetType: 'post', targetId: 'reported-post', status: 'visible', version: 3, pendingSince: '2026-10-17T09:00:00Z', reviewOverdue: false, title: '周末散步同行', body: '请联系我 090-1234-5678', images: [] }] : [] } });
  });
  await page.goto('/');
  await page.getByLabel('管理员邮箱').fill('reviewer@example.jp');
  await page.getByLabel('登录密码').fill('local-password');
  await page.getByLabel('二次验证代码').fill('123456');
  await page.getByRole('button', { name: '验证并登录' }).click();
  await page.getByRole('button', { name: '举报处理' }).click();
  await page.getByRole('button', { name: '查看详情' }).click();
  await expect(page.getByText('内容包含私人联系方式')).toBeVisible();
  await expect(page.getByText('请联系我 090-1234-5678')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('report-detail.png'), fullPage: true });
});

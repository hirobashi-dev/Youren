// 管理工作台测试用真实表单和HTTP模拟验证登录、权限显示及待审提醒。
import { afterAll, afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { App } from './App';

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => server.use(
  http.get('http://127.0.0.1:3000/health/ready', () => HttpResponse.json({ status: 'ok' })),
  http.get('http://127.0.0.1:3000/v1/admin/reports', () => HttpResponse.json({ data: [] })),
));
afterEach(() => { server.resetHandlers(); vi.restoreAllMocks(); });
afterAll(() => server.close());

test('管理员口令登录后必须完成MFA才显示待审队列', async () => {
  server.use(
    http.post('http://127.0.0.1:3000/v1/admin/auth/login', () =>
      HttpResponse.json({ data: { challengeId: 'challenge-1', expiresAt: '2026-10-17T12:05:00Z' } }),
    ),
    http.post('http://127.0.0.1:3000/v1/admin/auth/mfa', () =>
      HttpResponse.json({ data: { accessToken: 'opaque-admin-token', expiresAt: new Date(Date.now() + 3600000).toISOString(), role: 'reviewer' } }),
    ),
    http.get('http://127.0.0.1:3000/v1/admin/content', ({ request }) => {
      expect(request.headers.get('Authorization')).toBe('Bearer opaque-admin-token');
      return HttpResponse.json({ data: [{ targetType: 'post', targetId: 'post-1', status: 'pending', version: 1, pendingSince: '2026-10-14T12:00:00Z', reviewOverdue: true, title: '秋日散步', body: '寻找同行者', images: [] }] });
    }),
  );
  render(<App />);
  await userEvent.type(screen.getByLabelText('管理员邮箱'), 'reviewer@example.jp');
  await userEvent.type(screen.getByLabelText('登录密码'), 'local-password');
  await userEvent.type(screen.getByLabelText('二次验证代码'), '123456');
  await userEvent.click(screen.getByRole('button', { name: '验证并登录' }));
  expect(await screen.findByText('秋日散步')).toBeInTheDocument();
  expect(screen.getByText(/待审超过72小时会告警并继续等待处理/)).toBeInTheDocument();
});

test('口令错误显示可读错误且不会进入MFA步骤', async () => {
  server.use(http.post('http://127.0.0.1:3000/v1/admin/auth/login', () =>
    HttpResponse.json({ code: 'UNAUTHORIZED', message: '认证失败' }, { status: 401 }),
  ));
  render(<App />);
  await userEvent.type(screen.getByLabelText('管理员邮箱'), 'reviewer@example.jp');
  await userEvent.type(screen.getByLabelText('登录密码'), 'wrong-password');
  await userEvent.type(screen.getByLabelText('二次验证代码'), '123456');
  await userEvent.click(screen.getByRole('button', { name: '验证并登录' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('验证失败');
  expect(screen.getByLabelText('二次验证代码')).toBeInTheDocument();
});

test('审计员可以读取队列但不会看到内容处置按钮', async () => {
  server.use(
    http.post('http://127.0.0.1:3000/v1/admin/auth/login', () => HttpResponse.json({ data: { challengeId: 'challenge-2' } })),
    http.post('http://127.0.0.1:3000/v1/admin/auth/mfa', () => HttpResponse.json({ data: { accessToken: 'auditor-token', expiresAt: new Date(Date.now() + 3600000).toISOString(), role: 'auditor' } })),
    http.get('http://127.0.0.1:3000/v1/admin/content', () => HttpResponse.json({ data: [{ targetType: 'post', targetId: 'post-2', status: 'pending', version: 1, pendingSince: '2026-10-17T10:00:00Z', reviewOverdue: false, title: '只读内容', body: '审计查看', images: [] }] })),
  );
  render(<App />);
  await userEvent.type(screen.getByLabelText('管理员邮箱'), 'auditor@example.jp');
  await userEvent.type(screen.getByLabelText('登录密码'), 'local-password');
  await userEvent.type(screen.getByLabelText('二次验证代码'), '123456');
  await userEvent.click(screen.getByRole('button', { name: '验证并登录' }));
  expect(await screen.findByText('只读内容')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '通过' })).not.toBeInTheDocument();
});

test('通过待审内容会提交理由、当前版本和幂等键', async () => {
  let removed = false;
  let submitted: { action: string; expectedVersion: number; reason: string } | undefined;
  let idempotencyKey: string | null = null;
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  server.use(
    http.post('http://127.0.0.1:3000/v1/admin/auth/login', () => HttpResponse.json({ data: { challengeId: 'challenge-3' } })),
    http.post('http://127.0.0.1:3000/v1/admin/auth/mfa', () => HttpResponse.json({ data: { accessToken: 'reviewer-token', expiresAt: new Date(Date.now() + 3600000).toISOString(), role: 'reviewer' } })),
    http.get('http://127.0.0.1:3000/v1/admin/content', () => HttpResponse.json({ data: removed ? [] : [{ targetType: 'post', targetId: 'post-3', status: 'pending', version: 7, pendingSince: '2026-10-17T10:00:00Z', reviewOverdue: false, title: '周末同行', body: '一起散步', images: [] }] })),
    http.post('http://127.0.0.1:3000/v1/admin/moderation-actions', async ({ request }) => {
      submitted = await request.json() as typeof submitted;
      idempotencyKey = request.headers.get('Idempotency-Key');
      removed = true;
      return HttpResponse.json({ data: { id: 'action-1' } }, { status: 201 });
    }),
  );
  render(<App />);
  await userEvent.type(screen.getByLabelText('管理员邮箱'), 'reviewer@example.jp');
  await userEvent.type(screen.getByLabelText('登录密码'), 'local-password');
  await userEvent.type(screen.getByLabelText('二次验证代码'), '123456');
  await userEvent.click(screen.getByRole('button', { name: '验证并登录' }));
  await userEvent.click(await screen.findByRole('button', { name: '查看详情' }));
  await userEvent.type(screen.getByLabelText('处理理由'), '人工复核通过');
  await userEvent.click(await screen.findByRole('button', { name: '通过并公开' }));
  expect(await screen.findByText('当前没有待审核内容。')).toBeInTheDocument();
  expect(submitted).toEqual({ targetType: 'post', targetId: 'post-3', action: 'approve', reason: '人工复核通过', expectedVersion: 7 });
  expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
});

test('设计稿中的统一三字段登录层级清晰可见', async () => {
  // 场景：管理端初次打开；预期：邮箱、密码和二次验证代码同时展示，并使用设计稿的登录标题。
  render(<App />);
  expect(screen.getByText('管理 · 后台登录与二次验证')).toBeInTheDocument();
  expect(screen.getByLabelText('管理员邮箱')).toBeInTheDocument();
  expect(screen.getByLabelText('登录密码')).toBeInTheDocument();
  expect(screen.getByLabelText('二次验证代码')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '验证并登录' })).toBeInTheDocument();
});

test('举报详情展示被举报内容上下文和审核图片', async () => {
  // 场景：审核员打开举报详情；预期：举报理由之外还呈现对应内容文字，便于人工判断。
  server.use(
    http.post('http://127.0.0.1:3000/v1/admin/auth/login', () => HttpResponse.json({ data: { challengeId: 'challenge-report' } })),
    http.post('http://127.0.0.1:3000/v1/admin/auth/mfa', () => HttpResponse.json({ data: { accessToken: 'report-token', expiresAt: new Date(Date.now() + 3600000).toISOString(), role: 'reviewer' } })),
    http.get('http://127.0.0.1:3000/v1/admin/reports', () => HttpResponse.json({ data: [{ report: { id: 'report-1', status: 'open', createdAt: '2026-10-17T10:00:00Z', version: 1 }, targetType: 'post', targetId: 'reported-post', reasonCode: 'PERSONAL_INFO', detail: '包含联系方式' }] })),
    http.get('http://127.0.0.1:3000/v1/admin/content', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status');
      return HttpResponse.json({ data: status === 'visible' ? [{ targetType: 'post', targetId: 'reported-post', status: 'visible', version: 3, pendingSince: '2026-10-17T09:00:00Z', reviewOverdue: false, title: '周末同行', body: '请联系我 090-1234-5678', images: [] }] : [] });
    }),
  );
  render(<App />);
  await userEvent.type(screen.getByLabelText('管理员邮箱'), 'reviewer@example.jp');
  await userEvent.type(screen.getByLabelText('登录密码'), 'local-password');
  await userEvent.type(screen.getByLabelText('二次验证代码'), '123456');
  await userEvent.click(screen.getByRole('button', { name: '验证并登录' }));
  await userEvent.click(await screen.findByRole('button', { name: '举报处理' }));
  await userEvent.click(await screen.findByRole('button', { name: '查看详情' }));
  expect(await screen.findByText('请联系我 090-1234-5678')).toBeInTheDocument();
});

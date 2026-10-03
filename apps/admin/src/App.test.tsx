// HTTP失败恢复与纯网络失败都应向使用者提供可重试状态。
import { beforeAll, afterAll, afterEach, test, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { App } from './App';
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
test('503显示失败，重试后连接成功', async () => {
  let attempts = 0;
  server.use(
    http.get('http://127.0.0.1:3000/health/ready', () =>
      ++attempts === 1
        ? HttpResponse.json({ status: 'unavailable' }, { status: 503 })
        : HttpResponse.json({ status: 'ok' }),
    ),
  );
  render(<App />);
  expect(await screen.findByText('连接失败')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '重试' }));
  expect(await screen.findByText('连接成功')).toBeInTheDocument();
});
test('网络错误不显示成功', async () => {
  server.use(
    http.get('http://127.0.0.1:3000/health/ready', () => HttpResponse.error()),
  );
  render(<App />);
  expect(await screen.findByText('连接失败')).toBeInTheDocument();
});

// 通过真实fetch/MSW验证超时、主动取消和响应边界，不暴露接口原始错误。
import { afterAll, afterEach, beforeAll, expect, test } from 'vitest';
import { delay, http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { getReadiness } from './health';
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
test('接口挂起两秒后返回不可用', async () => {
  server.use(
    http.get('http://127.0.0.1:3000/health/ready', async () => {
      await delay('infinite');
      return HttpResponse.json({ status: 'ok' });
    }),
  );
  expect(await getReadiness()).toBe('unavailable');
});
test('外部主动取消不允许返回成功', async () => {
  server.use(
    http.get('http://127.0.0.1:3000/health/ready', async () => {
      await delay('infinite');
      return HttpResponse.json({ status: 'ok' });
    }),
  );
  const controller = new AbortController();
  const result = getReadiness(controller.signal);
  controller.abort();
  expect(await result).toBe('unavailable');
});
test('预先取消及无效响应均不可当作连接成功', async () => {
  const controller = new AbortController();
  controller.abort();
  expect(await getReadiness(controller.signal)).toBe('unavailable');
  for (const response of [null, { status: 'unavailable' }, { status: true }]) {
    server.use(
      http.get('http://127.0.0.1:3000/health/ready', () =>
        HttpResponse.json(response),
      ),
    );
    expect(await getReadiness()).toBe('unavailable');
  }
});

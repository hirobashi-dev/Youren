import { getReadiness } from '../src/api/health';

// 网络由受控fetch模拟，验证超时/取消的可观察结果及公开地址。
const originalFetch = global.fetch;
beforeEach(() => {
  process.env.EXPO_PUBLIC_API_BASE_URL = 'http://example.test:3000';
});
afterEach(() => {
  global.fetch = originalFetch;
  jest.useRealTimers();
  delete process.env.EXPO_PUBLIC_API_BASE_URL;
});

test('HTTP成功且状态ok才视为连接成功', async () => {
  global.fetch = jest.fn(
    async () =>
      ({ ok: true, json: async () => ({ status: 'ok' }) }) as Response,
  );
  await expect(getReadiness()).resolves.toBe('ready');
  expect(global.fetch).toHaveBeenCalledWith(
    'http://example.test:3000/health/ready',
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  );
});

test.each([null, { status: 'unavailable' }, { status: true }])(
  '异常健康响应不能视为成功：%p',
  async (body) => {
    global.fetch = jest.fn(
      async () => ({ ok: true, json: async () => body }) as Response,
    );
    await expect(getReadiness()).resolves.toBe('unavailable');
  },
);

test('网络错误安全返回不可用，不抛出连接详情', async () => {
  global.fetch = jest.fn(async () => {
    throw new Error('private network detail');
  });
  await expect(getReadiness()).resolves.toBe('unavailable');
});

test('两秒无响应取消请求并返回不可用', async () => {
  jest.useFakeTimers();
  global.fetch = jest.fn(
    (_url, init) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener(
          'abort',
          () => reject(new Error('cancelled')),
          { once: true },
        );
      }),
  );
  const result = getReadiness();
  await jest.advanceTimersByTimeAsync(2000);
  await expect(result).resolves.toBe('unavailable');
});

test('调用方取消后请求返回不可用', async () => {
  global.fetch = jest.fn(
    (_url, init) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener(
          'abort',
          () => reject(new Error('cancelled')),
          { once: true },
        );
      }),
  );
  const controller = new AbortController();
  const result = getReadiness(controller.signal);
  controller.abort();
  await expect(result).resolves.toBe('unavailable');
});

test('已取消的调用方不再发出网络请求', async () => {
  global.fetch = jest.fn();
  const controller = new AbortController();
  controller.abort();
  await expect(getReadiness(controller.signal)).resolves.toBe('unavailable');
  expect(global.fetch).not.toHaveBeenCalled();
});

import { ApiClient, ApiError } from '../src/api/client';
import { AuthApi } from '../src/api/auth';
import { ProfileApi } from '../src/api/profile';
import { account, failure, profile, reply } from './fixtures';
// 删除严格解析、超时或安全映射都会使以下边界测试失败。
const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
  jest.useRealTimers();
});
test('发送验证码使用规范邮箱和指定用途，响应必须完整', async () => {
  global.fetch = jest.fn(async () =>
    reply({ challengeId: account.id, expiresIn: 600, retryAfter: 60 }, 202),
  );
  const auth = new AuthApi(new ApiClient('http://example.test'));
  await expect(
    auth.sendCode(' A@example.test ', 'register'),
  ).resolves.toMatchObject({ retryAfter: 60 });
  expect(global.fetch).toHaveBeenCalledWith(
    'http://example.test/v1/auth/email-code',
    expect.objectContaining({
      body: '{"email":"A@example.test","purpose":"register"}',
    }),
  );
});
test.each([
  {},
  { data: {}, requestId: 't' },
  { data: account, requestId: 't', secret: 'x' },
])('畸形成功信封安全拒绝 %p', async (body) => {
  global.fetch = jest.fn(
    async () => ({ ok: true, status: 200, json: async () => body }) as Response,
  );
  await expect(
    new ProfileApi(new ApiClient('http://example.test')).me('access'),
  ).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcome: 'unknown' });
});
test.each([
  [429, 'RATE_LIMITED'],
  [503, 'TEMPORARILY_UNAVAILABLE'],
  [412, 'VERSION_CONFLICT'],
  [428, 'PRECONDITION_REQUIRED'],
])('服务失败%s只展示稳定安全提示', async (status, code) => {
  global.fetch = jest.fn(async () => failure(Number(status), String(code)));
  try {
    await new ProfileApi(new ApiClient('http://example.test')).me('access');
    throw new Error('expected rejection');
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    expect((error as Error).message).not.toContain('secret');
    expect(error).toMatchObject({ status, outcome: 'rejected' });
  }
});
test('公开资料拒绝邮箱或恋爱字段而不是渲染私人数据', async () => {
  global.fetch = jest.fn(async () =>
    reply({ ...profile, email: 'private@example.test', dating: {} }),
  );
  await expect(
    new ProfileApi(new ApiClient('http://example.test')).publicProfile(
      account.id,
    ),
  ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
test('首次保存使用带双引号版本0，不固化昵称建议上限', async () => {
  global.fetch = jest.fn(async () =>
    reply({ profile: { ...profile, moderationStatus: 'pending' }, version: 1 }),
  );
  await new ProfileApi(new ApiClient('http://example.test')).save('access', 0, {
    nickname: '中'.repeat(40),
    bio: '',
  });
  expect(global.fetch).toHaveBeenCalledWith(
    expect.any(String),
    expect.objectContaining({
      headers: expect.objectContaining({
        'If-Match': '"0"',
        Authorization: 'Bearer access',
      }),
    }),
  );
});
test('请求超时和取消不暴露网络细节', async () => {
  jest.useFakeTimers();
  global.fetch = jest.fn(
    (_url, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>
          reject(new Error('private socket')),
        );
      }),
  );
  const pending = new AuthApi(
    new ApiClient('http://example.test', 50),
  ).policy();
  const assertion = expect(pending).rejects.toMatchObject({
    code: 'TIMEOUT',
    outcome: 'unknown',
  });
  await jest.advanceTimersByTimeAsync(50);
  await assertion;
});
test('已取消请求不发送', async () => {
  global.fetch = jest.fn();
  const controller = new AbortController();
  controller.abort();
  await expect(
    new AuthApi(new ApiClient('http://example.test')).policy(controller.signal),
  ).rejects.toMatchObject({ outcome: 'not-sent' });
  expect(global.fetch).not.toHaveBeenCalled();
});
// 刷新形状不允许混入登录账号；防止测试替身掩盖真实合同差异。
test('刷新仅接受完整令牌对并拒绝多余账号字段', async () => {
  const auth = new AuthApi(new ApiClient('http://example.test'));
  global.fetch = jest.fn(async () =>
    reply({ accessToken: 'a', refreshToken: 'r' }),
  );
  await expect(auth.refresh('old')).resolves.toEqual({
    accessToken: 'a',
    refreshToken: 'r',
  });
  global.fetch = jest.fn(async () =>
    reply({ accessToken: 'a', refreshToken: 'r', account }),
  );
  await expect(auth.refresh('old')).rejects.toMatchObject({
    code: 'INVALID_RESPONSE',
  });
});
// 在请求发出后卸载，取消结果属于未知，不能展示底层响应或重复认证写入。
test('正在执行的请求取消会中止网络且返回安全错误', async () => {
  const controller = new AbortController();
  global.fetch = jest.fn(
    (_url, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>
          reject(new Error('private socket')),
        );
      }),
  );
  const request = new AuthApi(new ApiClient('http://example.test')).policy(
    controller.signal,
  );
  const assertion = expect(request).rejects.toMatchObject({
    code: 'CANCELLED',
    outcome: 'unknown',
  });
  controller.abort();
  await assertion;
});
// 畸形JSON是响应问题，不能伪装成没有发送的网络问题。
test('不能解析的成功响应映射为安全响应错误', async () => {
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        json: async () => {
          throw new SyntaxError('private response');
        },
      }) as unknown as Response,
  );
  await expect(
    new AuthApi(new ApiClient('http://example.test')).policy(),
  ).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcome: 'unknown' });
});

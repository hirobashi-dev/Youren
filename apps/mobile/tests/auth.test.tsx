import {
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import { AuthScreen } from '../src/screens/AuthScreen';
import { AccountNavigator } from '../src/screens/AccountNavigator';
import { ApiClient } from '../src/api/client';
import { AuthApi } from '../src/api/auth';
import { ProfileApi } from '../src/api/profile';
import { SessionStore } from '../src/auth/sessionStore';
import { account, failure, memoryStorage, reply, session } from './fixtures';
// 删除主动条款、输入校验、失败重试或游客导航会破坏真实画面行为。
const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
  jest.useRealTimers();
});
function setup() {
  const client = new ApiClient('http://example.test');
  const auth = new AuthApi(client);
  const store = new SessionStore(memoryStorage(), auth);
  return { auth, store, profileApi: new ProfileApi(client) };
}
test('邮箱不合法时不发送挑战，验证码必须六位', async () => {
  const props = setup();
  global.fetch = jest.fn(async () =>
    reply({ challengeId: account.id, expiresIn: 600, retryAfter: 0 }, 202),
  );
  render(
    <AuthScreen {...props} onBack={() => undefined} onDone={() => undefined} />,
  );
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'wrong');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  expect(screen.getByText('请输入有效邮箱。')).toBeTruthy();
  expect(global.fetch).not.toHaveBeenCalled();
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'a@example.test');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  await screen.findByText('验证码已发送，请查收邮箱。');
  fireEvent.changeText(screen.getByLabelText('验证码'), '123');
  fireEvent.press(screen.getByRole('button', { name: '登录' }));
  expect(screen.getByText('请输入六位数字验证码。')).toBeTruthy();
  expect(props.store.getSnapshot().account).toBeNull();
});
test('注册条款默认未选，当前服务端版本主动接受后才提交', async () => {
  const props = setup();
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/policy')
      ? reply({
          termsVersion: 'current-terms',
          privacyVersion: 'current-privacy',
        })
      : String(url).endsWith('/email-code')
        ? reply({ challengeId: account.id, expiresIn: 600, retryAfter: 0 }, 202)
        : reply(session, 201),
  );
  render(
    <AuthScreen {...props} onBack={() => undefined} onDone={() => undefined} />,
  );
  fireEvent.press(screen.getByRole('button', { name: '创建账号' }));
  await screen.findByRole('checkbox');
  expect(screen.getByRole('checkbox').props.accessibilityState.checked).toBe(
    false,
  );
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'a@example.test');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  await screen.findByText('验证码已发送，请查收邮箱。');
  fireEvent.changeText(screen.getByLabelText('验证码'), '123456');
  fireEvent.press(screen.getByRole('button', { name: '注册' }));
  expect(screen.getByText('请先阅读并主动同意条款与隐私说明。')).toBeTruthy();
  fireEvent.press(screen.getByRole('checkbox'));
  fireEvent.press(screen.getByRole('button', { name: '注册' }));
  await waitFor(() => expect(props.store.getSnapshot().status).toBe('ready'));
  expect(global.fetch).toHaveBeenCalledWith(
    'http://example.test/v1/auth/register',
    expect.objectContaining({
      body: '{"challengeId":"11111111-1111-4111-8111-111111111111","code":"123456","termsVersion":"current-terms","privacyVersion":"current-privacy"}',
    }),
  );
});
test.each([
  [429, 'RATE_LIMITED', '操作太频繁'],
  [503, 'TEMPORARILY_UNAVAILABLE', '服务暂时不可用'],
])('验证码错误%s展示安全信息且允许恢复', async (status, code, message) => {
  const props = setup();
  global.fetch = jest.fn(async () => failure(Number(status), String(code)));
  render(
    <AuthScreen {...props} onBack={() => undefined} onDone={() => undefined} />,
  );
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'a@example.test');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  await screen.findByText(new RegExp(String(message)));
  expect(screen.queryByText(/secret/)).toBeNull();
});
test('游客浏览保留首页入口，主动进入登录，成功后进入资料', async () => {
  const props = setup();
  global.fetch = jest.fn(async (url) =>
    String(url).includes('/health/')
      ? ({ ok: true, json: async () => ({ status: 'ok' }) } as Response)
      : String(url).endsWith('/email-code')
        ? reply({ challengeId: account.id, expiresIn: 600, retryAfter: 0 }, 202)
        : String(url).endsWith('/me')
          ? reply(account)
          : reply(session),
  );
  render(<AccountNavigator {...props} />);
  await screen.findByText('连接成功');
  fireEvent.press(screen.getByRole('button', { name: '登录或注册' }));
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'a@example.test');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  await screen.findByText('验证码已发送，请查收邮箱。');
  fireEvent.changeText(screen.getByLabelText('验证码'), '123456');
  fireEvent.press(screen.getByRole('button', { name: '登录' }));
  await screen.findByText('完善普通资料');
  expect(screen.queryByText(/恋爱|年龄声明/)).toBeNull();
});
// 冷却与提交锁阻止重复请求；服务恢复后用户能继续操作真实表单。
test('验证码发送期间和冷却期间重复点击均不再发送', async () => {
  const props = setup();
  let finish!: (response: Response) => void;
  global.fetch = jest.fn(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  render(
    <AuthScreen {...props} onBack={() => undefined} onDone={() => undefined} />,
  );
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'a@example.test');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  finish(
    reply({ challengeId: account.id, expiresIn: 600, retryAfter: 60 }, 202),
  );
  await screen.findByText('验证码已发送，请查收邮箱。');
  const button = await screen.findByRole('button', { name: /秒后可重新发送/ });
  fireEvent.press(button);
  expect(button.props.accessibilityState.disabled).toBe(true);
  expect(global.fetch).toHaveBeenCalledTimes(1);
});
// 登录与接续不是同一成功边界：接续失败仍保留登录，并展示稳定重试入口。
test('登录成功接续失败仍显示账号，显式重试后移除接续入口', async () => {
  const props = setup();
  global.fetch = jest.fn(async () =>
    reply(
      {
        guestToken: 'guest-secret',
        principalId: account.id,
        expiresAt: '2026-11-04T00:00:00Z',
      },
      201,
    ),
  );
  await props.store.createGuest();
  global.fetch = jest.fn(async (url) =>
    String(url).includes('/health/')
      ? ({ ok: true, json: async () => ({ status: 'ok' }) } as Response)
      : String(url).endsWith('/email-code')
        ? reply({ challengeId: account.id, expiresIn: 600, retryAfter: 0 }, 202)
        : String(url).endsWith('/guest-claims')
          ? reply({ claimedContentCounts: { posts: 0, comments: 0 } })
          : reply({
              ...session,
              account: { ...account, onboardingRequired: false },
              onboardingRequired: false,
              claimStatus: 'failed',
            }),
  );
  render(<AccountNavigator {...props} />);
  await screen.findByText('游客浏览');
  fireEvent.press(screen.getByRole('button', { name: '登录或注册' }));
  fireEvent.changeText(screen.getByLabelText('邮箱'), 'a@example.test');
  fireEvent.press(screen.getByRole('button', { name: '发送验证码' }));
  await screen.findByText('验证码已发送，请查收邮箱。');
  fireEvent.changeText(screen.getByLabelText('验证码'), '123456');
  fireEvent.press(screen.getByRole('button', { name: '登录' }));
  await screen.findByText('我的账号');
  fireEvent.press(screen.getByRole('button', { name: '重试接续游客身份' }));
  await screen.findByText('游客身份已接续。');
  expect(screen.queryByRole('button', { name: '重试接续游客身份' })).toBeNull();
});

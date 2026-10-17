import {
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import { AccountNavigator } from '../src/screens/AccountNavigator';
import { ApiClient } from '../src/api/client';
import { AuthApi } from '../src/api/auth';
import { ProfileApi } from '../src/api/profile';
import { SessionStore, SESSION_KEY } from '../src/auth/sessionStore';
import {
  account,
  failure,
  memoryStorage,
  profile,
  reply,
  session,
} from './fixtures';

const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
});

// 资料提交遇到401时验证导航切换不丢失用户尚未保存的输入。
test('资料提交401触发刷新期间保留编辑并在新令牌上重试', async () => {
  const client = new ApiClient('http://example.test');
  const storage = memoryStorage();
  const store = new SessionStore(storage, new AuthApi(client));
  await store.accept(session);

  let refreshCount = 0;
  let saveCount = 0;
  let completeRefresh!: (value: Response) => void;
  const refreshGate = new Promise<Response>((resolve) => {
    completeRefresh = resolve;
  });
  global.fetch = jest.fn(async (url) => {
    const path = String(url);
    if (path.endsWith('/auth/refresh')) {
      refreshCount += 1;
      return refreshCount === 1
        ? reply({ accessToken: 'access-two', refreshToken: 'refresh-two' })
        : refreshGate;
    }
    if (path.endsWith('/me/profile')) {
      saveCount += 1;
      if (saveCount === 1) return failure(401, 'AUTH_REQUIRED');
      return reply({
        profile: { ...profile, moderationStatus: 'pending' },
        version: 1,
      });
    }
    if (path.endsWith('/me')) return reply(account);
    throw new Error(`未预期的接口：${path}`);
  });

  render(
    <AccountNavigator
      store={store}
      auth={new AuthApi(client)}
      profileApi={new ProfileApi(client)}
    />,
  );
  await screen.findByText('我的账号');
  fireEvent.press(screen.getByRole('button', { name: '编辑资料' }));
  await screen.findByText('完善普通资料');
  await waitFor(() =>
    expect(screen.getByLabelText('昵称').props.value).toBe(''),
  );
  fireEvent.changeText(screen.getByLabelText('昵称'), '正在编辑的新昵称');
  fireEvent.changeText(screen.getByLabelText('简介'), '尚未提交的简介');
  fireEvent.press(screen.getByRole('button', { name: '保存资料' }));

  // 刷新未返回时私有编辑画面应隐藏，但会话恢复后原编辑与提交都要继续。
  await screen.findByText('游客浏览');
  expect(screen.queryByText('正在编辑的新昵称')).toBeNull();
  completeRefresh(
    reply({ accessToken: 'access-three', refreshToken: 'refresh-three' }),
  );

  await screen.findByText('资料已保存，待审核后公开。');
  expect(screen.getByLabelText('昵称').props.value).toBe('正在编辑的新昵称');
  expect(saveCount).toBe(2);
  expect(refreshCount).toBe(2);
  expect(JSON.parse(storage.values.get(SESSION_KEY) ?? '{}').refreshToken).toBe(
    'refresh-three',
  );
});

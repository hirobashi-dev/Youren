import {
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import { ProfileScreen } from '../src/screens/ProfileScreen';
import { PublicProfileScreen } from '../src/screens/PublicProfileScreen';
import { SettingsScreen } from '../src/screens/SettingsScreen';
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
// 实际编辑/冲突/隐私/退出行为使用完整HTTP合同夹具。
const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
});
async function setup() {
  const client = new ApiClient('http://example.test');
  const storage = memoryStorage();
  const store = new SessionStore(storage, new AuthApi(client));
  await store.accept(session);
  return {
    store,
    storage,
    profileApi: new ProfileApi(client),
    onBack: () => undefined,
  };
}
test('首次资料读取后保存，待审草稿不显示发布成功', async () => {
  const props = await setup();
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/me')
      ? reply(account)
      : reply({
          profile: { ...profile, moderationStatus: 'pending' },
          version: 1,
        }),
  );
  render(<ProfileScreen {...props} />);
  await screen.findByText('完善普通资料');
  fireEvent.changeText(screen.getByLabelText('昵称'), '小林');
  fireEvent.changeText(screen.getByLabelText('简介'), '在日本散步');
  fireEvent.press(screen.getByRole('button', { name: '保存资料' }));
  await screen.findByText('资料已保存，待审核后公开。');
  expect(screen.queryByText('发布成功')).toBeNull();
  expect(props.store.getSnapshot().account?.profileVersion).toBe(1);
});
test.each([
  [412, 'VERSION_CONFLICT'],
  [428, 'PRECONDITION_REQUIRED'],
])('版本错误%s保留输入并提供重新读取', async (status, code) => {
  const props = await setup();
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/me')
      ? reply(account)
      : failure(Number(status), String(code)),
  );
  render(<ProfileScreen {...props} />);
  await screen.findByText('完善普通资料');
  fireEvent.changeText(screen.getByLabelText('昵称'), '未丢失的昵称');
  fireEvent.press(screen.getByRole('button', { name: '保存资料' }));
  await screen.findByText(/重新读取/);
  expect(screen.getByLabelText('昵称').props.value).toBe('未丢失的昵称');
  expect(screen.getByRole('button', { name: '重新读取资料' })).toBeTruthy();
});
test('资料503加载失败可重新读取，空昵称不能保存', async () => {
  const props = await setup();
  global.fetch = jest.fn(async () => failure(503, 'TEMPORARILY_UNAVAILABLE'));
  render(<ProfileScreen {...props} />);
  await screen.findByText(/服务暂时不可用/);
  global.fetch = jest.fn(async () => reply(account));
  fireEvent.press(screen.getByRole('button', { name: '重新读取资料' }));
  await screen.findByText('完善普通资料');
  fireEvent.changeText(screen.getByLabelText('昵称'), '\u00a0 ');
  fireEvent.press(screen.getByRole('button', { name: '保存资料' }));
  expect(screen.getByText('请填写昵称。')).toBeTruthy();
});
test('初始草稿公开404提供真实未公开提示，不暴露本人邮箱', async () => {
  const props = await setup();
  global.fetch = jest.fn(async () => failure(404, 'NOT_FOUND'));
  render(<PublicProfileScreen {...props} accountId={account.id} />);
  await screen.findByText('资料尚未公开，审核通过后才能在这里看到。');
  expect(screen.queryByText(account.email)).toBeNull();
});
test('普通公开主页只展示公开资料，没有邮箱和审核状态', async () => {
  const props = await setup();
  global.fetch = jest.fn(async () => reply(profile));
  render(<PublicProfileScreen {...props} accountId={account.id} />);
  await screen.findByText('小林');
  expect(screen.queryByText(account.email)).toBeNull();
  expect(screen.queryByText(/pending|恋爱|年龄/)).toBeNull();
});
test('设置变更等待服务端成功，离线退出也清除本机秘密', async () => {
  const props = await setup();
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/me')
      ? reply(account)
      : String(url).endsWith('/settings')
        ? reply({ ...account.settings, allowStrangerDm: false })
        : failure(503, 'TEMPORARILY_UNAVAILABLE'),
  );
  render(<SettingsScreen {...props} />);
  await screen.findByRole('switch', { name: '允许陌生人私聊' });
  fireEvent(
    screen.getByRole('switch', { name: '允许陌生人私聊' }),
    'valueChange',
    false,
  );
  await waitFor(() =>
    expect(
      screen.getByRole('switch', { name: '允许陌生人私聊' }).props.value,
    ).toBe(false),
  );
  fireEvent.press(screen.getByRole('button', { name: '退出登录' }));
  await waitFor(() => expect(props.store.getSnapshot().status).toBe('guest'));
  expect(props.storage.values.has(SESSION_KEY)).toBe(false);
});
// 服务拒绝设置修改时控件和本人资料均保留原值，不虚构保存成功。
test('设置503失败保留原通知偏好并显示恢复提示', async () => {
  const props = await setup();
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/me')
      ? reply(account)
      : failure(503, 'TEMPORARILY_UNAVAILABLE'),
  );
  render(<SettingsScreen {...props} />);
  await screen.findByRole('switch', { name: '接收推送通知' });
  fireEvent(
    screen.getByRole('switch', { name: '接收推送通知' }),
    'valueChange',
    false,
  );
  await screen.findByText(/服务暂时不可用/);
  expect(screen.getByRole('switch', { name: '接收推送通知' }).props.value).toBe(
    true,
  );
  expect(props.store.getSnapshot().account?.settings.pushEnabled).toBe(true);
});

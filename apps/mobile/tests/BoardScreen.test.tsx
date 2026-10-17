import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { BoardScreen } from '../src/screens/BoardScreen';
import { BoardApi } from '../src/api/board';
import { ApiClient } from '../src/api/client';
import { AuthApi } from '../src/api/auth';
import { ProfileApi } from '../src/api/profile';
import { AccountNavigator } from '../src/screens/AccountNavigator';
import { GUEST_KEY, SessionStore } from '../src/auth/sessionStore';
import { memoryStorage } from './fixtures';

// B01首页突出公开讨论；空结果保持真实空态，并提供搜索、发帖和主导航入口。
test('留言板首页按B01展示导航、筛选摘要和真实空列表', async () => {
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [],
          page: { nextCursor: null, hasMore: false },
          requestId: 'home',
        }),
      }) as Response,
  );
  const client = new ApiClient('http://example.test');
  const store = new SessionStore(memoryStorage(), new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );

  expect(await screen.findByText('留言板首页')).toBeTruthy();
  expect(screen.getByRole('button', { name: '搜索 · 地区' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '全部' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '兴趣' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '同城' })).toBeTruthy();
  expect(screen.getByText('最新 ↓')).toBeTruthy();
  expect(
    screen.getByText(
      '还没有公开留言。你可以写下第一条，审核通过后大家就能看到。',
    ),
  ).toBeTruthy();
  expect(screen.getByText('发现')).toBeTruthy();
  expect(screen.getByText('消息')).toBeTruthy();
  expect(screen.getByText('我的')).toBeTruthy();
  expect(screen.getByRole('button', { name: '发帖' })).toBeTruthy();
  expect(
    screen.getByRole('tab', { name: '留言板' }).props.accessibilityState
      .selected,
  ).toBe(true);
});

// B02搜索词通过公开帖子接口的q参数筛选，不能只改变输入框或本地空态。
test('搜索筛选提交关键词并返回留言板首页', async () => {
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [],
          page: { nextCursor: null, hasMore: false },
          requestId: 'search',
        }),
      }) as Response,
  );
  const client = new ApiClient('http://example.test');
  const store = new SessionStore(memoryStorage(), new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );

  fireEvent.press(await screen.findByRole('button', { name: '搜索 · 地区' }));
  expect(screen.getByTestId('page-back-chevron')).toBeTruthy();
  fireEvent.changeText(screen.getByLabelText('搜索讨论'), '河边散步');
  fireEvent.press(screen.getByRole('button', { name: '应用筛选' }));

  await screen.findByText('留言板首页');
  expect(global.fetch).toHaveBeenCalledWith(
    expect.stringContaining('/posts?q=%E6%B2%B3%E8%BE%B9%E6%95%A3%E6%AD%A5'),
    expect.anything(),
  );
});

// B04发布页说明游客身份、审核规则与图片上限，避免表单缺少设计稿中的关键提示。
test('发布讨论页展示游客说明、审核提示和图片数量限制', async () => {
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [],
          page: { nextCursor: null, hasMore: false },
          requestId: 'compose',
        }),
      }) as Response,
  );
  const client = new ApiClient('http://example.test');
  const storage = memoryStorage();
  storage.values.set(
    GUEST_KEY,
    JSON.stringify({
      token: 'guest-token',
      key: '33333333-3333-4333-8333-333333333333',
    }),
  );
  const store = new SessionStore(storage, new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );

  fireEvent.press(await screen.findByRole('button', { name: '发帖' }));

  expect(await screen.findByRole('header', { name: '发布讨论' })).toBeTruthy();
  expect(screen.getByTestId('page-back-chevron')).toBeTruthy();
  expect(screen.getByText(/游客发布 · 无需注册/)).toBeTruthy();
  expect(screen.getByText(/首次提交后进入人工审核/)).toBeTruthy();
  expect(screen.getByText(/最多 9 张/)).toBeTruthy();
  expect(screen.getByText('标题 *')).toBeTruthy();
  expect(screen.getByText('正文 *')).toBeTruthy();
  expect(screen.getByText('1–2000 字')).toBeTruthy();
  expect(screen.getByText('图片')).toBeTruthy();
  expect(screen.getByRole('button', { name: '保存草稿' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '发布讨论' })).toBeTruthy();
  fireEvent.changeText(screen.getByLabelText('标题 *'), '我的草稿标题');
  fireEvent.changeText(screen.getByLabelText('正文 *'), '草稿正文');
  fireEvent.press(screen.getByRole('button', { name: '保存草稿' }));
  fireEvent.press(screen.getByRole('button', { name: '返回首页' }));
  fireEvent.press(screen.getByRole('button', { name: '发帖' }));
  expect((await screen.findByLabelText('标题 *')).props.value).toBe(
    '我的草稿标题',
  );
  expect(screen.getByLabelText('正文 *').props.value).toBe('草稿正文');
});

// B05从回复卡片进入独立编辑页，并显示引用目标与稳定游客身份说明。
test('帖子详情可打开带引用上下文的独立回复页', async () => {
  const postId = '11111111-1111-4111-8111-111111111111';
  const commentId = '22222222-2222-4222-8222-222222222222';
  global.fetch = jest.fn(async (input) => {
    const url = String(input);
    const data = url.includes(`/posts/${postId}/comments`)
      ? [
          {
            id: commentId,
            postId,
            replyToId: null,
            body: '上周傍晚去过，夕阳很漂亮。',
            authorDisplay: { kind: 'guest', label: '游客 B' },
            images: [],
            publishedAt: '2026-10-10T00:00:00Z',
            expiresAt: '2027-10-10T00:00:00Z',
            capabilities: { canRead: true, canSend: true },
          },
        ]
      : [
          {
            id: postId,
            title: '周末散步',
            body: '想找人一起逛公园。',
            authorDisplay: { kind: 'guest', label: '游客 A' },
            region: null,
            interests: [],
            images: [],
            replyCount: 1,
            publishedAt: '2026-10-10T00:00:00Z',
            expiresAt: '2027-10-10T00:00:00Z',
            capabilities: { canRead: true, canSend: true },
          },
        ];
    return {
      ok: true,
      status: 200,
      headers: new Headers(),
      json: async () => ({
        data,
        page: { nextCursor: null, hasMore: false },
        requestId: 'reply',
      }),
    } as Response;
  });
  const client = new ApiClient('http://example.test');
  const storage = memoryStorage();
  storage.values.set(
    GUEST_KEY,
    JSON.stringify({
      token: 'guest-token',
      key: '33333333-3333-4333-8333-333333333333',
    }),
  );
  const store = new SessionStore(storage, new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );

  fireEvent.press(
    await screen.findByRole('button', { name: '打开留言：周末散步' }),
  );
  fireEvent.press(await screen.findByRole('button', { name: '回复这条内容' }));

  expect(await screen.findByText('回复讨论')).toBeTruthy();
  expect(
    screen.getByText('回复 游客 B：上周傍晚去过，夕阳很漂亮。'),
  ).toBeTruthy();
  expect(screen.getByText(/以游客身份回复/)).toBeTruthy();
  expect(screen.getByText(/最多 3 张/)).toBeTruthy();
});

// R01按设计稿展示明确的举报类别、目标和处置边界，避免误导用户以为会自动删除。
test('帖子举报页明确显示对象、分类和人工处理说明', async () => {
  const postId = '11111111-1111-4111-8111-111111111111';
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [
            {
              id: postId,
              title: '周末散步',
              body: '想找人一起逛公园。',
              authorDisplay: { kind: 'guest', label: '游客 A' },
              region: null,
              interests: [],
              images: [],
              replyCount: 0,
              publishedAt: '2026-10-10T00:00:00Z',
              expiresAt: '2027-10-10T00:00:00Z',
              capabilities: { canRead: true, canSend: true },
            },
          ],
          page: { nextCursor: null, hasMore: false },
          requestId: 'report',
        }),
      }) as Response,
  );
  const client = new ApiClient('http://example.test');
  const store = new SessionStore(memoryStorage(), new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );

  fireEvent.press(
    await screen.findByRole('button', { name: '打开留言：周末散步' }),
  );
  fireEvent.press(await screen.findByRole('button', { name: '举报这条留言' }));

  expect(await screen.findByText('举报内容')).toBeTruthy();
  expect(screen.getByText('举报对象：周末散步')).toBeTruthy();
  expect(screen.getByRole('button', { name: '垃圾广告 / 刷屏' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '诈骗 / 虚假信息' })).toBeTruthy();
  expect(screen.getByText(/提交表示受理，不代表内容会立即删除/)).toBeTruthy();
});

// 公开列表使用服务端公开数据；游客未保存身份时可浏览但不能直接创建内容。
test('留言板加载公开帖子并提示游客先保存身份', async () => {
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [
            {
              id: '11111111-1111-4111-8111-111111111111',
              title: '周末散步',
              body: '想找人一起逛公园。',
              authorDisplay: { kind: 'guest', label: '游客' },
              region: null,
              interests: [],
              images: [],
              replyCount: 0,
              publishedAt: '2026-10-10T00:00:00Z',
              expiresAt: '2027-10-10T00:00:00Z',
              capabilities: { canRead: true, canSend: true },
            },
          ],
          page: { nextCursor: null, hasMore: false },
          requestId: 'req',
        }),
      }) as Response,
  );
  const store = new SessionStore(
    memoryStorage(),
    new AuthApi(new ApiClient('http://example.test')),
  );
  await store.restore();
  render(
    <BoardScreen
      api={new BoardApi(new ApiClient('http://example.test'))}
      store={store}
      onBack={() => {}}
    />,
  );
  await screen.findByText('周末散步');
  fireEvent.press(screen.getByRole('button', { name: '发帖' }));
  expect(
    await screen.findAllByText('保存游客身份后即可发布内容。'),
  ).toHaveLength(1);
});

// 地区和兴趣选择展示人类可读名称，用户不需要输入数据库内部代码。
test('从公开字典选择都道府县和兴趣标签', async () => {
  const prefecture = {
    code: 'JP-13',
    parentCode: null,
    level: 'prefecture',
    nameJa: '東京都',
    nameZh: '东京都',
  };
  const municipality = {
    code: 'JP-13-101',
    parentCode: 'JP-13',
    level: 'municipality',
    nameJa: '千代田区',
    nameZh: '千代田区',
  };
  const interest = {
    id: '22222222-2222-4222-8222-222222222222',
    code: 'walk',
    nameZh: '散步',
  };
  global.fetch = jest.fn(async (input) => {
    const url = String(input);
    const data = url.includes('/regions?parentCode=JP-13')
      ? [municipality]
      : url.endsWith('/regions')
        ? [prefecture]
        : url.endsWith('/interests')
          ? [interest]
          : [];
    return {
      ok: true,
      status: 200,
      headers: new Headers(),
      json: async () => ({
        data,
        page: { nextCursor: null, hasMore: false },
        requestId: 'catalog',
      }),
    } as Response;
  });
  const client = new ApiClient('http://example.test');
  const storage = memoryStorage();
  storage.values.set(
    GUEST_KEY,
    JSON.stringify({
      token: 'guest-token',
      key: '33333333-3333-4333-8333-333333333333',
    }),
  );
  const store = new SessionStore(storage, new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );
  fireEvent.press(await screen.findByRole('button', { name: '搜索 · 地区' }));
  fireEvent.press(await screen.findByRole('button', { name: '不限地区' }));
  fireEvent.press(
    await screen.findByRole('button', { name: '东京都 · 東京都' }),
  );
  fireEvent.press(
    await screen.findByRole('button', { name: '千代田区 · 千代田区' }),
  );
  fireEvent.press(screen.getByRole('button', { name: '应用筛选' }));
  fireEvent.press(screen.getByRole('button', { name: '发帖' }));
  expect(await screen.findByText('已选择：千代田区')).toBeTruthy();
  fireEvent.press(screen.getByRole('button', { name: '选择兴趣标签' }));
  fireEvent.press(screen.getByRole('button', { name: '散步' }));
  expect(screen.getByRole('button', { name: '散步' })).toBeTruthy();
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
});

// 首页入口对游客始终可用，进入留言板后只读取公开帖子，不要求自动创建游客账号。
test('游客从首页进入留言板并查看公开内容', async () => {
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/health/ready')
      ? ({ ok: true, json: async () => ({ status: 'ok' }) } as Response)
      : ({
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => ({
            data: [],
            page: { nextCursor: null, hasMore: false },
            requestId: 'req',
          }),
        } as Response),
  );
  const client = new ApiClient('http://example.test');
  const auth = new AuthApi(client);
  const store = new SessionStore(memoryStorage(), auth);
  render(
    <AccountNavigator
      store={store}
      auth={auth}
      profileApi={new ProfileApi(client)}
      boardApi={new BoardApi(client)}
    />,
  );
  await screen.findByText('游客浏览');
  fireEvent.press(screen.getByRole('button', { name: '留言板' }));
  expect(
    await screen.findByText(
      '还没有公开留言。你可以写下第一条，审核通过后大家就能看到。',
    ),
  ).toBeTruthy();
});

// 本人内容的类型与审核状态是两组独立筛选；标题和摘要应明确说明组合结果。
test('我的内容清楚区分内容类型和审核状态筛选', async () => {
  const storage = memoryStorage();
  storage.values.set(
    GUEST_KEY,
    JSON.stringify({
      token: 'guest-token',
      key: '33333333-3333-4333-8333-333333333333',
    }),
  );
  global.fetch = jest.fn(
    async () =>
      ({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [],
          page: { nextCursor: null, hasMore: false },
          requestId: 'req',
        }),
      }) as Response,
  );
  const client = new ApiClient('http://example.test');
  const store = new SessionStore(storage, new AuthApi(client));
  await store.restore();
  render(
    <BoardScreen api={new BoardApi(client)} store={store} onBack={() => {}} />,
  );

  fireEvent.press(await screen.findByRole('tab', { name: '我的' }));
  expect(await screen.findByText('内容类型')).toBeTruthy();
  expect(screen.getByText('审核状态')).toBeTruthy();
  expect(
    screen.getByRole('button', { name: '我的留言' }).props.accessibilityState
      .selected,
  ).toBe(true);
  expect(
    screen.getByRole('button', { name: '我的回复' }).props.accessibilityState
      .selected,
  ).toBe(false);
  expect(
    screen.getByRole('button', { name: '全部状态' }).props.accessibilityState
      .selected,
  ).toBe(true);
  expect(screen.getByLabelText('正在查看：我的留言 · 全部状态')).toBeTruthy();

  fireEvent.press(screen.getByRole('button', { name: '我的回复' }));
  expect(
    screen.getByRole('button', { name: '我的留言' }).props.accessibilityState
      .selected,
  ).toBe(false);
  expect(
    screen.getByRole('button', { name: '我的回复' }).props.accessibilityState
      .selected,
  ).toBe(true);
  expect(
    await screen.findByLabelText('正在查看：我的回复 · 全部状态'),
  ).toBeTruthy();
  fireEvent.press(screen.getByRole('button', { name: '待审核' }));
  expect(
    screen.getByRole('button', { name: '全部状态' }).props.accessibilityState
      .selected,
  ).toBe(false);
  expect(
    screen.getByRole('button', { name: '待审核' }).props.accessibilityState
      .selected,
  ).toBe(true);
  expect(
    await screen.findByLabelText('正在查看：我的回复 · 待审核'),
  ).toBeTruthy();
});

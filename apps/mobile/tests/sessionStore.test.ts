import { ApiClient, ApiError } from '../src/api/client';
import { AuthApi } from '../src/api/auth';
import { SessionStore, SESSION_KEY, GUEST_KEY } from '../src/auth/sessionStore';
import { account, failure, memoryStorage, reply, session } from './fixtures';
// 真实状态机配受控网络/存储；不允许重复旧刷新秘密或退出后复活。
const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
  jest.useRealTimers();
});
function setup(online = () => true) {
  const storage = memoryStorage();
  const store = new SessionStore(
    storage,
    new AuthApi(new ApiClient('http://example.test')),
    online,
  );
  return { store, storage };
}
// 匿名浏览不创建主体；只有用户主动保存游客身份后才允许留言板写操作携带凭证。
test('游客内容凭证仅在主动保存身份后提供', async () => {
  const { store } = setup();
  await store.restore();
  expect(store.contentCredentials()).toBeNull();
  global.fetch = jest.fn(async () => reply({
    guestToken: 'guest-secret',
    principalId: '22222222-2222-4222-8222-222222222222',
    expiresAt: '2027-01-01T00:00:00Z',
  }));
  await store.createGuest();
  expect(store.contentCredentials()).toEqual({ guestToken: 'guest-secret' });
});
test('启动刷新后原子保存新令牌，并发调用只消费一次', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  let resolve!: (response: Response) => void;
  global.fetch = jest.fn((url) =>
    String(url).endsWith('/refresh')
      ? new Promise<Response>((r) => {
          resolve = r;
        })
      : Promise.resolve(reply(account)),
  );
  const a = store.refresh();
  const b = store.refresh();
  await new Promise((r) => setTimeout(r, 0));
  expect(JSON.parse(storage.values.get(SESSION_KEY)!)).toMatchObject({
    refreshPending: true,
  });
  resolve(reply({ accessToken: 'access-two', refreshToken: 'refresh-two' }));
  await Promise.all([a, b]);
  expect(
    (global.fetch as jest.Mock).mock.calls.filter(([url]) =>
      String(url).endsWith('/refresh'),
    ),
  ).toHaveLength(1);
  expect(store.getSnapshot().status).toBe('ready');
  expect(JSON.parse(storage.values.get(SESSION_KEY)!)).toEqual({
    accessToken: 'access-two',
    refreshToken: 'refresh-two',
    refreshPending: false,
  });
});
test('明确离线不发送且保留凭证，联网可恢复', async () => {
  let online = false;
  const { store, storage } = setup(() => online);
  await store.accept(session);
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? reply({ accessToken: 'access-two', refreshToken: 'refresh-two' })
      : reply(account),
  );
  await store.refresh();
  expect(store.getSnapshot().status).toBe('offline');
  expect(global.fetch).not.toHaveBeenCalled();
  expect(storage.values.has(SESSION_KEY)).toBe(true);
  online = true;
  await store.refresh();
  expect(store.getSnapshot().status).toBe('ready');
});
test('503确认未消费刷新令牌，保留后可重试', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  global.fetch = jest.fn(async () => failure(503, 'TEMPORARILY_UNAVAILABLE'));
  await store.refresh();
  expect(store.getSnapshot().status).toBe('offline');
  expect(JSON.parse(storage.values.get(SESSION_KEY)!)).toMatchObject({
    refreshPending: false,
  });
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? reply({ accessToken: 'access-two', refreshToken: 'refresh-two' })
      : reply(account),
  );
  await store.refresh();
  expect(store.getSnapshot().status).toBe('ready');
});
test.each([401, 403])('明确失效%s清除账号秘密回游客', async (status) => {
  const { store, storage } = setup();
  await store.accept(session);
  global.fetch = jest.fn(async () => failure(status, 'SESSION_REVOKED'));
  await store.refresh();
  expect(store.getSnapshot().status).toBe('guest');
  expect(storage.values.has(SESSION_KEY)).toBe(false);
});
test('刷新响应丢失保留秘密但重新启动与重试均不重放', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  global.fetch = jest.fn(async () => {
    throw new Error('socket closed');
  });
  await store.refresh();
  await store.refresh();
  const restarted = new SessionStore(
    storage,
    new AuthApi(new ApiClient('http://example.test')),
  );
  await restarted.restore();
  expect(global.fetch).toHaveBeenCalledTimes(1);
  expect(restarted.getSnapshot().status).toBe('reauth');
  expect(storage.values.has(SESSION_KEY)).toBe(true);
});
test('退出先清本地，即使晚到刷新成功也不能恢复身份', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  let resolve!: (response: Response) => void;
  global.fetch = jest.fn((_url) =>
    String(_url).endsWith('/refresh')
      ? new Promise<Response>((r) => {
          resolve = r;
        })
      : Promise.resolve({ ok: true, status: 204 } as Response),
  );
  const pending = store.refresh();
  await new Promise((r) => setTimeout(r, 0));
  await store.logout();
  resolve(reply(session));
  await pending;
  expect(store.getSnapshot().status).toBe('guest');
  expect(storage.values.has(SESSION_KEY)).toBe(false);
});
test('安全存储保存失败不能将会话视为成功', async () => {
  const storage = memoryStorage();
  storage.set = async () => {
    throw new Error('keystore');
  };
  const store = new SessionStore(
    storage,
    new AuthApi(new ApiClient('http://example.test')),
  );
  await store.accept(session);
  expect(store.getSnapshot().status).toBe('storage_error');
  expect(store.getSnapshot().account).toBeNull();
});
test('刷新标记保存失败绝不发出请求', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  storage.set = async () => {
    throw new Error('keystore');
  };
  global.fetch = jest.fn();
  await store.refresh();
  expect(global.fetch).not.toHaveBeenCalled();
  expect(store.getSnapshot().status).toBe('storage_error');
});
test('游客接续失败保留独立凭证与稳定重试键，成功才清除', async () => {
  const { store, storage } = setup();
  global.fetch = jest.fn(async () =>
    reply(
      {
        guestToken: 'guest-secret',
        principalId: session.account.id,
        expiresAt: '2026-11-04T00:00:00Z',
      },
      201,
    ),
  );
  await store.createGuest();
  await store.accept({ ...session, claimStatus: 'failed' });
  global.fetch = jest.fn(async () => failure(503, 'TEMPORARILY_UNAVAILABLE'));
  await store.claimGuest();
  expect(storage.values.has(GUEST_KEY)).toBe(true);
  global.fetch = jest.fn(async () =>
    reply({ claimedContentCounts: { posts: 0, comments: 0 } }),
  );
  await store.claimGuest();
  expect(global.fetch).toHaveBeenCalledWith(
    expect.any(String),
    expect.objectContaining({
      headers: expect.objectContaining({
        'Idempotency-Key': session.account.id,
      }),
    }),
  );
  expect(storage.values.has(GUEST_KEY)).toBe(false);
  expect(store.getSnapshot().claimPending).toBe(false);
});
// 服务端刷新只返回令牌；启动必须持久化后再读取本人，不能靠伪造账号夹具恢复。
test('重新启动按真实刷新令牌响应读取最新本人资料', async () => {
  const { storage } = setup();
  storage.values.set(
    SESSION_KEY,
    JSON.stringify({
      accessToken: 'old-access',
      refreshToken: 'old-refresh',
      refreshPending: false,
    }),
  );
  const latest = { ...account, onboardingRequired: false };
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? reply({ accessToken: 'new-access', refreshToken: 'new-refresh' })
      : reply(latest),
  );
  const restarted = new SessionStore(
    storage,
    new AuthApi(new ApiClient('http://example.test')),
  );
  await restarted.restore();
  expect(restarted.getSnapshot()).toMatchObject({
    status: 'ready',
    account: latest,
  });
  expect(global.fetch).toHaveBeenCalledWith(
    'http://example.test/v1/me',
    expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer new-access' }),
    }),
  );
});
// 第二个旧请求在轮换完成后才收到401，必须复用新访问令牌而非再轮换。
test('并发私有请求迟到401仅触发一次刷新', async () => {
  const { store } = setup();
  await store.accept(session);
  let late!: (response: Response) => void;
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? reply({ accessToken: 'new-access', refreshToken: 'new-refresh' })
      : reply(account),
  );
  let first = true;
  const a = store.authorized(async (token) => {
    if (first) {
      first = false;
      throw new ApiError('AUTH_REQUIRED', 401, 'rejected');
    }
    return token;
  });
  const b = store.authorized(async (token) => {
    if (token === 'access-one') {
      await new Promise<Response>((r) => {
        late = r;
      });
      throw new ApiError('AUTH_REQUIRED', 401, 'rejected');
    }
    return token;
  });
  await a;
  late(reply(account));
  await b;
  expect(
    (global.fetch as jest.Mock).mock.calls.filter(([url]) =>
      String(url).endsWith('/refresh'),
    ),
  ).toHaveLength(1);
  expect(store.accessToken()).toBe('new-access');
});
// 新令牌保存失败时不能继续读取资料，更不能显示持久会话已经恢复。
test('刷新成功但新秘密保存失败显示安全存储失败且不读取本人', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  const set = storage.set;
  storage.set = async (key, value) => {
    if (value.includes('new-access')) throw new Error('keystore');
    await set(key, value);
  };
  global.fetch = jest.fn(async () =>
    reply({ accessToken: 'new-access', refreshToken: 'new-refresh' }),
  );
  await store.refresh();
  expect(store.getSnapshot().status).toBe('storage_error');
  expect(store.getSnapshot().message).toBe('无法安全保存登录信息，请重试。');
  expect(global.fetch).toHaveBeenCalledTimes(1);
});
// 账号限制与游客秘密无效同为403，但只有后者能够删除游客接续凭证。
test('账号受限导致接续403保留游客身份', async () => {
  const { store, storage } = setup();
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
  await store.createGuest();
  await store.accept(session);
  global.fetch = jest.fn(async () => failure(403, 'ACCOUNT_RESTRICTED'));
  await store.claimGuest();
  expect(storage.values.has(GUEST_KEY)).toBe(true);
  expect(store.getSnapshot().claimPending).toBe(true);
});
// 新登录与旧刷新交错时旧身份响应不得覆盖新账号。
test('重新登录成功后旧刷新响应不能覆盖新身份', async () => {
  const { store } = setup();
  await store.accept(session);
  let finish!: (response: Response) => void;
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? new Promise<Response>((resolve) => {
          finish = resolve;
        })
      : reply({
          ...session,
          accessToken: 'login-access',
          refreshToken: 'login-refresh',
        }),
  );
  const refresh = store.refresh();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await store.login('login', account.id, '123456');
  finish(
    reply({
      accessToken: 'old-response-access',
      refreshToken: 'old-response-refresh',
    }),
  );
  await refresh;
  expect(store.accessToken()).toBe('login-access');
});
// 远程退出消息也有身份边界，旧退出请求失败不得覆盖新会话通知。
test('旧退出网络失败不覆盖后来成功的登录消息', async () => {
  const { store } = setup();
  await store.accept(session);
  let finish!: (response: Response) => void;
  global.fetch = jest.fn(
    async () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  const logout = store.logout();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await store.accept(session);
  finish(failure(503, 'TEMPORARILY_UNAVAILABLE'));
  await logout;
  expect(store.getSnapshot()).toMatchObject({ status: 'ready', message: '' });
});
// 资料加载暂时失败必须保留刚轮换的令牌，绝不能恢复已经消费的旧秘密。
test('刷新后本人503保留新令牌且隐藏私密资料', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? reply({ accessToken: 'new-access', refreshToken: 'new-refresh' })
      : failure(503, 'TEMPORARILY_UNAVAILABLE'),
  );
  await store.refresh();
  expect(store.getSnapshot()).toMatchObject({
    status: 'offline',
    account: null,
  });
  expect(JSON.parse(storage.values.get(SESSION_KEY)!)).toEqual({
    accessToken: 'new-access',
    refreshToken: 'new-refresh',
    refreshPending: false,
  });
});
test('刷新后本人明确401清除新秘密', async () => {
  const { store, storage } = setup();
  await store.accept(session);
  global.fetch = jest.fn(async (url) =>
    String(url).endsWith('/refresh')
      ? reply({ accessToken: 'new-access', refreshToken: 'new-refresh' })
      : failure(401, 'AUTH_REQUIRED'),
  );
  await store.refresh();
  expect(store.getSnapshot().status).toBe('guest');
  expect(storage.values.has(SESSION_KEY)).toBe(false);
});
// 超时可能发生在服务端已经轮换之后；pending持久标记阻止旧令牌再次发送。
test('刷新超时保留秘密与pending，重试不重放', async () => {
  jest.useFakeTimers();
  const storage = memoryStorage();
  const store = new SessionStore(
    storage,
    new AuthApi(new ApiClient('http://example.test', 50)),
  );
  await store.accept(session);
  global.fetch = jest.fn(
    (_url, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>
          reject(new Error('closed')),
        );
      }),
  );
  const request = store.refresh();
  await jest.advanceTimersByTimeAsync(50);
  await request;
  await store.refresh();
  expect(store.getSnapshot()).toMatchObject({
    status: 'reauth',
    account: null,
  });
  expect(JSON.parse(storage.values.get(SESSION_KEY)!)).toMatchObject({
    refreshPending: true,
  });
  expect(global.fetch).toHaveBeenCalledTimes(1);
});
test('安全存储读取失败不调用恢复接口', async () => {
  const { store, storage } = setup();
  storage.get = async () => {
    throw new Error('private keystore');
  };
  global.fetch = jest.fn();
  await store.restore();
  expect(store.getSnapshot().status).toBe('storage_error');
  expect(global.fetch).not.toHaveBeenCalled();
});
// 无效游客凭证由服务端明确FORBIDDEN确认，接续失败不影响已经成功的登录。
test('游客凭证明确失效清除接续秘密但保留账号会话', async () => {
  const { store, storage } = setup();
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
  await store.createGuest();
  await store.accept(session);
  global.fetch = jest.fn(async () => failure(403, 'FORBIDDEN'));
  await store.claimGuest();
  expect(store.getSnapshot()).toMatchObject({
    status: 'ready',
    claimPending: false,
  });
  expect(storage.values.has(GUEST_KEY)).toBe(false);
  expect(storage.values.has(SESSION_KEY)).toBe(true);
});
// 一个秘密删除失败也必须尝试另一个，失败期间不允许访问原账号。
test('退出时账号秘密删除失败仍清理游客秘密并封锁访问', async () => {
  const { store, storage } = setup();
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
  await store.createGuest();
  await store.accept(session);
  const remove = storage.remove;
  storage.remove = async (key) => {
    if (key === SESSION_KEY) throw new Error('locked');
    await remove(key);
  };
  await store.logout();
  expect(storage.values.has(GUEST_KEY)).toBe(false);
  expect(store.getSnapshot().status).toBe('storage_error');
  expect(() => store.accessToken()).toThrow();
});
// 游客接续也是私有请求，访问令牌过期后应共享轮换而不是让重试永久失败。
test('游客接续401刷新一次后使用稳定键完成接续', async () => {
  const { store, storage } = setup();
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
  await store.createGuest();
  await store.accept(session);
  global.fetch = jest.fn(async (url, options) => {
    if (String(url).endsWith('/refresh'))
      return reply({ accessToken: 'new-access', refreshToken: 'new-refresh' });
    if (String(url).endsWith('/me')) return reply(account);
    return (options?.headers as Record<string, string>)?.Authorization ===
      'Bearer new-access'
      ? reply({ claimedContentCounts: { posts: 0, comments: 0 } })
      : failure(401, 'AUTH_REQUIRED');
  });
  await store.claimGuest();
  expect(storage.values.has(GUEST_KEY)).toBe(false);
  expect(store.getSnapshot().claimPending).toBe(false);
});

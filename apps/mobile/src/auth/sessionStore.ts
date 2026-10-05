import { AuthApi } from '../api/auth';
import { ApiError, safeMessage } from '../api/client';
import { exact, type AuthSession, type Me, type Policy } from '../api/types';
export const SESSION_KEY = 'youren.session.v1';
export const GUEST_KEY = 'youren.guest.v1';
export type SecretStorage = {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
};
type Credentials = {
  accessToken: string;
  refreshToken: string;
  refreshPending: boolean;
};
type Guest = { token: string; key: string };
export type SessionState = {
  status:
    | 'loading'
    | 'guest'
    | 'ready'
    | 'offline'
    | 'reauth'
    | 'storage_error';
  account: Me | null;
  claimPending: boolean;
  guestSaved: boolean;
  message: string;
};
// 单一秘密对象与串行持久写保证轮换原子性；epoch阻止退出前请求恢复身份。
export class SessionStore {
  private credentials: Credentials | null = null;
  private guest: Guest | null = null;
  private epoch = 0;
  private writes: Promise<unknown> = Promise.resolve();
  private refreshing: Promise<void> | null = null;
  private listeners = new Set<() => void>();
  private state: SessionState = {
    status: 'loading',
    account: null,
    claimPending: false,
    guestSaved: false,
    message: '',
  };
  constructor(
    private readonly storage: SecretStorage,
    private readonly auth: AuthApi,
    private readonly online = () => true,
  ) {}
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private publish(change: Partial<SessionState>) {
    this.state = { ...this.state, ...change };
    this.listeners.forEach((listener) => listener());
  }
  private serialized<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.writes.then(operation, operation);
    this.writes = next.catch(() => undefined);
    return next;
  }
  private storageFailure() {
    this.publish({
      status: 'storage_error',
      account: null,
      message: new ApiError('STORAGE').message,
    });
  }
  accessToken() {
    if (
      this.state.status !== 'ready' ||
      !this.credentials ||
      this.credentials.refreshPending
    )
      throw new ApiError('AUTH_REQUIRED', 401, 'not-sent');
    return this.credentials.accessToken;
  }
  updateAccount(account: Me) {
    if (this.state.status === 'ready') this.publish({ account });
  }
  async restore() {
    const epoch = this.epoch;
    try {
      await this.serialized(async () => {
        const [raw, guestRaw] = await Promise.all([
          this.storage.get(SESSION_KEY),
          this.storage.get(GUEST_KEY),
        ]);
        if (epoch !== this.epoch) return;
        if (guestRaw) {
          const guest: unknown = JSON.parse(guestRaw);
          if (
            !exact(guest, ['token', 'key']) ||
            typeof guest.token !== 'string' ||
            !guest.token ||
            typeof guest.key !== 'string' ||
            !/^[0-9a-f-]{36}$/i.test(guest.key)
          )
            throw new ApiError('STORAGE');
          this.guest = guest as Guest;
        }
        if (raw) {
          const value: unknown = JSON.parse(raw);
          if (
            !exact(value, ['accessToken', 'refreshToken', 'refreshPending']) ||
            typeof value.accessToken !== 'string' ||
            !value.accessToken ||
            typeof value.refreshToken !== 'string' ||
            !value.refreshToken ||
            typeof value.refreshPending !== 'boolean'
          )
            throw new ApiError('STORAGE');
          this.credentials = value as Credentials;
        }
        this.publish({
          guestSaved: !!this.guest,
          claimPending: !!this.guest,
          status: this.credentials ? 'loading' : 'guest',
          account: null,
        });
      });
      if (epoch === this.epoch && this.credentials) await this.refresh();
    } catch {
      if (epoch === this.epoch) this.storageFailure();
    }
  }
  async accept(session: AuthSession, expectedEpoch = ++this.epoch) {
    this.refreshing = null;
    try {
      await this.serialized(async () => {
        if (expectedEpoch !== this.epoch) return;
        const credentials = {
          accessToken: session.accessToken,
          refreshToken: session.refreshToken,
          refreshPending: false,
        };
        await this.storage.set(SESSION_KEY, JSON.stringify(credentials));
        if (expectedEpoch !== this.epoch) return;
        this.credentials = credentials;
        if (session.claimStatus === 'claimed' && this.guest) {
          await this.storage.remove(GUEST_KEY);
          this.guest = null;
        }
        this.publish({
          status: 'ready',
          account: session.account,
          claimPending: !!this.guest,
          guestSaved: !!this.guest,
          message:
            session.claimStatus === 'failed'
              ? '已登录，游客身份接续未完成，可再次尝试。'
              : '',
        });
      });
    } catch {
      if (expectedEpoch === this.epoch) this.storageFailure();
    }
  }
  // 从发起登录起捕获epoch，晚到登录响应同样不能越过退出边界。
  async login(
    purpose: 'login' | 'register',
    challengeId: string,
    code: string,
    policy?: Policy,
    signal?: AbortSignal,
  ) {
    const epoch = ++this.epoch;
    this.refreshing = null;
    const session = await this.auth.authenticate(
      purpose,
      challengeId,
      code,
      policy,
      this.guest?.token,
      signal,
    );
    if (epoch !== this.epoch || signal?.aborted) return;
    await this.accept(session, epoch);
  }
  refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing;
    const pending = this.performRefresh();
    this.refreshing = pending;
    void pending.finally(() => {
      if (this.refreshing === pending) this.refreshing = null;
    });
    return pending;
  }
  private async performRefresh() {
    const epoch = this.epoch;
    if (!this.credentials) {
      this.publish({ status: 'guest', account: null });
      return;
    }
    if (this.credentials.refreshPending) {
      this.publish({
        status: 'reauth',
        account: null,
        message: '上次登录恢复未取得结果，请重新登录以保护账号。',
      });
      return;
    }
    if (!this.online()) {
      this.publish({
        status: 'offline',
        account: null,
        message: '当前无法连接，请联网后恢复登录。',
      });
      return;
    }
    const credentials = this.credentials;
    try {
      // 此写入必须成功并持久化才允许POST；崩溃后保留pending，绝不重放旧秘密。
      await this.serialized(async () => {
        if (epoch !== this.epoch) return;
        await this.storage.set(
          SESSION_KEY,
          JSON.stringify({ ...credentials, refreshPending: true }),
        );
        if (epoch === this.epoch) {
          this.credentials = { ...credentials, refreshPending: true };
          this.publish({ status: 'loading', account: null, message: '' });
        }
      });
    } catch {
      if (epoch === this.epoch) this.storageFailure();
      return;
    }
    if (epoch !== this.epoch) return;
    let rotated = false;
    try {
      const tokens = await this.auth.refresh(credentials.refreshToken);
      if (epoch !== this.epoch) return;
      // 先原子保存新令牌；随后资料读取失败不能回写已经消费的旧刷新令牌。
      await this.serialized(async () => {
        if (epoch !== this.epoch) return;
        const next = { ...tokens, refreshPending: false };
        try {
          await this.storage.set(SESSION_KEY, JSON.stringify(next));
        } catch {
          throw new ApiError('STORAGE');
        }
        if (epoch === this.epoch) {
          this.credentials = next;
          rotated = true;
        }
      });
      if (epoch !== this.epoch) return;
      const account = await this.auth.account(tokens.accessToken);
      if (epoch === this.epoch)
        this.publish({ status: 'ready', account, message: '' });
    } catch (error) {
      if (epoch !== this.epoch) return;
      try {
        await this.serialized(async () => {
          if (epoch !== this.epoch) return;
          if (error instanceof ApiError && error.code === 'STORAGE') {
            this.storageFailure();
          } else if (
            error instanceof ApiError &&
            error.outcome === 'rejected' &&
            [401, 403].includes(error.status)
          ) {
            await this.storage.remove(SESSION_KEY);
            this.credentials = null;
            this.publish({
              status: 'guest',
              account: null,
              message: '登录已失效，请重新登录。',
            });
          } else if (rotated) {
            this.publish({
              status: 'offline',
              account: null,
              message: safeMessage(error),
            });
          } else if (
            error instanceof ApiError &&
            (error.outcome === 'not-sent' ||
              (error.outcome === 'rejected' &&
                [429, 503].includes(error.status)))
          ) {
            await this.storage.set(SESSION_KEY, JSON.stringify(credentials));
            this.credentials = credentials;
            this.publish({
              status: 'offline',
              account: null,
              message: safeMessage(error),
            });
          } else {
            this.publish({
              status: 'reauth',
              account: null,
              message: '未能确认登录恢复结果，请重新登录以保护账号。',
            });
          }
        });
      } catch {
        if (epoch === this.epoch) this.storageFailure();
      }
    }
  }
  async createGuest(signal?: AbortSignal) {
    if (this.guest) return;
    const epoch = this.epoch;
    const guest = await this.auth.createGuest(signal);
    if (epoch !== this.epoch || signal?.aborted) return;
    try {
      await this.serialized(async () => {
        if (epoch !== this.epoch) return;
        const value = { token: guest.guestToken, key: guest.principalId };
        await this.storage.set(GUEST_KEY, JSON.stringify(value));
        if (epoch === this.epoch) {
          this.guest = value;
          this.publish({
            guestSaved: true,
            claimPending: true,
            message: '游客身份已安全保存在本机。',
          });
        }
      });
    } catch {
      this.storageFailure();
    }
  }
  async claimGuest() {
    const epoch = this.epoch;
    const guest = this.guest;
    if (!guest) return;
    try {
      // 接续同样共享401刷新锁，令牌轮换后仍使用原游客秘密与幂等键。
      await this.authorized((token) =>
        this.auth.claimGuest(token, guest.token, guest.key),
      );
      await this.serialized(async () => {
        if (epoch !== this.epoch) return;
        await this.storage.remove(GUEST_KEY);
        this.guest = null;
        this.publish({
          guestSaved: false,
          claimPending: false,
          message: '游客身份已接续。',
        });
      });
    } catch (error) {
      if (epoch !== this.epoch) return;
      // 只有服务端明确拒绝游客秘密才删除；网络错误和未知结果保留同一重试键。
      if (
        error instanceof ApiError &&
        error.outcome === 'rejected' &&
        error.status === 403 &&
        error.code === 'FORBIDDEN'
      ) {
        try {
          await this.serialized(async () => {
            if (epoch !== this.epoch) return;
            await this.storage.remove(GUEST_KEY);
            this.guest = null;
            this.publish({
              guestSaved: false,
              claimPending: false,
              message: '游客身份已失效，无法接续。',
            });
          });
        } catch {
          this.storageFailure();
        }
      } else
        this.publish({
          message:
            error instanceof ApiError
              ? safeMessage(error)
              : new ApiError('STORAGE').message,
        });
    }
  }
  async logout() {
    const token = this.credentials?.accessToken;
    const epoch = ++this.epoch;
    this.refreshing = null;
    this.publish({ status: 'loading', account: null, message: '' });
    try {
      await this.serialized(async () => {
        // 尽力删除两类秘密；一项失败不能阻止另一项清理，内存身份立即失效。
        this.credentials = null;
        this.guest = null;
        const results = await Promise.allSettled([
          this.storage.remove(SESSION_KEY),
          this.storage.remove(GUEST_KEY),
        ]);
        if (results.some((result) => result.status === 'rejected'))
          throw new ApiError('STORAGE');
        this.publish({
          status: 'guest',
          account: null,
          claimPending: false,
          guestSaved: false,
          message: '',
        });
      });
    } catch {
      this.storageFailure();
      return;
    }
    if (token) {
      try {
        await this.auth.logout(token);
      } catch {
        if (epoch === this.epoch)
          this.publish({
            message:
              '本机已退出。暂时无法确认远程退出，请联网后重新登录管理账号。',
          });
      }
    }
  }
  // 私有请求401只合并一次刷新；晚到旧请求不得给新身份重放操作。
  async authorized<T>(operation: (token: string) => Promise<T>): Promise<T> {
    const epoch = this.epoch;
    const token = this.accessToken();
    try {
      return await operation(token);
    } catch (error) {
      if (
        !(error instanceof ApiError) ||
        error.status !== 401 ||
        error.outcome !== 'rejected' ||
        epoch !== this.epoch
      )
        throw error;
      // 旧请求401晚到时已有新令牌；正在刷新则加入同一锁，避免第二次消费。
      if (this.refreshing || this.credentials?.accessToken === token)
        await this.refresh();
      if (epoch !== this.epoch)
        throw new ApiError('AUTH_REQUIRED', 401, 'not-sent');
      return operation(this.accessToken());
    }
  }
}

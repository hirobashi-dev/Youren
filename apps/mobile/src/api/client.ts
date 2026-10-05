import { exact, object } from './types';
// outcome区分未发送、明确拒绝和未知结果；刷新逻辑不能把未知结果当可重试。
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    public readonly status = 0,
    public readonly outcome: 'not-sent' | 'rejected' | 'unknown' = 'unknown',
    public readonly retryAfter = 0,
    public readonly fieldLimit?: { field: 'nickname' | 'bio'; limit: number },
  ) {
    const messages: Record<string, string> = {
      OFFLINE: '当前无法连接，请检查网络。',
      CANCELLED: '操作已取消。',
      TIMEOUT: '等待超时，请检查网络后重试。',
      INVALID_RESPONSE: '暂时无法读取结果，请稍后重试。',
      STORAGE: '无法安全保存登录信息，请重试。',
      AUTH_REQUIRED: '请重新登录。',
      SESSION_REVOKED: '登录已失效，请重新登录。',
      ACCOUNT_RESTRICTED: '当前账号无法使用此功能。',
      RATE_LIMITED: '操作太频繁，请稍后重试。',
      TEMPORARILY_UNAVAILABLE: '服务暂时不可用，请稍后重试。',
      VERSION_CONFLICT: '资料已在其他设备更新，请重新读取后再保存。',
      PRECONDITION_REQUIRED: '请先重新读取资料，再保存修改。',
      VALIDATION_FAILED: '请检查填写的内容。',
      MEDIA_NOT_READY: '头像暂时无法修改。',
      NOT_FOUND: '公开资料暂不可用。',
      FORBIDDEN: '当前无法完成此操作。',
    };
    super(
      fieldLimit
        ? `${fieldLimit.field === 'nickname' ? '昵称' : '简介'}超过当前允许的${fieldLimit.limit}个可见字符，请缩短后重试。`
        : (messages[code] ?? '操作未完成，请稍后重试。'),
    );
  }
}
export const safeMessage = (error: unknown) =>
  error instanceof ApiError ? error.message : '操作未完成，请稍后重试。';
function errorBody(value: unknown) {
  return (
    exact(value, ['code', 'message', 'requestId'], ['details']) &&
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    typeof value.requestId === 'string' &&
    (value.details === undefined ||
      (Array.isArray(value.details) &&
        value.details.every(
          (detail) =>
            exact(detail, ['field', 'reason'], ['limit']) &&
            typeof detail.field === 'string' &&
            typeof detail.reason === 'string' &&
            (detail.limit === undefined ||
              (Number.isSafeInteger(detail.limit) &&
                Number(detail.limit) >= 0)),
        )))
  );
}
export class ApiClient {
  constructor(
    private readonly baseUrl = process.env.EXPO_PUBLIC_API_BASE_URL ??
      'http://127.0.0.1:3000',
    private readonly timeoutMs = 10000,
  ) {}
  async request<T>(
    path: string,
    validate: (data: unknown) => data is T,
    options: {
      method?: string;
      body?: unknown;
      token?: string;
      headers?: Record<string, string>;
      signal?: AbortSignal;
      empty?: boolean;
    } = {},
  ): Promise<T> {
    if (options.signal?.aborted) throw new ApiError('CANCELLED', 0, 'not-sent');
    const controller = new AbortController();
    let timedOut = false;
    const cancel = () => controller.abort();
    options.signal?.addEventListener('abort', cancel, { once: true });
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, this.timeoutMs);
    try {
      const response = await fetch(
        `${this.baseUrl.replace(/\/$/, '')}/v1${path}`,
        {
          method: options.method ?? 'GET',
          headers: {
            Accept: 'application/json',
            ...(options.body !== undefined
              ? { 'Content-Type': 'application/json' }
              : {}),
            ...(options.token
              ? { Authorization: `Bearer ${options.token}` }
              : {}),
            ...options.headers,
          },
          ...(options.body !== undefined
            ? { body: JSON.stringify(options.body) }
            : {}),
          signal: controller.signal,
        },
      );
      if (options.empty && response.status === 204) return undefined as T;
      // JSON解析失败说明已收到畸形响应，不能错误归类为未连接。
      let body: unknown;
      try {
        body = await response.json();
      } catch {
        if (controller.signal.aborted)
          throw new ApiError(timedOut ? 'TIMEOUT' : 'CANCELLED');
        throw new ApiError('INVALID_RESPONSE', response.status);
      }
      if (!response.ok) {
        if (!errorBody(body) || !object(body))
          throw new ApiError('INVALID_RESPONSE', response.status);
        const statuses: Record<number, string> = {
          401: 'AUTH_REQUIRED',
          403: 'FORBIDDEN',
          404: 'NOT_FOUND',
          412: 'VERSION_CONFLICT',
          428: 'PRECONDITION_REQUIRED',
          429: 'RATE_LIMITED',
          503: 'TEMPORARILY_UNAVAILABLE',
        };
        // 同一403可能是账号限制或游客秘密拒绝，保留已知安全码以区分清理边界。
        const code =
          response.status === 403 && body.code === 'ACCOUNT_RESTRICTED'
            ? 'ACCOUNT_RESTRICTED'
            : (statuses[response.status] ?? String(body.code));
        let fieldLimit:
          | { field: 'nickname' | 'bio'; limit: number }
          | undefined;
        if (response.status === 422 && Array.isArray(body.details)) {
          const detail = body.details.find(
            (d) =>
              object(d) &&
              ['nickname', 'bio'].includes(String(d.field)) &&
              Number.isSafeInteger(d.limit) &&
              Number(d.limit) > 0,
          );
          if (object(detail))
            fieldLimit = {
              field: detail.field as 'nickname' | 'bio',
              limit: Number(detail.limit),
            };
        }
        const seconds = Number(response.headers?.get('Retry-After') ?? 0);
        throw new ApiError(
          code,
          response.status,
          'rejected',
          Number.isSafeInteger(seconds) && seconds >= 0 ? seconds : 0,
          fieldLimit,
        );
      }
      if (
        !exact(body, ['data', 'requestId']) ||
        typeof body.requestId !== 'string' ||
        !validate(body.data)
      )
        throw new ApiError('INVALID_RESPONSE');
      return body.data;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(
        timedOut
          ? 'TIMEOUT'
          : controller.signal.aborted
            ? 'CANCELLED'
            : 'OFFLINE',
      );
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', cancel);
    }
  }
}

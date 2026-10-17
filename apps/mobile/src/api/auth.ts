import { ApiClient } from './client';
import {
  isAuthSession,
  isChallenge,
  isGuestClaim,
  isGuestSession,
  isMe,
  isPolicy,
  isRefreshTokens,
  type Policy,
} from './types';
// 认证请求只发送合同字段；挑战用途和条款版本由真实服务端约束。
export class AuthApi {
  constructor(private readonly client: ApiClient) {}
  policy(signal?: AbortSignal) {
    return this.client.request('/auth/policy', isPolicy, { signal });
  }
  sendCode(email: string, purpose: 'login' | 'register', signal?: AbortSignal) {
    return this.client.request('/auth/email-code', isChallenge, {
      method: 'POST',
      body: { email: email.trim(), purpose },
      signal,
    });
  }
  authenticate(
    purpose: 'login' | 'register',
    challengeId: string,
    code: string,
    policy?: Policy,
    guestToken?: string,
    signal?: AbortSignal,
  ) {
    return this.client.request(`/auth/${purpose}`, isAuthSession, {
      method: 'POST',
      body: {
        challengeId,
        code,
        ...(guestToken ? { guestToken } : {}),
        ...(purpose === 'register' && policy ? policy : {}),
      },
      signal,
    });
  }
  refresh(refreshToken: string) {
    return this.client.request('/auth/refresh', isRefreshTokens, {
      method: 'POST',
      body: { refreshToken },
    });
  }
  account(token: string) {
    return this.client.request('/me', isMe, { token });
  }
  logout(token: string) {
    return this.client.request(
      '/auth/logout',
      (value): value is undefined => value === undefined,
      { method: 'POST', token, empty: true },
    );
  }
  createGuest(signal?: AbortSignal) {
    return this.client.request('/guest-sessions', isGuestSession, {
      method: 'POST',
      signal,
    });
  }
  claimGuest(token: string, guestToken: string, key: string) {
    return this.client.request('/me/guest-claims', isGuestClaim, {
      method: 'POST',
      token,
      body: { guestToken },
      headers: { 'Idempotency-Key': key },
    });
  }
}

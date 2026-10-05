import type { Me, AuthSession, PublicProfile } from '../src/api/types';
// 完整合同夹具仅替代外部网络与安全存储，不替代业务行为。
export const account: Me = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'a@example.test',
  status: 'active',
  profile: null,
  profileVersion: null,
  settings: {
    allowStrangerDm: true,
    pushEnabled: true,
    lockscreenPreview: false,
  },
  ageDeclaration: {
    status: 'not_declared',
    declaredAt: null,
    withdrawnAt: null,
  },
  onboardingRequired: true,
};
export const session: AuthSession = {
  accessToken: 'access-one',
  refreshToken: 'refresh-one',
  account,
  onboardingRequired: true,
  claimStatus: 'not_requested',
};
export const profile: PublicProfile = {
  id: account.id,
  nickname: '小林',
  bio: '在日本散步',
  avatar: null,
  region: null,
  interests: [],
  capabilities: { canRead: true, canMessage: false },
};
export function reply(data: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => ({ data, requestId: 'trace' }),
  } as Response;
}
export function failure(status: number, code: string) {
  return {
    ok: false,
    status,
    headers: new Headers({ 'Retry-After': '60' }),
    json: async () => ({
      code,
      message: 'secret internal text',
      requestId: 'trace',
    }),
  } as Response;
}
export function memoryStorage() {
  const values = new Map<string, string>();
  return {
    values,
    get: async (key: string) => values.get(key) ?? null,
    set: async (key: string, value: string) => {
      values.set(key, value);
    },
    remove: async (key: string) => {
      values.delete(key);
    },
  };
}

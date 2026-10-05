import { ApiClient } from './client';
import {
  isMe,
  isProfileVersion,
  isPublicProfile,
  isSettings,
  type ProfileInput,
  type Settings,
} from './types';
// 不读取合同建议长度作为硬上限；服务端可配置限额和字素校验始终权威。
export class ProfileApi {
  constructor(private readonly client: ApiClient) {}
  me(token: string, signal?: AbortSignal) {
    return this.client.request('/me', isMe, { token, signal });
  }
  save(
    token: string,
    version: number,
    body: ProfileInput,
    signal?: AbortSignal,
  ) {
    return this.client.request('/me/profile', isProfileVersion, {
      method: 'PATCH',
      token,
      body,
      headers: { 'If-Match': `"${version}"` },
      signal,
    });
  }
  settings(token: string, body: Partial<Settings>, signal?: AbortSignal) {
    return this.client.request('/me/settings', isSettings, {
      method: 'PATCH',
      token,
      body,
      signal,
    });
  }
  publicProfile(id: string, token?: string, signal?: AbortSignal) {
    return this.client.request(
      `/profiles/${encodeURIComponent(id)}`,
      isPublicProfile,
      { token, signal },
    );
  }
}

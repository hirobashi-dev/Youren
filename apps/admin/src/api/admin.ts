// 管理客户端只在内存中保存独立令牌，并为所有后台请求附加该令牌。
export type AdminSession = { accessToken: string; expiresAt: string; role: 'reviewer' | 'operator' | 'auditor' };
export type ReviewImage = { id: string; url: string; width: number; height: number; expiresAt: string };
export type ReviewContent = { targetType: 'post' | 'comment'; targetId: string; status: 'pending' | 'rejected' | 'visible' | 'hidden'; version: number; pendingSince: string; reviewOverdue: boolean; title: string | null; body: string; images: ReviewImage[] };
export type AdminReport = { report: { id: string; status: 'open' | 'reviewing' | 'resolved'; createdAt: string; version: number }; targetType: string; targetId: string; reasonCode: string; detail: string };

const base = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:3000';
export class AdminApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message); }
}
async function request<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const response = await fetch(`${base}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init.headers },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new AdminApiError(response.status, payload.code || 'REQUEST_FAILED', payload.message || '请求失败，请稍后重试。');
  return payload.data as T;
}

export function beginAdminLogin(loginName: string, password: string) {
  return request<{ challengeId: string; expiresAt: string }>('/v1/admin/auth/login', { method: 'POST', body: JSON.stringify({ loginName, password }) });
}
export function completeAdminMfa(challengeId: string, code: string) {
  return request<AdminSession>('/v1/admin/auth/mfa', { method: 'POST', body: JSON.stringify({ challengeId, code }) });
}
export function getReviewQueue(session: AdminSession, status: 'pending' | 'rejected' | 'visible' | 'hidden', options: { targetType?: 'post' | 'comment'; limit?: number } = {}) {
  // 举报详情按目标类型缩小读取范围；列表使用默认上限，详情最多查100条。
  const query = new URLSearchParams({ status, limit: String(options.limit ?? 50) });
  if (options.targetType) query.set('targetType', options.targetType);
  return request<ReviewContent[]>(`/v1/admin/content?${query}`, {}, session.accessToken);
}
export function getReports(session: AdminSession) {
  return request<AdminReport[]>('/v1/admin/reports?limit=50', {}, session.accessToken);
}
export function decideContent(session: AdminSession, item: ReviewContent, action: 'approve' | 'reject' | 'hide' | 'restore' | 'delete', reason: string) {
  return request('/v1/admin/moderation-actions', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ targetType: item.targetType, targetId: item.targetId, action, reason, expectedVersion: item.version }) }, session.accessToken);
}
export function resolveReport(session: AdminSession, item: AdminReport, resolution: 'action_taken' | 'no_action', reason: string) {
  return request(`/v1/admin/reports/${item.report.id}/resolve`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ resolution, reason, expectedVersion: item.report.version }) }, session.accessToken);
}
export async function loadReviewImage(session: AdminSession, image: ReviewImage) {
  // 服务端返回绝对短时URL；限定同源后再附管理令牌，避免外站重定向式泄漏。
  const target = new URL(image.url, base);
  if (target.origin !== new URL(base).origin) throw new Error('图片地址不可用');
  const response = await fetch(target, { headers: { Authorization: `Bearer ${session.accessToken}` } });
  if (!response.ok) throw new Error('图片暂时无法读取');
  return URL.createObjectURL(await response.blob());
}

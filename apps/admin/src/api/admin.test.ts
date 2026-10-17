// 管理图片接口只请求 API 同源短时展示地址，并携带独立管理令牌。
import { afterEach, expect, test, vi } from 'vitest';
import { getReviewQueue, loadReviewImage, type AdminSession, type ReviewImage } from './admin';

afterEach(() => { vi.restoreAllMocks(); Reflect.deleteProperty(URL, 'createObjectURL'); });
const session: AdminSession = { accessToken: 'admin-only-token', role: 'reviewer', expiresAt: '2099-01-01T00:00:00Z' };
const image: ReviewImage = { id: 'image-1', url: 'http://127.0.0.1:3000/v1/admin/media/image-1/display?expiresAt=4102444800', width: 100, height: 80, expiresAt: '2099-01-01T00:00:00Z' };

test('加载绝对管理展示URL时附带管理会话令牌', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(new Blob(['image']), { status: 200 }));
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:preview') });
  await expect(loadReviewImage(session, image)).resolves.toBe('blob:preview');
  expect(fetch).toHaveBeenCalledWith(new URL(image.url), { headers: { Authorization: 'Bearer admin-only-token' } });
});

test('拒绝把管理员令牌发送到非API来源的图片URL', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch');
  await expect(loadReviewImage(session, { ...image, url: 'https://outside.example/image.jpg' })).rejects.toThrow('图片地址不可用');
  expect(fetch).not.toHaveBeenCalled();
});

test('举报详情查询按目标类型限制审核队列并允许提高读取上限', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: [] }), { status: 200 }));
  await getReviewQueue(session, 'visible', { targetType: 'post', limit: 100 });
  expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:3000/v1/admin/content?status=visible&limit=100&targetType=post', expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer admin-only-token' }) }));
});

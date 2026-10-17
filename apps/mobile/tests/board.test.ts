import { ApiClient } from '../src/api/client';
import { BoardApi } from '../src/api/board';
import { failure, reply } from './fixtures';

// 验证公开列表分页合同与提交幂等键，畸形响应必须安全拒绝。
test('公开列表携带筛选游标，发帖携带唯一幂等键', async () => {
  const client = new ApiClient('http://example.test');
  const api = new BoardApi(client);
  const requests: Array<{ url: string; init: RequestInit }> = [];
  global.fetch = jest.fn(async (url, init) => {
    requests.push({ url: String(url), init: init ?? {} });
    if (String(url).includes('/posts?'))
      return {
        ok: true,
        status: 200,
        headers: new Headers(),
        json: async () => ({
          data: [],
          page: { nextCursor: null, hasMore: false },
          requestId: 'request-one',
        }),
      } as Response;
    return reply(
      {
        id: '00000000-0000-4000-8000-000000000001',
        status: 'pending',
        publishedAt: null,
        expiresAt: null,
        version: 1,
      },
      201,
    );
  });

  await expect(
    api.posts({ regionCode: 'JP-13', category: 'city' }),
  ).resolves.toMatchObject({
    data: [],
    page: { hasMore: false, nextCursor: null },
  });
  await api.createPost(
    {
      title: '东京散步',
      body: '一起看看街区。',
      category: 'city',
      mediaIds: [],
    },
    { accessToken: 'access-one', guestToken: null },
  );

  expect(requests[0].url).toContain('regionCode=JP-13');
  expect(requests[0].url).toContain('category=city');
  expect(requests[1].init.headers).toEqual(
    expect.objectContaining({
      'Idempotency-Key': expect.any(String),
      Authorization: 'Bearer access-one',
    }),
  );
});

// 上传意向响应丢失后不能伪造已完成状态，错误仍需由安全消息层处理。
test('媒体状态响应格式错误时拒绝继续提交内容', async () => {
  const api = new BoardApi(new ApiClient('http://example.test'));
  global.fetch = jest.fn(async () => failure(200, 'INVALID_RESPONSE'));
  await expect(api.posts()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});

// 目录请求使用展示名称而非要求用户输入内部地区代码或兴趣ID。
test('地区目录请求携带父级代码且校验兴趣目录分页响应', async () => {
  const api = new BoardApi(new ApiClient('http://example.test'));
  const requests: string[] = [];
  global.fetch = jest.fn(async (url) => {
    requests.push(String(url));
    return {
      ok: true,
      status: 200,
      headers: new Headers(),
      json: async () => ({
        data: [],
        page: { nextCursor: null, hasMore: false },
        requestId: 'catalog',
      }),
    } as Response;
  });
  await expect(api.regions('JP-13')).resolves.toMatchObject({
    data: [],
    page: { hasMore: false },
  });
  await expect(api.interests()).resolves.toMatchObject({ data: [] });
  expect(requests[0]).toContain('/regions?parentCode=JP-13');
  expect(requests[1]).toContain('/interests');
});

// 到期/删除的本人记录只返回安全占位DTO，不应因缺少正文被客户端误判为畸形响应。
test('本人内容列表接受不含正文的终态占位', async () => {
  const api = new BoardApi(new ApiClient('http://example.test'));
  global.fetch = jest.fn(async () => ({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => ({
      data: [{ kind: 'posts', id: '11111111-1111-4111-8111-111111111111', status: 'expired', unavailable: true }],
      page: { nextCursor: null, hasMore: false },
      requestId: 'request-expired',
    }),
  }) as Response);

  await expect(api.ownContent('posts', 'expired', undefined, { guestToken: 'guest-secret' }))
    .resolves.toMatchObject({ data: [{ status: 'expired', unavailable: true }] });
});

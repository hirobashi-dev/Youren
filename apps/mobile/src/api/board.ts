import { ApiClient } from './client';
import { exact, object } from './types';

export type BoardCredentials = {
  accessToken?: string;
  guestToken?: string | null;
};
export type Image = {
  id: string;
  url: string;
  width: number;
  height: number;
  expiresAt: string;
};
export type PublicPost = {
  id: string;
  title: string;
  body: string;
  authorDisplay: Record<string, unknown>;
  region: unknown;
  interests: unknown[];
  images: Image[];
  replyCount: number;
  publishedAt: string;
  expiresAt: string;
  capabilities: Record<string, boolean>;
};
export type PublicComment = {
  id: string;
  postId: string;
  body: string;
  authorDisplay: Record<string, unknown>;
  images: Image[];
  replyToId: string | null;
  publishedAt: string;
  expiresAt: string;
  capabilities: Record<string, boolean>;
};
// 终态记录只含状态与资源编号，正文字段仅存在于仍可读取的记录。
export type UnavailableOwnContent = {
  kind: 'posts' | 'comments';
  id: string;
  status: 'deleted' | 'expired';
  unavailable: true;
};
export type AvailableOwnContent = {
  kind: 'posts' | 'comments';
  id: string;
  status: 'pending' | 'visible' | 'rejected' | 'hidden';
  publishedAt: string | null;
  expiresAt: string | null;
  version: number;
  title?: string;
  body: string;
  category?: 'interest' | 'city' | 'general';
  regionCode?: string | null;
  interestIds?: string[];
  images: Image[];
  parentUnavailable: boolean;
};
export type OwnContent = UnavailableOwnContent | AvailableOwnContent;
export type BoardPage<T> = {
  data: T[];
  page: { nextCursor: string | null; hasMore: boolean };
  requestId: string;
};
export type PostInput = {
  title: string;
  body: string;
  category: 'interest' | 'city' | 'general';
  regionCode?: string | null;
  interestIds?: string[];
  mediaIds: string[];
};
export type CommentInput = {
  body: string;
  mediaIds: string[];
  replyToId?: string | null;
};
export type PostEditInput = Omit<PostInput, 'mediaIds'> & {
  mediaIds?: string[];
};
export type CommentEditInput = Omit<CommentInput, 'mediaIds'> & {
  mediaIds?: string[];
};
export type MutationResult = {
  id: string;
  status: string;
  publishedAt: string | null;
  expiresAt: string | null;
  version: number;
};
export type UploadIntent = {
  mediaId: string;
  uploadUrl: string;
  uploadHeaders: Record<string, string>;
  expiresAt: string;
};
export type MediaState = {
  id: string;
  status: string;
  purpose: string;
  sizeBytes: number;
};
export type Region = {
  code: string;
  parentCode: string | null;
  level: string;
  nameJa: string;
  nameZh: string;
};
export type Interest = { id: string; code: string; nameZh: string };

const text = (value: unknown): value is string => typeof value === 'string';
const time = (value: unknown): value is string =>
  text(value) && Number.isFinite(Date.parse(value));
const nullableTime = (value: unknown): value is string | null =>
  value === null || time(value);
const uuid = (value: unknown) =>
  text(value) &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    value,
  );
const image = (value: unknown): value is Image =>
  exact(value, ['id', 'url', 'width', 'height', 'expiresAt']) &&
  uuid(value.id) &&
  text(value.url) &&
  Number.isSafeInteger(value.width) &&
  Number.isSafeInteger(value.height) &&
  time(value.expiresAt);
const page = <T>(
  value: unknown,
  item: (row: unknown) => row is T,
): value is BoardPage<T> =>
  exact(value, ['data', 'page', 'requestId']) &&
  Array.isArray(value.data) &&
  value.data.every(item) &&
  exact(value.page, ['nextCursor', 'hasMore']) &&
  (value.page.nextCursor === null || text(value.page.nextCursor)) &&
  typeof value.page.hasMore === 'boolean' &&
  text(value.requestId);
const author = (value: unknown) => {
  if (!object(value) || !text(value.kind)) return false;
  return value.kind === 'guest'
    ? exact(value, ['kind', 'label']) && text(value.label)
    : value.kind === 'account' &&
        exact(value, ['kind', 'accountId', 'nickname', 'avatar']) &&
        uuid(value.accountId) &&
        text(value.nickname) &&
        value.avatar === null;
};
const flags = (value: unknown) =>
  object(value) &&
  Object.values(value).every((item) => typeof item === 'boolean');
const publicPost = (value: unknown): value is PublicPost =>
  exact(value, [
    'id',
    'title',
    'body',
    'authorDisplay',
    'region',
    'interests',
    'images',
    'replyCount',
    'publishedAt',
    'expiresAt',
    'capabilities',
  ]) &&
  uuid(value.id) &&
  text(value.title) &&
  text(value.body) &&
  author(value.authorDisplay) &&
  Array.isArray(value.interests) &&
  Array.isArray(value.images) &&
  value.images.every(image) &&
  Number.isSafeInteger(value.replyCount) &&
  time(value.publishedAt) &&
  time(value.expiresAt) &&
  flags(value.capabilities);
const publicComment = (value: unknown): value is PublicComment =>
  exact(value, [
    'id',
    'postId',
    'body',
    'authorDisplay',
    'images',
    'replyToId',
    'publishedAt',
    'expiresAt',
    'capabilities',
  ]) &&
  uuid(value.id) &&
  uuid(value.postId) &&
  text(value.body) &&
  author(value.authorDisplay) &&
  Array.isArray(value.images) &&
  value.images.every(image) &&
  (value.replyToId === null || uuid(value.replyToId)) &&
  time(value.publishedAt) &&
  time(value.expiresAt) &&
  flags(value.capabilities);
const ownContent = (value: unknown): value is OwnContent => {
  if (
    exact(value, ['kind', 'id', 'status', 'unavailable']) &&
    ['posts', 'comments'].includes(String(value.kind)) &&
    uuid(value.id) &&
    ['deleted', 'expired'].includes(String(value.status)) &&
    value.unavailable === true
  )
    return true;

  return (
    exact(
    value,
    [
      'kind',
      'id',
      'status',
      'publishedAt',
      'expiresAt',
      'version',
      'body',
      'images',
      'parentUnavailable',
    ],
    ['title', 'category', 'regionCode', 'interestIds'],
    ) &&
  ['posts', 'comments'].includes(String(value.kind)) &&
  uuid(value.id) &&
  ['pending', 'visible', 'rejected', 'hidden'].includes(String(value.status)) &&
  nullableTime(value.publishedAt) &&
  nullableTime(value.expiresAt) &&
  Number.isSafeInteger(value.version) &&
  text(value.body) &&
  Array.isArray(value.images) &&
  value.images.every(image) &&
  typeof value.parentUnavailable === 'boolean' &&
  (value.title === undefined || text(value.title)) &&
  (value.category === undefined ||
    ['interest', 'city', 'general'].includes(String(value.category))) &&
  (value.regionCode === undefined ||
    value.regionCode === null ||
    text(value.regionCode)) &&
  (value.interestIds === undefined ||
    (Array.isArray(value.interestIds) && value.interestIds.every(uuid)))
  );
};
const mutation = (value: unknown): value is MutationResult =>
  exact(value, ['id', 'status', 'publishedAt', 'expiresAt', 'version']) &&
  uuid(value.id) &&
  text(value.status) &&
  nullableTime(value.publishedAt) &&
  nullableTime(value.expiresAt) &&
  Number.isSafeInteger(value.version);
const intent = (value: unknown): value is UploadIntent =>
  exact(value, ['mediaId', 'uploadUrl', 'uploadHeaders', 'expiresAt']) &&
  uuid(value.mediaId) &&
  text(value.uploadUrl) &&
  object(value.uploadHeaders) &&
  Object.values(value.uploadHeaders).every(text) &&
  time(value.expiresAt);
const mediaState = (value: unknown): value is MediaState =>
  exact(value, ['id', 'status', 'purpose', 'sizeBytes']) &&
  uuid(value.id) &&
  text(value.status) &&
  text(value.purpose) &&
  Number.isSafeInteger(value.sizeBytes);
const result = (
  value: unknown,
): value is {
  id: string;
  status: string;
  createdAt: string;
  version: number;
} =>
  exact(value, ['id', 'status', 'createdAt', 'version']) &&
  uuid(value.id) &&
  text(value.status) &&
  time(value.createdAt) &&
  Number.isSafeInteger(value.version);
const region = (value: unknown): value is Region =>
  exact(value, ['code', 'parentCode', 'level', 'nameJa', 'nameZh']) &&
  text(value.code) &&
  (value.parentCode === null || text(value.parentCode)) &&
  text(value.level) &&
  text(value.nameJa) &&
  text(value.nameZh);
const interest = (value: unknown): value is Interest =>
  exact(value, ['id', 'code', 'nameZh']) &&
  uuid(value.id) &&
  text(value.code) &&
  text(value.nameZh);

// 幂等键只在一次用户提交期间复用；未知网络结果应使用同一键查询/重试，避免重复发帖。
export function newMutationKey() {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (part) => {
    const random = Math.floor(Math.random() * 16);
    return (part === 'x' ? random : (random & 3) | 8).toString(16);
  });
}

// 游客凭证和账号令牌严格分开；公开读取不附带本机任何身份秘密。
function credentialHeaders(credentials?: BoardCredentials) {
  if (credentials?.accessToken && credentials.guestToken)
    throw new Error('身份状态无效');
  return {
    ...(credentials?.accessToken ? { token: credentials.accessToken } : {}),
    ...(credentials?.guestToken
      ? { headers: { 'X-Guest-Token': credentials.guestToken } }
      : {}),
  };
}

export class BoardApi {
  constructor(private readonly client: ApiClient) {}
  // 公开字典使用与后端相同的分页外壳，结果严格校验后才供筛选器展示。
  regions(parentCode?: string, signal?: AbortSignal) {
    const query = parentCode
      ? `?parentCode=${encodeURIComponent(parentCode)}`
      : '';
    return this.client.request(
      `/regions${query}`,
      (value): value is BoardPage<Region> => page(value, region),
      { signal },
    );
  }
  interests(signal?: AbortSignal) {
    return this.client.request(
      '/interests',
      (value): value is BoardPage<Interest> => page(value, interest),
      { signal },
    );
  }
  posts(
    filters: {
      regionCode?: string;
      interestId?: string;
      category?: string;
      q?: string;
      cursor?: string;
    } = {},
    signal?: AbortSignal,
  ) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filters))
      if (value) query.set(key, value);
    const suffix = query.size ? `?${query.toString()}` : '';
    return this.client.request(
      `/posts${suffix}`,
      (value): value is BoardPage<PublicPost> => page(value, publicPost),
      { signal },
    );
  }
  post(id: string, signal?: AbortSignal) {
    return this.client.request(`/posts/${encodeURIComponent(id)}`, publicPost, {
      signal,
    });
  }
  comments(
    postId: string,
    cursor?: string,
    credentials?: BoardCredentials,
    signal?: AbortSignal,
  ) {
    const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
    return this.client.request(
      `/posts/${encodeURIComponent(postId)}/comments${query}`,
      (value): value is BoardPage<PublicComment> => page(value, publicComment),
      { ...credentialHeaders(credentials), signal },
    );
  }
  ownContent(
    kind: 'posts' | 'comments',
    status: string | undefined,
    cursor: string | undefined,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    const query = new URLSearchParams({ kind });
    if (status) query.set('status', status);
    if (cursor) query.set('cursor', cursor);
    return this.client.request(
      `/me/content?${query}`,
      (value): value is BoardPage<OwnContent> => page(value, ownContent),
      { ...credentialHeaders(credentials), signal },
    );
  }
  ownDetail(
    kind: 'posts' | 'comments',
    id: string,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `/me/content/${kind}/${encodeURIComponent(id)}`,
      ownContent,
      { ...credentialHeaders(credentials), signal },
    );
  }
  createPost(
    input: PostInput,
    credentials: BoardCredentials,
    key = newMutationKey(),
    signal?: AbortSignal,
  ) {
    return this.client.request('/posts', mutation, {
      method: 'POST',
      body: input,
      headers: {
        'Idempotency-Key': key,
        ...credentialHeaders(credentials).headers,
      },
      token: credentials.accessToken,
      signal,
    });
  }
  createComment(
    postId: string,
    input: CommentInput,
    credentials: BoardCredentials,
    key = newMutationKey(),
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `/posts/${encodeURIComponent(postId)}/comments`,
      mutation,
      {
        method: 'POST',
        body: input,
        headers: {
          'Idempotency-Key': key,
          ...credentialHeaders(credentials).headers,
        },
        token: credentials.accessToken,
        signal,
      },
    );
  }
  updatePost(
    id: string,
    version: number,
    input: PostEditInput,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    return this.client.request(`/posts/${encodeURIComponent(id)}`, mutation, {
      method: 'PATCH',
      body: input,
      headers: {
        'If-Match': `"${version}"`,
        ...credentialHeaders(credentials).headers,
      },
      token: credentials.accessToken,
      signal,
    });
  }
  updateComment(
    id: string,
    version: number,
    input: CommentEditInput,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `/comments/${encodeURIComponent(id)}`,
      mutation,
      {
        method: 'PATCH',
        body: input,
        headers: {
          'If-Match': `"${version}"`,
          ...credentialHeaders(credentials).headers,
        },
        token: credentials.accessToken,
        signal,
      },
    );
  }
  deletePost(id: string, credentials: BoardCredentials, signal?: AbortSignal) {
    return this.client.request(
      `/posts/${encodeURIComponent(id)}`,
      (value): value is undefined => value === undefined,
      {
        method: 'DELETE',
        empty: true,
        headers: {
          'Idempotency-Key': newMutationKey(),
          ...credentialHeaders(credentials).headers,
        },
        token: credentials.accessToken,
        signal,
      },
    );
  }
  deleteComment(
    id: string,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `/comments/${encodeURIComponent(id)}`,
      (value): value is undefined => value === undefined,
      {
        method: 'DELETE',
        empty: true,
        headers: {
          'Idempotency-Key': newMutationKey(),
          ...credentialHeaders(credentials).headers,
        },
        token: credentials.accessToken,
        signal,
      },
    );
  }
  report(
    input: {
      targetType: 'post' | 'comment';
      targetId: string;
      reasonCode: string;
      detail?: string;
    },
    credentials: BoardCredentials,
    key = newMutationKey(),
    signal?: AbortSignal,
  ) {
    return this.client.request('/reports', result, {
      method: 'POST',
      body: input,
      headers: {
        'Idempotency-Key': key,
        ...credentialHeaders(credentials).headers,
      },
      token: credentials.accessToken,
      signal,
    });
  }
  uploadIntent(
    file: { fileName: string; mime: string; sizeBytes: number },
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    return this.client.request('/media/upload-intents', intent, {
      method: 'POST',
      body: { purpose: 'board', ...file },
      ...credentialHeaders(credentials),
      signal,
    });
  }
  async upload(
    intentValue: UploadIntent,
    uri: string,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    if (signal?.aborted) throw new Error('请求已取消');
    const local = await fetch(uri, { signal });
    if (!local.ok) throw new Error('无法读取所选图片');
    const bytes = await local.blob();
    const headers = {
      ...intentValue.uploadHeaders,
      ...(credentials.guestToken
        ? { 'X-Guest-Token': credentials.guestToken }
        : {}),
      ...(credentials.accessToken
        ? { Authorization: `Bearer ${credentials.accessToken}` }
        : {}),
    };
    const response = await fetch(intentValue.uploadUrl, {
      method: 'PUT',
      headers,
      body: bytes,
      signal,
    });
    if (!response.ok) throw new Error('图片上传失败，请重试');
  }
  completeUpload(
    id: string,
    credentials: BoardCredentials,
    signal?: AbortSignal,
  ) {
    return this.client.request(
      `/media/${encodeURIComponent(id)}/complete`,
      mediaState,
      { method: 'POST', ...credentialHeaders(credentials), signal },
    );
  }
  media(id: string, credentials: BoardCredentials, signal?: AbortSignal) {
    return this.client.request(`/media/${encodeURIComponent(id)}`, mediaState, {
      ...credentialHeaders(credentials),
      signal,
    });
  }
  deleteMedia(id: string, credentials: BoardCredentials, signal?: AbortSignal) {
    return this.client.request(
      `/media/${encodeURIComponent(id)}`,
      (value): value is undefined => value === undefined,
      {
        method: 'DELETE',
        empty: true,
        ...credentialHeaders(credentials),
        signal,
      },
    );
  }
}

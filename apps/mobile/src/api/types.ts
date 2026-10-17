// 本人和公开资料使用独立类型，禁止把邮箱、审核及恋爱字段带入主页。
export type Settings = {
  allowStrangerDm: boolean;
  pushEnabled: boolean;
  lockscreenPreview: boolean;
};
export type Region = {
  code: string;
  parentCode: string | null;
  level: 'prefecture' | 'municipality';
  nameJa: string;
  nameZh: string;
};
export type Interest = { id: string; code: string; nameZh: string };
export type PublicProfile = {
  id: string;
  nickname: string;
  bio: string;
  avatar: null;
  region: Region | null;
  interests: Interest[];
  capabilities: Partial<
    Record<
      | 'canRead'
      | 'canSend'
      | 'canEdit'
      | 'canDelete'
      | 'canJoin'
      | 'canMessage',
      boolean
    >
  >;
};
export type OwnerProfile = PublicProfile & {
  moderationStatus: 'pending' | 'approved' | 'rejected';
};
export type Me = {
  id: string;
  email: string;
  status: 'active' | 'restricted' | 'deleting' | 'deleted';
  profile: OwnerProfile | null;
  profileVersion: number | null;
  settings: Settings;
  ageDeclaration: {
    status: 'not_declared' | 'self_declared' | 'withdrawn';
    declaredAt: string | null;
    withdrawnAt: string | null;
  };
  onboardingRequired: boolean;
};
export type AuthSession = {
  accessToken: string;
  refreshToken: string;
  account: Me;
  onboardingRequired: boolean;
  claimStatus: 'not_requested' | 'claimed' | 'failed';
};
// 刷新合同仅交付新令牌对，本人资料须使用新访问令牌独立读取。
export type RefreshTokens = { accessToken: string; refreshToken: string };
export type Policy = { termsVersion: string; privacyVersion: string };
export type Challenge = {
  challengeId: string;
  expiresIn: number;
  retryAfter: number;
};
export type GuestSession = {
  guestToken: string;
  principalId: string;
  expiresAt: string;
};
export type ProfileVersion = { profile: OwnerProfile; version: number };
export type ProfileInput = {
  nickname?: string;
  bio?: string;
  regionCode?: string | null;
  interestIds?: string[];
  avatarMediaId?: null;
};
export type GuestClaim = {
  claimedContentCounts: { posts: number; comments: number };
};
type RecordValue = Record<string, unknown>;
export function object(value: unknown): value is RecordValue {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export function exact(
  value: unknown,
  required: string[],
  optional: string[] = [],
): value is RecordValue {
  return (
    object(value) &&
    required.every((key) => Object.hasOwn(value, key)) &&
    Object.keys(value).every(
      (key) => required.includes(key) || optional.includes(key),
    )
  );
}
const text = (value: unknown): value is string => typeof value === 'string';
const nonempty = (value: unknown): value is string =>
  text(value) && value.length > 0;
const uuid = (value: unknown) =>
  text(value) &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const natural = (value: unknown) =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const timestamp = (value: unknown) =>
  text(value) &&
  /T.*(?:Z|[+-]\d\d:\d\d)$/.test(value) &&
  Number.isFinite(Date.parse(value));
const nullableTime = (value: unknown) => value === null || timestamp(value);
export const isSettings = (value: unknown): value is Settings =>
  exact(value, ['allowStrangerDm', 'pushEnabled', 'lockscreenPreview']) &&
  Object.values(value).every((v) => typeof v === 'boolean');
const isRegion = (value: unknown): value is Region =>
  exact(value, ['code', 'parentCode', 'level', 'nameJa', 'nameZh']) &&
  nonempty(value.code) &&
  (value.parentCode === null || text(value.parentCode)) &&
  ['prefecture', 'municipality'].includes(String(value.level)) &&
  text(value.nameJa) &&
  text(value.nameZh);
const isInterest = (value: unknown): value is Interest =>
  exact(value, ['id', 'code', 'nameZh']) &&
  uuid(value.id) &&
  nonempty(value.code) &&
  text(value.nameZh);
const profileKeys = [
  'id',
  'nickname',
  'bio',
  'avatar',
  'region',
  'interests',
  'capabilities',
];
function profileFields(value: RecordValue) {
  return (
    uuid(value.id) &&
    text(value.nickname) &&
    text(value.bio) &&
    value.avatar === null &&
    (value.region === null || isRegion(value.region)) &&
    Array.isArray(value.interests) &&
    value.interests.every(isInterest) &&
    exact(
      value.capabilities,
      [],
      ['canRead', 'canSend', 'canEdit', 'canDelete', 'canJoin', 'canMessage'],
    ) &&
    Object.values(value.capabilities).every((v) => typeof v === 'boolean')
  );
}
// 当前服务端尚无受控图片交付，拒绝非空头像，不能展示未知存储地址。
export const isPublicProfile = (value: unknown): value is PublicProfile =>
  exact(value, profileKeys) && profileFields(value);
export const isOwnerProfile = (value: unknown): value is OwnerProfile =>
  exact(value, [...profileKeys, 'moderationStatus']) &&
  profileFields(value) &&
  ['pending', 'approved', 'rejected'].includes(String(value.moderationStatus));
export function isMe(value: unknown): value is Me {
  return (
    exact(value, [
      'id',
      'email',
      'status',
      'profile',
      'profileVersion',
      'settings',
      'ageDeclaration',
      'onboardingRequired',
    ]) &&
    uuid(value.id) &&
    text(value.email) &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.email) &&
    ['active', 'restricted', 'deleting', 'deleted'].includes(
      String(value.status),
    ) &&
    (value.profile === null || isOwnerProfile(value.profile)) &&
    (value.profileVersion === null ||
      (natural(value.profileVersion) && Number(value.profileVersion) > 0)) &&
    isSettings(value.settings) &&
    typeof value.onboardingRequired === 'boolean' &&
    exact(value.ageDeclaration, ['status', 'declaredAt', 'withdrawnAt']) &&
    ['not_declared', 'self_declared', 'withdrawn'].includes(
      String(value.ageDeclaration.status),
    ) &&
    nullableTime(value.ageDeclaration.declaredAt) &&
    nullableTime(value.ageDeclaration.withdrawnAt)
  );
}
export const isAuthSession = (value: unknown): value is AuthSession =>
  exact(value, [
    'accessToken',
    'refreshToken',
    'account',
    'onboardingRequired',
    'claimStatus',
  ]) &&
  nonempty(value.accessToken) &&
  nonempty(value.refreshToken) &&
  isMe(value.account) &&
  typeof value.onboardingRequired === 'boolean' &&
  ['not_requested', 'claimed', 'failed'].includes(String(value.claimStatus));
export const isRefreshTokens = (value: unknown): value is RefreshTokens =>
  exact(value, ['accessToken', 'refreshToken']) &&
  nonempty(value.accessToken) &&
  nonempty(value.refreshToken);
export const isPolicy = (value: unknown): value is Policy =>
  exact(value, ['termsVersion', 'privacyVersion']) &&
  nonempty(value.termsVersion) &&
  nonempty(value.privacyVersion);
export const isChallenge = (value: unknown): value is Challenge =>
  exact(value, ['challengeId', 'expiresIn', 'retryAfter']) &&
  uuid(value.challengeId) &&
  natural(value.expiresIn) &&
  Number(value.expiresIn) > 0 &&
  natural(value.retryAfter);
export const isGuestSession = (value: unknown): value is GuestSession =>
  exact(value, ['guestToken', 'principalId', 'expiresAt']) &&
  nonempty(value.guestToken) &&
  uuid(value.principalId) &&
  timestamp(value.expiresAt);
export const isProfileVersion = (value: unknown): value is ProfileVersion =>
  exact(value, ['profile', 'version']) &&
  isOwnerProfile(value.profile) &&
  natural(value.version) &&
  Number(value.version) > 0;
export const isGuestClaim = (value: unknown): value is GuestClaim =>
  exact(value, ['claimedContentCounts']) &&
  exact(value.claimedContentCounts, ['posts', 'comments']) &&
  natural(value.claimedContentCounts.posts) &&
  natural(value.claimedContentCounts.comments);

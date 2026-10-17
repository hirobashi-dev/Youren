import { useEffect, useRef, useState } from 'react';
import { Image, Pressable, Text, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import {
  BoardApi,
  newMutationKey,
  type BoardCredentials,
  type BoardPage,
  type Image as BoardImage,
  type Interest,
  type OwnContent,
  type PublicComment,
  type PublicPost,
  type Region,
} from '../api/board';
import { ApiError, safeMessage } from '../api/client';
import { imageSelectionError } from '../api/media';
import { SessionStore } from '../auth/sessionStore';
import { Action, Field, Notice, Page, styles } from '../components/Form';
import { theme } from '../theme';

// 页面模式对应设计稿中的独立流程；回复与举报回执不嵌在详情表单里。
type Mode =
  | 'feed'
  | 'search'
  | 'detail'
  | 'compose'
  | 'reply'
  | 'mine'
  | 'edit'
  | 'report'
  | 'report-done';
type DraftImage = {
  uri: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  width: number;
  height: number;
};
type ComposeDraft = {
  title: string;
  body: string;
  category: 'general' | 'interest' | 'city';
  regionCode: string;
  selectedRegion: Region | null;
  interestIds: string[];
  images: DraftImage[];
};
const STATUS_LABELS: Record<string, string> = {
  '': '全部状态',
  pending: '待审核',
  visible: '已公开',
  rejected: '需修改',
  hidden: '已隐藏',
  expired: '已到期',
  deleted: '已删除',
};
const displayAuthor = (value: Record<string, unknown>) =>
  String(value.label ?? value.nickname ?? 'Youren用户');
const readableName = (value: unknown) => {
  if (!value || typeof value !== 'object') return '';
  const row = value as Record<string, unknown>;
  return String(row.nameZh ?? row.label ?? row.name ?? '');
};
const relativeTime = (value: string) => {
  const minutes = Math.max(
    0,
    Math.floor((Date.now() - Date.parse(value)) / 60_000),
  );
  if (minutes < 60) return `${Math.max(1, minutes)} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  return days < 30
    ? `${days} 天前`
    : new Date(value).toLocaleDateString('zh-CN');
};

// 首页筛选项采用短胶囊，突出当前频道且保持真实列表内容的视觉优先级。
function FilterPill({
  label,
  selected,
  onPress,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      onPress={onPress}
      style={{
        minHeight: 38,
        paddingHorizontal: 15,
        borderRadius: 20,
        justifyContent: 'center',
        backgroundColor: selected ? '#E1F0EC' : theme.surface,
        borderColor: selected ? '#C7DFD9' : '#D8E2E1',
        borderWidth: 1,
      }}
    >
      <Text
        style={{ color: selected ? theme.primary : theme.ink, fontSize: 14 }}
      >
        {label}
      </Text>
    </Pressable>
  );
}

// 仅展示阶段三已开放的留言板入口；未实现模块显示为导航标签，不伪造对应页面。
function BoardBottomNavigation({
  onSelect,
}: {
  onSelect: (item: '发现' | '留言板' | '消息' | '我的') => void;
}) {
  const items = [
    ['发现', '◇'],
    ['留言板', '▤'],
    ['消息', '◉'],
    ['我的', '♙'],
  ] as const;
  return (
    <View
      style={{
        flexDirection: 'row',
        justifyContent: 'space-around',
        paddingVertical: 8,
      }}
    >
      {items.map(([label, icon]) => (
        <Pressable
          key={label}
          accessibilityRole="tab"
          accessibilityLabel={label}
          accessibilityState={{ selected: label === '留言板' }}
          onPress={() => onSelect(label)}
          style={{ alignItems: 'center', minWidth: 56, gap: 2 }}
        >
          <Text
            style={{
              color: label === '留言板' ? theme.primary : theme.muted,
              fontSize: 18,
            }}
          >
            {icon}
          </Text>
          <Text
            style={{
              color: label === '留言板' ? theme.primary : theme.muted,
              fontSize: 11,
            }}
          >
            {label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

// 手机端沿用已确认的清爽绿色主题；所有写操作沿用当前账号或显式保存的游客身份。
export function BoardScreen({
  api,
  store,
  onBack,
}: {
  api: BoardApi;
  store: SessionStore;
  onBack: () => void;
}) {
  const [mode, setMode] = useState<Mode>('feed');
  const [posts, setPosts] = useState<PublicPost[]>([]);
  const [post, setPost] = useState<PublicPost | null>(null);
  const [comments, setComments] = useState<PublicComment[]>([]);
  // 非空时只引用一条既有回复，空值表示直接回复帖子。
  const [replyTarget, setReplyTarget] = useState<PublicComment | null>(null);
  const [regions, setRegions] = useState<Region[]>([]);
  const [selectedRegion, setSelectedRegion] = useState<Region | null>(null);
  const [interestOptions, setInterestOptions] = useState<Interest[]>([]);
  const [regionParent, setRegionParent] = useState<string | null>(null);
  const [ownItems, setOwnItems] = useState<OwnContent[]>([]);
  const [ownKind, setOwnKind] = useState<'posts' | 'comments'>('posts');
  const [category, setCategory] = useState('');
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [composeCategory, setComposeCategory] = useState<
    'general' | 'interest' | 'city'
  >('general');
  const [regionCode, setRegionCode] = useState('');
  const [interestId, setInterestId] = useState('');
  const [composeInterestIds, setComposeInterestIds] = useState<string[]>([]);
  const [showRegionOptions, setShowRegionOptions] = useState(false);
  const [showInterestOptions, setShowInterestOptions] = useState(false);
  const [showCategoryOptions, setShowCategoryOptions] = useState(false);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [replyBody, setReplyBody] = useState('');
  const [images, setImages] = useState<DraftImage[]>([]);
  const [savedDraft, setSavedDraft] = useState<ComposeDraft | null>(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [reportReason, setReportReason] = useState('spam');
  const [reportDetail, setReportDetail] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [needsGuest, setNeedsGuest] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [mutationKey, setMutationKey] = useState(newMutationKey);
  const [selected, setSelected] = useState<OwnContent | null>(null);
  const lock = useRef(false);

  // 筛选和发帖复用服务端字典；目录为空时明确提示，避免让用户猜内部代码。
  useEffect(() => {
    const controller = new AbortController();
    void Promise.all([
      api.regions(undefined, controller.signal),
      api.interests(controller.signal),
    ])
      .then(([regionPage, interestPage]) => {
        if (controller.signal.aborted) return;
        setRegions(regionPage.data);
        setInterestOptions(interestPage.data);
      })
      .catch((error) => {
        if (!controller.signal.aborted) showError(error);
      });
    return () => controller.abort();
  }, [api]);

  const credentials = (): BoardCredentials | null => store.contentCredentials();
  const withIdentity = <T,>(
    operation: (value: BoardCredentials) => Promise<T>,
  ) => {
    const identity = credentials();
    if (!identity) throw new ApiError('AUTH_REQUIRED', 401, 'not-sent');
    if (identity.accessToken)
      return store.authorized((token) => operation({ accessToken: token }));
    return operation(identity);
  };
  const showError = (error: unknown) =>
    setNotice(
      error instanceof ApiError
        ? safeMessage(error)
        : '操作未完成，请检查网络后重试。',
    );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setNotice('');
    void api
      .posts(
        {
          category: category || undefined,
          regionCode: regionCode || undefined,
          interestId: interestId || undefined,
          q: appliedQuery || undefined,
        },
        controller.signal,
      )
      .then((result) => {
        if (controller.signal.aborted) return;
        setPosts(result.data);
        setNextCursor(result.page.nextCursor);
        setHasMore(result.page.hasMore);
      })
      .catch((error) => {
        if (!controller.signal.aborted) showError(error);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [api, category, regionCode, interestId, appliedQuery, attempt]);

  async function openPost(item: PublicPost) {
    setPost(item);
    setMode('detail');
    setNotice('');
    try {
      const result = await api.comments(item.id, undefined, undefined);
      setComments(result.data);
    } catch (error) {
      showError(error);
    }
  }
  async function openMine(kind = ownKind, status = statusFilter) {
    if (!credentials()) {
      setNeedsGuest(true);
      setNotice('保存游客身份后即可查看本人内容。');
      return;
    }
    setOwnKind(kind);
    setMode('mine');
    setLoading(true);
    try {
      const result = await withIdentity((identity) =>
        api.ownContent(kind, status || undefined, undefined, identity),
      );
      setOwnItems(result.data);
      setNextCursor(result.page.nextCursor);
      setHasMore(result.page.hasMore);
    } catch (error) {
      showError(error);
    } finally {
      setLoading(false);
    }
  }
  function startCompose() {
    if (!credentials()) {
      setNeedsGuest(true);
      setNotice('保存游客身份后即可发布内容。');
      return;
    }
    setNeedsGuest(false);
    // 同一应用会话内恢复手动保存的草稿；草稿暂不跨应用重启持久化。
    if (savedDraft) {
      setTitle(savedDraft.title);
      setBody(savedDraft.body);
      setImages(savedDraft.images);
      setComposeCategory(savedDraft.category);
      setRegionCode(savedDraft.regionCode);
      setSelectedRegion(savedDraft.selectedRegion);
      setComposeInterestIds(savedDraft.interestIds);
    } else {
      setTitle('');
      setBody('');
      setImages([]);
      setComposeCategory('general');
      setComposeInterestIds([]);
    }
    setMutationKey(newMutationKey());
    setNotice('');
    setMode('compose');
  }
  async function chooseRegion(region: Region) {
    setRegionCode(region.code);
    setSelectedRegion(region);
    setRegionParent(
      region.level === 'prefecture' ? region.code : region.parentCode,
    );
    if (region.level === 'prefecture') {
      try {
        setRegions((await api.regions(region.code)).data);
      } catch (error) {
        showError(error);
      }
    }
  }
  async function showPrefectures() {
    setRegionParent(null);
    try {
      setRegions((await api.regions()).data);
    } catch (error) {
      showError(error);
    }
  }
  async function saveGuestAndCompose() {
    if (busy) return;
    setBusy(true);
    try {
      await store.createGuest();
      if (store.contentCredentials()) startCompose();
      else
        setNotice(
          store.getSnapshot().message || '游客身份保存失败，请稍后重试。',
        );
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  }
  async function pickImages(limit: number) {
    if (busy) return;
    const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      setNotice('需要允许访问照片，才能添加图片。');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsMultipleSelection: true,
      selectionLimit: Math.max(1, limit - images.length),
      quality: 1,
    });
    if (result.canceled) return;
    const chosen: DraftImage[] = [];
    for (const asset of result.assets) {
      const error = imageSelectionError(
        asset,
        images.length + chosen.length,
        limit,
      );
      if (error) {
        setNotice(error);
        return;
      }
      if (asset.width * asset.height > 20_000_000) {
        setNotice('图片像素不能超过 2000 万。');
        return;
      }
      chosen.push({
        uri: asset.uri,
        fileName: asset.fileName ?? `image-${chosen.length + 1}`,
        mimeType: asset.mimeType!,
        fileSize: asset.fileSize!,
        width: asset.width,
        height: asset.height,
      });
    }
    setImages((current) => [...current, ...chosen]);
    setNotice('');
  }
  async function uploadImages(limit: number) {
    const identity = credentials();
    if (!identity) throw new ApiError('AUTH_REQUIRED', 401, 'not-sent');
    const mediaIds: string[] = [];
    for (const asset of images.slice(0, limit)) {
      // 上传意向未知时绝不盲目新建第二个意向；未绑定文件由服务端24小时清理。
      const intent = await withIdentity((current) =>
        api.uploadIntent(
          {
            fileName: asset.fileName,
            mime: asset.mimeType,
            sizeBytes: asset.fileSize,
          },
          current,
        ),
      );
      await withIdentity((current) => api.upload(intent, asset.uri, current));
      const status = await withIdentity((current) =>
        api.completeUpload(intent.mediaId, current),
      );
      if (!['approved', 'ready'].includes(status.status))
        throw new Error('图片仍在处理中，请稍后重试。');
      mediaIds.push(intent.mediaId);
    }
    return mediaIds;
  }
  async function submitPost() {
    if (lock.current || busy) return;
    if (!title.trim() || !body.trim()) {
      setNotice('请填写标题和内容。');
      return;
    }
    lock.current = true;
    setBusy(true);
    setNotice('正在上传图片并提交审核。');
    try {
      const mediaIds = await uploadImages(9);
      await withIdentity((identity) =>
        api.createPost(
          {
            title,
            body,
            category: composeCategory,
            regionCode: regionCode || null,
            interestIds: composeInterestIds,
            mediaIds,
          },
          identity,
          mutationKey,
        ),
      );
      setImages([]);
      setSavedDraft(null);
      setMode('mine');
      setStatusFilter('pending');
      setMutationKey(newMutationKey());
      await openMine('posts', 'pending');
      setNotice('已提交，审核通过后会在留言板公开。');
    } catch (error) {
      showError(error);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  // 回复创建沿用游客凭证与幂等键；引用目标为空时创建帖子级回复。
  async function submitReply() {
    if (!post || lock.current || busy) return;
    if (!replyBody.trim() && images.length === 0) {
      setNotice('请填写回复或添加图片。');
      return;
    }
    lock.current = true;
    setBusy(true);
    try {
      const mediaIds = await uploadImages(3);
      await withIdentity((identity) =>
        api.createComment(
          post.id,
          { body: replyBody, mediaIds, replyToId: replyTarget?.id ?? null },
          identity,
          mutationKey,
        ),
      );
      setReplyBody('');
      setImages([]);
      setMutationKey(newMutationKey());
      const result = await api.comments(post.id);
      setComments(result.data);
      setMode('detail');
      setReplyTarget(null);
      setNotice('回复已提交，审核通过后公开。');
    } catch (error) {
      showError(error);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  // 受理后进入独立回执页，避免将收到举报误显示为已删除或已处理。
  async function submitReport(
    targetType: 'post' | 'comment',
    targetId: string,
  ) {
    if (lock.current || busy) return;
    setBusy(true);
    lock.current = true;
    try {
      await withIdentity((identity) =>
        api.report(
          {
            targetType,
            targetId,
            reasonCode: reportReason,
            detail: reportDetail,
          },
          identity,
          mutationKey,
        ),
      );
      setMode('report-done');
      setMutationKey(newMutationKey());
      setNotice('举报已提交，平台将进行人工处理。');
    } catch (error) {
      showError(error);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function deleteOwn(item: OwnContent) {
    if (lock.current || busy) return;
    setBusy(true);
    lock.current = true;
    try {
      await withIdentity((identity) =>
        item.kind === 'posts'
          ? api.deletePost(item.id, identity)
          : api.deleteComment(item.id, identity),
      );
      setOwnItems((current) => current.filter((value) => value.id !== item.id));
      setNotice('内容已删除。');
    } catch (error) {
      showError(error);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  const addImageControls = (limit: number) => (
    <>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {images.map((asset, index) => (
          <View key={`${asset.uri}-${index}`} style={{ position: 'relative' }}>
            <Image
              source={{ uri: asset.uri }}
              style={{ width: 104, height: 92, borderRadius: 12 }}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`移除图片 ${index + 1}`}
              onPress={() =>
                setImages((current) =>
                  current.filter((_, position) => position !== index),
                )
              }
              style={{
                position: 'absolute',
                right: 4,
                top: 4,
                width: 26,
                height: 26,
                borderRadius: 13,
                alignItems: 'center',
                justifyContent: 'center',
                backgroundColor: 'rgba(23, 61, 65, 0.78)',
              }}
            >
              <Text style={{ color: theme.surface, fontWeight: '700' }}>×</Text>
            </Pressable>
          </View>
        ))}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="添加图片"
          disabled={busy || images.length >= limit}
          onPress={() => void pickImages(limit)}
          style={{
            width: 104,
            height: 92,
            borderRadius: 12,
            borderWidth: 1,
            borderStyle: 'dashed',
            borderColor: '#B8CDD0',
            alignItems: 'center',
            justifyContent: 'center',
            opacity: busy || images.length >= limit ? 0.5 : 1,
          }}
        >
          <Text style={{ color: theme.primary, fontSize: 13 }}>
            ＋ 添加图片
          </Text>
        </Pressable>
      </View>
      <Text style={{ color: theme.muted, fontSize: 12, lineHeight: 19 }}>
        最多 {limit} 张；支持 JPEG、PNG、WebP，单张不超过 5 MB、2000 万像素。
      </Text>
    </>
  );

  if (mode === 'search')
    return (
      <Page title="搜索与筛选" onBack={() => setMode('feed')}>
        <Field label="搜索讨论" value={query} onChange={setQuery} />
        <Text style={styles.label}>分类</Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {[
            ['', '全部'],
            ['interest', '兴趣'],
            ['city', '同城'],
          ].map(([value, label]) => (
            <FilterPill
              key={value || 'all'}
              label={label}
              selected={category === value}
              onPress={() => setCategory(value)}
            />
          ))}
        </View>
        <Text style={styles.label}>地区</Text>
        <Action
          label={
            regionCode ? (selectedRegion?.nameZh ?? regionCode) : '不限地区'
          }
          secondary
          onPress={() => setShowRegionOptions((value) => !value)}
        />
        {showRegionOptions && regionParent ? (
          <Action
            label="返回都道府县"
            secondary
            onPress={() => void showPrefectures()}
          />
        ) : null}
        {showRegionOptions
          ? regions.map((region) => (
              <Action
                key={region.code}
                label={`${region.nameZh} · ${region.nameJa}`}
                secondary={regionCode !== region.code}
                selected={regionCode === region.code}
                onPress={() => void chooseRegion(region)}
              />
            ))
          : null}
        <Text style={styles.label}>兴趣</Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {interestOptions.map((interest) => (
            <FilterPill
              key={interest.id}
              label={interest.nameZh}
              selected={interestId === interest.id}
              onPress={() =>
                setInterestId((current) =>
                  current === interest.id ? '' : interest.id,
                )
              }
            />
          ))}
        </View>
        <Notice>排序：最新发布</Notice>
        <Action
          label="清除筛选"
          secondary
          onPress={() => {
            setQuery('');
            setCategory('');
            setRegionCode('');
            setSelectedRegion(null);
            setRegionParent(null);
            setInterestId('');
          }}
        />
        <Action
          label="应用筛选"
          onPress={() => {
            setAppliedQuery(query.trim());
            setAttempt((value) => value + 1);
            setMode('feed');
          }}
        />
      </Page>
    );

  if (mode === 'compose')
    return (
      <Page
        title="发布讨论"
        onBack={() => setMode('feed')}
        headerAction={
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="保存草稿"
            onPress={() => {
              setSavedDraft({
                title,
                body,
                category: composeCategory,
                regionCode,
                selectedRegion,
                interestIds: composeInterestIds,
                images,
              });
              setNotice('草稿已暂存到当前应用会话，关闭应用后不会保留。');
            }}
            style={{ paddingHorizontal: 8, paddingVertical: 10 }}
          >
            <Text style={{ color: theme.primary, fontSize: 13 }}>保存草稿</Text>
          </Pressable>
        }
        footer={
          <View
            style={{ paddingHorizontal: 20, paddingTop: 10, paddingBottom: 8 }}
          >
            <Action
              label={busy ? '正在提交' : '发布讨论'}
              disabled={busy}
              onPress={() => void submitPost()}
            />
          </View>
        }
      >
        <View
          style={{
            padding: 14,
            borderRadius: 12,
            borderWidth: 1,
            borderColor: '#C8E0DA',
            backgroundColor: '#E5F3EF',
          }}
        >
          <Text style={{ color: theme.muted, fontSize: 13, lineHeight: 20 }}>
            游客发布 · 无需注册。请保留本机数据，方便管理内容。
          </Text>
        </View>
        <Text style={{ color: theme.muted, fontSize: 13, lineHeight: 21 }}>
          首次提交后进入人工审核；审核通过才公开，公开内容保留一年。
        </Text>
        <Field
          label="标题 *"
          value={title}
          editable={!busy}
          maxLength={60}
          onChange={setTitle}
        />
        <Field
          label="正文 *"
          value={body}
          multiline
          editable={!busy}
          maxLength={2000}
          onChange={setBody}
        />
        <Text style={{ color: theme.muted, fontSize: 12 }}>
          {body.length > 0 ? `${body.length} / 2000 字` : '1–2000 字'}
        </Text>
        <View style={{ gap: 8 }}>
          <View
            style={{ flexDirection: 'row', justifyContent: 'space-between' }}
          >
            <Text style={styles.label}>图片</Text>
            <Text style={{ color: theme.primary, fontSize: 12 }}>
              {images.length} / 9
            </Text>
          </View>
          {addImageControls(9)}
          <Text style={{ color: theme.muted, fontSize: 12 }}>
            图片审核通过后才会公开展示。
          </Text>
        </View>
        <Text style={styles.label}>分类 *</Text>
        <Action
          label={`${composeCategory === 'general' ? '综合' : composeCategory === 'interest' ? '兴趣' : '城市'}　⌄`}
          secondary
          onPress={() => setShowCategoryOptions((open) => !open)}
        />
        {showCategoryOptions ? (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            {(['general', 'interest', 'city'] as const).map((value) => (
              <FilterPill
                key={value}
                label={
                  value === 'general'
                    ? '综合'
                    : value === 'interest'
                      ? '兴趣'
                      : '城市'
                }
                selected={composeCategory === value}
                onPress={() => {
                  setComposeCategory(value);
                  setShowCategoryOptions(false);
                }}
              />
            ))}
          </View>
        ) : null}
        <Text style={styles.label}>地区（可选）</Text>
        <Action
          label={
            regionCode
              ? `地区 · ${selectedRegion?.nameZh ?? regionCode}`
              : '选择地区（可选）'
          }
          secondary
          onPress={() => setShowRegionOptions((value) => !value)}
        />
        {regionCode ? (
          <Notice>已选择：{selectedRegion?.nameZh ?? regionCode}</Notice>
        ) : null}
        {showRegionOptions && regionParent ? (
          <Action
            label="返回都道府县"
            secondary
            onPress={() => void showPrefectures()}
          />
        ) : null}
        {showRegionOptions && regions.length ? (
          <View style={{ gap: 8 }}>
            {regions.map((region) => (
              <Action
                key={region.code}
                label={`${region.nameZh} · ${region.nameJa}`}
                secondary={regionCode !== region.code}
                onPress={() => void chooseRegion(region)}
              />
            ))}
          </View>
        ) : showRegionOptions ? (
          <Notice>地区目录暂不可用，可以稍后重试。</Notice>
        ) : null}
        <Text style={styles.label}>兴趣标签（可选）</Text>
        <Action
          label={
            composeInterestIds.length
              ? `已选 ${composeInterestIds.length} 个兴趣`
              : '选择兴趣标签'
          }
          secondary
          onPress={() => setShowInterestOptions((value) => !value)}
        />
        {showInterestOptions && interestOptions.length ? (
          <View style={{ gap: 8 }}>
            {interestOptions.map((interest) => (
              <Action
                key={interest.id}
                label={interest.nameZh}
                secondary={!composeInterestIds.includes(interest.id)}
                onPress={() =>
                  setComposeInterestIds((current) =>
                    current.includes(interest.id)
                      ? current.filter((id) => id !== interest.id)
                      : [...current, interest.id],
                  )
                }
              />
            ))}
          </View>
        ) : showInterestOptions ? (
          <Notice>暂时没有可选兴趣标签。</Notice>
        ) : null}
        {notice ? <Notice>{notice}</Notice> : null}
      </Page>
    );

  if (mode === 'detail' && post)
    return (
      <Page title={post.title} onBack={() => setMode('feed')}>
        <Notice>{post.body}</Notice>
        <Notice>
          {displayAuthor(post.authorDisplay)} · 回复 {post.replyCount}
        </Notice>
        {post.images.map((image) => (
          <Image
            key={image.id}
            source={{ uri: image.url }}
            accessibilityLabel="留言图片"
            style={{ width: '100%', height: 220, borderRadius: 12 }}
            resizeMode="cover"
          />
        ))}
        <Action
          label="举报这条留言"
          secondary
          onPress={() => {
            setSelected(null);
            setMode('report');
            setMutationKey(newMutationKey());
          }}
        />
        <Text style={styles.title}>回复</Text>
        {comments.map((comment) => (
          <View
            key={comment.id}
            style={{
              padding: 14,
              backgroundColor: theme.surface,
              borderRadius: 12,
              gap: 8,
            }}
          >
            <Text style={styles.rowLabel}>
              {displayAuthor(comment.authorDisplay)}
            </Text>
            <Text style={styles.rowLabel}>{comment.body}</Text>
            {comment.images.map((image) => (
              <Image
                key={image.id}
                source={{ uri: image.url }}
                accessibilityLabel="回复图片"
                style={{ width: '100%', height: 160, borderRadius: 10 }}
                resizeMode="cover"
              />
            ))}
            {/* 引用仅在一层内展示，避免回复树在窄屏上无限缩进。 */}
            {comment.replyToId ? (
              <Text style={{ color: theme.muted, fontSize: 12 }}>
                回复了讨论中的另一条内容
              </Text>
            ) : null}
            <Action
              label="回复这条内容"
              secondary
              onPress={() => {
                setReplyTarget(comment);
                setReplyBody('');
                setImages([]);
                setMode('reply');
              }}
            />
            <Action
              label="举报回复"
              secondary
              onPress={() => {
                setReportReason('spam');
                setReportDetail('');
                setSelected({ kind: 'comments', id: comment.id } as OwnContent);
                setMode('report');
                setMutationKey(newMutationKey());
              }}
            />
          </View>
        ))}
        <Action
          label="写回复"
          onPress={() => {
            setReplyTarget(null);
            setReplyBody('');
            setImages([]);
            setMode('reply');
          }}
        />
        {notice ? <Notice>{notice}</Notice> : null}
      </Page>
    );

  if (mode === 'report')
    return (
      <Page title="举报内容" onBack={() => setMode('detail')}>
        <Notice>举报由人工处理，提交举报不会自动隐藏被举报内容。</Notice>
        <Notice>
          举报对象：{selected ? '回复内容' : (post?.title ?? '当前内容')}
        </Notice>
        {(['spam', 'abuse', 'illegal', 'privacy', 'other'] as const).map(
          (reason) => (
            <Action
              key={reason}
              label={
                {
                  spam: '垃圾广告 / 刷屏',
                  abuse: '骚扰 / 不友善',
                  illegal: '诈骗 / 虚假信息',
                  privacy: '泄露个人隐私',
                  other: '其他问题',
                }[reason]
              }
              secondary={reportReason !== reason}
              onPress={() => setReportReason(reason)}
            />
          ),
        )}
        <Field
          label="补充说明（可选）"
          value={reportDetail}
          onChange={setReportDetail}
          multiline
          editable={!busy}
        />
        <Notice>游客也可以举报。提交表示受理，不代表内容会立即删除。</Notice>
        <Action
          label="提交举报"
          disabled={busy}
          onPress={() =>
            selected
              ? void submitReport('comment', selected.id)
              : post
                ? void submitReport('post', post.id)
                : undefined
          }
        />
        {notice ? <Notice>{notice}</Notice> : null}
      </Page>
    );

  // 回复页同时呈现引用上下文、游客身份与图片额度，提交后回到详情。
  if (mode === 'reply' && post)
    return (
      <Page title="回复讨论" onBack={() => setMode('detail')}>
        {replyTarget ? (
          <Notice>
            回复 {displayAuthor(replyTarget.authorDisplay)}：{replyTarget.body}
          </Notice>
        ) : null}
        <Field
          label="回复内容"
          value={replyBody}
          onChange={setReplyBody}
          multiline
          editable={!busy}
        />
        <Text style={styles.label}>图片（{images.length}/3）</Text>
        {addImageControls(3)}
        <Notice>你将以游客身份回复。同一帖子中保持这个标识。</Notice>
        <Action
          label={busy ? '正在发布' : '发布回复'}
          disabled={busy}
          onPress={() => void submitReply()}
        />
        {notice ? <Notice>{notice}</Notice> : null}
      </Page>
    );

  // 举报回执只确认收到，不承诺审核结论或立即隐藏内容。
  if (mode === 'report-done')
    return (
      <Page title="举报已受理" onBack={() => setMode('detail')}>
        <View style={{ alignItems: 'center', gap: 12, paddingVertical: 42 }}>
          <Text style={{ color: theme.primary, fontSize: 42 }}>✓</Text>
          <Text accessibilityRole="header" style={styles.title}>
            举报已提交
          </Text>
          <Text
            style={{ color: theme.muted, textAlign: 'center', lineHeight: 22 }}
          >
            我们会按社区规则处理。你可以返回继续浏览。
          </Text>
        </View>
        <Notice>提交举报不会自动删除内容或退出群聊。</Notice>
        <Action
          label="返回原页面"
          secondary
          onPress={() => setMode('detail')}
        />
      </Page>
    );

  if (mode === 'mine')
    return (
      <Page title="我的内容" onBack={() => setMode('feed')}>
        {/* 内容类型只控制留言或回复；与下方审核状态筛选相互独立。 */}
        <View
          style={{
            gap: 10,
            padding: 14,
            borderRadius: 16,
            backgroundColor: theme.surface,
          }}
        >
          <Text accessibilityRole="header" style={styles.label}>
            内容类型
          </Text>
          <View style={{ flexDirection: 'row', gap: 8 }}>
            {(['posts', 'comments'] as const).map((kind) => (
              <View key={kind} style={{ flex: 1 }}>
                <Action
                  label={kind === 'posts' ? '我的留言' : '我的回复'}
                  secondary={ownKind !== kind}
                  selected={ownKind === kind}
                  onPress={() => void openMine(kind, statusFilter)}
                />
              </View>
            ))}
          </View>
        </View>
        <View
          style={{
            gap: 10,
            padding: 14,
            borderRadius: 16,
            backgroundColor: theme.surface,
          }}
        >
          <Text accessibilityRole="header" style={styles.label}>
            审核状态
          </Text>
          <View
            style={{
              flexDirection: 'row',
              flexWrap: 'wrap',
              justifyContent: 'space-between',
              rowGap: 8,
            }}
          >
            {['', 'pending', 'visible', 'rejected', 'hidden', 'expired'].map(
              (status) => (
                <View key={status || 'all'} style={{ width: '48%' }}>
                  <Action
                    label={STATUS_LABELS[status] ?? status}
                    secondary={statusFilter !== status}
                    selected={statusFilter === status}
                    onPress={() => {
                      setStatusFilter(status);
                      void openMine(ownKind, status);
                    }}
                  />
                </View>
              ),
            )}
          </View>
        </View>
        {/* 浅绿色摘要承载两组筛选的组合结果，并通过无障碍标签朗读当前条件。 */}
        <View
          accessibilityLabel={`正在查看：${ownKind === 'posts' ? '我的留言' : '我的回复'} · ${STATUS_LABELS[statusFilter] ?? statusFilter}`}
          accessibilityLiveRegion="polite"
          style={{
            gap: 3,
            marginBottom: 14,
            padding: 12,
            borderRadius: 11,
            borderColor: '#CBDFDA',
            borderWidth: 1,
            backgroundColor: '#E5F3EF',
          }}
        >
          <Text style={{ color: theme.muted, fontSize: 12 }}>正在查看</Text>
          <Text style={{ color: theme.ink, fontSize: 14, fontWeight: '600' }}>
            {ownKind === 'posts' ? '我的留言' : '我的回复'} ·{' '}
            {STATUS_LABELS[statusFilter] ?? statusFilter}
          </Text>
        </View>
        <Notice>
          这些内容由本机游客凭证管理。注册后可接续，不会自动公开关联你的账号。
        </Notice>
        {loading ? <Notice>正在读取内容。</Notice> : null}
        {ownItems.length === 0 && !loading ? (
          <Notice>这里暂时没有内容。</Notice>
        ) : null}
        {ownItems.map((item) => (
          <View
            key={item.id}
            style={{
              gap: 8,
              padding: 14,
              backgroundColor: theme.surface,
              borderRadius: 12,
            }}
          >
            <Text style={styles.label}>
              {'unavailable' in item
                ? item.kind === 'posts'
                  ? '留言'
                  : '回复'
                : (item.title ?? (item.kind === 'posts' ? '无标题' : '回复'))}
            </Text>
            <Text style={styles.rowLabel}>
              状态：
              {{
                pending: '待审核',
                visible: '已公开',
                rejected: '需修改',
                hidden: '已隐藏',
                expired: '已到期',
                deleted: '已删除',
              }[item.status] ?? item.status}
            </Text>
            <Text style={styles.rowLabel}>
              {'unavailable' in item ? '内容不可用' : item.body || '内容不可用'}
            </Text>
            {'unavailable' in item ? null : item.parentUnavailable ? (
              <Notice>原留言已到期或不可见，回复无法公开查看。</Notice>
            ) : null}
            {['pending', 'rejected'].includes(item.status) ? (
              <Action
                label="编辑并重新提交"
                secondary
                onPress={() => {
                  if ('unavailable' in item) return;
                  setSelected(item);
                  setTitle(item.title ?? '');
                  setBody(item.body);
                  setComposeCategory(item.category ?? 'general');
                  setRegionCode(item.regionCode ?? '');
                  setComposeInterestIds(item.interestIds ?? []);
                  setImages([]);
                  setMode('edit');
                }}
              />
            ) : null}
            {['pending', 'rejected', 'visible', 'hidden'].includes(
              item.status,
            ) ? (
              <Action
                label="删除内容"
                secondary
                onPress={() => void deleteOwn(item)}
              />
            ) : null}
          </View>
        ))}
        {notice ? <Notice>{notice}</Notice> : null}
      </Page>
    );

  if (mode === 'edit' && selected && !('unavailable' in selected))
    return (
      <Page title="修改并重新审核" onBack={() => setMode('mine')}>
        <Notice>
          保存后会重新进入人工审核；原公开内容不会被这次编辑立即覆盖。
        </Notice>
        {selected.kind === 'posts' ? (
          <Field
            label="标题"
            value={title}
            onChange={setTitle}
            editable={!busy}
          />
        ) : null}
        <Field
          label="内容"
          value={body}
          onChange={setBody}
          multiline
          editable={!busy}
        />
        <Action
          label="保存修改并重新审核"
          disabled={busy}
          onPress={() => {
            if (busy || lock.current || !selected) return;
            lock.current = true;
            setBusy(true);
            setNotice('正在保存修改。');
            void withIdentity((identity) =>
              selected.kind === 'posts'
                ? api.updatePost(
                    selected.id,
                    selected.version,
                    {
                      title,
                      body,
                      category: composeCategory,
                      regionCode: regionCode || null,
                      interestIds: composeInterestIds,
                    },
                    identity,
                  )
                : api.updateComment(
                    selected.id,
                    selected.version,
                    { body },
                    identity,
                  ),
            )
              .then(() => {
                setNotice('修改已提交，等待人工重新审核。');
                setMode('mine');
                return openMine(selected.kind, 'pending');
              })
              .catch(showError)
              .finally(() => {
                lock.current = false;
                setBusy(false);
              });
          }}
        />
        {notice ? <Notice>{notice}</Notice> : null}
      </Page>
    );

  return (
    <Page
      title="留言板首页"
      onBack={onBack}
      showBack={false}
      headerAction={
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="搜索 · 地区"
          onPress={() => {
            setQuery(appliedQuery);
            setMode('search');
          }}
          style={{ paddingHorizontal: 8, paddingVertical: 10 }}
        >
          <Text style={{ color: theme.primary, fontSize: 13 }}>
            搜索 · 地区
          </Text>
        </Pressable>
      }
      footer={
        <BoardBottomNavigation
          onSelect={(item) => {
            if (item === '留言板') {
              setNotice('');
              return;
            }
            if (item === '我的') {
              void openMine();
              return;
            }
            setNotice(`${item}页面将在后续阶段开放。`);
          }}
        />
      }
      floatingAction={
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="发帖"
          onPress={startCompose}
          style={{
            minWidth: 112,
            minHeight: 48,
            paddingHorizontal: 18,
            borderRadius: 24,
            backgroundColor: theme.primary,
            justifyContent: 'center',
            alignItems: 'center',
            elevation: 5,
          }}
        >
          <Text
            style={{ color: theme.surface, fontWeight: '700', fontSize: 14 }}
          >
            ＋ 发帖
          </Text>
        </Pressable>
      }
    >
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {[
          ['', '全部'],
          ['interest', '兴趣'],
          ['city', '同城'],
        ].map(([value, label]) => (
          <FilterPill
            key={value || 'all'}
            label={label}
            selected={category === value}
            onPress={() => {
              setCategory(value);
              setAttempt((current) => current + 1);
            }}
          />
        ))}
      </View>
      <View
        style={{
          flexDirection: 'row',
          justifyContent: 'space-between',
          alignItems: 'center',
          paddingVertical: 2,
        }}
      >
        <View>
          <Text style={{ color: theme.muted, fontSize: 12 }}>
            {regionCode
              ? `${selectedRegion?.nameZh ?? regionCode} · `
              : '不限地区 · '}
            {interestId
              ? interestOptions.find((item) => item.id === interestId)?.nameZh
              : '兴趣筛选'}
          </Text>
        </View>
        <Text style={{ color: theme.muted, fontSize: 12 }}>最新 ↓</Text>
      </View>
      {notice ? <Notice>{notice}</Notice> : null}
      {loading ? <Notice>正在读取公开留言。</Notice> : null}
      {posts.length === 0 && !loading ? (
        <View
          style={{
            alignItems: 'center',
            padding: 24,
            borderRadius: 18,
            backgroundColor: theme.surface,
            borderColor: '#D8E2E1',
            borderWidth: 1,
            gap: 8,
          }}
        >
          <Text style={{ color: theme.primary, fontSize: 30 }}>▤</Text>
          <Text style={{ color: theme.ink, fontWeight: '700', fontSize: 17 }}>
            这里还没有讨论
          </Text>
          <Text
            style={{
              color: theme.muted,
              textAlign: 'center',
              fontSize: 14,
              lineHeight: 22,
            }}
          >
            还没有公开留言。你可以写下第一条，审核通过后大家就能看到。
          </Text>
        </View>
      ) : null}
      {posts.map((item) => (
        <Pressable
          key={item.id}
          accessibilityRole="button"
          accessibilityLabel={`打开留言：${item.title}`}
          onPress={() => void openPost(item)}
          style={{
            gap: 8,
            padding: 14,
            backgroundColor: theme.surface,
            borderColor: '#D8E2E1',
            borderWidth: 1,
            borderRadius: 18,
          }}
        >
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
            {item.interests.map((interest, index) => (
              <Text
                key={`interest-${index}`}
                style={{
                  backgroundColor: '#E8F3EF',
                  color: theme.primary,
                  paddingHorizontal: 7,
                  paddingVertical: 4,
                  borderRadius: 5,
                  fontSize: 11,
                }}
              >
                {readableName(interest)}
              </Text>
            ))}
            {readableName(item.region) ? (
              <Text
                style={{
                  backgroundColor: '#E8F3EF',
                  color: theme.primary,
                  paddingHorizontal: 7,
                  paddingVertical: 4,
                  borderRadius: 5,
                  fontSize: 11,
                }}
              >
                {readableName(item.region)}
              </Text>
            ) : null}
          </View>
          <Text
            accessibilityRole="header"
            style={{ color: theme.ink, fontSize: 16, fontWeight: '700' }}
          >
            {item.title}
          </Text>
          <Text
            numberOfLines={3}
            style={{ color: theme.ink, fontSize: 14, lineHeight: 22 }}
          >
            {item.body}
          </Text>
          {item.images.slice(0, 1).map((image: BoardImage) => (
            <Image
              key={image.id}
              source={{ uri: image.url }}
              accessibilityLabel="留言图片"
              style={{ width: '100%', height: 160, borderRadius: 12 }}
              resizeMode="cover"
            />
          ))}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <View
              style={{
                width: 32,
                height: 32,
                borderRadius: 16,
                backgroundColor: '#E1F0EC',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Text style={{ color: theme.primary, fontWeight: '600' }}>
                {displayAuthor(item.authorDisplay).slice(0, 1)}
              </Text>
            </View>
            <Text style={{ color: theme.muted, fontSize: 11, flex: 1 }}>
              {displayAuthor(item.authorDisplay)} ·{' '}
              {relativeTime(item.publishedAt)}
            </Text>
            <Text style={{ color: theme.muted, fontSize: 11 }}>
              {item.replyCount} 条回复
            </Text>
          </View>
        </Pressable>
      ))}
      {hasMore && nextCursor ? (
        <Action
          label="加载更多"
          secondary
          disabled={loading}
          onPress={() => {
            const cursor = nextCursor;
            setLoading(true);
            void api
              .posts({
                category: category || undefined,
                regionCode: regionCode || undefined,
                interestId: interestId || undefined,
                q: appliedQuery || undefined,
                cursor,
              })
              .then((result: BoardPage<PublicPost>) => {
                setPosts((current) => [...current, ...result.data]);
                setNextCursor(result.page.nextCursor);
                setHasMore(result.page.hasMore);
              })
              .catch(showError)
              .finally(() => setLoading(false));
          }}
        />
      ) : null}
    </Page>
  );
}

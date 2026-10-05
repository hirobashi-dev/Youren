import { useEffect, useState } from 'react';
import { Text } from 'react-native';
import { ProfileApi } from '../api/profile';
import { ApiError, safeMessage } from '../api/client';
import type { PublicProfile } from '../api/types';
import { SessionStore } from '../auth/sessionStore';
import { Action, Notice, Page, styles } from '../components/Form';
// 公开视图只请求公开DTO，不借用本人草稿；首次待审404不伪装已发布。
export function PublicProfileScreen({
  profileApi,
  store,
  accountId,
  onBack,
}: {
  profileApi: ProfileApi;
  store: SessionStore;
  accountId: string;
  onBack: () => void;
}) {
  const [profile, setProfile] = useState<PublicProfile | null>(null);
  const [message, setMessage] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setProfile(null);
    setMessage('正在读取公开资料。');
    const request =
      store.getSnapshot().status === 'ready'
        ? store.authorized((token) =>
            profileApi.publicProfile(accountId, token, controller.signal),
          )
        : profileApi.publicProfile(accountId, undefined, controller.signal);
    void request
      .then((value) => {
        if (!controller.signal.aborted) {
          setProfile(value);
          setMessage('');
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setMessage(
            error instanceof ApiError && error.status === 404
              ? '资料尚未公开，审核通过后才能在这里看到。'
              : safeMessage(error),
          );
      })
      .finally(() => {
        if (!controller.signal.aborted) setBusy(false);
      });
    return () => controller.abort();
  }, [accountId, attempt, profileApi, store]);
  return (
    <Page title="公开主页" onBack={onBack}>
      {profile && (
        <>
          <Text style={styles.title}>{profile.nickname}</Text>
          <Notice>{profile.bio || '暂无简介。'}</Notice>
          {profile.region ? <Notice>{profile.region.nameZh}</Notice> : null}
          {profile.interests.length ? (
            <Notice>
              {profile.interests.map((interest) => interest.nameZh).join('、')}
            </Notice>
          ) : null}
        </>
      )}
      {message ? <Notice>{message}</Notice> : null}
      <Action
        label="重新读取公开资料"
        secondary
        disabled={busy}
        onPress={() => setAttempt((value) => value + 1)}
      />
    </Page>
  );
}

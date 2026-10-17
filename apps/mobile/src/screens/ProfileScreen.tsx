import { useEffect, useRef, useState } from 'react';
import { ProfileApi } from '../api/profile';
import { safeMessage } from '../api/client';
import type { Me } from '../api/types';
import { SessionStore } from '../auth/sessionStore';
import { Action, Field, Notice, Page } from '../components/Form';
// 编辑本人最新草稿，版本冲突保留输入；长度由服务端扩展字素规则判定。
export function ProfileScreen({
  profileApi,
  store,
  onBack,
}: {
  profileApi: ProfileApi;
  store: SessionStore;
  onBack: () => void;
}) {
  const [me, setMe] = useState<Me | null>(null);
  const [nickname, setNickname] = useState('');
  const [bio, setBio] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const lock = useRef(false);
  const lifecycle = useRef(new AbortController());
  useEffect(() => {
    const controller = lifecycle.current;
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    lock.current = true;
    setMessage('正在读取资料。');
    void store
      .authorized((token) => profileApi.me(token, controller.signal))
      .then((value) => {
        if (controller.signal.aborted) return;
        setMe(value);
        store.updateAccount(value);
        setNickname(value.profile?.nickname ?? '');
        setBio(value.profile?.bio ?? '');
        setMessage('');
      })
      .catch((error) => {
        if (!controller.signal.aborted) setMessage(safeMessage(error));
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setBusy(false);
          lock.current = false;
        }
      });
    return () => controller.abort();
  }, [attempt, profileApi, store]);
  async function save() {
    if (lock.current || !me) return;
    if (!nickname.trim()) {
      setMessage('请填写昵称。');
      return;
    }
    lock.current = true;
    setBusy(true);
    setMessage('');
    try {
      const result = await store.authorized((token) =>
        profileApi.save(
          token,
          me.profileVersion ?? 0,
          { nickname, bio },
          lifecycle.current.signal,
        ),
      );
      if (!lifecycle.current.signal.aborted) {
        const next = {
          ...me,
          profile: result.profile,
          profileVersion: result.version,
          onboardingRequired: false,
        };
        setMe(next);
        store.updateAccount(next);
        setMessage(
          result.profile.moderationStatus === 'approved'
            ? '资料已保存。'
            : result.profile.moderationStatus === 'rejected'
              ? '资料已保存，请根据审核结果修改。'
              : '资料已保存，待审核后公开。',
        );
      }
    } catch (error) {
      if (!lifecycle.current.signal.aborted) setMessage(safeMessage(error));
    } finally {
      if (!lifecycle.current.signal.aborted) {
        setBusy(false);
        lock.current = false;
      }
    }
  }
  const moderation = {
    pending: '待审核',
    approved: '已通过审核',
    rejected: '未通过审核',
  };
  return (
    <Page title={me?.profile ? '编辑普通资料' : '完善普通资料'} onBack={onBack}>
      <Notice>昵称与简介用于普通主页。保存后不代表已经公开。</Notice>
      {me && (
        <>
          <Field
            label="昵称"
            value={nickname}
            editable={!busy}
            onChange={setNickname}
          />
          <Field
            label="简介"
            value={bio}
            multiline
            editable={!busy}
            onChange={setBio}
          />
          {me.profile && (
            <Notice>
              当前草稿：{moderation[me.profile.moderationStatus]}
              {me.profile.region ? `\n地区：${me.profile.region.nameZh}` : ''}
              {me.profile.interests.length
                ? `\n兴趣：${me.profile.interests.map((interest) => interest.nameZh).join('、')}`
                : ''}
            </Notice>
          )}
          <Notice>头像功能正在准备，暂时无法上传。</Notice>
          <Action
            label="保存资料"
            disabled={busy}
            onPress={() => void save()}
          />
        </>
      )}
      {message ? <Notice>{message}</Notice> : null}
      <Action
        label="重新读取资料"
        secondary
        disabled={busy}
        onPress={() => setAttempt((value) => value + 1)}
      />
    </Page>
  );
}

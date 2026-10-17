import { useEffect, useRef, useState } from 'react';
import { Switch, Text, View } from 'react-native';
import { ProfileApi } from '../api/profile';
import { safeMessage } from '../api/client';
import type { Settings } from '../api/types';
import { SessionStore } from '../auth/sessionStore';
import { Action, Notice, Page, styles } from '../components/Form';
// 设置只在服务端确认后更新；推送设置不虚构设备已注册或系统权限已开启。
export function SettingsScreen({
  profileApi,
  store,
  onBack,
}: {
  profileApi: ProfileApi;
  store: SessionStore;
  onBack: () => void;
}) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [attempt, setAttempt] = useState(0);
  const lifecycle = useRef(new AbortController());
  const lock = useRef(false);
  useEffect(() => {
    const controller = lifecycle.current;
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    lock.current = true;
    void store
      .authorized((token) => profileApi.me(token, controller.signal))
      .then((me) => {
        if (!controller.signal.aborted) {
          setSettings(me.settings);
          store.updateAccount(me);
          setMessage('');
        }
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
  async function change(key: keyof Settings, value: boolean) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    try {
      const result = await store.authorized((token) =>
        profileApi.settings(token, { [key]: value }, lifecycle.current.signal),
      );
      if (!lifecycle.current.signal.aborted) {
        setSettings(result);
        const me = store.getSnapshot().account;
        if (me) store.updateAccount({ ...me, settings: result });
        setMessage('设置已保存。');
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
  const labels: Record<keyof Settings, string> = {
    allowStrangerDm: '允许陌生人私聊',
    pushEnabled: '接收推送通知',
    lockscreenPreview: '锁屏显示消息预览',
  };
  return (
    <Page title="隐私与通知" onBack={onBack}>
      {settings &&
        (Object.keys(labels) as (keyof Settings)[]).map((key) => (
          <View key={key} style={styles.row}>
            <Text style={styles.rowLabel}>{labels[key]}</Text>
            <Switch
              accessibilityRole="switch"
              accessibilityLabel={labels[key]}
              accessibilityState={{ checked: settings[key], disabled: busy }}
              value={settings[key]}
              disabled={busy}
              onValueChange={(value) => void change(key, value)}
            />
          </View>
        ))}
      <Notice>
        关闭陌生人私聊不影响已有联系，屏蔽仍会阻止双方通信。通知偏好还需要系统权限与推送功能支持。
      </Notice>
      {message ? <Notice>{message}</Notice> : null}
      <Action
        label="重新读取设置"
        secondary
        disabled={busy}
        onPress={() => setAttempt((value) => value + 1)}
      />
      <Action
        label="退出登录"
        disabled={busy}
        onPress={() => void store.logout()}
      />
    </Page>
  );
}

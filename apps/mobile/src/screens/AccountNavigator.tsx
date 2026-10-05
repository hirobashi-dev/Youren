import { useEffect, useState, useSyncExternalStore } from 'react';
import { Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { AuthApi } from '../api/auth';
import { ProfileApi } from '../api/profile';
import { safeMessage } from '../api/client';
import { SessionStore } from '../auth/sessionStore';
import { Action, Notice, styles } from '../components/Form';
import { FoundationScreen } from './FoundationScreen';
import { AuthScreen } from './AuthScreen';
import { ProfileScreen } from './ProfileScreen';
import { SettingsScreen } from './SettingsScreen';
import { PublicProfileScreen } from './PublicProfileScreen';
// 浏览始终可用；私有画面只在持久化会话有效时显示，不展示恋爱或年龄字段。
export function AccountNavigator({
  store,
  auth,
  profileApi,
}: {
  store: SessionStore;
  auth: AuthApi;
  profileApi: ProfileApi;
}) {
  const state = useSyncExternalStore(
    store.subscribe,
    store.getSnapshot,
    store.getSnapshot,
  );
  const [route, setRoute] = useState<
    'home' | 'auth' | 'profile' | 'settings' | 'public'
  >('home');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  useEffect(() => {
    void store.restore();
  }, [store]);
  const onBack = () => setRoute('home');
  if (route === 'auth')
    return (
      <AuthScreen
        auth={auth}
        store={store}
        onBack={onBack}
        onDone={() =>
          setRoute(
            store.getSnapshot().account?.onboardingRequired
              ? 'profile'
              : 'home',
          )
        }
      />
    );
  if (state.status === 'ready') {
    if (route === 'profile')
      return (
        <ProfileScreen store={store} profileApi={profileApi} onBack={onBack} />
      );
    if (route === 'settings')
      return (
        <SettingsScreen store={store} profileApi={profileApi} onBack={onBack} />
      );
    if (route === 'public' && state.account)
      return (
        <PublicProfileScreen
          accountId={state.account.id}
          store={store}
          profileApi={profileApi}
          onBack={onBack}
        />
      );
  }
  async function saveGuest() {
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      await store.createGuest();
    } catch (error) {
      setMessage(safeMessage(error));
    } finally {
      setBusy(false);
    }
  }
  async function claim() {
    if (busy) return;
    setBusy(true);
    await store.claimGuest();
    setBusy(false);
  }
  return (
    <View style={styles.flex}>
      <SafeAreaView edges={['top']} style={{ padding: 16, gap: 8 }}>
        <Text style={styles.label}>
          {state.status === 'ready' ? '我的账号' : '游客浏览'}
        </Text>
        {state.message || message ? (
          <Notice>{message || state.message}</Notice>
        ) : null}
        {state.status === 'ready' ? (
          <>
            <View style={styles.row}>
              <View style={styles.flex}>
                <Action label="编辑资料" onPress={() => setRoute('profile')} />
              </View>
              <View style={styles.flex}>
                <Action
                  label="设置"
                  secondary
                  onPress={() => setRoute('settings')}
                />
              </View>
            </View>
            <Action
              label="查看公开主页"
              secondary
              onPress={() => setRoute('public')}
            />
            {state.claimPending ? (
              <Action
                label="重试接续游客身份"
                disabled={busy}
                secondary
                onPress={() => void claim()}
              />
            ) : null}
          </>
        ) : (
          <>
            <Action
              label="登录或注册"
              disabled={state.status === 'loading'}
              onPress={() => setRoute('auth')}
            />
            {state.status === 'offline' ? (
              <Action
                label="恢复登录"
                secondary
                onPress={() => void store.refresh()}
              />
            ) : null}
            {state.status === 'reauth' || state.status === 'storage_error' ? (
              <Action
                label="清除本机登录信息"
                secondary
                onPress={() => void store.logout()}
              />
            ) : null}
            {state.status === 'guest' && !state.guestSaved ? (
              <Action
                label="保存游客身份"
                secondary
                disabled={busy}
                onPress={() => void saveGuest()}
              />
            ) : null}
            {state.guestSaved ? (
              <Notice>游客身份已保存在本机，可在登录时接续。</Notice>
            ) : null}
          </>
        )}
      </SafeAreaView>
      <FoundationScreen />
    </View>
  );
}

import { useEffect, useState, useSyncExternalStore } from 'react';
import { Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { AuthApi } from '../api/auth';
import { ProfileApi } from '../api/profile';
import { ApiClient } from '../api/client';
import { BoardApi } from '../api/board';
import { safeMessage } from '../api/client';
import { SessionStore } from '../auth/sessionStore';
import { Action, Notice, styles } from '../components/Form';
import { FoundationScreen } from './FoundationScreen';
import { AuthScreen } from './AuthScreen';
import { ProfileScreen } from './ProfileScreen';
import { SettingsScreen } from './SettingsScreen';
import { PublicProfileScreen } from './PublicProfileScreen';
import { BoardScreen } from './BoardScreen';
// 浏览始终可用；私有画面只在持久化会话有效时显示，留言板由独立路由承载。
const fallbackBoardApi = new BoardApi(new ApiClient());
export function AccountNavigator({
  store,
  auth,
  profileApi,
  boardApi = fallbackBoardApi,
}: {
  store: SessionStore;
  auth: AuthApi;
  profileApi: ProfileApi;
  boardApi?: BoardApi;
}) {
  const state = useSyncExternalStore(
    store.subscribe,
    store.getSnapshot,
    store.getSnapshot,
  );
  const [route, setRoute] = useState<
    'home' | 'auth' | 'profile' | 'settings' | 'public' | 'board'
  >('home');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  // 刷新或临时离线时隐藏私有页但保留组件，避免中断请求并丢失未提交草稿。
  const preservePrivateRoute =
    route === 'profile' || route === 'settings' || route === 'public';
  const privateRoute = () => {
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
    return null;
  };
  useEffect(() => {
    void store.restore();
  }, [store]);
  const onBack = () => setRoute('home');
  if (route === 'board')
    return <BoardScreen api={boardApi} store={store} onBack={onBack} />;
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
  const canPreservePrivateRoute =
    preservePrivateRoute &&
    ['ready', 'loading', 'offline'].includes(state.status);
  const privateVisible = state.status === 'ready';
  return (
    <View style={styles.flex}>
      {canPreservePrivateRoute ? (
        <View
          style={privateVisible ? styles.flex : { display: 'none' }}
          accessibilityElementsHidden={!privateVisible}
          importantForAccessibility={
            privateVisible ? 'auto' : 'no-hide-descendants'
          }
        >
          {privateRoute()}
        </View>
      ) : null}
      {privateVisible && preservePrivateRoute ? null : (
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
                    <Action
                      label="编辑资料"
                      onPress={() => setRoute('profile')}
                    />
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
                {state.status === 'reauth' ||
                state.status === 'storage_error' ? (
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
            <Action
              label="留言板"
              secondary
              onPress={() => setRoute('board')}
            />
          </SafeAreaView>
          <FoundationScreen />
        </View>
      )}
    </View>
  );
}

import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AccountNavigator } from './src/screens/AccountNavigator';
import { ApiClient } from './src/api/client';
import { AuthApi } from './src/api/auth';
import { ProfileApi } from './src/api/profile';
import { SessionStore } from './src/auth/sessionStore';
import { secureStorage } from './src/auth/secureStorage';
// 入口共用一个会话状态机，所有并发私有请求共享安全刷新锁。
const client = new ApiClient();
const auth = new AuthApi(client);
const store = new SessionStore(secureStorage, auth);
const profileApi = new ProfileApi(client);
export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      <AccountNavigator store={store} auth={auth} profileApi={profileApi} />
    </SafeAreaProvider>
  );
}

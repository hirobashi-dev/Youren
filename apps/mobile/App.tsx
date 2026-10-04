import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { FoundationScreen } from './src/screens/FoundationScreen';

// 手机入口统一提供安全区，基础阶段仅显示连接状态。
export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      <FoundationScreen />
    </SafeAreaProvider>
  );
}

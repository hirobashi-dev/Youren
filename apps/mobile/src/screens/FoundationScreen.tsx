import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getReadiness } from '../api/health';
import { theme } from '../theme';

// 卸载或新请求会取消旧连接，旧请求不能覆盖当前页面状态。
export function FoundationScreen() {
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<'loading' | 'ready' | 'unavailable'>(
    'loading',
  );
  useEffect(() => {
    const controller = new AbortController();
    setStatus('loading');
    void getReadiness(controller.signal).then((result) => {
      if (!controller.signal.aborted) setStatus(result);
    });
    return () => controller.abort();
  }, [attempt]);
  const labels = {
    loading: '连接中',
    ready: '连接成功',
    unavailable: '连接失败',
  };
  const descriptions = {
    loading: '正在连接服务，请稍候。',
    ready: '已连接服务，可以继续使用。',
    unavailable: '暂时无法连接服务，请检查网络后重试。',
  };
  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.brand}>友缘</Text>
        <Text style={styles.subtitle}>在日本，遇见同好</Text>
        <View style={styles.card}>
          <View style={styles.statusRow}>
            {status === 'loading' ? (
              <ActivityIndicator color={theme.primary} />
            ) : (
              <View
                style={[
                  styles.dot,
                  status === 'unavailable' && styles.offlineDot,
                ]}
              />
            )}
            <Text accessibilityLiveRegion="polite" style={styles.status}>
              {labels[status]}
            </Text>
          </View>
          <Text style={styles.description}>{descriptions[status]}</Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="重试"
            accessibilityState={{ disabled: status === 'loading' }}
            disabled={status === 'loading'}
            onPress={() => setAttempt((value) => value + 1)}
            style={({ pressed }) => [
              styles.button,
              status === 'loading' && styles.disabled,
              pressed && styles.pressed,
            ]}
          >
            <Text style={styles.buttonText}>重试</Text>
          </Pressable>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

// 可滚动内容与安全区适配小屏和系统字号；按钮高度满足触控需求。
const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: theme.canvas },
  content: {
    flexGrow: 1,
    paddingHorizontal: 24,
    paddingVertical: 48,
    justifyContent: 'center',
  },
  brand: { fontSize: 36, fontWeight: '700', color: theme.ink },
  subtitle: {
    fontSize: 16,
    color: theme.muted,
    marginTop: 12,
    marginBottom: 32,
  },
  card: { backgroundColor: theme.surface, borderRadius: 20, padding: 24 },
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  status: { fontSize: 24, fontWeight: '600', color: theme.ink, flexShrink: 1 },
  dot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: theme.accent,
  },
  offlineDot: { backgroundColor: theme.muted },
  description: {
    fontSize: 16,
    lineHeight: 26,
    color: theme.muted,
    marginTop: 16,
    marginBottom: 24,
  },
  button: {
    minHeight: 48,
    backgroundColor: theme.primary,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 12,
  },
  buttonText: { fontSize: 16, fontWeight: '600', color: theme.surface },
  disabled: { opacity: 0.55 },
  pressed: { opacity: 0.8 },
});

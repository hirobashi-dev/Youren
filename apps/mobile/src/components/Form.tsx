import type { PropsWithChildren } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { theme } from '../theme';
// 共用绿色表单骨架：系统字体、可滚动安全区、键盘避让与至少48dp触控区域。
export function Page({
  title,
  children,
  onBack,
}: PropsWithChildren<{ title: string; onBack: () => void }>) {
  return (
    <SafeAreaView style={styles.safe}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={styles.content}
        >
          <Action label="返回首页" secondary onPress={onBack} />
          <Text accessibilityRole="header" style={styles.title}>
            {title}
          </Text>
          <View style={styles.card}>{children}</View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
export function Action({
  label,
  onPress,
  disabled = false,
  secondary = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        secondary && styles.secondary,
        disabled && styles.disabled,
        pressed && styles.pressed,
      ]}
    >
      <Text style={[styles.buttonText, secondary && styles.secondaryText]}>
        {label}
      </Text>
    </Pressable>
  );
}
export function Field({
  label,
  value,
  onChange,
  multiline = false,
  editable = true,
  email = false,
  numeric = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  multiline?: boolean;
  editable?: boolean;
  email?: boolean;
  numeric?: boolean;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChange}
        editable={editable}
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType={
          email ? 'email-address' : numeric ? 'number-pad' : 'default'
        }
        textContentType={
          email ? 'emailAddress' : numeric ? 'oneTimeCode' : undefined
        }
        multiline={multiline}
        style={[styles.input, multiline && styles.multiline]}
      />
    </View>
  );
}
export function Notice({ children }: PropsWithChildren) {
  return (
    <Text accessibilityLiveRegion="polite" style={styles.notice}>
      {children}
    </Text>
  );
}
export const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: theme.canvas },
  flex: { flex: 1 },
  content: { flexGrow: 1, padding: 24, gap: 16 },
  title: {
    color: theme.ink,
    fontSize: 28,
    fontWeight: '700',
    marginVertical: 8,
  },
  card: {
    padding: 20,
    gap: 16,
    borderRadius: 20,
    backgroundColor: theme.surface,
  },
  button: {
    minHeight: 48,
    borderRadius: 12,
    backgroundColor: theme.primary,
    paddingHorizontal: 16,
    paddingVertical: 12,
    justifyContent: 'center',
    alignItems: 'center',
  },
  buttonText: {
    color: theme.surface,
    fontSize: 16,
    fontWeight: '600',
    textAlign: 'center',
  },
  secondary: {
    backgroundColor: theme.surface,
    borderColor: theme.primary,
    borderWidth: 1,
  },
  secondaryText: { color: theme.primary },
  disabled: { opacity: 0.55 },
  pressed: { opacity: 0.8 },
  field: { gap: 8 },
  label: { color: theme.ink, fontSize: 16, fontWeight: '600' },
  input: {
    minHeight: 48,
    padding: 12,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: theme.muted,
    color: theme.ink,
    fontSize: 17,
    backgroundColor: theme.surface,
  },
  multiline: { minHeight: 120, textAlignVertical: 'top' },
  notice: { color: theme.muted, fontSize: 16, lineHeight: 26 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 48 },
  rowLabel: { flex: 1, color: theme.ink, fontSize: 16, lineHeight: 24 },
});

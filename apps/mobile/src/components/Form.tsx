import type { PropsWithChildren, ReactNode } from 'react';
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
  showBack = true,
  headerAction,
  footer,
  floatingAction,
}: PropsWithChildren<{
  title: string;
  onBack: () => void;
  showBack?: boolean;
  headerAction?: ReactNode;
  footer?: ReactNode;
  floatingAction?: ReactNode;
}>) {
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
          <View style={styles.appBar}>
            {showBack ? (
              <Action label="返回首页" compact secondary onPress={onBack} />
            ) : null}
            <Text accessibilityRole="header" style={styles.title}>
              {title}
            </Text>
            {headerAction}
          </View>
          {children}
        </ScrollView>
        {footer ? <View style={styles.footer}>{footer}</View> : null}
        {floatingAction ? (
          <View style={styles.floatingAction}>{floatingAction}</View>
        ) : null}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
export function Action({
  label,
  onPress,
  disabled = false,
  secondary = false,
  selected,
  compact = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
  selected?: boolean;
  compact?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{
        disabled,
        ...(selected === undefined ? {} : { selected }),
      }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        compact && styles.compactButton,
        secondary && styles.secondary,
        selected && styles.selectedButton,
        disabled && styles.disabled,
        pressed && styles.pressed,
      ]}
    >
      <Text
        style={[
          styles.buttonText,
          (secondary || selected) && styles.secondaryText,
          compact && styles.compactText,
        ]}
      >
        {compact ? '‹' : label}
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
  maxLength,
  email = false,
  numeric = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  multiline?: boolean;
  editable?: boolean;
  maxLength?: number;
  email?: boolean;
  numeric?: boolean;
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      {/* 输入最大长度遵循后端内容约束，避免提交后才因超限失败。 */}
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChange}
        editable={editable}
        maxLength={maxLength}
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
  content: { flexGrow: 1, padding: 20, gap: 16 },
  // 手机页面统一使用白色顶栏和连续画布，内容区由各页面按业务分组。
  appBar: {
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginHorizontal: -20,
    marginTop: -20,
    paddingHorizontal: 16,
    backgroundColor: theme.surface,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#D8E2E1',
  },
  footer: {
    backgroundColor: theme.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#D8E2E1',
  },
  floatingAction: { position: 'absolute', right: 18, bottom: 72 },
  title: {
    color: theme.ink,
    fontSize: 21,
    fontWeight: '700',
    marginVertical: 0,
    flex: 1,
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
  compactButton: {
    minHeight: 44,
    width: 44,
    paddingHorizontal: 0,
    paddingVertical: 0,
    borderWidth: 0,
  },
  compactText: { fontSize: 24, fontWeight: '400' },
  selectedButton: { backgroundColor: '#E1F0EC', borderColor: '#C7DFD9' },
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

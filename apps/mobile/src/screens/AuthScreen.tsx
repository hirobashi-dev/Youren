import { useEffect, useRef, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { AuthApi } from '../api/auth';
import { ApiError, safeMessage } from '../api/client';
import type { Challenge, Policy } from '../api/types';
import { SessionStore } from '../auth/sessionStore';
import { Action, Field, Notice, Page, styles } from '../components/Form';
// 邮箱挑战绑定用途；修改邮箱或用途使旧挑战失效，条款始终由用户主动选择。
export function AuthScreen({
  auth,
  store,
  onBack,
  onDone,
}: {
  auth: AuthApi;
  store: SessionStore;
  onBack: () => void;
  onDone: () => void;
}) {
  const [purpose, setPurpose] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [policyAttempt, setPolicyAttempt] = useState(0);
  const [accepted, setAccepted] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [message, setMessage] = useState('');
  const [waitUntil, setWaitUntil] = useState(0);
  const [remaining, setRemaining] = useState(0);
  const lifecycle = useRef(new AbortController());
  useEffect(() => {
    const controller = lifecycle.current;
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    if (purpose === 'register') {
      setPolicy(null);
      setAccepted(false);
      void auth
        .policy(controller.signal)
        .then((value) => {
          if (!controller.signal.aborted) setPolicy(value);
        })
        .catch((error) => {
          if (!controller.signal.aborted) setMessage(safeMessage(error));
        });
    }
    return () => controller.abort();
  }, [auth, purpose, policyAttempt]);
  useEffect(() => {
    const update = () =>
      setRemaining(Math.max(0, Math.ceil((waitUntil - Date.now()) / 1000)));
    update();
    const timer = setInterval(update, 1000);
    return () => clearInterval(timer);
  }, [waitUntil]);
  function changePurpose() {
    if (lock.current) return;
    setPurpose(purpose === 'login' ? 'register' : 'login');
    setChallenge(null);
    setCode('');
    setAccepted(false);
    setMessage('');
  }
  async function send() {
    if (lock.current || remaining > 0) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setMessage('请输入有效邮箱。');
      return;
    }
    lock.current = true;
    setBusy(true);
    setMessage('');
    try {
      const result = await auth.sendCode(
        email,
        purpose,
        lifecycle.current.signal,
      );
      if (!lifecycle.current.signal.aborted) {
        setChallenge(result);
        setCode('');
        setWaitUntil(Date.now() + result.retryAfter * 1000);
        setMessage('验证码已发送，请查收邮箱。');
      }
    } catch (error) {
      if (!lifecycle.current.signal.aborted) {
        setMessage(safeMessage(error));
        if (error instanceof ApiError && error.retryAfter)
          setWaitUntil(Date.now() + error.retryAfter * 1000);
      }
    } finally {
      lock.current = false;
      if (!lifecycle.current.signal.aborted) setBusy(false);
    }
  }
  async function submit() {
    if (lock.current) return;
    if (!challenge) {
      setMessage('请先发送邮箱验证码。');
      return;
    }
    if (!/^[0-9]{6}$/.test(code)) {
      setMessage('请输入六位数字验证码。');
      return;
    }
    if (purpose === 'register' && (!policy || !accepted)) {
      setMessage('请先阅读并主动同意条款与隐私说明。');
      return;
    }
    lock.current = true;
    setBusy(true);
    setMessage('');
    try {
      await store.login(
        purpose,
        challenge.challengeId,
        code,
        policy ?? undefined,
        lifecycle.current.signal,
      );
      if (!lifecycle.current.signal.aborted) {
        if (store.getSnapshot().status === 'ready') onDone();
        else setMessage(store.getSnapshot().message);
      }
    } catch (error) {
      if (!lifecycle.current.signal.aborted) {
        setMessage(safeMessage(error));
        if (
          error instanceof ApiError &&
          error.status === 429 &&
          error.retryAfter
        )
          setWaitUntil(Date.now() + error.retryAfter * 1000);
        if (
          purpose === 'register' &&
          error instanceof ApiError &&
          error.status === 422
        ) {
          setAccepted(false);
          setPolicyAttempt((value) => value + 1);
        }
      }
    } finally {
      lock.current = false;
      if (!lifecycle.current.signal.aborted) setBusy(false);
    }
  }
  return (
    <Page title={purpose === 'login' ? '邮箱登录' : '创建账号'} onBack={onBack}>
      <Notice>浏览无需注册。使用邮箱验证码登录，继续管理自己的资料。</Notice>
      <Field
        label="邮箱"
        value={email}
        email
        editable={!busy}
        onChange={(value) => {
          setEmail(value);
          setChallenge(null);
          setCode('');
        }}
      />
      <Action
        label={remaining > 0 ? `${remaining}秒后可重新发送` : '发送验证码'}
        disabled={busy || remaining > 0}
        onPress={() => void send()}
      />
      <Field
        label="验证码"
        value={code}
        numeric
        editable={!busy}
        onChange={setCode}
      />
      {purpose === 'register' && (
        <View style={{ gap: 12 }}>
          <Action
            label={expanded ? '收起条款与隐私说明' : '阅读条款与隐私说明'}
            secondary
            onPress={() => setExpanded((value) => !value)}
          />
          {expanded && (
            <Notice>
              这是开发测试说明，正式服务条款与隐私政策正文尚未发布，不代表法律政策已生效。邮箱用于验证码和账号识别，资料草稿经审核才公开；游客身份仅由本机凭证证明。正式启用前将提供完整政策供阅读。
              {policy
                ? ` 当前测试条款版本：${policy.termsVersion}；隐私版本：${policy.privacyVersion}。`
                : ' 正在读取当前版本。'}
            </Notice>
          )}
          {policy ? (
            <Pressable
              accessibilityRole="checkbox"
              accessibilityLabel="同意条款与隐私说明"
              accessibilityState={{ checked: accepted, disabled: busy }}
              disabled={busy}
              style={styles.row}
              onPress={() => {
                setExpanded(true);
                setAccepted((value) => !value);
              }}
            >
              <Text style={styles.label}>{accepted ? '☑' : '☐'}</Text>
              <Text style={styles.rowLabel}>
                我已阅读并同意开发测试条款与隐私说明
              </Text>
            </Pressable>
          ) : (
            <Action
              label="重新读取条款版本"
              secondary
              disabled={busy}
              onPress={() => setPolicyAttempt((value) => value + 1)}
            />
          )}
        </View>
      )}
      {message ? <Notice>{message}</Notice> : null}
      <Action
        label={purpose === 'login' ? '登录' : '注册'}
        disabled={busy}
        onPress={() => void submit()}
      />
      <Action
        label={purpose === 'login' ? '创建账号' : '已有账号，去登录'}
        secondary
        disabled={busy}
        onPress={changePurpose}
      />
    </Page>
  );
}

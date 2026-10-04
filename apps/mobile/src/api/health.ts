// 地址是公开配置；两秒超时与页面取消共用一个控制器，不暴露原始网络错误。
export async function getReadiness(
  signal?: AbortSignal,
): Promise<'ready' | 'unavailable'> {
  if (signal?.aborted) return 'unavailable';
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener('abort', cancel, { once: true });
  const timer = setTimeout(cancel, 2000);
  try {
    const baseUrl =
      process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://127.0.0.1:3000';
    const response = await fetch(`${baseUrl.replace(/\/$/, '')}/health/ready`, {
      signal: controller.signal,
    });
    return response.ok && (await response.json())?.status === 'ok'
      ? 'ready'
      : 'unavailable';
  } catch {
    return 'unavailable';
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', cancel);
  }
}

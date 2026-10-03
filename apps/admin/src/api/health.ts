// 成功必须同时满足HTTP状态与安全响应；超时可取消，错误不暴露内部正文。
export async function getReadiness(
  signal?: AbortSignal,
): Promise<'ready' | 'unavailable'> {
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener('abort', cancel, { once: true });
  if (signal?.aborted) cancel();
  const timer = setTimeout(cancel, 2000);
  try {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:3000'}/health/ready`,
      { signal: controller.signal },
    );
    return response.ok && (await response.json()).status === 'ok'
      ? 'ready'
      : 'unavailable';
  } catch {
    return 'unavailable';
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', cancel);
  }
}

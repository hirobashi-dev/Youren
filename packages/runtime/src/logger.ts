// 日志采用字段和值双白名单，任意请求正文/错误堆栈都不能进入输出。
export function redactLog(value: unknown): Record<string, string | number> {
  if (!value || typeof value !== 'object') return {};
  const result: Record<string, string | number> = {};
  for (const [key, item] of Object.entries(value)) {
    if (
      ['event', 'service', 'requestId', 'status'].includes(key) &&
      typeof item === 'string' &&
      /^[a-zA-Z0-9_-]{1,80}$/.test(item)
    )
      result[key] = item;
    if (
      ['statusCode', 'durationMs'].includes(key) &&
      typeof item === 'number' &&
      Number.isFinite(item) &&
      item >= 0
    )
      result[key] = item;
  }
  return result;
}
export function logEvent(value: unknown): void {
  console.log(JSON.stringify(redactLog(value)));
}

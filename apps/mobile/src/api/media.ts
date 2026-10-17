export const IMAGE_LIMIT_BYTES = 5_000_000;
const ACCEPTED_MIME = new Set(['image/jpeg', 'image/png', 'image/webp']);

// 上传前拒绝无法可靠识别格式、大小缺失或超过服务端硬限制的本机文件。
export function imageSelectionError(
  asset: { mimeType?: string | null; fileSize?: number | null },
  currentCount: number,
  limit: number,
) {
  if (currentCount >= limit)
    return limit === 3
      ? '回复最多添加 3 张图片。'
      : '帖子最多添加 9 张图片。';
  if (!asset.mimeType || !ACCEPTED_MIME.has(asset.mimeType))
    return '仅支持 JPEG、PNG 或 WebP 图片。';
  if (!Number.isSafeInteger(asset.fileSize) || Number(asset.fileSize) < 1)
    return '无法读取图片大小，请重新选择。';
  if (Number(asset.fileSize) > IMAGE_LIMIT_BYTES)
    return '单张图片不能超过 5 MB。';
  return null;
}

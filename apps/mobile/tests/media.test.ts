import { imageSelectionError, IMAGE_LIMIT_BYTES } from '../src/api/media';

// 图片选择在上传前校验格式、真实文件大小和每种内容允许的张数。
test('拒绝不支持格式和超过五百万字节的图片', () => {
  expect(imageSelectionError({ mimeType: 'image/gif', fileSize: 100 }, 0, 9)).toBe('仅支持 JPEG、PNG 或 WebP 图片。');
  expect(imageSelectionError({ mimeType: 'image/jpeg', fileSize: IMAGE_LIMIT_BYTES + 1 }, 0, 9)).toBe('单张图片不能超过 5 MB。');
});

// 超过图片数量时整批不接受，避免UI截断造成用户误以为图片已全部保存。
test('拒绝超过帖子和回复图片数量上限的选择', () => {
  expect(imageSelectionError({ mimeType: 'image/png', fileSize: 10 }, 9, 9)).toBe('帖子最多添加 9 张图片。');
  expect(imageSelectionError({ mimeType: 'image/webp', fileSize: 10 }, 3, 3)).toBe('回复最多添加 3 张图片。');
});

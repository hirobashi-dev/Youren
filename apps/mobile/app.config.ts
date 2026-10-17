import type { ExpoConfig } from 'expo/config';
// 开发应用标识与正式发布隔离；不在公开配置写入服务端凭据。
const config: ExpoConfig = {
  name: 'Youren·开发',
  slug: 'youren-mobile',
  version: '0.1.0',
  // 首阶段只验收两种手机平台，all导出不额外启用Web依赖。
  platforms: ['ios', 'android'],
  scheme: 'youren-dev',
  orientation: 'portrait',
  userInterfaceStyle: 'light',
  ios: {
    bundleIdentifier: 'jp.youren.app.dev',
    // 按本次EAS交互确认的标准/豁免加密声明设置，不启用非豁免加密。
    infoPlist: { ITSAppUsesNonExemptEncryption: false },
    supportsTablet: false,
  },
  android: { package: 'jp.youren.app.dev' },
  // 安全存储密钥不能随Android自动备份恢复到无法解密的新设备。
  plugins: [
    'expo-dev-client',
    ['expo-secure-store', { configureAndroidBackup: true }],
    // 图片选择只读取用户主动挑选的照片，不请求相机或麦克风权限。
    [
      'expo-image-picker',
      { photosPermission: '允许 Youren 访问照片，以便添加到留言或回复中。' },
    ],
  ],
  // 将动态Expo配置绑定到用户刚创建的EAS项目，供云构建解析项目身份。
  extra: { eas: { projectId: 'a0d99061-5c20-4cc8-b75b-141ae378932a' } },
};
export default config;

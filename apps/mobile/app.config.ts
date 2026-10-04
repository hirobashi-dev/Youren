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
  plugins: ['expo-dev-client'],
  // 将动态Expo配置绑定到用户刚创建的EAS项目，供云构建解析项目身份。
  extra: { eas: { projectId: 'a0d99061-5c20-4cc8-b75b-141ae378932a' } },
};
export default config;

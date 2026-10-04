import type { ExpoConfig } from 'expo/config';
// 开发应用标识与正式发布隔离；不在公开配置写入服务端凭据。
const config: ExpoConfig = {
  name: '友缘·开发',
  slug: 'youren-mobile',
  version: '0.1.0',
  // 首阶段只验收两种手机平台，all导出不额外启用Web依赖。
  platforms: ['ios', 'android'],
  scheme: 'youren-dev',
  orientation: 'portrait',
  userInterfaceStyle: 'light',
  ios: { bundleIdentifier: 'jp.youren.app.dev', supportsTablet: false },
  android: { package: 'jp.youren.app.dev' },
  plugins: ['expo-dev-client'],
};
export default config;

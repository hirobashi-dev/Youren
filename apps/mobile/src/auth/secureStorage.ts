import * as SecureStore from 'expo-secure-store';
import type { SecretStorage } from './sessionStore';
// Android密钥备份排除由Expo插件配置；iOS仅解锁且限本机，秘密不进入普通存储。
const options: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};
export const secureStorage: SecretStorage = {
  get: (key) => SecureStore.getItemAsync(key, options),
  set: (key, value) => SecureStore.setItemAsync(key, value, options),
  remove: (key) => SecureStore.deleteItemAsync(key, options),
};

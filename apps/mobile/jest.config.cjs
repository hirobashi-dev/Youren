// 使用Expo原生模拟环境，测试真实页面和客户端，不连接外部服务。
module.exports = {
  preset: 'jest-expo',
  // 不把Windows绝对路径拼进glob，避免含.local等点号目录时路径被错误转义。
  testMatch: ['**/tests/**/*.test.ts?(x)'],
  setupFilesAfterEnv: ['<rootDir>/tests/setup.ts'],
};

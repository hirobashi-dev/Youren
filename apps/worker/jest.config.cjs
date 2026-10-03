// 工程生命周期测试使用显式注入依赖，真实连接单独验证。
module.exports = {
  testMatch: ['**/tests/**/*.ts'],
  transform: { '^.+\\.tsx?$': ['ts-jest', { tsconfig: 'tsconfig.json' }] },
};

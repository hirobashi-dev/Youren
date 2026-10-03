// Jest测试包含HTTP和显式隔离依赖集成场景。
module.exports = {
  testMatch: ['**/test/**/*.ts'],
  transform: { '^.+\\.tsx?$': ['ts-jest', { tsconfig: 'tsconfig.json' }] },
};

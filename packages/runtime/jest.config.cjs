// 不依赖全局编译器；测试直接执行TypeScript源码。
module.exports = {
  testMatch: ['**/tests/**/*.spec.ts'],
  transform: { '^.+\\.tsx?$': ['ts-jest', { tsconfig: 'tsconfig.json' }] },
};

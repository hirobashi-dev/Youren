// 只检查工程新增源码；生成合同、迁移和既有图册不做无关格式修改。
import tseslint from 'typescript-eslint';
export default tseslint.config(
  {
    ignores: [
      '.local/**',
      'backend/**/target/**',
      '**/node_modules/**',
      '**/dist/**',
      '**/generated/**',
      '**/.expo/**',
      '**/android/**',
      '**/ios/**',
      'docs/**',
      'packages/contracts/**',
      'packages/database/tools/**',
      'packages/database/tests/**',
    ],
  },
  ...tseslint.configs.recommended,
  {
    files: ['**/*.cjs'],
    languageOptions: { sourceType: 'commonjs' },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
);

// 独立启动本次Java API和Vite，不复用或关闭用户正在运行的服务。
import { defineConfig } from '@playwright/test';
const apiPort = process.env.YOUREN_TEST_API_PORT ?? '3001';
export default defineConfig({
  testDir: 'tests',
  testMatch: 'java-api.spec.ts',
  use: { baseURL: 'http://127.0.0.1:5173', channel: 'chrome' },
  webServer: [
    {
      command: 'node ../../tools/start-test-api.cjs',
      url: `http://127.0.0.1:${apiPort}/health/live`,
      reuseExistingServer: false,
      timeout: 30000,
    },
    {
      command:
        'node ../../node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort',
      url: 'http://127.0.0.1:5173',
      reuseExistingServer: false,
    },
  ],
});

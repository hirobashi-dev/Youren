// 每个场景清理DOM，避免上次渲染影响当前状态断言。
import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
afterEach(cleanup);

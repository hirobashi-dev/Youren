import { fireEvent, render, screen } from '@testing-library/react-native';
import { FoundationScreen } from '../src/screens/FoundationScreen';

// 只替换网络边界；页面状态和重试使用真实客户端处理。
const originalFetch = global.fetch;
afterEach(() => {
  global.fetch = originalFetch;
});

test('依赖故障显示失败，点击重试后恢复成功', async () => {
  let requests = 0;
  global.fetch = jest.fn(async () => {
    requests += 1;
    return {
      ok: requests > 1,
      json: async () => ({ status: requests > 1 ? 'ok' : 'unavailable' }),
    } as Response;
  });
  render(<FoundationScreen />);
  expect(await screen.findByText('连接失败')).toBeTruthy();
  fireEvent.press(screen.getByRole('button', { name: '重试' }));
  expect(await screen.findByText('连接成功')).toBeTruthy();
});

test('请求进行中禁止重复提交，完成后允许重试', async () => {
  let finish!: (response: Response) => void;
  global.fetch = jest.fn(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  render(<FoundationScreen />);
  expect(screen.getByText('连接中')).toBeTruthy();
  expect(screen.getByRole('button', { name: '重试' })).toBeDisabled();
  finish({ ok: true, json: async () => ({ status: 'ok' }) } as Response);
  expect(await screen.findByText('连接成功')).toBeTruthy();
  expect(screen.getByRole('button', { name: '重试' })).toBeEnabled();
});

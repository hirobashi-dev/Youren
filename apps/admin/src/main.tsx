// 仅挂载工程状态页，不初始化尚未实现的管理员会话。
import { createRoot } from 'react-dom/client';
import { App } from './App';
createRoot(document.getElementById('root')!).render(<App />);

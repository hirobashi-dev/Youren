// 每次重试取消上一请求，防止过期响应覆盖当前连接状态。
import { useEffect, useState } from 'react';
import { getReadiness } from './api/health';
import './styles.css';
export function App() {
  const [attempt, setAttempt] = useState(0),
    [status, setStatus] = useState<'loading' | 'ready' | 'unavailable'>(
      'loading',
    );
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setStatus('loading');
    getReadiness(controller.signal).then((result) => {
      if (active) setStatus(result);
    });
    return () => {
      active = false;
      controller.abort();
    };
  }, [attempt]);
  return (
    <div className="workspace">
      <aside>
        <a className="brand" href="/">
          友缘<span>管理工作台</span>
        </a>
        <div className="nav-active">工作台</div>
        <p className="aside-note">第一阶段 · 工程基础</p>
      </aside>
      <main>
        <header>
          <span>工作台 / 连接检查</span>
          <span>开发环境</span>
        </header>
        <section className="intro">
          <p className="eyebrow">友缘 · 在日本，相遇同行</p>
          <h1>准备好，开始连接。</h1>
          <p>先确认服务可用，再进入下一步。</p>
        </section>
        <section className="connection" aria-labelledby="connection-title">
          <div>
            <span className="dot" data-state={status} />
            <h2 id="connection-title">服务连接</h2>
          </div>
          <p role="status" aria-live="polite">
            {status === 'loading'
              ? '正在连接'
              : status === 'ready'
                ? '连接成功'
                : '连接失败'}
          </p>
          <p className="hint">
            {status === 'unavailable'
              ? '暂时无法连接，请确认服务已启动后重试。'
              : status === 'ready'
                ? '服务已就绪，可以继续开发。'
                : '正在检查，请稍候。'}
          </p>
          <button
            disabled={status === 'loading'}
            onClick={() => setAttempt((n) => n + 1)}
          >
            重试
          </button>
        </section>
        <footer>兴趣交友 · 恋爱配对 · 同城活动</footer>
      </main>
    </div>
  );
}

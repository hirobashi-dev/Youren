// 管理端沿用设计稿的轻量工作台结构；所有处置仍由服务端校验权限、版本和审计理由。
import { useEffect, useState } from 'react';
import { getReadiness } from './api/health';
import { AdminApiError, AdminReport, AdminSession, ReviewContent, beginAdminLogin, completeAdminMfa, decideContent, getReports, getReviewQueue, loadReviewImage, resolveReport } from './api/admin';
import './styles.css';

type QueueStatus = 'pending' | 'rejected' | 'visible' | 'hidden';

function AdminImage({ session, image }: { session: AdminSession; image: ReviewContent['images'][number] }) {
  const [src, setSrc] = useState('');
  useEffect(() => {
    let active = true;
    let objectUrl = '';
    loadReviewImage(session, image).then((url) => { objectUrl = url; if (active) setSrc(url); else URL.revokeObjectURL(url); }).catch(() => setSrc(''));
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [session, image]);
  return src ? <img className="review-image" src={src} alt="审核内容图片" /> : <span className="image-pending">图片加载中或已过期</span>;
}

function statusLabel(status: QueueStatus) {
  return status === 'pending' ? '待审核' : status === 'rejected' ? '待修改' : status === 'visible' ? '已公开' : '已隐藏';
}

export function App() {
  const [attempt, setAttempt] = useState(0);
  const [apiStatus, setApiStatus] = useState<'loading' | 'ready' | 'unavailable'>('loading');
  const [loginName, setLoginName] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [session, setSession] = useState<AdminSession | null>(null);
  const [area, setArea] = useState<'content' | 'reports'>('content');
  const [queueStatus, setQueueStatus] = useState<QueueStatus>('pending');
  const [items, setItems] = useState<ReviewContent[]>([]);
  const [reports, setReports] = useState<AdminReport[]>([]);
  const [pendingCount, setPendingCount] = useState(0);
  const [openReports, setOpenReports] = useState(0);
  const [selected, setSelected] = useState<ReviewContent | null>(null);
  const [selectedReport, setSelectedReport] = useState<AdminReport | null>(null);
  const [reportTarget, setReportTarget] = useState<ReviewContent | null>(null);
  const [targetLoading, setTargetLoading] = useState(false);
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setApiStatus('loading');
    getReadiness(controller.signal).then((result) => { if (active) setApiStatus(result); });
    return () => { active = false; controller.abort(); };
  }, [attempt]);

  useEffect(() => {
    if (!session) return;
    const remaining = Date.parse(session.expiresAt) - Date.now();
    if (remaining <= 0) { setSession(null); setError('管理会话已过期，请重新登录。'); return; }
    // 浏览器定时器有最大延迟；长会话分段检查，避免溢出后立即错误退出。
    const timer = window.setTimeout(() => {
      if (Date.parse(session.expiresAt) <= Date.now()) { setSession(null); setError('管理会话已过期，请重新登录。'); }
      else setSession({ ...session });
    }, Math.min(remaining, 2_147_000_000));
    return () => window.clearTimeout(timer);
  }, [session]);

  async function refresh(current: AdminSession, nextArea = area, nextStatus = queueStatus) {
    setLoading(true);
    setError('');
    try {
      if (nextArea === 'reports') {
        const rows = await getReports(current);
        setReports(rows);
        setOpenReports(rows.filter((entry) => entry.report.status !== 'resolved').length);
      } else if (nextStatus === 'pending') {
        const rows = await getReviewQueue(current, 'pending');
        setItems(rows);
        setPendingCount(rows.length);
      } else {
        // 已处理队列由原状态组成，分别读取后合并，避免引入新的服务端查询语义。
        const rows = await Promise.all((['rejected', 'visible', 'hidden'] as const).map((status) => getReviewQueue(current, status)));
        setItems(rows.flat());
      }
    } catch (cause) {
      if (cause instanceof AdminApiError && cause.status === 401) { setSession(null); setItems([]); setReports([]); setError('管理会话已过期，请重新登录。'); }
      else setError(cause instanceof Error ? cause.message : '读取失败，请重试。');
    } finally { setLoading(false); }
  }

  async function submitLogin(event: React.FormEvent) {
    event.preventDefault(); setError('');
    try {
      // 视觉上一次填写两种凭证；接口仍先创建口令挑战，再提交动态验证码。
      const challenge = await beginAdminLogin(loginName, password);
      const next = await completeAdminMfa(challenge.challengeId, mfaCode);
      setSession(next); setPassword(''); setMfaCode(''); setArea('content'); setQueueStatus('pending');
      await refresh(next, 'content', 'pending');
      const reportRows = await getReports(next).catch(() => []);
      setReports(reportRows);
      setOpenReports(reportRows.filter((entry) => entry.report.status !== 'resolved').length);
    } catch { setError('验证失败，请检查管理员邮箱、密码和二次验证代码。'); }
  }

  async function changeArea(next: 'content' | 'reports') {
    setArea(next); setSelected(null); setSelectedReport(null); setReason('');
    if (session) await refresh(session, next, queueStatus);
  }

  async function changeQueue(next: QueueStatus) {
    setQueueStatus(next); setSelected(null); setReason('');
    if (session) await refresh(session, 'content', next);
  }

  function openContent(item: ReviewContent) { setSelected(item); setSelectedReport(null); setReportTarget(null); setReason(''); }
  async function openReport(report: AdminReport) {
    setSelectedReport(report); setSelected(null); setReportTarget(null); setReason('');
    if (!session) return;
    setTargetLoading(true);
    try {
      // 按举报目标类型读取有限队列，再精确匹配编号，避免无边界加载其他类型内容。
      const queues = await Promise.all((['pending', 'rejected', 'visible', 'hidden'] as const).map((status) => getReviewQueue(session, status, { targetType: report.targetType as 'post' | 'comment', limit: 100 })));
      const target = queues.flat().find((item) => item.targetType === report.targetType && item.targetId === report.targetId) ?? null;
      setReportTarget(target);
    } catch { setReportTarget(null); }
    finally { setTargetLoading(false); }
  }

  async function moderate(action: 'approve' | 'reject' | 'hide' | 'restore' | 'delete') {
    if (!session || !selected || !reason.trim()) return;
    const label = action === 'approve' ? '通过' : action === 'reject' ? '驳回' : action === 'hide' ? '隐藏' : action === 'restore' ? '恢复公开' : '删除';
    if (!window.confirm(`确认${label}这条内容？操作会记录到审核审计。`)) return;
    setError('');
    try {
      await decideContent(session, selected, action, reason.trim());
      setSelected(null); setReason(''); await refresh(session);
    } catch (cause) {
      if (cause instanceof AdminApiError && cause.status === 412) setError('内容版本已变化，请重新加载后再处理。');
      else if (cause instanceof AdminApiError && cause.status === 401) { setSession(null); setError('管理会话已过期，请重新登录。'); }
      else setError('操作失败，请确认会话与内容状态后重试。');
    }
  }

  async function closeReport(resolution: 'action_taken' | 'no_action') {
    if (!session || !selectedReport || !reason.trim()) return;
    if (!window.confirm('确认结案这条举报？结案不会自动删除被举报内容。')) return;
    try {
      await resolveReport(session, selectedReport, resolution, reason.trim());
      setSelectedReport(null); setReason(''); await refresh(session, 'reports');
    } catch { setError('举报状态已变化或结案失败，请重新加载后再处理。'); }
  }

  const reviewerCanAct = session?.role !== 'auditor';
  const visibleItems = items.filter((item) => area !== 'content' || queueStatus === 'pending' || item.status !== 'pending');

  return <div className="workspace">
    <main className="admin-main">
      <header className="topbar"><a className="brand" href="/">Youren <span>管理后台</span></a><span className="api-state"><i className={`connection-dot ${apiStatus}`} /> <span role="status">{apiStatus === 'ready' ? '服务已连接' : apiStatus === 'loading' ? '正在连接' : '服务连接失败'}</span><button className="retry" onClick={() => setAttempt((n) => n + 1)}>重试</button></span></header>
      {!session ? <section className="auth-wrap">
        <div className="auth-card"><p className="eyebrow">管理 · 后台登录与二次验证</p><h1>社区管理</h1><p className="auth-intro">登录管理工作台，处理内容与举报。</p>
          <form onSubmit={submitLogin}><label>管理员邮箱<input aria-label="管理员邮箱" type="email" autoComplete="username" value={loginName} onChange={(event) => setLoginName(event.target.value)} required /></label><label>登录密码<input aria-label="登录密码" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label><label>二次验证代码<input aria-label="二次验证代码" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={mfaCode} onChange={(event) => setMfaCode(event.target.value)} required /></label><button disabled={apiStatus !== 'ready'}>验证并登录</button></form>
          <p className="mint-note">管理会话与普通用户分离。动态验证码不会保存在浏览器中。</p>
          {error && <p role="alert" className="error">{error}</p>}
        </div>
      </section> : <>
        <section className="page-heading"><div><p className="eyebrow">管理 · 审核工作台</p><h1>{selected || selectedReport ? '举报与内容审核详情' : area === 'content' ? '审核工作台' : '举报处理'}</h1><p>人工审核记录完整留存；待审超过72小时会告警并继续等待处理。</p></div><div className="session-info"><span>{session.role === 'auditor' ? '审计只读' : session.role === 'operator' ? '审核与操作' : '审核员'}</span><button className="quiet" onClick={() => { setSession(null); setItems([]); setReports([]); }}>退出</button></div></section>
        {!selected && !selectedReport && <>
          <nav className="primary-nav" aria-label="管理模块"><button aria-pressed={area === 'content'} onClick={() => void changeArea('content')}>内容审核</button><button aria-pressed={area === 'reports'} onClick={() => void changeArea('reports')}>举报处理</button></nav>
          <section className="summary-grid" aria-label="待处理概况"><article><span>待审核内容</span><strong>{pendingCount}</strong><small>包含需要人工判断的图片与文字</small></article><article><span>未处理举报</span><strong>{openReports}</strong><small>需要查看举报理由并人工结案</small></article></section>
          {area === 'content' && <div className="filter-row" aria-label="内容状态"><button aria-pressed={queueStatus === 'pending'} onClick={() => void changeQueue('pending')}>待审核</button><button aria-pressed={queueStatus !== 'pending'} onClick={() => void changeQueue('visible')}>已处理</button><button className="refresh-button" onClick={() => void refresh(session)}>重新加载</button></div>}
          {area === 'content' && queueStatus !== 'pending' && <div className="subfilters" aria-label="已处理状态">{(['rejected', 'visible', 'hidden'] as const).map((status) => <button key={status} aria-pressed={queueStatus === status} onClick={() => void changeQueue(status)}>{statusLabel(status)}</button>)}</div>}
          {error && <p role="alert" className="error">{error}</p>}
          {loading ? <p role="status" className="empty">正在加载…</p> : area === 'reports' ? <section className="table-panel"><div className="table-heading"><h2>举报记录</h2><span>{reports.length} 条</span></div>{reports.length ? <div className="table-scroll"><table><thead><tr><th>举报内容</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead><tbody>{reports.map((entry) => <tr className="report-row" key={entry.report.id}><td><strong>{entry.reasonCode}</strong><small>{entry.targetType} · {entry.targetId.slice(0, 8)}</small></td><td><span className={`status-pill ${entry.report.status === 'resolved' ? 'done' : ''}`}>{entry.report.status === 'resolved' ? '已结案' : '待处理'}</span></td><td>{new Date(entry.report.createdAt).toLocaleString()}</td><td><button className="table-action" onClick={() => openReport(entry)}>查看详情</button></td></tr>)}</tbody></table></div> : <p className="empty">当前没有举报记录。</p>}</section> : <section className="table-panel"><div className="table-heading"><h2>{queueStatus === 'pending' ? '待审核内容' : '已处理内容'}</h2><span>{visibleItems.length} 条</span></div>{visibleItems.length ? <div className="table-scroll"><table><thead><tr><th>内容</th><th>类型</th><th>状态</th><th>进入时间</th><th>操作</th></tr></thead><tbody>{visibleItems.map((item) => <tr className="content-row" key={`${item.targetType}-${item.targetId}`}><td><strong>{item.title || item.body.slice(0, 42) || '留言回复'}</strong>{item.reviewOverdue && <small className="overdue-label">超过72小时，等待人工处理</small>}</td><td>{item.targetType === 'post' ? '留言' : '回复'}{item.images.length ? ` · ${item.images.length} 张图片` : ''}</td><td><span className={`status-pill ${item.status === 'visible' ? 'done' : ''}`}>{statusLabel(item.status)}</span></td><td>{new Date(item.pendingSince).toLocaleString()}</td><td><button className="table-action" onClick={() => openContent(item)}>查看详情</button></td></tr>)}</tbody></table></div> : <p className="empty">{queueStatus === 'pending' ? '当前没有待审核内容。' : '当前没有已处理内容。'}</p>}</section>}
        </>}
        {selected && <section className="detail-panel"><button className="back-link" onClick={() => { setSelected(null); setReason(''); }}>‹ 返回审核工作台</button><div className="detail-layout"><article className="detail-content"><span className="status-pill">{statusLabel(selected.status)}{selected.reviewOverdue ? ' · 超过72小时' : ''}</span><p className="detail-kicker">{selected.targetType === 'post' ? '留言' : '回复'} · {selected.targetId.slice(0, 8)}</p><h2>{selected.title || '留言回复'}</h2><p className="review-body">{selected.body}</p>{selected.images.length > 0 && <div className="image-grid">{selected.images.map((image) => <AdminImage key={image.id} session={session} image={image} />)}</div>}<div className="mint-note">内容首次公开后按保留规则到期；待审超时只告警，不自动驳回。</div><p className="meta">提交时间：{new Date(selected.pendingSince).toLocaleString()} · 当前版本 {selected.version}</p></article><aside className="decision-panel"><h2>审核处理</h2><p>请先查看文字和图片，再记录处理理由。</p><label>处理理由<textarea aria-label="处理理由" value={reason} onChange={(event) => setReason(event.target.value)} rows={4} placeholder="写明判断依据" /></label>{error && <p role="alert" className="error">{error}</p>}{reviewerCanAct && <div className="decision-actions">{selected.status === 'pending' && <><button onClick={() => void moderate('approve')} disabled={!reason.trim()}>通过并公开</button><button className="quiet" onClick={() => void moderate('reject')} disabled={!reason.trim()}>驳回并通知修改</button></>}{selected.status === 'visible' && <button className="danger" onClick={() => void moderate('hide')} disabled={!reason.trim()}>隐藏内容</button>}{selected.status === 'hidden' && <button onClick={() => void moderate('restore')} disabled={!reason.trim()}>恢复公开</button>}<button className="danger text-action" onClick={() => void moderate('delete')} disabled={!reason.trim()}>删除内容</button></div>}{!reviewerCanAct && <p className="mint-note">当前账号仅有审计查看权限。</p>}</aside></div></section>}
        {selectedReport && <section className="detail-panel"><button className="back-link" onClick={() => { setSelectedReport(null); setReportTarget(null); setReason(''); }}>‹ 返回举报列表</button><div className="detail-layout"><article className="detail-content"><span className={`status-pill ${selectedReport.report.status === 'resolved' ? 'done' : ''}`}>{selectedReport.report.status === 'resolved' ? '已结案' : '待处理'}</span><p className="detail-kicker">举报详情 · {selectedReport.targetType}</p><h2>{selectedReport.reasonCode}</h2><p className="review-body">{selectedReport.detail || '举报人未补充说明。'}</p><div className="mint-note">举报目标：{selectedReport.targetType} · {selectedReport.targetId.slice(0, 8)}<br />举报时间：{new Date(selectedReport.report.createdAt).toLocaleString()}</div><section className="reported-context"><h3>被举报内容</h3>{targetLoading ? <p role="status">正在读取内容…</p> : reportTarget ? <><span className="status-pill">{statusLabel(reportTarget.status)}</span><h4>{reportTarget.title || '留言回复'}</h4><p className="review-body">{reportTarget.body}</p>{reportTarget.images.length > 0 && <div className="image-grid">{reportTarget.images.map((image) => <AdminImage key={image.id} session={session} image={image} />)}</div>}</> : <p className="meta">目标内容当前不可用，可能已删除或到期；请依据举报信息按流程处理。</p>}</section></article><aside className="decision-panel"><h2>处理举报</h2><p>结案理由会记录到管理审计中；结案本身不会自动修改内容。</p><label>结案理由<textarea aria-label="结案理由" value={reason} onChange={(event) => setReason(event.target.value)} rows={4} placeholder="说明采取了什么措施，或为何无需措施" /></label>{error && <p role="alert" className="error">{error}</p>}{reviewerCanAct && selectedReport.report.status !== 'resolved' && <div className="decision-actions"><button onClick={() => void closeReport('action_taken')} disabled={!reason.trim()}>已采取措施并结案</button><button className="quiet" onClick={() => void closeReport('no_action')} disabled={!reason.trim()}>无需措施并结案</button></div>}</aside></div></section>}
      </>}
      <footer>仅展示处理所需信息；管理员权限与每次处置均由服务端核验并记录。</footer>
    </main>
  </div>;
}

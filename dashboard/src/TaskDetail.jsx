import React, { useCallback, useEffect, useState } from 'react'
import { api } from './api.js'

const BUDGETS = ['', 'small', 'medium', 'large']

function Quad({ title, cls, count, children }) {
  return (
    <section className={`quad ${cls}`}>
      <h3>{title} <span className="count">{count}</span></h3>
      <div className="quad-body">{children}</div>
    </section>
  )
}

export default function TaskDetail({ taskId, onChanged }) {
  const [budget, setBudget] = useState('')
  const [view, setView] = useState('')
  const [card, setCard] = useState(null)
  const [versions, setVersions] = useState([])
  const [replayed, setReplayed] = useState(null)   // 回放中的历史卡
  const [taskInfo, setTaskInfo] = useState(null)
  const [toAgent, setToAgent] = useState('')
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  const load = useCallback(async () => {
    try {
      setReplayed(null)
      const [c, vs] = await Promise.all([
        api.getStateCard(taskId, budget || undefined, view || undefined),
        api.listVersions(taskId).catch(() => []),
      ])
      setCard(c)
      setVersions(vs)
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [taskId, budget, view])

  useEffect(() => { load() }, [load, onChanged])
  useEffect(() => {
    api.listTasks().then((ts) => setTaskInfo(ts.find((t) => t.taskId === taskId) || null))
  }, [taskId, onChanged])

  async function act(fn, okMsg) {
    try {
      const result = await fn()
      setNotice(okMsg)
      if (result) setReplayed(result)
      await load()
      onChanged()
      setTimeout(() => setNotice(null), 2500)
    } catch (e) {
      setError(e.message)
      setTimeout(() => setError(null), 4000)
    }
  }

  if (!card) {
    return <div className="placeholder">{error || '加载中…'}</div>
  }

  const shown = replayed || card

  return (
    <div className="detail">
      <header className="detail-header">
        <div>
          <h2>{taskId}</h2>
          <p className="sub">
            {taskInfo?.description || ''}
            {taskInfo && ` · ${taskInfo.status} · v${taskInfo.version}`}
            {taskInfo?.ownerAgent && ` · 持有者 @${taskInfo.ownerAgent}（交接 ${taskInfo.handoffCount} 次）`}
          </p>
        </div>
        <div className="actions">
          <select value={budget} onChange={(e) => { setBudget(e.target.value); setReplayed(null) }}>
            {BUDGETS.map((b) => <option key={b} value={b}>{b ? `预算 ${b}` : '默认预算'}</option>)}
          </select>
          <select value={view} onChange={(e) => { setView(e.target.value); setReplayed(null) }}>
            <option value="">full 视图</option>
            <option value="lean">lean（执行态）</option>
            <option value="audit">audit（审计）</option>
          </select>
          <button className="ghost" onClick={() => act(() => api.snapshot(taskId), '快照已生成')}>快照</button>
          <button
            className="ghost"
            disabled={taskInfo?.status === 'CLOSED'}
            onClick={() => act(() => api.closeTask(taskId), '任务已关闭')}
          >关闭</button>
          <div className="handoff">
            <input
              placeholder="目标 Agent"
              value={toAgent}
              onChange={(e) => setToAgent(e.target.value)}
            />
            <button
              className="ghost"
              disabled={!toAgent.trim()}
              onClick={() => act(() => api.handoff(taskId, toAgent.trim()), `已交接给 ${toAgent.trim()}`).then(() => setToAgent(''))}
            >交接</button>
          </div>
        </div>
      </header>

      {(error || notice) && <div className={error ? 'error-banner' : 'notice'}>{error || notice}</div>}

      {replayed && (
        <div className="replay-bar">
          正在查看历史版本 v{replayed.version}（{replayed.compiledAt}）
          <button className="link" onClick={() => setReplayed(null)}>返回当前卡</button>
        </div>
      )}

      <div className="version-strip">
        {versions.length === 0 && <span className="dim">尚无持久化版本（查询状态卡 / 快照 / 交接后生成）</span>}
        {versions.map((v) => (
          <button
            key={v.version}
            className={`version-chip ${replayed?.version === v.version ? 'active' : ''}`}
            title={`${v.estimatedTokens} tokens · ${v.compiledAt}`}
            onClick={() => act(() => api.replay(taskId, v.version), null)}
          >v{v.version}</button>
        ))}
      </div>

      <div className="quad-grid">
        <Quad title="必须遵守" cls="hard" count={shown.hardConstraints.length}>
          {shown.hardConstraints.length === 0 && <p className="dim">无</p>}
          {shown.hardConstraints.map((c) => (
            <div key={c.id} className="item">
              <p>{c.text}</p>
              <span className="tag">{c.level}</span>
            </div>
          ))}
        </Quad>

        <Quad title="已确认事实" cls="fact" count={shown.verifiedFacts.length}>
          {shown.verifiedFacts.length === 0 && <p className="dim">无</p>}
          {shown.verifiedFacts.map((f) => (
            <div key={f.id} className="item">
              <p><b>{f.key}</b> = {f.value}</p>
              <span className="tag">{f.source}</span>
            </div>
          ))}
        </Quad>

        <Quad title="待办 / 待决" cls="todo" count={shown.openTodos.length}>
          {shown.openTodos.length === 0 && <p className="dim">无</p>}
          {shown.openTodos.map((t) => (
            <div key={t.id} className="item">
              <p>{t.text}</p>
              <div className="todo-ops">
                <button className="mini" onClick={() => act(() => api.updateTodo(taskId, t.id, 'DONE'), '待办已完成')}>完成</button>
                <button className="mini" onClick={() => act(() => api.updateTodo(taskId, t.id, 'CANCELLED'), '待办已取消')}>取消</button>
              </div>
            </div>
          ))}
        </Quad>

        <Quad title="过期 / 冲突" cls="invalid" count={shown.invalidated.length}>
          {shown.invalidated.length === 0 && <p className="dim">无</p>}
          {shown.invalidated.map((i) => (
            <div key={i.id} className="item">
              <p>
                <span className={`reason ${i.reason.toLowerCase()}`}>{i.reason}</span>
                {i.supersededBy && <span className="dim"> → 被 {i.supersededBy} 覆盖</span>}
              </p>
              <span className="tag">{i.id}</span>
            </div>
          ))}
        </Quad>
      </div>

      <footer className="card-footer">
        估算 token：<b>{shown.estimatedTokens}</b> · 版本 v{shown.version}
        {shown.softConstraints.length > 0 && ` · 软约束 ${shown.softConstraints.length} 条`}
      </footer>
    </div>
  )
}

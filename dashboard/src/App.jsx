import React, { useCallback, useEffect, useState } from 'react'
import { api } from './api.js'
import TaskDetail from './TaskDetail.jsx'

const STATUS_LABEL = {
  ACTIVE: '进行中',
  SNAPSHOTTED: '已快照',
  CLOSED: '已关闭',
}

export default function App() {
  const [tasks, setTasks] = useState([])
  const [selected, setSelected] = useState(null)
  const [error, setError] = useState(null)
  const [form, setForm] = useState({ taskId: '', description: '', restoreFrom: '' })
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      setTasks(await api.listTasks())
      setError(null)
    } catch (e) {
      setError('无法连接 ASC 服务：' + e.message)
    }
  }, [])

  useEffect(() => {
    refresh()
    const timer = setInterval(refresh, 10000)
    return () => clearInterval(timer)
  }, [refresh])

  async function createTask(e) {
    e.preventDefault()
    if (!form.taskId.trim()) return
    setBusy(true)
    try {
      await api.createTask(form.taskId.trim(), form.description.trim(), form.restoreFrom.trim())
      setForm({ taskId: '', description: '', restoreFrom: '' })
      await refresh()
      setSelected(form.taskId.trim())
    } catch (e2) {
      setError(e2.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <header className="brand">
          <h1>ASC StateBoard</h1>
          <p>Agent 状态治理面板</p>
          <button className="ghost" onClick={refresh}>刷新</button>
        </header>

        <form className="create-form" onSubmit={createTask}>
          <input
            placeholder="任务 ID（必填）"
            value={form.taskId}
            onChange={(e) => setForm({ ...form, taskId: e.target.value })}
          />
          <input
            placeholder="描述"
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
          <input
            placeholder="从任务恢复（restoreFrom，可空）"
            value={form.restoreFrom}
            onChange={(e) => setForm({ ...form, restoreFrom: e.target.value })}
          />
          <button disabled={busy || !form.taskId.trim()}>
            {form.restoreFrom.trim() ? '恢复新任务' : '创建任务'}
          </button>
        </form>

        {error && <div className="error-banner">{error}</div>}

        <ul className="task-list">
          {tasks.map((t) => (
            <li
              key={t.taskId}
              className={t.taskId === selected ? 'selected' : ''}
              onClick={() => setSelected(t.taskId)}
            >
              <div className="task-row">
                <span className="task-id">{t.taskId}</span>
                <span className={`badge status-${t.status.toLowerCase()}`}>
                  {STATUS_LABEL[t.status] || t.status}
                </span>
              </div>
              <div className="task-meta">
                v{t.version} · {t.ownerAgent ? `@${t.ownerAgent} · 交接${t.handoffCount}次` : '未交接'}
              </div>
              {t.description && <div className="task-desc">{t.description}</div>}
            </li>
          ))}
          {tasks.length === 0 && !error && <li className="empty">暂无任务，先创建一个</li>}
        </ul>
      </aside>

      <main className="content">
        {selected
          ? <TaskDetail taskId={selected} onChanged={refresh} />
          : <div className="placeholder">从左侧选择一个任务，或创建新任务</div>}
      </main>
    </div>
  )
}

// ASC REST API 的最小封装：面板只调这些端点。
// 租户（v0.6）：localStorage 里的当前租户随每个请求走 X-ASC-Tenant 头，
// 缺省 "default"。切租户 = 换个数据视角（后端按租户隔离，跨租户一律 404）。
export function currentTenant() {
  return localStorage.getItem('asc.tenant') || 'default'
}

export function setTenant(tenant) {
  localStorage.setItem('asc.tenant', (tenant || 'default').trim() || 'default')
}

async function request(path, options = {}) {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json', 'X-ASC-Tenant': currentTenant() },
    ...options,
  })
  if (!resp.ok) {
    const body = await resp.json().catch(() => ({}))
    throw new Error(body.message || `${resp.status} ${resp.statusText}`)
  }
  if (resp.status === 204) return null
  const text = await resp.text()
  return text ? JSON.parse(text) : null
}

export const api = {
  listTasks: () => request('/v1/tasks'),
  createTask: (taskId, description, restoreFrom) =>
    request('/v1/tasks', {
      method: 'POST',
      body: JSON.stringify({ taskId, description, restoreFrom: restoreFrom || null }),
    }),
  getStateCard: (taskId, budget, view) => {
    const params = new URLSearchParams()
    if (budget) params.set('budget', budget)
    if (view) params.set('view', view)
    const qs = params.toString()
    return request(`/v1/tasks/${taskId}/state-card${qs ? `?${qs}` : ''}`)
  },
  updateTodo: (taskId, todoId, status) =>
    request(`/v1/tasks/${taskId}/todos/${todoId}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  snapshot: (taskId) => request(`/v1/tasks/${taskId}/snapshot`, { method: 'POST' }),
  closeTask: (taskId) => request(`/v1/tasks/${taskId}/close`, { method: 'POST' }),
  handoff: (taskId, toAgent) =>
    request(`/v1/tasks/${taskId}/handoff`, { method: 'POST', body: JSON.stringify({ toAgent }) }),
  listVersions: (taskId) => request(`/v1/tasks/${taskId}/versions`),
  replay: (taskId, version) =>
    request(`/v1/tasks/${taskId}/replay${version ? `?version=${version}` : ''}`),
}

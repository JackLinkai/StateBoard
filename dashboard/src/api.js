// ASC REST API 的最小封装：面板只调这些端点。
async function request(path, options = {}) {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
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
  getStateCard: (taskId, budget) =>
    request(`/v1/tasks/${taskId}/state-card${budget ? `?budget=${budget}` : ''}`),
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

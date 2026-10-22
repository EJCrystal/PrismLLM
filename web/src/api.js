const API_BASE = '/admin/api'

function readCookie(name) {
  const prefix = `${name}=`
  const item = document.cookie.split('; ').find((value) => value.startsWith(prefix))
  return item ? decodeURIComponent(item.slice(prefix.length)) : null
}

async function request(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers || {})
  if (options.body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const csrf = readCookie('gw_csrf')
    if (csrf) headers.set('X-CSRF-Token', csrf)
  }

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      method,
      headers,
      credentials: 'include',
    })
  } catch {
    throw new Error('无法连接网关，请确认服务已启动')
  }

  if (response.status === 204) return null

  const text = await response.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }

  if (!response.ok) {
    if (response.status === 401 && path !== '/login') {
      window.dispatchEvent(new CustomEvent('gateway:unauthorized'))
    }
    const error = new Error(data?.error?.message || `请求失败（${response.status}）`)
    error.status = response.status
    error.code = data?.error?.code
    throw error
  }

  return data
}

export const api = {
  login: (username, password) =>
    request('/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request('/logout', { method: 'POST' }),
  me: () => request('/me'),

  listChannels: () => request('/channels'),
  createChannel: (payload) => request('/channels', { method: 'POST', body: JSON.stringify(payload) }),
  updateChannel: (id, payload) =>
    request(`/channels/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteChannel: (id) => request(`/channels/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  listChannelModels: (id) => request(`/channels/${encodeURIComponent(id)}/models`),

  listAliases: () => request('/aliases'),
  createAlias: (payload) => request('/aliases', { method: 'POST', body: JSON.stringify(payload) }),
  updateAlias: (id, payload) =>
    request(`/aliases/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteAlias: (id) => request(`/aliases/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  listClientKeys: () => request('/client-keys'),
  createClientKey: (payload) =>
    request('/client-keys', { method: 'POST', body: JSON.stringify(payload) }),
  updateClientKey: (id, payload) =>
    request(`/client-keys/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteClientKey: (id) =>
    request(`/client-keys/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  listRequestLogs: (limit = 100) => request(`/request-logs?limit=${limit}`),
}

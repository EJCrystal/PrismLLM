// 页面会话内复用上游模型列表；只有显式刷新时才覆盖缓存。
const STORAGE_KEY = 'llm-gateway.upstream-model-cache-v2'
const cache = new Map(readStoredCache())

function readStoredCache() {
  if (typeof sessionStorage === 'undefined') return []
  try {
    const stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '{}')
    return Object.entries(stored).filter(([, models]) => Array.isArray(models))
  } catch {
    return []
  }
}

function persistCache() {
  if (typeof sessionStorage === 'undefined') return
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(Object.fromEntries(cache)))
  } catch {
    // 缓存空间不足时仍保留内存缓存，不影响模型选择。
  }
}

export function hasUpstreamModelCache(channelId) {
  return cache.has(channelId)
}

export function getUpstreamModelCache(channelId) {
  return cache.get(channelId) || []
}

export function getAuthorizedUpstreamModelCache(channelId) {
  return getUpstreamModelCache(channelId).filter((model) => model.authorized === true)
}

export function setUpstreamModelCache(channelId, models) {
  cache.set(channelId, models)
  persistCache()
}

export function clearUpstreamModelCache(channelId) {
  if (cache.delete(channelId)) persistCache()
}

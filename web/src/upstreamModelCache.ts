import type { ChannelModel } from './types/api'

// 页面会话内复用上游模型列表；只有显式刷新时才覆盖缓存。
const STORAGE_KEY = 'prism-llm.upstream-model-cache-v2'
const cache = new Map<string, ChannelModel[]>(readStoredCache())

function readStoredCache(): Array<[string, ChannelModel[]]> {
  if (typeof sessionStorage === 'undefined') return []
  try {
    const stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '{}')
    return Object.entries(stored as Record<string, unknown>)
      .filter((entry): entry is [string, ChannelModel[]] => Array.isArray(entry[1]))
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

export function hasUpstreamModelCache(channelId: string): boolean {
  return cache.has(channelId)
}

export function getUpstreamModelCache(channelId: string): ChannelModel[] {
  return cache.get(channelId) || []
}

export function getAuthorizedUpstreamModelCache(channelId: string): ChannelModel[] {
  return getUpstreamModelCache(channelId).filter((model) => model.authorized === true)
}

export function setUpstreamModelCache(channelId: string, models: ChannelModel[]): void {
  cache.set(channelId, models)
  persistCache()
}

export function clearUpstreamModelCache(channelId: string): void {
  if (cache.delete(channelId)) persistCache()
}

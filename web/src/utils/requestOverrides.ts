import type { JsonObject, JsonValue, ProtocolId, UpstreamProtocolOverrides, UpstreamRequestOverrides } from '../types/api'

export const REQUEST_OVERRIDE_SCOPES = [
  { id: 'common', label: '通用配置（所有协议）', errorLabel: '通用配置' },
  { id: 'chat_completions', label: 'Chat Completions（仅该协议）', errorLabel: 'Chat Completions' },
  { id: 'responses', label: 'Responses（仅该协议）', errorLabel: 'Responses' },
  { id: 'messages', label: 'Anthropic Messages（仅该协议）', errorLabel: 'Anthropic Messages' },
  { id: 'gemini', label: 'Gemini（仅该协议）', errorLabel: 'Gemini' },
]

const PROFILES = new Set(['auto', 'none', 'claude', 'codex', 'google', 'antigravity'])
const MANAGED_HEADERS = new Set([
  'authorization', 'proxy-authorization', 'x-api-key', 'x-goog-api-key', 'cookie', 'set-cookie',
  'host', 'content-length', 'content-type', 'content-encoding', 'transfer-encoding', 'connection', 'te', 'trailer',
  'accept-encoding', 'upgrade', 'expect', 'traceparent',
])

export interface RequestOverrideDraft {
  headerProfile: string | null
  userAgent: string | null
  headers: Record<string, string | null>
  body: JsonObject
}

export interface RequestOverrideEditorDraft {
  headerProfile: string
  userAgent: string
  headersText: string
  bodyText: string
  omitUserAgent: boolean
}

export type RequestOverrideDrafts = Record<string, RequestOverrideEditorDraft>

/** Snapshot raw editor text too, so invalid or partially typed JSON remains dirty. */
export function requestOverrideDraftSnapshot(
  drafts: RequestOverrideDrafts,
  inheritGlobal: boolean,
): string {
  return JSON.stringify({ drafts, inheritGlobal })
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function normalizeLayer(value: unknown = {}): RequestOverrideDraft {
  return {
    headerProfile: (value as Partial<RequestOverrideDraft>).headerProfile ?? null,
    userAgent: (value as Partial<RequestOverrideDraft>).userAgent ?? null,
    headers: { ...((value as Partial<RequestOverrideDraft>).headers ?? {}) },
    body: { ...((value as Partial<RequestOverrideDraft>).body ?? {}) },
  }
}

function hasOverrides(layer: RequestOverrideDraft): boolean {
  return layer.headerProfile != null || layer.userAgent != null ||
    Object.keys(layer.headers).length > 0 || Object.keys(layer.body).length > 0
}

export function normalizeRequestOverrides(value: unknown = {}): UpstreamRequestOverrides {
  const protocols: Partial<Record<ProtocolId, UpstreamProtocolOverrides>> = {}
  for (const { id } of REQUEST_OVERRIDE_SCOPES.slice(1)) {
    const layer = normalizeLayer((value as Partial<UpstreamRequestOverrides>).protocols?.[id as ProtocolId])
    if (hasOverrides(layer)) protocols[id as ProtocolId] = layer
  }
  return {
    inheritGlobal: (value as Partial<UpstreamRequestOverrides>).inheritGlobal !== false,
    ...normalizeLayer(value),
    protocols,
  }
}

export function createRequestOverrideDrafts(value: unknown = {}): RequestOverrideDrafts {
  const normalized = normalizeRequestOverrides(value)
  return Object.fromEntries(REQUEST_OVERRIDE_SCOPES.map(({ id }) => {
    const layer = id === 'common' ? normalized : normalizeLayer(normalized.protocols[id as ProtocolId])
    return [id, {
      headerProfile: layer.headerProfile || '',
      userAgent: layer.userAgent ?? '',
      headersText: JSON.stringify(layer.headers, null, 2),
      bodyText: JSON.stringify(layer.body, null, 2),
      omitUserAgent: layer.userAgent === '',
    }]
  }))
}

function parseObject(text: unknown, label: string): Record<string, unknown> {
  let value
  try {
    value = JSON.parse(String(text).trim() || '{}')
  } catch {
    throw new Error(`${label}不是有效的 JSON`)
  }
  if (!isObject(value)) throw new Error(`${label}必须是 JSON 对象`)
  return value
}

function jsonDepth(value: unknown, depth = 0): number {
  if (depth > 32) return depth
  if (value === null || typeof value !== 'object') return depth
  let deepest = depth + 1
  for (const item of Object.values(value)) deepest = Math.max(deepest, jsonDepth(item, depth + 1))
  return deepest
}

function validHeaderValue(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 8192 && /^[\t\x20-\x7e]*$/.test(value)
}

function validateLayer(layer: RequestOverrideDraft, label: string): void {
  if (layer.headerProfile != null && !PROFILES.has(layer.headerProfile)) throw new Error(`${label}请求头预设无效`)
  if (layer.userAgent != null && !validHeaderValue(layer.userAgent)) throw new Error(`${label}UA 包含非法字符或超过 8192 字符`)
  const entries = Object.entries(layer.headers)
  if (entries.length > 64) throw new Error(`${label}自定义请求头不能超过 64 个`)
  const names = new Set()
  for (const [name, value] of entries) {
    if (name.length > 128 || !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name)) throw new Error(`${label}请求头名称无效`)
    const lower = name.toLowerCase()
    if (names.has(lower)) throw new Error(`${label}请求头名称不能重复（不区分大小写）`)
    names.add(lower)
    if (MANAGED_HEADERS.has(lower)) throw new Error(`请求头 ${name} 由网关管理`)
    if (value !== null && !validHeaderValue(value)) throw new Error(`请求头 ${name} 的值必须是字符串或 null，且不能包含非法字符`)
  }
  if (Object.hasOwn(layer.body, 'model') || Object.hasOwn(layer.body, 'stream')) {
    throw new Error(`${label}请求体覆盖不能修改 model 或 stream`)
  }
  if (jsonDepth(layer.body) > 32) throw new Error(`${label}请求体覆盖嵌套不能超过 32 层`)
}

export function buildRequestOverrides(
  drafts: RequestOverrideDrafts,
  inheritGlobal = true,
): UpstreamRequestOverrides {
  const layers: Record<string, RequestOverrideDraft> = {}
  for (const { id, label, errorLabel } of REQUEST_OVERRIDE_SCOPES) {
    const draft = drafts[id]
    const validationLabel = errorLabel || label
    const layer = {
      headerProfile: draft.headerProfile || null,
      userAgent: draft.omitUserAgent ? '' : (draft.userAgent.trim() || null),
      headers: parseObject(draft.headersText, `${validationLabel}请求头`) as Record<string, string | null>,
      body: parseObject(draft.bodyText, `${validationLabel}请求体`) as JsonObject,
    }
    validateLayer(layer, validationLabel)
    layers[id] = layer
  }
  const result: UpstreamRequestOverrides = {
    inheritGlobal,
    ...layers.common,
    protocols: {},
  }
  for (const { id } of REQUEST_OVERRIDE_SCOPES.slice(1)) {
    if (hasOverrides(layers[id])) result.protocols[id as ProtocolId] = layers[id]
  }
  if (new TextEncoder().encode(JSON.stringify(result)).length > 65536) throw new Error('请求覆盖配置不能超过 64KB')
  return result
}

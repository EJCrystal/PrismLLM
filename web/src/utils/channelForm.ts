import type { Channel, ChannelKeyState, ChannelRequest } from '../types/api'
import { buildChannelKeyRateLimitPayload } from './channelKeyRateLimit'

/**
 * 将渠道编辑表单转换为管理 API 请求体。
 *
 * @param {object} form 渠道表单
 * @param {string[]} authorizedModels 已授权上游模型
 * @param {Record<string, string>} canonicalModels 上游模型到对外 ID 的映射
 * @param {Record<string, {canonicalModelId: string|null, mappingSource: string|null}>} originalMappings 编辑前的映射
 * @param {Array<object>} keys 渠道密钥草稿；为空时回退到旧版单凭据字段
 * @returns {object}
 */
export interface ChannelFormDraft {
  name: string
  baseUrl: string
  credential?: string | null
  authType?: string | null
  protocols: string[]
  enabled: boolean
  priority?: number | string | null
  requestOverrides?: ChannelRequest['requestOverrides'] | null
}

export interface ChannelKeyDraft {
  id?: string | null
  state?: ChannelKeyState
  enabled?: boolean
  name?: string | null
  credential?: string | null
  priority?: number | string | null
  stateVersion?: number | null
  models?: string[] | null
  rateLimit?: unknown
}

export interface OriginalModelMapping {
  canonicalModelId: string | null
  mappingSource: string | null
}

export function buildChannelPayload(
  form: ChannelFormDraft,
  authorizedModels: string[],
  canonicalModels: Record<string, string>,
  originalMappings: Record<string, OriginalModelMapping> = {},
  keys: ChannelKeyDraft[] = [],
): ChannelRequest {
  const payload: ChannelRequest = {
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    credential: keys.length ? null : String(form.credential ?? '').trim() || null,
    authType: form.authType || 'bearer',
    protocols: [...form.protocols],
    enabled: form.enabled,
    modelMappings: authorizedModels.map((upstreamModel) => {
      const canonicalModelId = canonicalModels[upstreamModel]?.trim() || null
      const original = originalMappings[upstreamModel]
      const mappingSource = canonicalModelId
        ? original?.canonicalModelId === canonicalModelId
          ? (original.mappingSource || 'manual')
          : 'manual'
        : null
      return {
        upstreamModel,
        canonicalModelId,
        mappingSource,
      }
    }),
    priority: form.priority === '' || form.priority === null || form.priority === undefined ? null : Number(form.priority) || 1,
  }
  if (keys.length) {
    payload.keys = keys.map((key) => {
      const state: ChannelKeyState = key.state || (key.enabled === false ? 'MANUAL_DISABLED' : 'ACTIVE')
      if (state !== 'ACTIVE' && state !== 'MANUAL_DISABLED') {
        throw new Error(`密钥「${key.name || ''}」的状态只能提交 ACTIVE 或 MANUAL_DISABLED；自动停用状态由系统维护`)
      }
      return {
        id: key.id || null,
        ...(key.id && key.stateVersion != null ? { stateVersion: key.stateVersion } : {}),
        name: String(key.name ?? '').trim(),
        credential: String(key.credential ?? '').trim() || null,
        state: state as 'ACTIVE' | 'MANUAL_DISABLED',
        priority: key.priority === '' || key.priority === null || key.priority === undefined
          ? 1
          : Number(key.priority) || 1,
        models: [...new Set((key.models || []).map((model) => String(model).trim()).filter(Boolean))],
        rateLimit: buildChannelKeyRateLimitPayload(key.rateLimit),
      }
    })
  }
  if (form.requestOverrides != null) payload.requestOverrides = form.requestOverrides
  return payload
}

/**
 * 合并协议探测结果：保留用户已设置的优先级，新探测到的协议追加到末尾。
 *
 * @param {string[]} existing 当前协议顺序
 * @param {string[]} supported 探测到的协议
 * @returns {string[]}
 */
export function mergeProbedProtocols(existing: string[], supported: string[]): string[] {
  const retained = existing.filter((protocol) => supported.includes(protocol))
  return [...retained, ...supported.filter((protocol) => !retained.includes(protocol))]
}

/**
 * 判断密钥是否可路由。
 * ACTIVE 为真；AUTO_TEMPORARY 且到期时间小于等于当前时间为真；其余为假。
 */
export function isKeyRoutable(key: Pick<import('../types/api').ChannelKey, 'state' | 'autoDisableUntil'> | null | undefined, now = Date.now()): boolean {
  if (!key) return false
  if (key.state === 'ACTIVE') return true
  if (key.state === 'AUTO_TEMPORARY') {
    return Boolean(key.autoDisableUntil && now >= key.autoDisableUntil)
  }
  return false
}

export function buildPriorityUpdatePayload(channel: Pick<Channel, 'name' | 'baseUrl' | 'authType' | 'protocols' | 'enabled'>, priority: number): ChannelRequest {
  return {
    name: channel.name,
    baseUrl: channel.baseUrl,
    authType: channel.authType || 'bearer',
    protocols: [...(channel.protocols || [])],
    enabled: channel.enabled,
    priority,
  }
}

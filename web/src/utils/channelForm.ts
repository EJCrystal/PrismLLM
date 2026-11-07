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
  protocolAuthTypes?: Record<string, string> | null
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

/** Canonicalize editor mappings for dirty-state comparisons, not persistence. */
export function canonicalModelSnapshot(canonicalModels: Record<string, string>): Array<[string, string]> {
  return Object.entries(canonicalModels)
    .filter(([, value]) => value.trim() !== '')
    .sort(([left], [right]) => left.localeCompare(right))
}

export function parseManualModelIds(text: string): string[] {
  const models = [...new Set(text.split(/[\s,，;；]+/u).filter(Boolean))]
  if (!models.length) throw new Error('请填写至少一个上游模型 ID')
  if (models.some((model) => model.length > 200)) {
    throw new Error('每个上游模型 ID 不能超过 200 个字符')
  }
  return models
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
    protocolAuthTypes: { ...(form.protocolAuthTypes || {}) },
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
      const rawState = key.state ?? (key.enabled === false ? 'MANUAL_DISABLED' : key.id ? undefined : 'ACTIVE')
      const isAutoDisabled = rawState === 'AUTO_TEMPORARY' || rawState === 'AUTO_PERMANENT'
      if (!key.id && isAutoDisabled) {
        throw new Error(`密钥「${key.name || ''}」的状态只能提交 ACTIVE 或 MANUAL_DISABLED；自动停用状态由系统维护`)
      }
      if (rawState !== undefined && rawState !== 'ACTIVE' && rawState !== 'MANUAL_DISABLED' && !isAutoDisabled) {
        throw new Error(`密钥「${key.name || ''}」的状态只能提交 ACTIVE 或 MANUAL_DISABLED；自动停用状态由系统维护`)
      }
      const assignableState = (rawState === 'ACTIVE' || rawState === 'MANUAL_DISABLED') ? rawState : undefined
      return {
        id: key.id || null,
        ...(key.id && key.stateVersion != null ? { stateVersion: key.stateVersion } : {}),
        name: String(key.name ?? '').trim(),
        credential: String(key.credential ?? '').trim() || null,
        ...(assignableState ? { state: assignableState } : {}),
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

export function removeProbeAddedProtocols(selected: string[], probeAdded: string[]): string[] {
  const probeAddedSet = new Set(probeAdded)
  return selected.filter((protocol) => !probeAddedSet.has(protocol))
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

export type ChannelRoutingStatus = 'DISABLED' | 'NO_ROUTABLE_KEYS' | 'ACTIVE'

/**
 * 渠道层面的选路状态，用于纠正「渠道启用但没有任何可路由密钥」时的误导展示。
 *
 * 渠道启用状态只表达运营意图：配置的密钥全部停用（人工或自动）后渠道已经接不到请求，
 * 但 `enabled` 仍为 true。这里只做展示派生，不写回 `enabled`。
 *
 * 只看静态密钥：管理面渠道列表不加载订阅账号，避免把「账号仍可用」误报成渠道无凭据。
 * 未配置密钥的新渠道返回 ACTIVE —— 订阅账号仍可能支撑它，界面不应据此断言不可用。
 */
export function channelRoutingStatus(channel: Pick<Channel, 'enabled' | 'keys'> | null | undefined, now = Date.now()): ChannelRoutingStatus {
  if (!channel?.enabled) return 'DISABLED'
  const keys = channel.keys ?? []
  if (!keys.length) return 'ACTIVE'
  return keys.some((key) => isKeyRoutable(key, now)) ? 'ACTIVE' : 'NO_ROUTABLE_KEYS'
}

/**
 * 紧挨启用开关的提示徽章文案。
 * 只陈述「已配置的密钥全部停用」这一可核实事实，不断言渠道完全不可用：
 * 自定义渠道还可能通过订阅账号继续提供候选，而渠道列表不加载账号。
 */
export function channelRoutingHint(channel: Pick<Channel, 'enabled' | 'keys'> | null | undefined, now = Date.now()): string {
  return channelRoutingStatus(channel, now) === 'NO_ROUTABLE_KEYS' ? '密钥全部停用' : ''
}

export function buildPriorityUpdatePayload(channel: Pick<Channel, 'name' | 'baseUrl' | 'authType' | 'protocolAuthTypes' | 'protocols' | 'enabled'>, priority: number): ChannelRequest {
  return {
    name: channel.name,
    baseUrl: channel.baseUrl,
    authType: channel.authType || 'bearer',
    ...(Object.keys(channel.protocolAuthTypes || {}).length
      ? { protocolAuthTypes: { ...(channel.protocolAuthTypes || {}) } }
      : {}),
    protocols: [...(channel.protocols || [])],
    enabled: channel.enabled,
    priority,
  }
}

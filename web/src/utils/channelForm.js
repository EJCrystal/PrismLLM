import { buildChannelKeyRateLimitPayload } from './channelKeyRateLimit.js'

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
export function buildChannelPayload(
  form,
  authorizedModels,
  canonicalModels,
  originalMappings = {},
  keys = [],
) {
  const payload = {
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    credential: String(form.credential ?? '').trim() || null,
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
      const state = key.state || (key.enabled === false ? 'MANUAL_DISABLED' : 'ACTIVE')
      if (state !== 'ACTIVE' && state !== 'MANUAL_DISABLED') {
        throw new Error(`密钥「${key.name || ''}」的状态只能提交 ACTIVE 或 MANUAL_DISABLED；自动停用状态由系统维护`)
      }
      return {
        id: key.id || null,
        ...(key.id && key.stateVersion != null ? { stateVersion: key.stateVersion } : {}),
        name: String(key.name ?? '').trim(),
        credential: String(key.credential ?? '').trim() || null,
        state,
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
export function mergeProbedProtocols(existing, supported) {
  const retained = existing.filter((protocol) => supported.includes(protocol))
  return [...retained, ...supported.filter((protocol) => !retained.includes(protocol))]
}

/**
 * 判断密钥是否可路由。
 * ACTIVE 为真；AUTO_TEMPORARY 且到期时间小于等于当前时间为真；其余为假。
 */
export function isKeyRoutable(key, now = Date.now()) {
  if (!key) return false
  if (key.state === 'ACTIVE') return true
  if (key.state === 'AUTO_TEMPORARY') {
    return Boolean(key.autoDisableUntil && now >= key.autoDisableUntil)
  }
  return false
}

export function buildPriorityUpdatePayload(channel, priority) {
  return {
    name: channel.name,
    baseUrl: channel.baseUrl,
    authType: channel.authType || 'bearer',
    protocols: [...(channel.protocols || [])],
    enabled: channel.enabled,
    priority,
  }
}

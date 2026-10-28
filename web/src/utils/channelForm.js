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
    payload.keys = keys.map((key) => ({
      id: key.id || null,
      ...(key.id && key.stateVersion != null ? { stateVersion: key.stateVersion } : {}),
      name: String(key.name ?? '').trim(),
      credential: String(key.credential ?? '').trim() || null,
      enabled: key.enabled !== false,
      priority: key.priority === '' || key.priority === null || key.priority === undefined
        ? 1
        : Number(key.priority) || 1,
      models: [...new Set((key.models || []).map((model) => String(model).trim()).filter(Boolean))],
    }))
  }
  return payload
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

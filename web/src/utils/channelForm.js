/**
 * 将渠道编辑表单转换为管理 API 请求体。
 *
 * @param {object} form 渠道表单
 * @param {string[]} authorizedModels 已授权上游模型
 * @param {Record<string, string>} canonicalModels 上游模型到对外 ID 的映射
 * @param {Record<string, {canonicalModelId: string|null, mappingSource: string|null}>} originalMappings 编辑前的映射
 * @returns {object}
 */
export function buildChannelPayload(
  form,
  authorizedModels,
  canonicalModels,
  originalMappings = {},
) {
  return {
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    credential: form.credential.trim() || null,
    authType: 'bearer',
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
    exposeUpstreamModels: form.exposeUpstreamModels,
    priority: Number(form.priority) || 0,
  }
}

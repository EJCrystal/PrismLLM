export const VISIBLE_MODEL_LIMIT = 3

/**
 * 计算渠道授权模型在界面中的显示项列表（文本、上游原名及悬停提示）。
 *
 * @param {object} channel 渠道对象，包含 models 和 modelMappings
 * @returns {Array<{ upstreamModel: string, displayName: string, isCustom: boolean, title: string }>}
 */
export function channelDisplayModels(channel) {
  if (!channel || !Array.isArray(channel.models)) return []
  const mappingsByUpstream = new Map(
    (channel.modelMappings || []).map((m) => [m.upstreamModel, m.canonicalModelId]),
  )
  const grouped = new Map()
  channel.models.forEach((upstreamModel) => {
    const canonical = mappingsByUpstream.get(upstreamModel)?.trim()
    const displayName = canonical || upstreamModel
    const entry = grouped.get(displayName) || { displayName, upstreamModels: [] }
    entry.upstreamModels.push(upstreamModel)
    grouped.set(displayName, entry)
  })
  return [...grouped.values()].map(({ displayName, upstreamModels }) => {
    const uniqueUpstreams = [...new Set(upstreamModels)]
    const isCustom = uniqueUpstreams.some((upstreamModel) => {
      const canonical = mappingsByUpstream.get(upstreamModel)?.trim()
      return !!(canonical && canonical !== upstreamModel)
    })
    const showUpstreams = isCustom || uniqueUpstreams.length > 1
    return {
      upstreamModel: uniqueUpstreams[0],
      upstreamModels: uniqueUpstreams,
      displayName,
      isCustom,
      title: showUpstreams ? `${displayName} (上游模型: ${uniqueUpstreams.join('、')})` : '',
    }
  })
}

/**
 * 渠道列表行内直接展示的前 N 个模型。
 */
export function visibleModels(channel) {
  return channelDisplayModels(channel).slice(0, VISIBLE_MODEL_LIMIT)
}

/**
 * 超过限制时的 +N 折叠徽章鼠标悬停提示。
 * 自定义项显示：`对外名 (上游: 上游名)`，未自定义项仅显示：`上游名`。
 */
export function hiddenModelsTitle(channel) {
  return channelDisplayModels(channel)
    .map((m) => (m.isCustom ? `${m.displayName} (上游: ${m.upstreamModels.join('、')})` : m.displayName))
    .join(', ')
}

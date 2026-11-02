import type { ChannelModelMapping } from '../types/api'

export const VISIBLE_MODEL_LIMIT = 3

export interface DisplayChannel {
  models?: string[] | null
  modelMappings?: ChannelModelMapping[] | null
}

export type ChannelStatusFilter = 'all' | 'enabled' | 'disabled'

export function channelModelFilterOptions(channels: readonly DisplayChannel[] | null | undefined): string[] {
  if (!Array.isArray(channels)) return []
  return [...new Set(channels.flatMap((channel) => channelDisplayModels(channel).map((model) => model.displayName)))].sort()
}

export function channelMatchesFilters(
  channel: DisplayChannel & { enabled: boolean },
  status: ChannelStatusFilter,
  model: string,
): boolean {
  if (status === 'enabled' && !channel.enabled) return false
  if (status === 'disabled' && channel.enabled) return false
  return !model || channelDisplayModels(channel).some((item) => item.displayName === model)
}

export interface ChannelDisplayModel {
  upstreamModel: string
  upstreamModels: string[]
  displayName: string
  isCustom: boolean
  title: string
}

/**
 * 计算渠道授权模型在界面中的显示项列表（文本、上游原名及悬停提示）。
 *
 * @param {object} channel 渠道对象，包含 models 和 modelMappings
 * @returns {Array<{ upstreamModel: string, displayName: string, isCustom: boolean, title: string }>}
 */
export function channelDisplayModels(channel: DisplayChannel | null | undefined): ChannelDisplayModel[] {
  if (!channel || !Array.isArray(channel.models)) return []
  const mappingsByUpstream = new Map<string, string | null>(
    (channel.modelMappings || []).map((m) => [m.upstreamModel, m.canonicalModelId]),
  )
  const grouped = new Map<string, { displayName: string, upstreamModels: string[] }>()
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
export function visibleModels(channel: DisplayChannel | null | undefined): ChannelDisplayModel[] {
  return channelDisplayModels(channel).slice(0, VISIBLE_MODEL_LIMIT)
}

/**
 * 超过限制时的 +N 折叠徽章鼠标悬停提示。
 * 自定义项显示：`对外名 (上游: 上游名)`，未自定义项仅显示：`上游名`。
 */
export function hiddenModelsTitle(channel: DisplayChannel | null | undefined): string {
  return channelDisplayModels(channel)
    .map((m) => (m.isCustom ? `${m.displayName} (上游: ${m.upstreamModels.join('、')})` : m.displayName))
    .join(', ')
}

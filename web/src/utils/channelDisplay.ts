import type { ChannelModelMapping } from '../types/api'

export const VISIBLE_MODEL_LIMIT = 3

export interface DisplayChannel {
  models?: string[] | null
  modelMappings?: ChannelModelMapping[] | null
}

export type ChannelStatusFilter = 'all' | 'enabled' | 'disabled'

/** Return a safe, user-clicked landing URL for a configured upstream endpoint. */
export function channelLandingUrl(baseUrl: string): string | null {
  try {
    const url = new URL(baseUrl)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
    const hostname = url.hostname.replace(/^\[|\]$/g, '').toLowerCase()
    if (isNonPublicHost(hostname)) return null
    url.username = ''
    url.password = ''
    url.pathname = url.pathname.replace(/\/+$/, '').replace(/\/v1$/i, '') || '/'
    return url.toString()
  } catch {
    return null
  }
}

function isNonPublicHost(hostname: string): boolean {
  if (hostname === 'localhost' || /\.(localhost|local|internal|lan)$/.test(hostname) || hostname === 'home.arpa') return true
  if (hostname.includes(':')) {
    const firstHextet = Number.parseInt(hostname.split(':')[0] || '0', 16)
    return hostname === '::' || hostname === '::1' || hostname.startsWith('::ffff:')
      || (firstHextet >= 0xfc00 && firstHextet <= 0xfdff)
      || (firstHextet >= 0xfe80 && firstHextet <= 0xfebf)
      || firstHextet >= 0xff00
  }
  // Do not turn a channel endpoint into a convenient link to a private or
  // otherwise non-routable IP address in the administrator's browser.
  const octets = hostname.split('.').map(Number)
  if (octets.length !== 4 || octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255)) return false
  const [first, second] = octets
  return first === 0 || first === 10 || first === 127 || first >= 224
    || (first === 100 && second >= 64 && second <= 127)
    || (first === 169 && second === 254)
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && (second === 0 || second === 168))
    || (first === 198 && (second === 18 || second === 19))
}

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

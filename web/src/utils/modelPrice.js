/** Find the price record for one channel/model pair. */
export function findModelPrice(prices, channelId, upstreamModel) {
  if (!Array.isArray(prices) || !channelId) return null
  const target = typeof upstreamModel === 'string' ? upstreamModel.trim() : ''
  if (!target) return null
  return prices.find((price) =>
    price &&
    price.channelId === channelId &&
    typeof price.upstreamModel === 'string' &&
    price.upstreamModel.trim() === target,
  ) || null
}

/** List the configured prices for one channel, ordered by upstream model. */
export function pricesForChannel(prices, channelId) {
  if (!Array.isArray(prices) || !channelId) return []
  return prices
    .filter((price) => price && price.channelId === channelId && typeof price.upstreamModel === 'string')
    .slice()
    .sort((left, right) => left.upstreamModel.localeCompare(right.upstreamModel))
}

/** Return the channel's upstream models that have no price record. */
export function unpricedChannelModels(channel, prices) {
  if (!channel || !Array.isArray(channel.models)) return []
  const models = [
    ...new Set(
      channel.models
        .filter((model) => typeof model === 'string')
        .map((model) => model.trim())
        .filter(Boolean),
    ),
  ]
  return models.filter((model) => !findModelPrice(prices, channel.id, model))
}

/** Build unpriced-model summaries keyed by channel id. Recomputed from current data. */
export function channelPriceSummaries(channels, prices) {
  const summaries = new Map()
  for (const channel of Array.isArray(channels) ? channels : []) {
    if (!channel?.id) continue
    const unpriced = unpricedChannelModels(channel, prices)
    summaries.set(channel.id, { unpriced, count: unpriced.length })
  }
  return summaries
}

/** Build the post-save price prompt, or null when nothing needs attention. */
export function buildPricePrompt(channel, count) {
  if (!channel?.id || !Number.isFinite(count) || count <= 0) return null
  return { channelId: channel.id, channelName: channel.name || '', count }
}

/** Locate the channel returned by a create/update call inside a freshly loaded list. */
export function resolveSavedChannel(channels, saved, editingId = null, fallbackName = null) {
  const list = Array.isArray(channels) ? channels : []
  const savedId = saved?.id || null
  if (savedId) {
    const byId = list.find((channel) => channel.id === savedId)
    if (byId) return byId
  }
  if (editingId) {
    const byEditing = list.find((channel) => channel.id === editingId)
    if (byEditing) return byEditing
  }
  const name = (saved?.name || fallbackName || '').trim()
  if (name) return list.find((channel) => (channel.name || '').trim() === name) || null
  return null
}

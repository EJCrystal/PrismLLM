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

/** Find one model-level default price by trimmed model ID. */
export function findDefaultModelPrice(defaultPrices, modelId) {
  if (!Array.isArray(defaultPrices)) return null
  const target = typeof modelId === 'string' ? modelId.trim() : ''
  if (!target) return null
  return defaultPrices.find((price) =>
    price &&
    typeof price.modelId === 'string' &&
    price.modelId.trim() === target,
  ) || null
}

/** Resolve a model default price by canonical ID first, then upstream model name. */
export function findDefaultPriceForModel(defaultPrices, upstreamModel, canonicalModelId = null) {
  const upstream = String(upstreamModel ?? '').trim()
  const canonical = String(canonicalModelId ?? '').trim()
  return findDefaultModelPrice(defaultPrices, canonical || upstream) ||
    (canonical && canonical !== upstream ? findDefaultModelPrice(defaultPrices, upstream) : null)
}

/** Resolve the model ID used by model-level default pricing. */
export function channelModelPricingId(channel, upstreamModel) {
  const model = String(upstreamModel ?? '').trim()
  if (!model) return ''
  const mapping = (Array.isArray(channel?.modelMappings) ? channel.modelMappings : [])
    .find((item) => String(item?.upstreamModel ?? '').trim() === model)
  return String(mapping?.canonicalModelId ?? '').trim() || model
}

/**
 * Resolve the effective price and its source for one channel model.
 * Channel overrides win over model defaults.
 */
export function resolveChannelModelPrice(channel, upstreamModel, prices, defaultPrices = []) {
  const channelPrice = findModelPrice(prices, channel?.id, upstreamModel)
  const modelId = channelModelPricingId(channel, upstreamModel)
  if (channelPrice) {
    return { source: 'channel', price: channelPrice, modelId, defaultPrice: null }
  }
  const upstream = String(upstreamModel ?? '').trim()
  const defaultPrice = findDefaultPriceForModel(
    defaultPrices,
    upstream,
    modelId !== upstream ? modelId : null,
  )
  if (defaultPrice) {
    return { source: 'default', price: defaultPrice, modelId, defaultPrice }
  }
  return { source: 'none', price: null, modelId, defaultPrice: null }
}

/** List the configured prices for one channel, ordered by upstream model. */
export function pricesForChannel(prices, channelId) {
  if (!Array.isArray(prices) || !channelId) return []
  return prices
    .filter((price) =>
      price &&
      price.channelId === channelId &&
      typeof price.upstreamModel === 'string' &&
      price.upstreamModel.trim(),
    )
    .slice()
    .sort((left, right) => left.upstreamModel.trim().localeCompare(right.upstreamModel.trim()))
}

/** Merge channel models with their configured price, matching trimmed model names. */
export function channelModelPriceOptions(channelModels, prices, channelId, defaultPrices = []) {
  const pricesByModel = new Map(
    pricesForChannel(prices, channelId).map((price) => [price.upstreamModel.trim(), price]),
  )
  return (Array.isArray(channelModels) ? channelModels : []).map((model) => ({
    ...model,
    price: pricesByModel.get(String(model.id).trim()) || null,
    defaultPrice: findDefaultPriceForModel(defaultPrices, model.id, model.canonicalModelId),
  }))
}

/** True when the active price already targets the selected channel/model. */
export function isSamePriceTarget(price, channelId, upstreamModel) {
  if (!price || price.channelId !== channelId) return false
  return String(price.upstreamModel ?? '').trim() === String(upstreamModel ?? '').trim()
}

/** Return the channel's upstream models that have no price record. */
export function unpricedChannelModels(channel, prices, defaultPrices = []) {
  if (!channel || !Array.isArray(channel.models)) return []
  const models = [
    ...new Set(
      channel.models
        .filter((model) => typeof model === 'string')
        .map((model) => model.trim())
        .filter(Boolean),
    ),
  ]
  return models.filter((model) => resolveChannelModelPrice(channel, model, prices, defaultPrices).source === 'none')
}

/** Build unpriced-model summaries keyed by channel id. Recomputed from current data. */
export function channelPriceSummaries(channels, prices, defaultPrices = []) {
  const summaries = new Map()
  for (const channel of Array.isArray(channels) ? channels : []) {
    if (!channel?.id) continue
    const unpriced = unpricedChannelModels(channel, prices, defaultPrices)
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

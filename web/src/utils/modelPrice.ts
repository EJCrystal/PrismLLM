import type { Channel, ChannelModel, ChannelModelMapping, ModelDefaultPrice, ModelPrice } from '../types/api'

export interface PriceChannel {
  id?: string | null
  name?: string | null
  models?: string[] | null
  modelMappings?: ChannelModelMapping[] | null
}

function toChannel(value: PriceChannel | Channel): Channel | null {
  return value as Channel
}

/** Resolve the display name of a channel, falling back to channel ID. */
export function channelDisplayName(channels: Array<Pick<Channel, 'id' | 'name'>> | null | undefined, channelId: string | null | undefined): string {
  if (!channelId) return ''
  const target = String(channelId).trim()
  const found = (Array.isArray(channels) ? channels : []).find((c) => c && c.id === target)
  return found?.name || target
}

/** Pick the model selector layout for the available channel models. */
export function channelModelChoiceMode(models: unknown[] | null | undefined): 'manual' | 'single' | 'multiple' {
  const count = Array.isArray(models) ? models.filter(Boolean).length : 0
  if (count === 0) return 'manual'
  if (count === 1) return 'single'
  return 'multiple'
}

/**
 * Merge channel authorization with discovered models so a target returned by
 * the channel route remains selectable even when upstream discovery omits it.
 */
export function channelModelOptionsForChannel(channel: PriceChannel | null | undefined, discoveredModels: ChannelModel[] = []): ChannelModel[] {
  if (!channel?.id) return []
  const entries = new Map<string, ChannelModel>()
  const mappings = new Map<string, ChannelModelMapping>(
    (Array.isArray(channel.modelMappings) ? channel.modelMappings : [])
      .map((mapping): [string, ChannelModelMapping] => [String(mapping?.upstreamModel ?? '').trim(), mapping])
      .filter(([model]) => Boolean(model)),
  )
  const addAuthorized = (modelId: string | null | undefined) => {
    const id = String(modelId ?? '').trim()
    if (!id) return
    const mapping = mappings.get(id)
    entries.set(id, {
      id,
      name: null,
      ownedBy: null,
      authorized: true,
      canonicalModelId: String(mapping?.canonicalModelId ?? '').trim() || null,
      mappingSource: mapping?.mappingSource || null,
    })
  }
  for (const mapping of Array.isArray(channel.modelMappings) ? channel.modelMappings : []) {
    addAuthorized(mapping?.upstreamModel)
  }
  for (const model of Array.isArray(channel.models) ? channel.models : []) {
    addAuthorized(model)
  }
  for (const model of Array.isArray(discoveredModels) ? discoveredModels : []) {
    const id = String(model?.id ?? '').trim()
    if (!id || model?.authorized !== true) continue
    entries.set(id, {
      ...entries.get(id),
      ...model,
      id,
      authorized: true,
      canonicalModelId: model.canonicalModelId ?? entries.get(id)?.canonicalModelId ?? null,
      mappingSource: model.mappingSource ?? entries.get(id)?.mappingSource ?? null,
    })
  }
  return [...entries.values()].sort((left, right) => left.id.localeCompare(right.id))
}

/** True when the initial model should be selected without replacing manual input. */
export function shouldSelectInitialModel(models: Array<Pick<ChannelModel, 'id'>> | null | undefined, currentModelId: string | null | undefined, manuallyEntered = false): boolean {
  if (!Array.isArray(models) || models.length === 0 || manuallyEntered) return false
  const current = String(currentModelId ?? '').trim()
  return !current || !models.some((model) => String(model?.id ?? '').trim() === current)
}

/** Find the price record for one channel/model pair. */
export function findModelPrice(prices: ModelPrice[] | null | undefined, channelId: string | null | undefined, upstreamModel: string | null | undefined): ModelPrice | null {
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
export function findDefaultModelPrice(defaultPrices: ModelDefaultPrice[] | null | undefined, modelId: string | null | undefined): ModelDefaultPrice | null {
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
export function findDefaultPriceForModel(defaultPrices: ModelDefaultPrice[] | null | undefined, upstreamModel: string | null | undefined, canonicalModelId: string | null = null): ModelDefaultPrice | null {
  const upstream = String(upstreamModel ?? '').trim()
  const canonical = String(canonicalModelId ?? '').trim()
  return findDefaultModelPrice(defaultPrices, canonical || upstream) ||
    (canonical && canonical !== upstream ? findDefaultModelPrice(defaultPrices, upstream) : null)
}

/** Resolve the model ID used by model-level default pricing. */
export function channelModelPricingId(channel: Pick<Channel, 'modelMappings'> | Pick<PriceChannel, 'modelMappings'> | null | undefined, upstreamModel: string | null | undefined): string {
  const model = String(upstreamModel ?? '').trim()
  if (!model) return ''
  const mapping = (Array.isArray(channel?.modelMappings) ? channel.modelMappings : [])
    .find((item) => String(item?.upstreamModel ?? '').trim() === model)
  return String(mapping?.canonicalModelId ?? '').trim() || model
}

/**
 * Build model-ID candidates for default pricing from channel mappings and authorized models.
 * Models that already have a default price configured are excluded unless matching currentModelId (when editing).
 */
export interface DefaultPriceModelOption {
  id: string
  upstreamModels: string[]
  channelNames: string[]
}

export function defaultPriceModelOptions(
  channels: PriceChannel[] | null | undefined,
  configuredPrices: ModelDefaultPrice[] = [],
  currentModelId: string | null = null,
): DefaultPriceModelOption[] {
  const entries = new Map<string, { id: string, upstreamModels: Set<string>, channelNames: Set<string> }>()
  const add = (upstreamModel: string | null | undefined, canonicalModelId: string | null | undefined, channelName: string | null | undefined) => {
    const upstream = String(upstreamModel ?? '').trim()
    const canonical = String(canonicalModelId ?? '').trim()
    const modelId = canonical || upstream
    if (!modelId) return
    const entry = entries.get(modelId) || { id: modelId, upstreamModels: new Set(), channelNames: new Set() }
    if (upstream) entry.upstreamModels.add(upstream)
    if (channelName) entry.channelNames.add(channelName)
    entries.set(modelId, entry)
  }
  for (const channel of Array.isArray(channels) ? channels : []) {
    if (!channel) continue
    const mapped = new Set<string>()
    for (const mapping of Array.isArray(channel.modelMappings) ? channel.modelMappings : []) {
      const upstream = String(mapping?.upstreamModel ?? '').trim()
      mapped.add(upstream)
      add(upstream, mapping?.canonicalModelId, channel.name)
    }
    for (const model of Array.isArray(channel.models) ? channel.models : []) {
      const upstream = String(model ?? '').trim()
      if (upstream && !mapped.has(upstream)) add(upstream, null, channel.name)
    }
  }

  const configuredIds = new Set(
    (Array.isArray(configuredPrices) ? configuredPrices : [])
      .map((p) => String(p?.modelId ?? '').trim().toLowerCase())
      .filter(Boolean),
  )
  const currentTrimmed = String(currentModelId ?? '').trim().toLowerCase()

  return [...entries.values()]
    .filter((entry) => {
      const id = entry.id.toLowerCase()
      if (currentTrimmed && id === currentTrimmed) return true
      return !configuredIds.has(id)
    })
    .map((entry) => ({
      id: entry.id,
      upstreamModels: [...entry.upstreamModels].sort(),
      channelNames: [...entry.channelNames].sort(),
    }))
    .sort((left, right) => left.id.localeCompare(right.id))
}

/**
 * Resolve the effective price and its source for one channel model.
 * Channel overrides win over model defaults.
 */
export interface ChannelModelPriceResolution {
  source: 'channel' | 'default' | 'none'
  price: ModelPrice | ModelDefaultPrice | null
  modelId: string
  defaultPrice: ModelDefaultPrice | null
}

export function resolveChannelModelPrice(
  channel: PriceChannel | null | undefined,
  upstreamModel: string | null | undefined,
  prices: ModelPrice[] | null | undefined,
  defaultPrices: ModelDefaultPrice[] = [],
): ChannelModelPriceResolution {
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

/** Resolve the aggregate source label for a displayed model group. */
export function resolveDisplayPriceSource(
  channel: PriceChannel | null | undefined,
  upstreamModels: string[] | null | undefined,
  prices: ModelPrice[] | null | undefined,
  defaultPrices: ModelDefaultPrice[] = [],
): 'channel' | 'default' | 'mixed' | 'none' {
  const sources = (Array.isArray(upstreamModels) ? upstreamModels : [])
    .map((model) => resolveChannelModelPrice(channel, model, prices, defaultPrices).source)
  if (sources.length === 0 || sources.some((source) => source === 'none')) return 'none'
  if (sources.every((source) => source === 'channel')) return 'channel'
  if (sources.every((source) => source === 'default')) return 'default'
  return 'mixed'
}

/** List the configured prices for one channel, ordered by upstream model. */
export function pricesForChannel(prices: ModelPrice[] | null | undefined, channelId: string | null | undefined): ModelPrice[] {
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
export function channelModelPriceOptions(
  channelModels: ChannelModel[] | null | undefined,
  prices: ModelPrice[] | null | undefined,
  channelId: string,
  defaultPrices: ModelDefaultPrice[] = [],
): Array<ChannelModel & { price: ModelPrice | null, defaultPrice: ModelDefaultPrice | null }> {
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
export function isSamePriceTarget(price: ModelPrice | null | undefined, channelId: string | null | undefined, upstreamModel: string | null | undefined): boolean {
  if (!price || price.channelId !== channelId) return false
  return String(price.upstreamModel ?? '').trim() === String(upstreamModel ?? '').trim()
}

/** Return the channel's upstream models that have no price record. */
export function unpricedChannelModels(
  channel: PriceChannel | null | undefined,
  prices: ModelPrice[] | null | undefined,
  defaultPrices: ModelDefaultPrice[] = [],
): string[] {
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
export function channelPriceSummaries(
  channels: PriceChannel[] | null | undefined,
  prices: ModelPrice[] | null | undefined,
  defaultPrices: ModelDefaultPrice[] = [],
): Map<string, { unpriced: string[], count: number }> {
  const summaries = new Map()
  for (const channel of Array.isArray(channels) ? channels : []) {
    if (!channel?.id) continue
    const unpriced = unpricedChannelModels(channel, prices, defaultPrices)
    summaries.set(channel.id, { unpriced, count: unpriced.length })
  }
  return summaries
}

/** Build the post-save price prompt, or null when nothing needs attention. */
export function buildPricePrompt(
  channel: Pick<Channel, 'id' | 'name'> | null | undefined,
  count: number,
): { channelId: string, channelName: string, count: number } | null {
  if (!channel?.id || !Number.isFinite(count) || count <= 0) return null
  return { channelId: channel.id, channelName: channel.name || '', count }
}

/** Locate the channel returned by a create/update call inside a freshly loaded list. */
export function resolveSavedChannel(
  channels: Channel[] | null | undefined,
  saved: Pick<Channel, 'id' | 'name'> | null | undefined,
  editingId: string | null = null,
  fallbackName: string | null = null,
): Channel | null {
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

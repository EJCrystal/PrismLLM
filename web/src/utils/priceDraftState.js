import { formatPriceSummary } from './format.js'

const PRICE_TIER_FIELDS = [
  'upToTokens',
  'inputPricePerMillion',
  'outputPricePerMillion',
  'cachedInputPricePerMillion',
  'cachedInputWritePricePerMillion',
]

function normalizeTier(tier) {
  return Object.fromEntries(
    PRICE_TIER_FIELDS.map((field) => [field, String(tier?.[field] ?? '')]),
  )
}

/** Stable key for one channel/model draft. */
export function priceDraftKey(channelId, modelId) {
  return `${String(channelId ?? '').trim()}\u0000${String(modelId ?? '').trim()}`
}

/** Normalize a price form to a comparable snapshot. */
export function priceDraftSnapshot(form) {
  return JSON.stringify({
    currency: String(form?.currency || 'USD'),
    inputPricePerMillion: String(form?.inputPricePerMillion ?? ''),
    outputPricePerMillion: String(form?.outputPricePerMillion ?? ''),
    cachedInputPricePerMillion: String(form?.cachedInputPricePerMillion ?? ''),
    cachedInputWritePricePerMillion: String(form?.cachedInputWritePricePerMillion ?? ''),
    pricingMode: String(form?.pricingMode || 'token'),
    perRequestPrice: String(form?.perRequestPrice ?? ''),
    priceTiers: (Array.isArray(form?.priceTiers) ? form.priceTiers : []).map(normalizeTier),
  })
}

/** Copy only editable price fields so drafts never share tier arrays. */
export function clonePriceDraftForm(form) {
  return {
    currency: form?.currency || 'USD',
    inputPricePerMillion: form?.inputPricePerMillion ?? '',
    outputPricePerMillion: form?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: form?.cachedInputPricePerMillion ?? '',
    cachedInputWritePricePerMillion: form?.cachedInputWritePricePerMillion ?? '',
    pricingMode: form?.pricingMode || 'token',
    perRequestPrice: form?.perRequestPrice ?? '',
    priceTiers: (Array.isArray(form?.priceTiers) ? form.priceTiers : [])
      .map((tier) => ({ ...tier })),
  }
}

/** Create an independent draft for one channel model. */
export function createPriceDraft({ channelId, modelId, form, price = null, baseline = null }) {
  const normalizedChannelId = String(channelId ?? '').trim()
  const normalizedModelId = String(modelId ?? '').trim()
  const draftForm = clonePriceDraftForm(form)
  return {
    key: priceDraftKey(normalizedChannelId, normalizedModelId),
    channelId: normalizedChannelId,
    modelId: normalizedModelId,
    form: draftForm,
    price: price || null,
    baseline: baseline ?? priceDraftSnapshot(draftForm),
  }
}

/** True when the draft differs from the last loaded or saved state. */
export function priceDraftHasChanges(draft) {
  if (!draft?.form) return false
  return priceDraftSnapshot(draft.form) !== draft.baseline
}

/** Merge stored drafts with the current form and return only changed models. */
export function collectDirtyPriceDrafts(drafts, currentDraft = null) {
  const byKey = new Map()
  const values = drafts instanceof Map ? drafts.values() : (drafts || [])
  for (const draft of values) {
    if (draft?.key) byKey.set(draft.key, draft)
  }
  if (currentDraft?.key) byKey.set(currentDraft.key, currentDraft)
  return [...byKey.values()]
    .filter(priceDraftHasChanges)
    .sort((left, right) => left.modelId.localeCompare(right.modelId))
}

/** Build the destructive-action prompt when removing a channel price override. */
export function buildChannelPriceDeleteMessage({ modelId, defaultPrice = null, hasUnsavedDraft = false }) {
  const fallback = defaultPrice
    ? `删除后将恢复为继承模型默认价（${formatPriceSummary(defaultPrice)}）。`
    : '删除后将恢复为未定价。'
  const draftNote = hasUnsavedDraft ? '\n该模型尚未保存的编辑也会一并丢弃。' : ''
  return `确定删除「${modelId}」的渠道覆盖价？\n\n${fallback}${draftNote}`
}

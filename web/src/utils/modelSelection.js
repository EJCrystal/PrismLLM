import { findModelPrice } from './modelPrice.js'
import { emptyPriceDraft, priceFormState } from './priceForm.js'

/** Resolve the editable draft and price record for one channel model. */
export function resolveModelSelectionTarget(channelId, upstreamModel, prices) {
  const modelId = String(upstreamModel ?? '').trim()
  const price = findModelPrice(prices, channelId, modelId)
  return {
    modelId,
    price,
    draft: price ? priceFormState(price) : emptyPriceDraft(),
  }
}

/** State to apply whenever the selected model changes or a new draft starts. */
export function clearedVersionHistoryState() {
  return {
    invalidateRequest: true,
    showVersions: false,
    loadingVersions: false,
    versionHistory: [],
  }
}

/** Decide whether a model watcher should load, clear, or preserve the draft. */
export function modelSelectionAction(existingPrice, activePrice, targetChanged) {
  if (existingPrice && activePrice?.id !== existingPrice.id) return 'load'
  if (!existingPrice && targetChanged) return 'clear'
  return 'preserve'
}

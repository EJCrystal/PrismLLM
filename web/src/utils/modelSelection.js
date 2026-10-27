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

/** Select a refreshed price only when the target was found and is not active. */
export function refreshedPriceToApply(existingPrice, activePrice) {
  return existingPrice && activePrice?.id !== existingPrice.id ? existingPrice : null
}

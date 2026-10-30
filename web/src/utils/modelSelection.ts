import type { ModelPrice } from '../types/api'
import { findModelPrice } from './modelPrice'
import { emptyPriceDraft, priceFormState, type PriceForm } from './priceForm'

/** Resolve the editable draft and price record for one channel model. */
export function resolveModelSelectionTarget(
  channelId: string,
  upstreamModel: string | null | undefined,
  prices: ModelPrice[] | null | undefined,
): { modelId: string, price: ModelPrice | null, draft: PriceForm } {
  const modelId = String(upstreamModel ?? '').trim()
  const price = findModelPrice(prices, channelId, modelId)
  return {
    modelId,
    price,
    draft: price ? priceFormState(price) : emptyPriceDraft(),
  }
}

/** Select a refreshed price only when the target was found and is not active. */
export function refreshedPriceToApply(existingPrice: ModelPrice | null | undefined, activePrice: ModelPrice | null | undefined): ModelPrice | null {
  return existingPrice && activePrice?.id !== existingPrice.id ? existingPrice : null
}

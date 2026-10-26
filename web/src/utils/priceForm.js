/** Build the editable price form and payload without silently changing its currency. */
export function priceFormState(price) {
  return {
    currency: price?.currency || 'USD',
    inputPricePerMillion: price?.inputPricePerMillion ?? '',
    outputPricePerMillion: price?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: price?.cachedInputPricePerMillion ?? '',
  }
}

export function buildPricePayload(form, channelId, upstreamModel) {
  return {
    channelId,
    upstreamModel: String(upstreamModel ?? '').trim(),
    currency: form.currency || 'USD',
    inputPricePerMillion: String(form.inputPricePerMillion ?? '').trim(),
    outputPricePerMillion: String(form.outputPricePerMillion ?? '').trim(),
    cachedInputPricePerMillion: String(form.cachedInputPricePerMillion ?? '').trim() || null,
  }
}

/** Reset the draft when the selected channel/model has no saved price. */
export function emptyPriceDraft() {
  return {
    currency: 'USD',
    inputPricePerMillion: '',
    outputPricePerMillion: '',
    cachedInputPricePerMillion: '',
  }
}

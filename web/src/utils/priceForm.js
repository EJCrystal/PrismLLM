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

/** Build the editable model default price form. */
export function defaultPriceFormState(price) {
  return {
    modelId: price?.modelId || '',
    currency: price?.currency || 'USD',
    inputPricePerMillion: price?.inputPricePerMillion ?? '',
    outputPricePerMillion: price?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: price?.cachedInputPricePerMillion ?? '',
  }
}

export function buildDefaultPricePayload(form, modelId) {
  return {
    modelId: String(modelId ?? '').trim(),
    currency: form.currency || 'USD',
    inputPricePerMillion: String(form.inputPricePerMillion ?? '').trim(),
    outputPricePerMillion: String(form.outputPricePerMillion ?? '').trim(),
    cachedInputPricePerMillion: String(form.cachedInputPricePerMillion ?? '').trim() || null,
  }
}

export function buildBatchPricePayload(form, channelId, upstreamModels) {
  return {
    channelId,
    upstreamModels: [...new Set((Array.isArray(upstreamModels) ? upstreamModels : [])
      .map((model) => String(model ?? '').trim())
      .filter(Boolean))],
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

/** True when the user has typed or loaded any price amount. */
export function hasPriceDraft(form) {
  if (!form) return false
  return ['inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion']
    .some((field) => String(form[field] ?? '').trim() !== '')
}

/** Parse a required non-negative price field; blank or invalid input returns null. */
export function parseNonNegativePrice(value) {
  const text = String(value ?? '').trim()
  if (!text) return null
  const parsed = Number(text)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

/** Parse an optional non-negative price field; blank input returns null. */
export function parseOptionalNonNegativePrice(value) {
  const text = String(value ?? '').trim()
  return text ? parseNonNegativePrice(text) : null
}

/** Load a saved price into the edit form. */
export function applyPriceToForm(form, price) {
  if (!form || !price) return form
  Object.assign(form, {
    channelId: price.channelId,
    upstreamModel: price.upstreamModel,
    ...priceFormState(price),
  })
  return form
}

/** Reset the form to a fresh USD draft for the selected channel. */
export function resetPriceForm(form) {
  if (!form) return form
  Object.assign(form, { upstreamModel: '', ...emptyPriceDraft() })
  return form
}

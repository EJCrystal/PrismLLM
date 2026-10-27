import { fromLocalInput, toLocalInput } from './format.js'

/** Build the editable price form and payload without silently changing its currency. */
export function priceFormState(price) {
  const scheduled = price?.scheduledVersion || null
  const scheduledTime = scheduled?.effectiveFrom && scheduled.effectiveFrom > Date.now()
    ? scheduled.effectiveFrom
    : null
  return {
    currency: scheduled?.currency || price?.currency || 'USD',
    inputPricePerMillion: scheduled?.inputPricePerMillion ?? price?.inputPricePerMillion ?? '',
    outputPricePerMillion: scheduled?.outputPricePerMillion ?? price?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: scheduled ? (scheduled.cachedInputPricePerMillion ?? '') : (price?.cachedInputPricePerMillion ?? ''),
    cachedInputWritePricePerMillion: scheduled ? (scheduled.cachedInputWritePricePerMillion ?? '') : (price?.cachedInputWritePricePerMillion ?? ''),
    pricingMode: scheduled?.pricingMode || price?.pricingMode || 'token',
    perRequestPrice: scheduled?.perRequestPrice ?? price?.perRequestPrice ?? '',
    priceTiers: Array.isArray(scheduled?.priceTiers)
      ? scheduled.priceTiers.map(priceTierFormState)
      : Array.isArray(price?.priceTiers) ? price.priceTiers.map(priceTierFormState) : [],
    isScheduled: Boolean(scheduledTime),
    effectiveAt: scheduledTime ? toLocalInput(scheduledTime) : '',
    scheduledSnapshot: scheduledTime ? scheduleSnapshot({
      ...price?.scheduledVersion,
      effectiveFrom: fromLocalInput(toLocalInput(scheduledTime)),
    }) : null,
    scheduledId: scheduledTime ? (price?.scheduledVersion?.id ?? null) : null,
  }
}

/** Stable comparison key for a pending scheduled price. */
export function scheduleSnapshot(value) {
  if (!value) return null
  return JSON.stringify({
    effectiveFrom: Number(value.effectiveFrom || 0),
    currency: value.currency || '',
    inputPricePerMillion: value.inputPricePerMillion ?? '',
    outputPricePerMillion: value.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: value.cachedInputPricePerMillion ?? '',
    cachedInputWritePricePerMillion: value.cachedInputWritePricePerMillion ?? '',
    pricingMode: value.pricingMode || '',
    perRequestPrice: value.perRequestPrice ?? '',
    priceTiers: normalizeSnapshotTiers(value.priceTiers, value.pricingMode),
  })
}

function normalizeSnapshotTiers(tiers, pricingMode) {
  if (!['tiered', 'volume'].includes(pricingMode)) return []
  return (Array.isArray(tiers) ? tiers : []).map((tier) => {
    const limit = String(tier?.upToTokens ?? '').trim()
    return {
      upToTokens: limit ? Number(limit) : null,
      inputPricePerMillion: String(tier?.inputPricePerMillion ?? '').trim(),
      outputPricePerMillion: String(tier?.outputPricePerMillion ?? '').trim(),
      cachedInputPricePerMillion: String(tier?.cachedInputPricePerMillion ?? '').trim() || null,
      cachedInputWritePricePerMillion: String(tier?.cachedInputWritePricePerMillion ?? '').trim() || null,
    }
  })
}

/** True only when the user has not altered the loaded pending schedule. */
export function isUnchangedSchedule(form) {
  if (!form?.scheduledSnapshot || !form.isScheduled || !form.effectiveAt) return false
  const current = scheduleSnapshot({
    currency: form.currency,
    inputPricePerMillion: form.inputPricePerMillion,
    outputPricePerMillion: form.outputPricePerMillion,
    cachedInputPricePerMillion: form.cachedInputPricePerMillion,
    cachedInputWritePricePerMillion: form.cachedInputWritePricePerMillion,
    pricingMode: form.pricingMode,
    perRequestPrice: form.perRequestPrice,
    priceTiers: priceTiersPayload(form),
    effectiveFrom: fromLocalInput(form.effectiveAt),
  })
  return current === form.scheduledSnapshot
}

function priceTierFormState(tier) {
  return {
    upToTokens: tier?.upToTokens ?? '',
    inputPricePerMillion: tier?.inputPricePerMillion ?? '',
    outputPricePerMillion: tier?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: tier?.cachedInputPricePerMillion ?? '',
    cachedInputWritePricePerMillion: tier?.cachedInputWritePricePerMillion ?? '',
  }
}

function priceTiersPayload(form) {
  if (!['tiered', 'volume'].includes(form?.pricingMode)) return []
  return (Array.isArray(form?.priceTiers) ? form.priceTiers : []).map((tier) => {
    const limit = String(tier?.upToTokens ?? '').trim()
    return {
    upToTokens: limit ? Number(limit) : null,
    inputPricePerMillion: String(tier?.inputPricePerMillion ?? '').trim(),
    outputPricePerMillion: String(tier?.outputPricePerMillion ?? '').trim(),
    cachedInputPricePerMillion: String(tier?.cachedInputPricePerMillion ?? '').trim() || null,
    cachedInputWritePricePerMillion: String(tier?.cachedInputWritePricePerMillion ?? '').trim() || null,
    }
  })
}

function pricingPayload(form) {
  const pricingMode = form?.pricingMode || 'token'
  return {
    pricingMode,
    inputPricePerMillion: pricingMode === 'token' ? String(form?.inputPricePerMillion ?? '').trim() : null,
    outputPricePerMillion: pricingMode === 'token' ? String(form?.outputPricePerMillion ?? '').trim() : null,
    cachedInputPricePerMillion: pricingMode === 'token'
      ? String(form?.cachedInputPricePerMillion ?? '').trim() || null
      : null,
    cachedInputWritePricePerMillion: pricingMode === 'token'
      ? String(form?.cachedInputWritePricePerMillion ?? '').trim() || null
      : null,
    perRequestPrice: pricingMode === 'per_request'
      ? String(form?.perRequestPrice ?? '').trim() || null
      : null,
    priceTiers: priceTiersPayload(form),
    effectiveAt: form?.isScheduled && form?.effectiveAt ? fromLocalInput(form.effectiveAt) : null,
    preserveSchedule: isUnchangedSchedule(form),
    expectedScheduleId: isUnchangedSchedule(form)
      ? form.scheduledId
      : null,
  }
}

export function buildPricePayload(form, channelId, upstreamModel) {
  return {
    channelId,
    upstreamModel: String(upstreamModel ?? '').trim(),
    currency: form.currency || 'USD',
    ...pricingPayload(form),
  }
}

/** Build the editable model default price form. */
export function defaultPriceFormState(price) {
  return {
    modelId: price?.modelId || '',
    currency: price?.currency || 'USD',
    ...priceFormState(price),
  }
}

export function buildDefaultPricePayload(form, modelId) {
  return {
    modelId: String(modelId ?? '').trim(),
    currency: form.currency || 'USD',
    ...pricingPayload(form),
  }
}

export function buildBatchPricePayload(form, channelId, upstreamModels) {
  return {
    channelId,
    upstreamModels: [...new Set((Array.isArray(upstreamModels) ? upstreamModels : [])
      .map((model) => String(model ?? '').trim())
      .filter(Boolean))],
    currency: form.currency || 'USD',
    ...pricingPayload(form),
  }
}

export function existingPricePayload(form, channelId, upstreamModel) {
  return buildPricePayload(form, channelId, upstreamModel)
}

export function existingDefaultPricePayload(form, modelId) {
  return buildDefaultPricePayload(form, modelId)
}

/** Reset the draft when the selected channel/model has no saved price. */
export function emptyPriceDraft() {
  return {
    currency: 'USD',
    inputPricePerMillion: '',
    outputPricePerMillion: '',
    cachedInputPricePerMillion: '',
    cachedInputWritePricePerMillion: '',
    pricingMode: 'token',
    perRequestPrice: '',
    priceTiers: [],
    scheduledSnapshot: null,
    scheduledId: null,
    isScheduled: false,
    effectiveAt: '',
  }
}

/** True when the user has typed or loaded any price amount. */
export function hasPriceDraft(form) {
  if (!form) return false
  return ['inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion', 'cachedInputWritePricePerMillion', 'perRequestPrice']
    .some((field) => String(form[field] ?? '').trim() !== '')
    || (Array.isArray(form.priceTiers) && form.priceTiers.some((tier) =>
      ['upToTokens', 'inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion', 'cachedInputWritePricePerMillion']
        .some((field) => String(tier?.[field] ?? '').trim() !== '')))
}

/** True when the currently selected billing mode contains an entered price. */
export function hasActivePriceDraft(form) {
  const mode = form?.pricingMode || 'token'
  if (mode === 'token') {
    return ['inputPricePerMillion', 'outputPricePerMillion']
      .some((field) => String(form?.[field] ?? '').trim() !== '')
  }
  if (mode === 'per_request') return String(form?.perRequestPrice ?? '').trim() !== ''
  if (mode === 'tiered' || mode === 'volume') {
    return Array.isArray(form?.priceTiers) && form.priceTiers.some((tier) =>
      ['inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion', 'cachedInputWritePricePerMillion']
        .some((field) => String(tier?.[field] ?? '').trim() !== ''))
  }
  return false
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

/** Validate the active pricing strategy before sending it to the API. */
export function priceFormError(form, options = {}) {
  const mode = form?.pricingMode || 'token'
  const batchAction = options.action === 'batch'
  const optionalFields = mode === 'token'
    ? ['cachedInputPricePerMillion', 'cachedInputWritePricePerMillion']
    : []
  if (mode === 'token') {
    if (batchAction &&
      (!String(form.inputPricePerMillion ?? '').trim() || !String(form.outputPricePerMillion ?? '').trim())
    ) {
      return '请先填写输入单价和输出单价，再批量套用'
    }
    if (parseNonNegativePrice(form.inputPricePerMillion) == null || parseNonNegativePrice(form.outputPricePerMillion) == null) {
      return '输入和输出单价必须是非负数字'
    }
  } else if (mode === 'per_request') {
    if (batchAction && !String(form.perRequestPrice ?? '').trim()) {
      return '请先填写按次费用，再批量套用'
    }
    if (parseNonNegativePrice(form.perRequestPrice) == null) return '按次费用必须是非负数字'
  } else if (mode === 'tiered' || mode === 'volume') {
    const tiers = Array.isArray(form.priceTiers) ? form.priceTiers : []
    if (batchAction && tiers.length === 0) {
      return '请先添加阶梯价格，再批量套用'
    }
    if (tiers.length === 0 || tiers.length > 20) return '请添加 1 到 20 个价格档位'
    let previous = 0
    for (let index = 0; index < tiers.length; index += 1) {
      const tier = tiers[index]
      const isLast = index === tiers.length - 1
      const limit = String(tier.upToTokens ?? '').trim()
      if (isLast ? limit !== '' : (!/^\d+$/.test(limit) || Number(limit) <= previous || !Number.isSafeInteger(Number(limit)))) {
        return '阶梯上限必须严格递增，最后一档留空表示不限'
      }
      if (!isLast) previous = Number(limit)
      if (batchAction &&
        (!String(tier.inputPricePerMillion ?? '').trim() || !String(tier.outputPricePerMillion ?? '').trim())
      ) {
        return '请先填写每档的输入和输出单价，再批量套用'
      }
      if (parseNonNegativePrice(tier.inputPricePerMillion) == null || parseNonNegativePrice(tier.outputPricePerMillion) == null) {
        return '每档的输入和输出单价都必须是非负数字'
      }
      for (const field of ['cachedInputPricePerMillion', 'cachedInputWritePricePerMillion']) {
        if (String(tier[field] ?? '').trim() && parseOptionalNonNegativePrice(tier[field]) == null) {
          return '每档缓存读写单价必须是非负数字'
        }
      }
    }
  } else {
    return '请选择有效的计费方式'
  }
  for (const field of optionalFields) {
    if (String(form[field] ?? '').trim() && parseOptionalNonNegativePrice(form[field]) == null) {
      return field === 'cachedInputPricePerMillion' ? '缓存读取单价必须是非负数字' : '缓存写入单价必须是非负数字'
    }
  }
  if (form?.isScheduled) {
    const effectiveAtMs = fromLocalInput(form.effectiveAt)
    if (!effectiveAtMs) return '请选择定时生效时间'
    if (effectiveAtMs <= Date.now()) return '定时生效时间必须是未来的时间'
  }
  return null
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

/** Build the editable price form and payload without silently changing its currency. */
import type { ModelDefaultPrice, ModelDefaultPriceRequest, ModelPrice, ModelPriceRequest, ModelPriceTier } from '../types/api'

export interface PriceFormTier {
  upToTokens: number | string
  inputPricePerMillion: number | string
  outputPricePerMillion: number | string
  cachedInputPricePerMillion: number | string
  cachedInputWritePricePerMillion: number | string
}

export interface PriceForm {
  currency: string
  inputPricePerMillion: number | string
  outputPricePerMillion: number | string
  cachedInputPricePerMillion: number | string
  cachedInputWritePricePerMillion: number | string
  pricingMode: string
  perRequestPrice: number | string
  priceTiers: PriceFormTier[]
}

export interface PriceEditable {
  currency?: string | null
  inputPricePerMillion?: number | string | null
  outputPricePerMillion?: number | string | null
  cachedInputPricePerMillion?: number | string | null
  cachedInputWritePricePerMillion?: number | string | null
  pricingMode?: string | null
  perRequestPrice?: number | string | null
  priceTiers?: Array<Partial<PriceFormTier>>
  modelId?: string | null
}

export function priceFormState(price: PriceEditable | ModelPrice | ModelDefaultPrice | null | undefined): Omit<PriceForm, 'priceTiers'> & { priceTiers: PriceFormTier[] } {
  return {
    currency: price?.currency || 'USD',
    inputPricePerMillion: price?.inputPricePerMillion ?? '',
    outputPricePerMillion: price?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: price?.cachedInputPricePerMillion ?? '',
    cachedInputWritePricePerMillion: price?.cachedInputWritePricePerMillion ?? '',
    pricingMode: price?.pricingMode || 'token',
    perRequestPrice: price?.perRequestPrice ?? '',
    priceTiers: Array.isArray(price?.priceTiers) ? (price.priceTiers as ModelPriceTier[]).map((tier) => priceTierFormState(tier as Partial<PriceFormTier>)) : [],
  }
}

function priceTierFormState(tier: Partial<PriceFormTier>): PriceFormTier {
  return {
    upToTokens: tier?.upToTokens ?? '',
    inputPricePerMillion: tier?.inputPricePerMillion ?? '',
    outputPricePerMillion: tier?.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: tier?.cachedInputPricePerMillion ?? '',
    cachedInputWritePricePerMillion: tier?.cachedInputWritePricePerMillion ?? '',
  }
}

function priceTiersPayload(form: PriceForm): ModelPriceTier[] {
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

function pricingPayload(form: PriceForm): Omit<ModelPriceRequest, 'channelId' | 'upstreamModel' | 'currency'> | Omit<ModelDefaultPriceRequest, 'modelId' | 'currency'> {
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
  }
}

export function buildPricePayload(form: PriceForm, channelId: string, upstreamModel: string | null | undefined): ModelPriceRequest {
  return {
    channelId,
    upstreamModel: String(upstreamModel ?? '').trim(),
    currency: form.currency || 'USD',
    ...pricingPayload(form),
  }
}

/** Build the editable model default price form. */
export function defaultPriceFormState(price: PriceEditable | null | undefined): PriceForm & { modelId: string } {
  return {
    modelId: price?.modelId || '',
    ...priceFormState(price),
  }
}

export function buildDefaultPricePayload(form: PriceForm, modelId: string | null | undefined): ModelDefaultPriceRequest {
  return {
    modelId: String(modelId ?? '').trim(),
    currency: form.currency || 'USD',
    ...pricingPayload(form),
  }
}

export function existingPricePayload(form: PriceForm, channelId: string, upstreamModel: string | null | undefined): ModelPriceRequest {
  return buildPricePayload(form, channelId, upstreamModel)
}

export function existingDefaultPricePayload(form: PriceForm, modelId: string | null | undefined): ModelDefaultPriceRequest {
  return buildDefaultPricePayload(form, modelId)
}

/** Reset the draft when the selected channel/model has no saved price. */
export function emptyPriceDraft(): PriceForm {
  return {
    currency: 'USD',
    inputPricePerMillion: '',
    outputPricePerMillion: '',
    cachedInputPricePerMillion: '',
    cachedInputWritePricePerMillion: '',
    pricingMode: 'token',
    perRequestPrice: '',
    priceTiers: [],
  }
}

/** True when the user has typed or loaded any price amount. */
export function hasPriceDraft(form: PriceForm | null | undefined): boolean {
  if (!form) return false
  return ['inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion', 'cachedInputWritePricePerMillion', 'perRequestPrice']
    .some((field) => String(form[field as keyof PriceForm] ?? '').trim() !== '')
    || (Array.isArray(form.priceTiers) && form.priceTiers.some((tier) =>
      ['upToTokens', 'inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion', 'cachedInputWritePricePerMillion']
        .some((field) => String(tier?.[field as keyof PriceFormTier] ?? '').trim() !== '')))
}

/** Parse a required non-negative price field; blank or invalid input returns null. */
export function parseNonNegativePrice(value: number | string | null | undefined): number | null {
  const text = String(value ?? '').trim()
  if (!text) return null
  const parsed = Number(text)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

/** Parse an optional non-negative price field; blank input returns null. */
export function parseOptionalNonNegativePrice(value: number | string | null | undefined): number | null {
  const text = String(value ?? '').trim()
  return text ? parseNonNegativePrice(text) : null
}

/** Validate the active pricing strategy before sending it to the API. */
export function priceFormError(form: PriceForm): string | null {
  const mode = form?.pricingMode || 'token'
  const optionalFields = mode === 'token'
    ? ['cachedInputPricePerMillion', 'cachedInputWritePricePerMillion']
    : []
  if (mode === 'token') {
    if (parseNonNegativePrice(form.inputPricePerMillion) == null || parseNonNegativePrice(form.outputPricePerMillion) == null) {
      return '输入和输出单价必须是非负数字'
    }
  } else if (mode === 'per_request') {
    if (parseNonNegativePrice(form.perRequestPrice) == null) return '按次费用必须是非负数字'
  } else if (mode === 'tiered' || mode === 'volume') {
    const tiers = Array.isArray(form.priceTiers) ? form.priceTiers : []
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
      if (parseNonNegativePrice(tier.inputPricePerMillion) == null || parseNonNegativePrice(tier.outputPricePerMillion) == null) {
        return '每档的输入和输出单价都必须是非负数字'
      }
      for (const field of ['cachedInputPricePerMillion', 'cachedInputWritePricePerMillion'] as const) {
        if (String(tier[field] ?? '').trim() && parseOptionalNonNegativePrice(tier[field]) == null) {
          return '每档缓存读写单价必须是非负数字'
        }
      }
    }
  } else {
    return '请选择有效的计费方式'
  }
  for (const field of optionalFields) {
    const value = form[field as keyof PriceForm]
    if (String(value ?? '').trim() && parseOptionalNonNegativePrice(value as string | number | null | undefined) == null) {
      return field === 'cachedInputPricePerMillion' ? '缓存读取单价必须是非负数字' : '缓存写入单价必须是非负数字'
    }
  }
  return null
}

/** Load a saved price into the edit form. */
export function applyPriceToForm<T extends Partial<PriceForm> & { channelId?: string, upstreamModel?: string }>(form: T, price: ModelPrice | null | undefined): T {
  if (!form || !price) return form
  Object.assign(form, {
    channelId: price.channelId,
    upstreamModel: price.upstreamModel,
    ...priceFormState(price),
  })
  return form
}

/** Reset the form to a fresh USD draft for the selected channel. */
export function resetPriceForm<T extends Partial<PriceForm> & { upstreamModel?: string }>(form: T): T {
  if (!form) return form
  Object.assign(form, { upstreamModel: '', ...emptyPriceDraft() })
  return form
}

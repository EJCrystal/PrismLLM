/**
 * 前端通用格式化工具函数库（单例缓存 Intl 实例，提供统一的数据展示格式与边界容错）。
 */

const formatters = new Map<string, Intl.DateTimeFormat>()

export function formatRelativeTime(value: number | string | Date | null | undefined, now = Date.now()): string {
  if (value == null || value === '') return '—'
  const timestamp = value instanceof Date ? value.getTime() : new Date(value).getTime()
  if (!Number.isFinite(timestamp) || !Number.isFinite(now)) return '—'
  if (timestamp > now + 60_000) return formatTime(value)
  const elapsed = Math.max(0, now - timestamp)
  if (elapsed < 60_000) return '刚刚'
  if (elapsed < 3_600_000) return Math.floor(elapsed / 60_000) + ' 分钟前'
  if (elapsed < 86_400_000) return Math.floor(elapsed / 3_600_000) + ' 小时前'
  return Math.floor(elapsed / 86_400_000) + ' 天前'
}

function getDateTimeFormatter(options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = JSON.stringify(options)
  let formatter = formatters.get(key)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('zh-CN', options)
    formatters.set(key, formatter)
  }
  return formatter
}

/**
 * 格式化时间戳或日期字符串。
 *
 * @param {number|string|Date|null} value 时间戳或日期
 * @param {object} options 格式化选项
 * @param {'short'|'medium'|'timeOnly'|'dateOnly'} options.style 样式：short (分), medium (秒), timeOnly (时分秒), dateOnly (仅日期)
 * @param {string} options.fallback 缺省值占位符
 * @returns {string}
 */
export interface FormatTimeOptions {
  style?: 'short' | 'medium' | 'timeOnly' | 'dateOnly'
  fallback?: string
}

export function formatTime(
  value: number | string | Date | null | undefined,
  { style = 'short', fallback = '—' }: FormatTimeOptions = {},
): string {
  if (value == null || value === '') return fallback
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return fallback

  let options: Intl.DateTimeFormatOptions
  if (style === 'medium') {
    options = { dateStyle: 'short', timeStyle: 'medium' }
  } else if (style === 'timeOnly') {
    options = { hour: '2-digit', minute: '2-digit', second: '2-digit' }
  } else if (style === 'dateOnly') {
    options = { dateStyle: 'short' }
  } else {
    options = { dateStyle: 'short', timeStyle: 'short' }
  }

  try {
    return getDateTimeFormatter(options).format(date)
  } catch {
    return fallback
  }
}

/**
 * 格式化耗时展示，全局唯一的时长单位规则：
 * 小于 1 秒用 `ms`，大于等于 1 秒一律换算成 `s`（整秒不带小数，非整秒保留 1 位）。
 *
 * 表格与 CSV 等需要可比较、可求和的列不要用本函数逐值切换单位，
 * 改用 [durationSeconds] 输出固定单位的数值。
 *
 * @param {number|null} ms 毫秒数值
 * @param {string} fallback 缺省值
 * @returns {string}
 */
export function formatDuration(ms: number | string | null | undefined, fallback = '—'): string {
  if (ms == null || Number.isNaN(Number(ms))) return fallback
  const num = Number(ms)
  if (num < 1000) return `${Math.round(num)} ms`
  // 先按 1 位小数取整，再判断是否整秒；否则 59999ms 会输出 "60.0 s"。
  const rounded = Math.round((num / 1000) * 10) / 10
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)} s`
}

/**
 * 把毫秒换算成固定单位的秒值，供表格 / CSV 等整列统一单位的场景使用。
 * 不做取整：毫秒本身是整数，换算成秒最多 3 位小数，精度无损
 * （截断到 2 位小数会让 12ms 变 0.01、4ms 变 0）。缺失或非法值返回
 * null，便于调用方输出空单元格。
 *
 * @param {number|string|null} ms 毫秒数值
 * @returns {number|null}
 */
export function durationSeconds(ms: number | string | null | undefined): number | null {
  if (ms == null || Number.isNaN(Number(ms))) return null
  return Number(ms) / 1000
}

/**
 * 格式化千分位数字。
 *
 * @param {number|null} value
 * @param {string} fallback
 * @returns {string}
 */
export function formatNumber(value: number | string | null | undefined, fallback = '—'): string {
  if (value == null || Number.isNaN(Number(value))) return fallback
  return Number(value).toLocaleString('zh-CN')
}

/**
 * 紧凑展示 Token 用量（例如 1.5k, 2.30M）。
 *
 * @param {number|null} value
 * @param {string} fallback
 * @returns {string}
 */
export function formatTokensCompact(value: number | string | null | undefined, fallback = '—'): string {
  if (value == null || Number.isNaN(Number(value))) return fallback
  const num = Number(value)
  if (num < 10_000) return num.toLocaleString('zh-CN')
  if (num < 1_000_000) return `${(num / 1_000).toFixed(1)}k`
  return `${(num / 1_000_000).toFixed(2)}M`
}

const CURRENCY_SYMBOLS = {
  USD: '$',
}

/**
 * 币种展示符号；USD 返回 $，其他历史币种回退为原代码。
 *
 * @param {string|null|undefined} currency 币种代号
 * @returns {string}
 */
export function currencySymbol(currency: string | null | undefined): string {
  if (!currency) return ''
  return CURRENCY_SYMBOLS[currency as keyof typeof CURRENCY_SYMBOLS] || currency
}

/**
 * 格式化消费金额，微金额保留高精度，较大金额自动截取规范小数。
 *
 * @param {number|string|null} amount 消费数值
 * @param {string} currency 币种代号
 * @returns {string|null}
 */
export function formatCost(amount: number | string | null | undefined, currency = 'USD'): string | null {
  if (amount == null) return null
  const value = Number(amount)
  if (!Number.isFinite(value)) return `${amount} ${currency || ''}`.trim()

  let text
  if (value === 0) {
    text = '0'
  } else if (value < 0.0001) {
    text = value.toFixed(8)
  } else if (value < 0.01) {
    text = value.toFixed(6)
  } else if (value < 1) {
    text = value.toFixed(4)
  } else {
    text = value.toFixed(2)
  }
  text = text.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
  const prefix = CURRENCY_SYMBOLS[currency as keyof typeof CURRENCY_SYMBOLS] || (currency ? `${currency} ` : '')
  return `${prefix}${text}`
}

/**
 * 格式化模型计费单价，保留最高 8 位高精度小数且不强制添加千分位，避免微小单价显示为 0。
 *
 * @param {number|string|null} value 单价值
 * @param {string} fallback 缺省值
 * @returns {string}
 */
export function formatUnitPrice(value: number | string | null | undefined, fallback = '—'): string {
  if (value == null || value === '' || Number.isNaN(Number(value))) return fallback
  const num = Number(value)
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 8, useGrouping: false })
}

/**
 * 格式化模型计费规则摘要（用于列表展示、无障碍提示与日志详情）。
 *
 * @param {object|null} price 单价对象或日志记录
 * @param {string} fallback 缺省值
 * @returns {string}
 */
export interface PriceSnapshotLike {
  pricingMode?: string | null
  currency?: string | null
  costCurrency?: string | null
  perRequestPrice?: string | null
  inputPricePerMillion?: string | null
  outputPricePerMillion?: string | null
  cachedInputPricePerMillion?: string | null
  cachedInputWritePricePerMillion?: string | null
  priceTiers?: Array<{ upToTokens?: number | null, inputPricePerMillion?: string | null, outputPricePerMillion?: string | null, cachedInputPricePerMillion?: string | null, cachedInputWritePricePerMillion?: string | null }>
}

export function formatPriceSummary(price: PriceSnapshotLike | null | undefined, fallback = '—'): string {
  if (!price) return fallback
  const mode = price.pricingMode || 'token'
  const currency = currencySymbol(price.currency || price.costCurrency || 'USD')
  if (mode === 'per_request') {
    return `按次 ${formatUnitPrice(price.perRequestPrice)} ${currency}`
  }
  if (mode === 'tiered') {
    const count = Array.isArray(price.priceTiers) ? price.priceTiers.length : 0
    return count > 0 ? `阶梯价 (${count} 档) · ${currency}` : `阶梯价 · ${currency}`
  }
  if (mode === 'volume') {
    const count = Array.isArray(price.priceTiers) ? price.priceTiers.length : 0
    return count > 0 ? `阶梯总量价 (${count} 档) · ${currency}` : `阶梯总量价 · ${currency}`
  }
  const input = formatUnitPrice(price.inputPricePerMillion)
  const output = formatUnitPrice(price.outputPricePerMillion)
  return `输入 ${input} · 输出 ${output} · ${currency} / 1M`
}

/**
 * 格式化日志保存的完整计费单价快照，包含缓存单价和各阶梯边界。
 *
 * @param {object|null} price 单价快照或请求日志
 * @returns {{label: string, value: string}[]}
 */
export interface PriceSnapshotRow {
  label: string
  value: string
}

export function formatPriceSnapshot(price: PriceSnapshotLike | null | undefined): PriceSnapshotRow[] {
  if (!price) return []
  const currency = currencySymbol(price.currency || price.costCurrency || 'USD')
  const unit = (value: string | null | undefined) => `${formatUnitPrice(value)} ${currency} / 1M`
  const cachedUnit = (value: string | null | undefined, input: string | null | undefined) => value == null
    ? `沿用输入价（${unit(input)}）`
    : unit(value)

  if (price.pricingMode === 'per_request') {
    return [{ label: '每次请求', value: `${formatUnitPrice(price.perRequestPrice)} ${currency}` }]
  }

  if (price.pricingMode === 'tiered' || price.pricingMode === 'volume') {
    const tiers = Array.isArray(price.priceTiers) ? price.priceTiers : []
    return tiers.map((tier, index) => {
      const previousLimit = index > 0 ? tiers[index - 1]?.upToTokens : null
      const limit = tier.upToTokens
      let range
      if (limit == null) {
        range = previousLimit == null ? '无上限' : `≥ ${formatNumber(Number(previousLimit) + 1)} tokens`
      } else if (previousLimit == null) {
        range = `≤ ${formatNumber(limit)} tokens`
      } else {
        range = `${formatNumber(Number(previousLimit) + 1)}–${formatNumber(limit)} tokens`
      }
      const rates = [
        `输入 ${formatUnitPrice(tier.inputPricePerMillion)}`,
        `输出 ${formatUnitPrice(tier.outputPricePerMillion)}`,
        `缓存读 ${cachedUnit(tier.cachedInputPricePerMillion, tier.inputPricePerMillion)}`,
        `缓存写 ${cachedUnit(tier.cachedInputWritePricePerMillion, tier.inputPricePerMillion)}`,
      ]
      return {
        label: `第 ${index + 1} 档 · ${range}`,
        value: `${rates.join(' · ')} · ${currency} / 1M`,
      }
    })
  }

  return [
    { label: '输入单价', value: unit(price.inputPricePerMillion) },
    { label: '输出单价', value: unit(price.outputPricePerMillion) },
    { label: '缓存读单价', value: cachedUnit(price.cachedInputPricePerMillion, price.inputPricePerMillion) },
    { label: '缓存写单价', value: cachedUnit(price.cachedInputWritePricePerMillion, price.inputPricePerMillion) },
  ]
}

/**
 * 将时间戳转为 datetime-local 输入框所需的本地时间字符串 (YYYY-MM-DDTHH:mm)。
 *
 * @param {number|string|null} value 毫秒时间戳或日期字符串
 * @returns {string}
 */
export function toLocalInput(value: number | string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

/**
 * 将 datetime-local 输入框的值解析为 UTC 毫秒时间戳。
 *
 * @param {string|null} value 本地时间字符串
 * @returns {number|null}
 */
export function fromLocalInput(value: string | null | undefined): number | null {
  if (!value) return null
  const time = new Date(value).getTime()
  return Number.isFinite(time) ? time : null
}

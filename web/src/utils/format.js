/**
 * 前端通用格式化工具函数库（单例缓存 Intl 实例，提供统一的数据展示格式与边界容错）。
 */

const formatters = new Map()

function getDateTimeFormatter(options) {
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
 * @param {'short'|'medium'|'timeOnly'} options.style 样式：short (分), medium (秒), timeOnly (时分秒)
 * @param {string} options.fallback 缺省值占位符
 * @returns {string}
 */
export function formatTime(value, { style = 'short', fallback = '—' } = {}) {
  if (value == null || value === '') return fallback
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return fallback

  let options
  if (style === 'medium') {
    options = { dateStyle: 'short', timeStyle: 'medium' }
  } else if (style === 'timeOnly') {
    options = { hour: '2-digit', minute: '2-digit', second: '2-digit' }
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
 * 格式化请求耗时（毫秒/秒）。
 *
 * @param {number|null} ms 毫秒数值
 * @param {string} fallback 缺省值
 * @returns {string}
 */
export function formatDuration(ms, fallback = '—') {
  if (ms == null || Number.isNaN(Number(ms))) return fallback
  const num = Number(ms)
  if (num < 1000) return `${Math.round(num)} ms`
  return `${(num / 1000).toFixed(2)} s`
}

/**
 * 格式化千分位数字。
 *
 * @param {number|null} value
 * @param {string} fallback
 * @returns {string}
 */
export function formatNumber(value, fallback = '—') {
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
export function formatTokensCompact(value, fallback = '—') {
  if (value == null || Number.isNaN(Number(value))) return fallback
  const num = Number(value)
  if (num < 10_000) return num.toLocaleString('zh-CN')
  if (num < 1_000_000) return `${(num / 1_000).toFixed(1)}k`
  return `${(num / 1_000_000).toFixed(2)}M`
}

const CURRENCY_SYMBOLS = {
  USD: '$',
  CNY: '¥',
  EUR: '€',
  JPY: '¥',
  GBP: '£',
}

/**
 * 格式化消费金额，微金额保留高精度，较大金额自动截取规范小数。
 *
 * @param {number|string|null} amount 消费数值
 * @param {string} currency 币种代号
 * @returns {string|null}
 */
export function formatCost(amount, currency = 'USD') {
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
  const prefix = CURRENCY_SYMBOLS[currency] || (currency ? `${currency} ` : '')
  return `${prefix}${text}`
}

/**
 * 格式化模型计费单价，保留最高 8 位高精度小数且不强制添加千分位，避免微小单价显示为 0。
 *
 * @param {number|string|null} value 单价值
 * @param {string} fallback 缺省值
 * @returns {string}
 */
export function formatUnitPrice(value, fallback = '—') {
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
export function formatPriceSummary(price, fallback = '—') {
  if (!price) return fallback
  const mode = price.pricingMode || 'token'
  const currency = price.currency || price.costCurrency || 'USD'
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

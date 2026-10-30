import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  formatTime,
  formatDuration,
  formatNumber,
  formatTokensCompact,
  formatCost,
  formatUnitPrice,
  formatPriceSummary,
  formatPriceSnapshot,
  toLocalInput,
  fromLocalInput,
} from '../src/utils/format'

describe('utils: format 格式化工具函数库', () => {
  describe('formatTime', () => {
    const fixedTs = new Date('2026-10-24T12:30:45Z').getTime()

    it('合法时间戳正确格式化为本地短时间', () => {
      const res = formatTime(fixedTs)
      assert.equal(typeof res, 'string')
      assert.equal(res.length > 0, true)
      assert.equal(res !== '—', true)
    })

    it('支持 medium 样式', () => {
      const res = formatTime(fixedTs, { style: 'medium' })
      assert.equal(typeof res, 'string')
      assert.equal(res !== '—', true)
    })

    it('支持 timeOnly 样式', () => {
      const res = formatTime(fixedTs, { style: 'timeOnly' })
      assert.equal(typeof res, 'string')
      assert.equal(res.includes(':'), true)
    })

    it('空值、null 或无效日期回退自定义 fallback', () => {
      assert.equal(formatTime(null), '—')
      assert.equal(formatTime(undefined, { fallback: '永不过期' }), '永不过期')
      assert.equal(formatTime('invalid-date', { fallback: 'N/A' }), 'N/A')
    })
  })

  describe('formatDuration', () => {
    it('小于 1000 毫秒格式化为 ms', () => {
      assert.equal(formatDuration(250), '250 ms')
      assert.equal(formatDuration(0), '0 ms')
    })

    it('大于等于 1000 毫秒换算为秒 (s)', () => {
      assert.equal(formatDuration(1500), '1.50 s')
      assert.equal(formatDuration(2000), '2.00 s')
    })

    it('null 或非数字回退占位符', () => {
      assert.equal(formatDuration(null), '—')
      assert.equal(formatDuration(undefined), '—')
      assert.equal(formatDuration('bad'), '—')
    })
  })

  describe('formatNumber & formatTokensCompact', () => {
    it('formatNumber 正确展示千分位', () => {
      assert.equal(formatNumber(1234567), '1,234,567')
      assert.equal(formatNumber(100), '100')
      assert.equal(formatNumber(null), '—')
    })

    it('formatTokensCompact 紧凑展示', () => {
      assert.equal(formatTokensCompact(500), '500')
      assert.equal(formatTokensCompact(15000), '15.0k')
      assert.equal(formatTokensCompact(1200000), '1.20M')
      assert.equal(formatTokensCompact(null), '—')
    })
  })

  describe('formatCost', () => {
    it('为 0 时返回 0', () => {
      assert.equal(formatCost(0, 'USD'), '$0')
    })

    it('金额为 null 时返回 null', () => {
      assert.equal(formatCost(null), null)
    })

    it('微金额保留精度，不同货币符号前缀正确', () => {
      assert.equal(formatCost(0.000025, 'USD'), '$0.000025')
      assert.equal(formatCost(0.005, 'CNY'), '¥0.005')
      assert.equal(formatCost(1.5, 'USD'), '$1.5')
      assert.equal(formatCost(10.25, 'EUR'), '€10.25')
    })

    it('空币种只返回金额，由调用方单独渲染币种', () => {
      assert.equal(formatCost(0.005, ''), '0.005')
      assert.equal(formatCost(10.25, ''), '10.25')
      assert.equal(formatCost(null, ''), null)
    })
  })

  describe('formatUnitPrice', () => {
    it('保留高精度微小单价且不强制千分位', () => {
      assert.equal(formatUnitPrice('0.000025'), '0.000025')
      assert.equal(formatUnitPrice(0.0015), '0.0015')
      assert.equal(formatUnitPrice(1.23456789), '1.23456789')
      assert.equal(formatUnitPrice(null), '—')
      assert.equal(formatUnitPrice(''), '—')
    })
  })

  describe('formatPriceSummary', () => {
    it('格式化 Token 单价', () => {
      assert.equal(
        formatPriceSummary({ inputPricePerMillion: '0.15', outputPricePerMillion: '0.6', currency: 'USD' }),
        '输入 0.15 · 输出 0.6 · USD / 1M',
      )
    })

    it('格式化按次费用', () => {
      assert.equal(
        formatPriceSummary({ pricingMode: 'per_request', perRequestPrice: '0.04', currency: 'USD' }),
        '按次 0.04 USD',
      )
    })

    it('格式化阶梯计费与总量阶梯计费', () => {
      assert.equal(
        formatPriceSummary({ pricingMode: 'tiered', priceTiers: [{}, {}], currency: 'CNY' }),
        '阶梯价 (2 档) · CNY',
      )
      assert.equal(
        formatPriceSummary({ pricingMode: 'volume', priceTiers: [{}], currency: 'USD' }),
        '阶梯总量价 (1 档) · USD',
      )
    })

    it('空值回退占位符', () => {
      assert.equal(formatPriceSummary(null), '—')
    })
  })

  describe('formatPriceSnapshot', () => {
    it('展示 token 模式的输入、输出和缓存读写单价', () => {
      assert.deepEqual(
        formatPriceSnapshot({
          pricingMode: 'token',
          costCurrency: 'USD',
          inputPricePerMillion: '1',
          outputPricePerMillion: '2',
          cachedInputPricePerMillion: '0.4',
          cachedInputWritePricePerMillion: '1.5',
        }),
        [
          { label: '输入单价', value: '1 USD / 1M' },
          { label: '输出单价', value: '2 USD / 1M' },
          { label: '缓存读单价', value: '0.4 USD / 1M' },
          { label: '缓存写单价', value: '1.5 USD / 1M' },
        ],
      )
    })

    it('显示 tiered 和 volume 的边界及每档缓存读写价格', () => {
      assert.deepEqual(
        formatPriceSnapshot({
          pricingMode: 'tiered',
          costCurrency: 'CNY',
          priceTiers: [
            { upToTokens: 128000, inputPricePerMillion: '1', outputPricePerMillion: '2' },
            { upToTokens: null, inputPricePerMillion: '3', outputPricePerMillion: '4', cachedInputPricePerMillion: '0.8', cachedInputWritePricePerMillion: '2.5' },
          ],
        }),
        [
          { label: '第 1 档 · ≤ 128,000 tokens', value: '输入 1 · 输出 2 · 缓存读 沿用输入价（1 CNY / 1M） · 缓存写 沿用输入价（1 CNY / 1M） · CNY / 1M' },
          { label: '第 2 档 · ≥ 128,001 tokens', value: '输入 3 · 输出 4 · 缓存读 0.8 CNY / 1M · 缓存写 2.5 CNY / 1M · CNY / 1M' },
        ],
      )
      assert.deepEqual(formatPriceSnapshot({ pricingMode: 'volume', priceTiers: [] }), [])
    })

    it('显示按次单价并安全处理空快照', () => {
      assert.deepEqual(formatPriceSnapshot({ pricingMode: 'per_request', perRequestPrice: '0.04', costCurrency: 'USD' }), [
        { label: '每次请求', value: '0.04 USD' },
      ])
      assert.deepEqual(formatPriceSnapshot(null), [])
    })
  })

  describe('toLocalInput & fromLocalInput', () => {
    it('时间戳与本地 datetime-local 格式安全往返转换', () => {
      const now = 1792942800000
      const localStr = toLocalInput(now)
      assert.match(localStr, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
      const roundTrip = fromLocalInput(localStr)
      assert.equal(typeof roundTrip, 'number')
      assert.ok(Math.abs(roundTrip - now) <= 60_000)
    })

    it('空值与无效值安全处理', () => {
      assert.equal(toLocalInput(null), '')
      assert.equal(toLocalInput('invalid'), '')
      assert.equal(fromLocalInput(null), null)
      assert.equal(fromLocalInput(''), null)
    })
  })
})

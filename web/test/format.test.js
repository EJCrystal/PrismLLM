import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  formatTime,
  formatDuration,
  formatNumber,
  formatTokensCompact,
  formatCost,
} from '../src/utils/format.js'

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
  })
})

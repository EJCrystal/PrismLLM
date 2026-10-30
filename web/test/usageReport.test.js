import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  usageCostAmount,
  usageCostEntries,
  usageCostForCurrency,
  usageReportPresetRange,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  validateUsageReportRange,
  USAGE_REPORT_MAX_SPAN_MS,
  USAGE_REPORT_PRESETS,
} from '../src/utils/usageReport'

describe('usageReport: 用量报表展示', () => {
  it('费用条目按币种排序并剔除空值，不隐含换算', () => {
    assert.deepEqual(usageCostEntries({ USD: '0.25', CNY: '2' }), [
      { currency: 'CNY', value: '2' },
      { currency: 'USD', value: '0.25' },
    ])
    assert.deepEqual(usageCostEntries({ USD: null }), [])
    assert.deepEqual(usageCostEntries({}), [])
  })

  it('单一美元条目正确提取', () => {
    assert.deepEqual(usageCostEntries({ USD: '12.3456' }), [
      { currency: 'USD', value: '12.3456' },
    ])
  })

  it('费用趋势只读取当前币种，缺失币种按零处理', () => {
    assert.equal(usageCostForCurrency({ USD: '0.25', CNY: '2' }, 'CNY'), 2)
    assert.equal(usageCostForCurrency({ USD: '0.25' }, 'CNY'), 0)
    assert.equal(usageCostForCurrency({ USD: '0.25 USD' }, 'USD'), 0.25)
    assert.equal(usageCostForCurrency({}, 'USD'), 0)
  })

  it('费用金额末尾的 USD 在展示和导出前统一移除', () => {
    assert.equal(usageCostAmount('0.25 USD'), '0.25')
    assert.equal(usageCostAmount(' 0.25 usd '), '0.25')
    assert.equal(usageCostAmount('0.25 (USD)'), '0.25')
    assert.equal(usageCostAmount('USD 0.25'), 'USD 0.25')
    assert.deepEqual(usageCostEntries({ USD: '0.25 USD', CNY: '2' }), [
      { currency: 'CNY', value: '2' },
      { currency: 'USD', value: '0.25' },
    ])
  })

  it('快捷项覆盖今天、昨天、最近 24 小时、7 天和 30 天', () => {
    assert.deepEqual(USAGE_REPORT_PRESETS.map((item) => item.label), [
      '今天', '昨天', '最近 24 小时', '最近 7 天', '最近 30 天',
    ])
  })

  it('今天和昨天按本地自然日切分', () => {
    const now = new Date(2026, 9, 29, 15, 30, 0, 0).getTime()
    const todayStart = new Date(2026, 9, 29, 0, 0, 0, 0).getTime()
    assert.deepEqual(usageReportPresetRange('today', now), { from: todayStart, to: now })
    assert.deepEqual(usageReportPresetRange('yesterday', now), {
      from: todayStart - 86_400_000,
      to: todayStart - 1,
    })
  })

  it('昨天范围始终从本地零点开始', () => {
    const now = new Date(2026, 9, 29, 15, 30, 0, 0).getTime()
    const { from } = usageReportPresetRange('yesterday', now)
    const start = new Date(from)
    assert.equal(start.getHours(), 0)
    assert.equal(start.getMinutes(), 0)
    assert.equal(start.getSeconds(), 0)
    assert.equal(start.getMilliseconds(), 0)
  })

  it('滚动窗口快捷项与 30 天上限一致', () => {
    const now = 10_000_000_000
    assert.deepEqual(usageReportPresetRange('last24h', now), { from: now - 86_400_000, to: now })
    assert.deepEqual(usageReportPresetRange('last7d', now), { from: now - 7 * 86_400_000, to: now })
    assert.deepEqual(usageReportPresetRange('last30d', now), { from: now - USAGE_REPORT_MAX_SPAN_MS, to: now })
  })

  it('自定义时间可在 datetime-local 与时间戳间往返', () => {
    const timestamp = new Date(2026, 9, 29, 9, 5, 0, 0).getTime()
    const local = toDatetimeLocalValue(timestamp)
    assert.equal(local, '2026-10-29T09:05')
    assert.equal(fromDatetimeLocalValue(local), timestamp)
    assert.ok(Number.isNaN(fromDatetimeLocalValue('')))
  })

  it('自定义范围拒绝倒序、缺值和超过 30 天', () => {
    const day = 86_400_000
    assert.equal(validateUsageReportRange(1, 2), '')
    assert.equal(validateUsageReportRange(1, 1), '', '起止相同应允许')
    assert.equal(validateUsageReportRange(2, 1), '开始时间不能晚于结束时间。')
    assert.equal(validateUsageReportRange(NaN, 2), '请选择完整的开始时间和结束时间。')
    assert.equal(validateUsageReportRange(1, 1 + 30 * day), '')
    assert.equal(validateUsageReportRange(1, 2 + 30 * day), '查询跨度不能超过 30 天。', '超出 1 毫秒也应拒绝')
  })
})

import {
  usageRate,
  usagePercent,
  fillTrendBuckets,
  buildTrendPoints,
  usageTrendMax,
  usageModelOptions,
  usageReportCsv,
  reportTimezoneOffset,
} from '../src/utils/usageReport'

describe('usageReport: 增强报表数据', () => {
  it('成功率按请求与错误计算并限制在 0 到 1', () => {
    assert.equal(usageRate(10, 2), 0.8)
    assert.equal(usageRate(0, 0), 0)
    assert.equal(usageRate(2, 3), 0)
    assert.equal(usagePercent(4, 1), '75.0%')
  })

  it('空时间桶补齐后保持连续，且保留原始值', () => {
    const hour = 3_600_000
    const rows = fillTrendBuckets([
      { bucketStart: hour, requests: 2 },
      { bucketStart: 3 * hour, requests: 1 },
    ], 0, 4 * hour, 'hour')
    assert.deepEqual(rows.map((row) => row.bucketStart), [0, hour, 2 * hour, 3 * hour, 4 * hour])
    assert.equal(rows[0].requests, 0)
    assert.equal(rows[1].requests, 2)
    assert.equal(rows[2].requests, 0)
    assert.deepEqual(rows[2].costs, {})
  })

  it('空报表也按查询范围补齐时间桶，并遵循浏览器时区', () => {
    const day = 86_400_000
    const rows = fillTrendBuckets([], 0, 2 * day, 'day', 480)
    assert.equal(rows.length, 3)
    assert.equal(rows[0].bucketStart, -8 * 3_600_000)
    assert.equal(rows[1].bucketStart, day - 8 * 3_600_000)
  })

  it('按周报表从查询起点开始，避免把末尾刚开始的自然周算成完整一周', () => {
    const week = 7 * 86_400_000
    const rows = fillTrendBuckets([], 0, 4 * week, 'week')
    assert.deepEqual(rows.map((row) => row.bucketStart), [0, week, 2 * week, 3 * week])
  })

  it('趋势折线坐标不会除零，并且最高点位于顶部内边距', () => {
    assert.equal(buildTrendPoints([], () => 1), '')
    const points = buildTrendPoints([{ value: 0 }, { value: 10 }], (row) => row.value)
    assert.equal(points.split(' ').length, 2)
    assert.ok(points.endsWith('8.0'))
  })

  it('主次折线共用最大值，避免各自归一化后误读', () => {
    const rows = [{ input: 100, output: 10 }, { input: 0, output: 50 }]
    assert.equal(usageTrendMax(rows, (row) => row.input, (row) => row.output), 100)
    const primary = buildTrendPoints(rows, (row) => row.input, 640, 150, 8, 100)
    const secondary = buildTrendPoints(rows, (row) => row.output, 640, 150, 8, 100)
    assert.notEqual(primary.split(' ')[0].split(',')[1], secondary.split(' ')[0].split(',')[1])
    assert.equal(usageTrendMax([], () => 0), 0)
  })

  it('模型筛选合并渠道已配置模型和历史请求模型', () => {
    const options = usageModelOptions({
      byModel: [
        { id: 'observed-only' },
        { id: 'shared' },
        { id: '__unresolved_model__' },
      ],
    }, [
      { id: 'a', models: ['shared', ' configured-a '] },
      { id: 'b', models: ['another', 'shared', null] },
      { id: 'c' },
    ])
    assert.deepEqual(options, ['another', 'configured-a', 'observed-only', 'shared'])
  })

  it('模型筛选容忍空响应并去重空白值', () => {
    assert.deepEqual(usageModelOptions(null, null), [])
    assert.deepEqual(usageModelOptions({}, [{ models: ['', '  ', null] }]), [])
  })

  it('CSV 导出包含分组维度和多币种费用，并正确转义逗号', () => {
    const csv = usageReportCsv({
      from: 0,
      to: 1,
    }, [{
      id: 'key-a',
      name: 'name,with,comma',
      requests: 3,
      errors: 1,
      inputTokens: 10,
      outputTokens: 20,
      cachedTokens: 1,
      cachedWriteTokens: 2,
      reasoningTokens: 3,
      totalTokens: 30,
      failovers: 1,
      avgFirstTokenMs: 12,
      avgTotalMs: 34,
      costs: { USD: '0.5', CNY: '2' },
    }], '按渠道')
    assert.ok(csv.startsWith('维度,'))
    assert.ok(csv.includes('"name,with,comma"'))
    assert.ok(csv.includes('66.7%'))
    assert.ok(csv.includes('CNY 2 / USD 0.5'))
  })

  it('CSV 导出中和以公式字符开头的文本', () => {
    const csv = usageReportCsv({ from: 0, to: 1 }, [{
      id: '=cmd',
      name: '=SUM(A1)',
      requests: 1,
      errors: 0,
      costs: {},
    }], '按渠道')
    assert.ok(csv.includes("'=SUM(A1)"), csv)
  })

  it('时区偏移按浏览器约定返回分钟数', () => {
    assert.equal(reportTimezoneOffset(new Date('2026-01-01T00:00:00Z')), -new Date('2026-01-01T00:00:00Z').getTimezoneOffset())
  })
})

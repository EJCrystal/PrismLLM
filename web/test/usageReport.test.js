import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { usageChartBucketRange } from '../src/utils/usageChart'
import {
  usageCostAmount,
  usageDeltaTone,
  usageReportPresetRange,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  validateUsageReportRange,
  USAGE_REPORT_MAX_SPAN_MS,
  USAGE_REPORT_PRESETS,
} from '../src/utils/usageReport'

describe('usageReport: 用量报表展示', () => {
  it('请求增加为成功色，错误和费用增加为警示色', () => {
    assert.equal(usageDeltaTone(120, 100), 'success')
    assert.equal(usageDeltaTone(120, 100, true), 'warning')
    assert.equal(usageDeltaTone(80, 100), 'warning')
    assert.equal(usageDeltaTone(80, 100, true), 'success')
    assert.equal(usageDeltaTone(0, 0), 'neutral')
    assert.equal(usageDeltaTone(120, 0), 'neutral')
    assert.equal(usageDeltaTone(120, 0, true), 'neutral')
  })

  it('费用金额末尾的 USD 在展示和导出前统一移除', () => {
    assert.equal(usageCostAmount('0.25 USD'), '0.25')
    assert.equal(usageCostAmount(' 0.25 usd '), '0.25')
    assert.equal(usageCostAmount('0.25 (USD)'), '0.25')
    assert.equal(usageCostAmount('USD 0.25'), 'USD 0.25')
    assert.equal(usageCostAmount(null), '')
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
    const exactTimestamp = new Date(2026, 9, 29, 9, 5, 31, 123).getTime()
    const exactLocal = toDatetimeLocalValue(exactTimestamp, true)
    assert.equal(exactLocal, '2026-10-29T09:05:31.123')
    assert.equal(fromDatetimeLocalValue(exactLocal), exactTimestamp)
  })

  it('趋势钻取保留报表范围与桶范围交集的精确时间戳', () => {
    const from = new Date(2026, 9, 29, 9, 5, 31).getTime()
    const to = new Date(2026, 9, 29, 10, 30, 29).getTime()
    const report = { from, to, granularity: 'hour' }

    const firstBucket = usageChartBucketRange(report, new Date(2026, 9, 29, 9, 0).getTime())
    assert.deepEqual(firstBucket, {
      from,
      to: new Date(2026, 9, 29, 10, 0).getTime(),
    })
    assert.equal(toDatetimeLocalValue(firstBucket.from, true), '2026-10-29T09:05:31')

    const lastBucket = usageChartBucketRange(report, new Date(2026, 9, 29, 10, 0).getTime())
    assert.deepEqual(lastBucket, {
      from: new Date(2026, 9, 29, 10, 0).getTime(),
      to,
    })
    assert.equal(toDatetimeLocalValue(lastBucket.to, true), '2026-10-29T10:30:29')
    const partialHour = usageChartBucketRange({ from, to, granularity: 'hour' }, new Date(2026, 9, 29, 9, 30).getTime())
    assert.deepEqual(partialHour, {
      from: new Date(2026, 9, 29, 9, 30).getTime(),
      to: new Date(2026, 9, 29, 10, 30).getTime(),
    })
  })

  it('趋势钻取使用正确的桶宽，保留短于一分钟的区间，并拒绝空交集', () => {
    const start = new Date(2026, 9, 29, 9, 0).getTime()
    assert.deepEqual(usageChartBucketRange({ from: start, to: start + 7_200_000, granularity: 'hour' }, start), {
      from: start, to: start + 3_600_000,
    })
    assert.deepEqual(usageChartBucketRange({ from: start, to: start + 8 * 86_400_000, granularity: 'week' }, start), {
      from: start, to: start + 7 * 86_400_000,
    })
    assert.deepEqual(usageChartBucketRange({ from: start, to: start + 2 * 86_400_000, granularity: 'day' }, start), {
      from: start, to: start + 86_400_000,
    })
    assert.deepEqual(usageChartBucketRange({ from: start + 1, to: start + 59_999, granularity: 'hour' }, start), {
      from: start + 1, to: start + 59_999,
    })
    assert.equal(usageChartBucketRange({ from: start + 60_000, to: start + 60_000, granularity: 'hour' }, start), null)
    assert.equal(usageChartBucketRange({ from: start, to: start + 3_600_000, granularity: 'hour' }, start + 3_600_000), null)
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
  usageCacheHitRate,
  fillTrendBuckets,
  buildTrendPoints,
  usageTrendMax,
  usageModelOptions,
  usageGroupLabel,
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

  it('缓存命中率只按缓存读除以输入，输入为 0 时不可计算', () => {
    assert.equal(usageCacheHitRate(100, 40), 0.4)
    assert.equal(usageCacheHitRate('200', '50'), 0.25)
    assert.equal(usageCacheHitRate(100, 120), 1, '异常上游数据不应超过 100%')
    assert.equal(usageCacheHitRate(0, 10), null)
    assert.equal(usageCacheHitRate(null, null), null)
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
    assert.equal(rows[2].costUsd, null)
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
    const rows = [{ input: 100, output: 10, cached: 80 }, { input: 0, output: 50, cached: 0 }]
    assert.equal(usageTrendMax(rows, (row) => row.input, (row) => row.output), 100)
    assert.equal(usageTrendMax(rows, (row) => row.input, (row) => row.output, (row) => row.cached), 100)
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

  it('分组名称只翻译映射自身的哨兵 ID，不被原型属性劫持', () => {
    assert.equal(usageGroupLabel({ id: '__unresolved_model__', name: null }), '未解析模型')
    assert.equal(usageGroupLabel({ id: 'gpt-6.1-sol', name: null }), 'gpt-6.1-sol')
    assert.equal(usageGroupLabel({ id: 'row-id', name: '显示名' }), '显示名')
    // toString / constructor 是 Object.prototype 上的继承属性，
    // 直接下标会取到函数而不是标签，这里必须回退到 name / id。
    assert.equal(usageGroupLabel({ id: 'toString', name: '真实模型名' }), '真实模型名')
    assert.equal(usageGroupLabel({ id: 'constructor', name: null }), 'constructor')
    assert.equal(usageGroupLabel({ id: 'hasOwnProperty', name: null }), 'hasOwnProperty')
  })

  it('CSV 导出包含分组维度和 USD 费用列，并正确转义逗号', () => {
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
      costUsd: '0.5',
    }], '按渠道')
    assert.ok(csv.startsWith('维度,'))
    assert.ok(csv.includes('"name,with,comma"'))
    assert.ok(csv.includes('66.7%'))
    assert.ok(csv.includes('费用 (USD)'))
    assert.ok(csv.includes(',0.5'))
  })

  it('CSV 耗时列固定用秒，同一列不做逐值单位切换', () => {
    const csv = usageReportCsv({ from: 0, to: 1 }, [{
      id: 'key-a',
      name: 'a',
      requests: 1,
      errors: 0,
      costUsd: null,
      avgFirstTokenMs: 850,
      avgTotalMs: 30_000,
      costUsd: null,
    }, {
      id: 'key-b',
      name: 'b',
      requests: 1,
      errors: 0,
      costUsd: null,
      avgFirstTokenMs: 2_400,
      avgTotalMs: 1_500,
    }, {
      id: 'key-c',
      name: 'c',
      requests: 1,
      errors: 0,
      costUsd: null,
      avgFirstTokenMs: 12,
      avgTotalMs: 4,
    }], '按渠道')
    const lines = csv.trim().split('\n')
    // 表头写明单位，值统一为秒，短耗时不会退回 ms 造成混排。
    assert.ok(lines[0].includes('平均首 token (s)'), lines[0])
    assert.ok(lines[0].includes('平均总耗时 (s)'), lines[0])
    assert.ok(!lines[0].includes('(ms)'), lines[0])
    assert.ok(lines[1].endsWith('0.85,30,'), lines[1])
    assert.ok(lines[2].endsWith('2.4,1.5,'), lines[2])
    // 毫秒换算成秒最多 3 位小数，短耗时精度不丢失（不能截断成 0.01 / 0）。
    assert.ok(lines[3].endsWith('0.012,0.004,'), lines[3])
  })

  it('CSV 耗时缺失时输出空单元格而不是 0', () => {
    const csv = usageReportCsv({ from: 0, to: 1 }, [{
      id: 'key-a',
      name: 'a',
      requests: 0,
      errors: 0,
      costUsd: null,
      avgFirstTokenMs: null,
      avgTotalMs: null,
    }], '按渠道')
    const row = csv.trim().split('\n')[1]
    assert.ok(row.endsWith(',,'), row)
  })

  it('CSV 导出中和以公式字符开头的文本', () => {
    const csv = usageReportCsv({ from: 0, to: 1 }, [{
      id: '=cmd',
      name: '=SUM(A1)',
      requests: 1,
      errors: 0,
      costUsd: null,
    }], '按渠道')
    assert.ok(csv.includes("'=SUM(A1)"), csv)
  })

  it('时区偏移按浏览器约定返回分钟数', () => {
    assert.equal(reportTimezoneOffset(new Date('2026-01-01T00:00:00Z')), -new Date('2026-01-01T00:00:00Z').getTimezoneOffset())
  })
})

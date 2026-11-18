import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { JSDOM } from 'jsdom'

describe('usage reports layout and contracts', () => {
  let server
  let component
  const firstBucket = new Date(2026, 10, 6).getTime()
  const baseTotal = {
    requests: 100, errors: 10, totalTokens: 5000, inputTokens: 4000, outputTokens: 1000,
    cachedTokens: 2000, cachedWriteTokens: 100, reasoningTokens: 40, activeClientKeys: 1,
    failovers: 2, avgTotalMs: 1000, avgFirstTokenMs: 100, costUsd: '387.92887372',
    successfulLatencySamples: 90, p50TotalMs: 500, p90TotalMs: 1500, p99TotalMs: 1900,
  }
  const group = { ...baseTotal, id: '11c555b7-5555-4444-aaaa-aaaab515b275', name: '测试密钥' }
  const report = (total = {}) => ({
    from: firstBucket, to: firstBucket + 3 * 86_400_000 - 1, granularity: 'day',
    total: { ...baseTotal, ...total },
    trend: [{ ...baseTotal, bucketStart: firstBucket }],
    byClientKey: [group], byChannel: [], byModel: [], byChannelKey: [], byProtocol: [],
    byErrorCode: [6, 4].map((requests, index) => ({ ...group, id: 'error-' + index, requests })),
  })
  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/UsageReportsView.vue')).default
  })
  after(async () => { await server?.close() })

  async function render(data = report(), configure = () => {}) {
    const wrapper = { ...component, setup(props, context) {
      const state = component.setup(props, context)
      state.report.value = data
      state.loading.value = false
      configure(state)
      return state
    } }
    const context = {}
    const html = await renderToString(createSSRApp(wrapper), context)
    return new JSDOM(html + (context.teleports?.body || '')).window.document
  }

  it('merges filters and embeds comparison in KPI cards without runtime summary', async () => {
    const document = await render(report(), state => { state.previousReport.value = report({ requests: 80, costUsd: '300' }) })
    assert.equal(document.querySelectorAll('.usage-filter-control').length, 1)
    assert.equal(document.querySelectorAll('.usage-kpi-delta').length, 4)
    assert.equal(document.querySelectorAll('.usage-period-comparison').length, 0)
    assert.ok(!document.body.textContent.includes('运行摘要'))
    assert.ok(document.querySelector('.usage-kpi-delta').textContent.includes('25.0%'))
    assert.ok([...document.querySelectorAll('input[type="datetime-local"]')].every(input => input.step === '60'))
  })

  it('keeps original cost and full IDs while shortening their visible values', async () => {
    const document = await render()
    const amount = document.querySelector('.report-cost')
    assert.equal(amount.textContent, '$387.93')
    assert.equal(amount.dataset.exact, '387.92887372 USD')
    assert.equal(amount.tabIndex, 0)
    const identifier = document.querySelector('.usage-dimension-id code')
    assert.equal(identifier.title, group.id)
    assert.ok(identifier.textContent.includes('…'))
    assert.ok(document.querySelector('button[aria-label="复制调用方密钥 ID"]'))
  })

  for (const boundary of ['from', 'to']) {
    it('preserves the other exact boundary when editing ' + boundary + ' after drilldown', async () => {
      const exact = {
        from: new Date(2026, 10, 6, 9, 5, 31, 123).getTime(),
        to: new Date(2026, 10, 6, 10, 15, 42, 456).getTime(),
      }
      const input = boundary === 'from' ? '2026-11-06T09:10' : '2026-11-06T11:15'
      await render(report(), state => {
        state.preset.value = ''
        state.exactRange.value = { ...exact }
        state.rangeDraft.value = { from: '2026-11-06T09:05', to: '2026-11-06T10:15', [boundary]: input }
        state.onRangeInput(boundary)
        assert.deepEqual(state.currentRange(), { ...exact, [boundary]: new Date(input).getTime() })
      })
    })
  }

  it('preserves the unedited preset boundary and only invalidates a cleared input', async () => {
    await render(report(), state => {
      const exact = { ...state.exactRange.value }
      state.rangeDraft.value.to = '2026-11-12T12:00'
      state.onRangeInput('to')
      assert.equal(state.preset.value, '')
      assert.deepEqual(state.currentRange(), { ...exact, to: new Date('2026-11-12T12:00').getTime() })
      state.rangeDraft.value.to = ''
      state.onRangeInput('to')
      assert.equal(state.currentRange().from, exact.from)
      assert.ok(Number.isNaN(state.currentRange().to))
      state.rangeDraft.value.to = '2026-11-12T13:00'
      state.onRangeInput('to')
      assert.deepEqual(state.currentRange(), { ...exact, to: new Date('2026-11-12T13:00').getTime() })
    })
  })

  it('does not invent latency for old servers or empty samples, but preserves zero', async () => {
    const old = await render(report({ successfulLatencySamples: undefined, p50TotalMs: undefined, p90TotalMs: undefined, p99TotalMs: undefined }))
    assert.ok(old.querySelector('.usage-latency-note').textContent.includes('请更新后端'))
    const empty = await render(report({ successfulLatencySamples: 0, p50TotalMs: null, p90TotalMs: null, p99TotalMs: null }))
    assert.ok(empty.querySelector('.usage-latency-note').textContent.includes('暂无有效样本'))
    assert.deepEqual([...empty.querySelectorAll('.usage-percentiles strong')].map(node => node.textContent), ['—', '—', '—'])
    const zero = await render(report({ successfulLatencySamples: 1, p50TotalMs: 0, p90TotalMs: 0, p99TotalMs: 0 }))
    assert.deepEqual([...zero.querySelectorAll('.usage-percentiles strong')].map(node => node.textContent), ['0 ms', '0 ms', '0 ms'])
  })

  it('keeps readout slots and drilldown action present before and after selection', async () => {
    const unselected = await render()
    const selected = await render(report(), state => { state.chartIndex.value = 0 })
    assert.equal(unselected.querySelectorAll('.usage-chart-values > span').length, selected.querySelectorAll('.usage-chart-values > span').length)
    assert.equal(unselected.querySelector('.usage-chart-filter').disabled, true)
    assert.equal(selected.querySelector('.usage-chart-filter').disabled, false)
  })

  it('renders error rate overlaid in requests trend and leaves empty buckets disconnected', async () => {
    const document = await render(report())
    assert.equal(document.querySelectorAll('.usage-segmented[aria-label="趋势指标"] button').length, 3)
    assert.ok(document.querySelector('.usage-error-row').textContent.includes('60.0%'))
    assert.equal(document.querySelectorAll('.usage-chart-line.rate').length, 1)
    assert.ok(document.querySelector('.usage-chart-point.rate'))
    assert.ok(document.querySelector('.usage-chart-legend').textContent.includes('无请求不连线'))
    assert.ok(document.querySelector('.usage-chart-y-axis.is-right').textContent.includes('%'))
    assert.ok(document.querySelector('.usage-chart').getAttribute('aria-label').includes('百分比'))

    const splitData = report()
    splitData.trend = [
      { ...baseTotal, bucketStart: firstBucket, requests: 100, errors: 10 },
      { ...baseTotal, bucketStart: firstBucket + 86_400_000, requests: 0, errors: 0 },
      { ...baseTotal, bucketStart: firstBucket + 2 * 86_400_000, requests: 100, errors: 20 },
    ]
    const split = await render(splitData, state => { state.chartIndex.value = 0 })
    assert.equal(split.querySelectorAll('.usage-chart-line.rate').length, 2)
    assert.equal(split.querySelectorAll('.usage-chart-markers .usage-chart-point.rate:not(.is-ring)').length, 2)
    assert.ok(split.querySelector('.usage-chart-markers .usage-chart-point.rate.is-ring'))
    assert.equal(split.querySelectorAll('.usage-chart circle').length, 0)
    const axisSpans = [...split.querySelectorAll('.usage-chart-axis > span')]
    assert.equal(axisSpans[0].classList.contains('is-first'), true)
    assert.equal(axisSpans[1].style.left, '50%')
    assert.equal(axisSpans[2].classList.contains('is-last'), true)

    const fourteenData = {
      ...report(),
      from: firstBucket,
      to: firstBucket + 14 * 86_400_000 - 1,
      trend: Array.from({ length: 14 }, (_, index) => ({ ...baseTotal, bucketStart: firstBucket + index * 86_400_000 })),
    }
    const fourteen = await render(fourteenData)
    const ticks14 = [...fourteen.querySelectorAll('.usage-chart-axis > span')]
    assert.equal(ticks14.length, 14)
    assert.equal(ticks14[0].classList.contains('is-hidden'), false)
    assert.equal(ticks14[13].classList.contains('is-hidden'), false)
    assert.equal(ticks14[12].classList.contains('is-hidden'), true)

    const narrowData = {
      ...report(),
      from: firstBucket,
      to: firstBucket + 8 * 86_400_000 - 1,
      trend: Array.from({ length: 8 }, (_, index) => ({ ...baseTotal, bucketStart: firstBucket + index * 86_400_000 })),
    }
    const narrow = await render(narrowData, state => { state.chartAxisWidth.value = 320 })
    const narrowVisible = [...narrow.querySelectorAll('.usage-chart-axis > span')].filter(node => !node.classList.contains('is-hidden'))
    assert.ok(narrowVisible.length <= 4)
    assert.equal(narrow.querySelectorAll('.usage-chart-axis > span')[0].classList.contains('is-hidden'), false)
    assert.equal(narrow.querySelectorAll('.usage-chart-axis > span')[7].classList.contains('is-hidden'), false)
  })

  it('scales small error rates and fees to their actual range', async () => {
    const data = report()
    data.trend = [{ ...data.trend[0], requests: 1000, errors: 1, costUsd: '0.00042' }]
    const rates = await render(data)
    const rateTicks = [...rates.querySelectorAll('.usage-chart-y-axis.is-right > span')].map(node => Number(node.textContent.replace('%', '')))
    assert.ok(rateTicks[0] >= 0.1 && rateTicks[0] < 1)
    assert.equal(rateTicks.at(-1), 0)
    assert.ok(rates.querySelector('.usage-chart-legend').textContent.includes('0.1%'))
    const fees = await render(data, state => { state.trendMetric.value = 'cost' })
    const feeTicks = [...fees.querySelectorAll('.usage-chart-y-axis > span')].map(node => Number(node.textContent))
    assert.ok(feeTicks[0] >= 0.00042 && feeTicks[0] < 0.001)
    assert.equal(feeTicks.at(-1), 0)
    assert.ok(new Set(feeTicks).size > 1)
  })

  it('distinguishes missing error rate samples from zero errors and shows token cache hit rate', async () => {
    const data = report()
    data.trend = [{ ...data.trend[0], requests: 0, errors: 0 }]
    const missing = await render(data, state => { state.chartIndex.value = 0 })
    assert.equal(missing.querySelectorAll('.usage-chart-line.rate').length, 0)
    assert.equal(missing.querySelectorAll('.usage-chart-point.rate').length, 0)
    assert.equal([...missing.querySelectorAll('.usage-chart-values b')].at(-1).textContent, '—')
    data.trend[0].requests = 10
    const zero = await render(data, state => { state.chartIndex.value = 0 })
    assert.equal(zero.querySelectorAll('.usage-chart-line.rate').length, 1)
    assert.equal([...zero.querySelectorAll('.usage-chart-values b')].at(-1).textContent, '0.0%')
    assert.ok(zero.querySelector('.usage-chart-point.rate'))

    const tokens = await render(report(), state => { state.trendMetric.value = 'tokens'; state.chartIndex.value = 0 })
    const labels = [...tokens.querySelectorAll('.usage-chart-values .usage-chart-series-label')].map(node => node.textContent)
    assert.ok(labels.includes('缓存命中率'))
    assert.equal([...tokens.querySelectorAll('.usage-chart-values b')].at(-1).textContent, '50.0%')
    assert.ok(tokens.querySelector('.usage-dimension-table').textContent.includes('缓存 50.0%'))
  })

  it('preserves copy access when the original name is missing', async () => {
    const data = report()
    data.byClientKey[0] = { ...group, name: null }
    const document = await render(data)
    assert.ok(document.querySelector('.usage-dimension-title strong').textContent.includes('…'))
    assert.ok(document.querySelector('button[aria-label="复制调用方密钥 ID"]'))
  })

  it('drills down into clicked chart bucket on desktop click', async () => {
    const data = report()
    data.trend = [
      { ...baseTotal, bucketStart: firstBucket },
      { ...baseTotal, bucketStart: firstBucket + 86_400_000 },
    ]
    await render(data, state => {
      const svg = { getBoundingClientRect: () => ({ left: 0, width: 640 }) }
      state.clickChart({ currentTarget: svg, clientX: 320 })
      assert.equal(state.preset.value, '')
      assert.equal(state.exactRange.value.from, firstBucket + 86_400_000)
      assert.equal(state.exactRange.value.to, firstBucket + 2 * 86_400_000)
    })
  })
})

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

  it('renders percentages against all errors and leaves empty error rate buckets disconnected', async () => {
    const document = await render(report(), state => { state.trendMetric.value = 'errorRate' })
    assert.ok(document.querySelector('.usage-error-row').textContent.includes('60.0%'))
    assert.equal(document.querySelectorAll('.usage-chart-line').length, 1)
    assert.ok(document.querySelector('.usage-chart-point'))
    assert.ok(document.querySelector('.usage-chart-legend').textContent.includes('无请求的时段不连线'))
    assert.ok(document.querySelector('.usage-chart-y-axis').textContent.includes('%'))
    assert.ok(document.querySelector('.usage-chart').getAttribute('aria-label').includes('百分比'))
  })

  it('scales small error rates and fees to their actual range', async () => {
    const data = report()
    data.trend = [{ ...data.trend[0], requests: 1000, errors: 1, costUsd: '0.00042' }]
    const rates = await render(data, state => { state.trendMetric.value = 'errorRate' })
    const rateTicks = [...rates.querySelectorAll('.usage-chart-y-axis > span')].map(node => Number(node.textContent.replace('%', '')))
    assert.ok(rateTicks[0] >= 0.1 && rateTicks[0] < 1)
    assert.equal(rateTicks.at(-1), 0)
    assert.ok(rates.querySelector('.usage-chart-legend').textContent.includes('0.1%'))
    const fees = await render(data, state => { state.trendMetric.value = 'cost' })
    const feeTicks = [...fees.querySelectorAll('.usage-chart-y-axis > span')].map(node => Number(node.textContent))
    assert.ok(feeTicks[0] >= 0.00042 && feeTicks[0] < 0.001)
    assert.equal(feeTicks.at(-1), 0)
    assert.ok(new Set(feeTicks).size > 1)
  })

  it('distinguishes missing error rate samples from zero errors', async () => {
    const data = report()
    data.trend = [{ ...data.trend[0], requests: 0, errors: 0 }]
    const missing = await render(data, state => { state.trendMetric.value = 'errorRate'; state.chartIndex.value = 0 })
    assert.equal(missing.querySelectorAll('.usage-chart-line').length, 0)
    assert.equal(missing.querySelectorAll('.usage-chart-point').length, 0)
    assert.equal(missing.querySelector('.usage-chart-values b').textContent, '—')
    data.trend[0].requests = 10
    const zero = await render(data, state => { state.trendMetric.value = 'errorRate'; state.chartIndex.value = 0 })
    assert.equal(zero.querySelectorAll('.usage-chart-line').length, 1)
    assert.equal(zero.querySelector('.usage-chart-values b').textContent, '0.0%')
    assert.ok(zero.querySelector('.usage-chart-point'))
  })

  it('preserves copy access when the original name is missing', async () => {
    const data = report()
    data.byClientKey[0] = { ...group, name: null }
    const document = await render(data)
    assert.ok(document.querySelector('.usage-dimension-title strong').textContent.includes('…'))
    assert.ok(document.querySelector('button[aria-label="复制调用方密钥 ID"]'))
  })
})

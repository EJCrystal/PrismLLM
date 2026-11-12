import { after, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { JSDOM } from 'jsdom'

describe('channel health detail dashboard', () => {
  let server
  let component
  let api
  let originals
  let healthCalls
  let usageCalls
  let clearCalls
  const timestamp = 1_800_000_000_000
  const metrics = {
    attempts: 100, successes: 95, failures: 5, excluded: 2, successRate: 0.95,
    firstTokenSamples: 80, p50FirstTokenMs: 12_000, p95FirstTokenMs: 82_000,
    p50TotalMs: 40_000, p95TotalMs: 120_000, outputTokensPerSecond: 22944.4, throughputSamples: 70,
    lastSuccessAt: timestamp - 1000, lastFailureAt: timestamp - 2000, lastObservedAt: timestamp,
  }
  const channel = { id: 'test-channel', name: '测试渠道', enabled: true }
  const active = { keyId: 'key-1', keyName: '主密钥', model: 'long-provider-model-name', state: 'ACTIVE', reason: null, until: null }
  const fixture = () => ({
    report: {
      window: '24h', from: timestamp - 86_400_000, to: timestamp, retentionDays: 30, indexing: false,
      minimumSamples: 20, staleAfterMs: 3_600_000, warningErrorRate: 0.05, criticalErrorRate: 0.2,
      slowFirstTokenMs: 30_000, slowP50FirstTokenMs: 10_000,
      channels: [{ channelId: channel.id, state: 'WARNING', reason: 'P95 首 token 超过阈值', metrics: { ...metrics }, coolingCandidates: 0, requests: { requests: 98, successes: 97, successRate: 0.99, failoverRate: 0.01, legacyRequests: 0 } }],
    },
    trend: [
      { from: timestamp - 3000, to: timestamp - 2000, metrics: { ...metrics, failures: 0, successRate: 1 } },
      { from: timestamp - 2000, to: timestamp - 1000, metrics: { ...metrics, attempts: 1 } },
      { from: timestamp - 1000, to: timestamp, metrics: { ...metrics, attempts: 0, failures: 0, successRate: null } },
    ],
    byModel: [{ id: 'model', name: 'long-provider-model-name', metrics: { ...metrics } }],
    byProtocol: [{ id: 'responses', name: 'Responses', metrics: { ...metrics } }],
    byKey: [{ id: 'key-1', name: '主密钥', metrics: { ...metrics } }],
    availability: [{ ...active }], errors: [{ code: 'responses_failed', count: 5, lastAt: timestamp }],
  })
  const usageFixture = () => ({ total: { requests: 90, inputTokens: 1000, cachedTokens: 500, cachedWriteTokens: 20 }, byModel: [{ id: 'model', name: 'long-provider-model-name', inputTokens: 1000, cachedTokens: 500, cachedWriteTokens: 20 }] })

  before(async () => {
    const modalId = '\0health-test-modal'
    server = await createServer({
      configFile: false,
      plugins: [{ name: 'health-test-modal', enforce: 'pre', resolveId(id, importer) { if (id === './Modal.vue' && importer?.endsWith('ChannelHealthModal.vue')) return modalId }, load(id) { if (id === modalId) return "import { h } from 'vue'; export default { setup(_, { slots }) { return () => h('section', slots.default?.()) } }" } }, vue()],
      optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom',
    })
    component = (await server.ssrLoadModule('/src/components/ChannelHealthModal.vue')).default
    api = (await server.ssrLoadModule('/src/api.ts')).api
    originals = { getChannelHealthDetail: api.getChannelHealthDetail, getUsageReport: api.getUsageReport, clearChannelCooldown: api.clearChannelCooldown }
  })
  beforeEach(() => {
    healthCalls = []; usageCalls = []; clearCalls = []
    api.getChannelHealthDetail = async (...args) => { healthCalls.push(args); return fixture() }
    api.getUsageReport = async (...args) => { usageCalls.push(args); return usageFixture() }
    api.clearChannelCooldown = async (...args) => { clearCalls.push(args) }
  })
  after(async () => { if (api) Object.assign(api, originals); await server?.close() })

  const render = async ({ data, interact, ...props } = {}) => {
    let state
    const events = []
    const wrapper = { ...component, async setup(actualProps, context) {
      state = component.setup(actualProps, context)
      await state.load()
      await nextTick()
      if (data) state.detail.value = data
      if (interact) await interact(state)
      await nextTick()
      return state
    } }
    const html = await renderToString(createSSRApp(wrapper, { channel, initialWindow: '24h', concurrentRequests: 0, onClose: changed => events.push(['close', changed]), onChanged: () => events.push(['changed']), ...props }))
    return { state, events, document: new JSDOM(html).window.document, html }
  }

  it('groups metrics and history before a single detail section and collapses explanations', async () => {
    const { document } = await render()
    const overview = document.querySelector('.health-overview')
    assert.ok(overview.querySelector('.health-metric-grid'))
    assert.ok(overview.querySelector('.health-overview-footer'))
    assert.ok(overview.querySelector('.health-trend'))
    assert.equal(document.querySelectorAll('.health-responsive-table').length, 1)
    assert.equal(document.querySelectorAll('details[open]').length, 0)
    assert.equal(document.querySelector('.health-throughput').textContent, '22.9ktok/s')
    assert.ok(overview.querySelector('.health-explanation').textContent.includes('22944.4 tok/s'))
    assert.ok(document.querySelector('.health-metric-warning'))
    assert.doesNotMatch(document.body.textContent, /绿色实色表示/)
    assert.equal(document.querySelectorAll('.health-responsive-table td[data-label]').length, 5)
  })

  it('preserves the performance dimension while switching cache views without refetching', async () => {
    const { document } = await render({ interact: async state => {
      const requests = [healthCalls.length, usageCalls.length]
      state.dimension.value = 'byProtocol'
      state.detailView.value = 'cache'
      assert.equal(state.cacheHitText.value, '50.0%')
      state.detailView.value = 'performance'
      await nextTick()
      assert.equal(state.dimension.value, 'byProtocol')
      assert.deepEqual([healthCalls.length, usageCalls.length], requests)
    } })
    assert.match(document.querySelector('.health-breakdown').textContent, /Responses/)
  })

  it('keeps cache usage separate and renders its loading, failure and empty states', async () => {
    for (const status of ['loading', 'failed', 'ready']) {
      const { document } = await render({ interact: state => {
        state.detailView.value = 'cache'
        state.usage.value = status === 'ready' ? { total: { requests: 0, inputTokens: 0, cachedTokens: 0, cachedWriteTokens: 0 }, byModel: [] } : null
        state.usageState.value = status
      } })
      assert.ok(document.querySelector('.health-overview'))
      assert.equal(document.querySelector('.health-breakdown'), null)
      assert.match(document.body.textContent, /不参与健康度判定/)
      assert.match(document.body.textContent, status === 'failed' ? /缓存用量读取失败/ : status === 'loading' ? /正在汇总缓存用量/ : /没有可统计的用量/)
    }
  })

  it('isolates actual usage request failures from the health verdict', async () => {
    api.getUsageReport = async () => { throw new Error('用量异常') }
    const { state, document } = await render({ interact: state => { state.detailView.value = 'cache' } })
    assert.equal(state.error.value, '')
    assert.match(document.querySelector('.health-verdict').textContent, /需关注/)
    assert.match(document.body.textContent, /缓存用量读取失败/)
  })

  it('keeps no-request and sparse trend shapes and exposes selected sample details', async () => {
    const { document } = await render({ interact: state => { state.selectedBucket.value = 1 } })
    assert.ok(document.querySelector('.health-history-bar.is-empty'))
    assert.ok(document.querySelector('.health-history-bar[data-state="UNKNOWN"]:not(.is-empty)'))
    assert.equal(document.querySelector('.health-history-bar.selected').getAttribute('aria-pressed'), 'true')
    assert.match(document.querySelector('.health-bucket-detail').textContent, /1 次尝试/)
  })

  it('does not imply a healthy route without authorized candidates or fabricate observations', async () => {
    const data = fixture()
    data.availability = []; data.errors = []
    data.report.channels[0].metrics = { ...metrics, lastSuccessAt: null, lastFailureAt: null }
    const { document } = await render({ data })
    assert.equal(document.querySelector('.health-route-dot'), null)
    assert.match(document.body.textContent, /尚未配置带有授权模型/)
    assert.match(document.body.textContent, /没有上游故障记录/)
    assert.equal(document.querySelectorAll('.health-observed-times dd')[0].textContent, '—')
  })

  it('expands complex routing while preserving key and candidate cooldown actions', async () => {
    const data = fixture()
    data.availability = [{ ...active, state: 'COOLDOWN', cooldownScope: 'key' }, { ...active, keyId: 'key-2', keyName: '副密钥', state: 'COOLDOWN', cooldownScope: 'candidate' }]
    const { document } = await render({ data })
    assert.ok(document.querySelector('.health-diagnostics.has-unavailable'))
    assert.equal(document.querySelectorAll('.health-availability-group').length, 2)
    assert.match(document.body.textContent, /解除全部冷却/)
    assert.match(document.body.textContent, /解除该密钥冷却/)
    assert.equal(document.querySelectorAll('.health-availability-list button').length, 1)
  })

  it('retains all three cooldown payloads and blocks closing during a request', async () => {
    let finish
    api.clearChannelCooldown = async (...args) => { clearCalls.push(args); await new Promise(resolve => { finish = resolve }) }
    const { events } = await render({ interact: async state => {
      for (const [scope, keyId, model] of [['channel'], ['key', 'key-1'], ['candidate', 'key-1', 'model']]) {
        const pending = state.clearCooldown(scope, keyId, model)
        state.tryClose()
        assert.ok(state.clearing.value)
        finish()
        await pending
        assert.equal(state.clearing.value, null)
      }
      state.tryClose()
    } })
    assert.deepEqual(clearCalls.map(args => args[1]), [
      { scope: 'channel', keyId: undefined, upstreamModel: undefined },
      { scope: 'key', keyId: 'key-1', upstreamModel: undefined },
      { scope: 'candidate', keyId: 'key-1', upstreamModel: 'model' },
    ])
    assert.deepEqual(events, [['changed'], ['changed'], ['changed'], ['close', true]])
  })

  it('reports uncertain cooldown failures without losing the refresh-on-close signal', async () => {
    api.clearChannelCooldown = async () => { throw new Error('解除失败') }
    const { state, events } = await render({ interact: async state => { await state.clearCooldown('channel'); state.tryClose() } })
    assert.equal(state.error.value, '解除失败')
    assert.deepEqual(events, [['close', true]])
  })

  it('uses the selected time window on reload and resets the selected trend bucket', async () => {
    const { state } = await render({ interact: async state => {
      state.selectedBucket.value = 1
      state.window.value = '7d'
      await state.load()
    } })
    assert.equal(healthCalls.at(-1)[1], '7d')
    assert.equal(state.selectedBucket.value, null)
    assert.equal(usageCalls.at(-1)[0].channelId, channel.id)
  })

  it('preserves explicit refresh and close notifications', async () => {
    const { events, state } = await render({ interact: async state => { await state.handleManualRefresh(); state.tryClose() } })
    assert.equal(state.manualRefreshing.value, false)
    assert.deepEqual(events, [['close', true]])
  })
})

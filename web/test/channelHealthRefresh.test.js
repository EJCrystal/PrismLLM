import { after, afterEach, before, beforeEach, describe, it, mock } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'

describe('channel health refresh isolation', () => {
  let server
  let component
  let api
  let originalGetHealth
  const originalDocument = globalThis.document
  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/ChannelsView.vue')).default
    api = (await server.ssrLoadModule('/src/api.ts')).api
    originalGetHealth = api.getChannelHealth
    globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  })
  beforeEach(() => { mock.timers.enable({ apis: ['setInterval'] }) })
  afterEach(() => { mock.timers.reset() })
  after(async () => {
    if (api) api.getChannelHealth = originalGetHealth
    globalThis.document = originalDocument
    await server?.close()
  })

  const report = {
    window: '24h', to: 1_800_000_000_000,
    channels: [{ channelId: 'test', state: 'HEALTHY', reason: '', requests: {}, metrics: { successRate: 0.96 }, coolingCandidates: 1, cooldownUntil: 1_800_000_060_000, recentAttempts: [{ outcome: 'FAILURE' }] }],
  }
  const setup = async () => {
    let bindings
    const wrapper = {
      ...component,
      setup(props, context) {
        bindings = component.setup(props, context)
        return bindings
      },
    }
    await renderToString(createSSRApp(wrapper))
    return bindings
  }

  it('retains display history but clears live cooldowns and stops the timer on a failed refresh', async () => {
    const state = await setup()
    try {
      api.getChannelHealth = async () => report
      await state.loadHealth()
      assert.equal(state.healthByChannel.value.get('test').coolingCandidates, 1)
      assert.notEqual(state.cooldownTimer, null)

      api.getChannelHealth = async () => { throw new Error('网络异常') }
      await state.loadHealth()
      assert.equal(state.healthReport.value, null)
      assert.equal(state.healthByChannel.value.size, 0)
      assert.equal(state.cooldownTimer, null)
      assert.equal(state.healthError.value, '网络异常')
      assert.equal(state.healthDisplayReport.value.to, report.to)
      assert.equal(state.healthDisplayByChannel.value.get('test').metrics.successRate, 0.96)
      assert.equal(state.healthDisplayByChannel.value.get('test').recentAttempts[0].outcome, 'FAILURE')

      const recovered = { ...report, to: report.to + 60_000, channels: [{ ...report.channels[0], coolingCandidates: 0 }] }
      api.getChannelHealth = async () => recovered
      await state.loadHealth()
      assert.equal(state.healthError.value, '')
      assert.equal(state.healthReport.value.to, recovered.to)
      assert.equal(state.healthDisplayReport.value.to, recovered.to)
      assert.equal(state.cooldownTimer, null)
    } finally {
      if (state.cooldownTimer != null) clearInterval(state.cooldownTimer)
    }
  })

  it('does not reuse a display snapshot from a different health window', async () => {
    const state = await setup()
    state.healthDisplayReport.value = { ...report, window: '7d' }
    api.getChannelHealth = async () => { throw new Error('网络异常') }
    await state.loadHealth()
    assert.equal(state.healthDisplayByChannel.value.size, 0)
    assert.equal(state.healthReport.value, null)
    assert.equal(state.cooldownTimer, null)
  })
})

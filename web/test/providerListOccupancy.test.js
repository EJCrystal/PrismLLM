import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createMockOAuthChannels, createMockOAuthProviders } from './fixtures/oauth-accounts/accounts.js'

const CHATGPT = 'fixture-chatgpt-channel'
const ANTIGRAVITY = 'fixture-antigravity-channel'
const BASE_ORDER = [CHATGPT, ANTIGRAVITY, 'oauth:unprovisioned', 'custom-a', 'custom-z']

function snapshot(counts) {
  return {
    sampledAt: Date.now(),
    channels: Object.entries(counts).map(([channelId, concurrentRequests]) => ({ channelId, concurrentRequests, keys: [] })),
    clientKeys: [],
  }
}

const displayedKeys = state => state.displayedProviders.value.map(item => item.key)

describe('提供商列表实时在途排序', () => {
  let server
  let component
  let api
  let originalGetOccupancy
  const originalDocument = globalThis.document

  before(async () => {
    server = await createServer({ configFile: false, envFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/ChannelsView.vue')).default
    api = (await server.ssrLoadModule('/src/api.ts')).api
    originalGetOccupancy = api.getOccupancy
    globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  })

  afterEach(() => {
    api.getOccupancy = originalGetOccupancy
  })

  after(async () => {
    globalThis.document = originalDocument
    await server?.close()
  })

  async function setup() {
    let state
    await renderToString(createSSRApp({
      ...component,
      setup(props, context) {
        state = component.setup(props, context)
        return state
      },
    }))
    const [chatgpt, antigravity, custom] = createMockOAuthChannels()
    state.channels.value = [
      chatgpt,
      antigravity,
      { ...custom, id: 'custom-z', name: 'Zeta API' },
      { ...custom, id: 'custom-a', name: 'Alpha API' },
    ]
    state.oauthProviders.value = [
      ...createMockOAuthProviders(),
      { id: 'unprovisioned', label: '未置备订阅', configured: false, managedChannel: true },
    ]
    return state
  }

  it('首次无快照、缺少占用或在途数相同时保留原有顺序', async () => {
    const state = await setup()
    assert.deepEqual(displayedKeys(state), BASE_ORDER)
    state.occupancy.value = snapshot({ [CHATGPT]: 0, 'custom-a': 0 })
    assert.deepEqual(displayedKeys(state), BASE_ORDER)
    state.occupancy.value = snapshot({ [CHATGPT]: 3, [ANTIGRAVITY]: 3, 'custom-a': 3, 'custom-z': 3 })
    assert.deepEqual(displayedKeys(state), [CHATGPT, ANTIGRAVITY, 'custom-a', 'custom-z', 'oauth:unprovisioned'])
  })

  it('按渠道总在途数降序混排官方订阅与自定义 API，快照变化立即重排', async () => {
    const state = await setup()
    const originalChannels = state.channels.value.map(channel => channel.id)
    let counts = { [CHATGPT]: 2, [ANTIGRAVITY]: 5, 'custom-a': 1, 'custom-z': 9 }
    api.getOccupancy = async () => snapshot(counts)
    await state.loadOccupancy()
    assert.deepEqual(displayedKeys(state), ['custom-z', ANTIGRAVITY, CHATGPT, 'custom-a', 'oauth:unprovisioned'])

    counts = { [CHATGPT]: 8, 'custom-a': 12 }
    await state.loadOccupancy()
    assert.deepEqual(displayedKeys(state), ['custom-a', CHATGPT, ANTIGRAVITY, 'oauth:unprovisioned', 'custom-z'])
    assert.deepEqual(state.channels.value.map(channel => channel.id), originalChannels)
    assert.deepEqual(state.providerItems.value.map(item => item.key), [CHATGPT, ANTIGRAVITY, 'oauth:unprovisioned', 'custom-z', 'custom-a'])
  })

  it('类型、状态、模型与名称筛选后的列表仍按在途数降序', async () => {
    const state = await setup()
    state.occupancy.value = snapshot({ [CHATGPT]: 2, [ANTIGRAVITY]: 5, 'custom-a': 1, 'custom-z': 9 })
    state.providerKindFilter.value = 'custom'
    state.channelStatusFilter.value = 'enabled'
    state.channelModelFilter.value = 'gpt-5'
    state.channelNameFilter.value = 'API'
    assert.deepEqual(displayedKeys(state), ['custom-z', 'custom-a'])
    state.channelNameFilter.value = 'Alpha'
    assert.deepEqual(displayedKeys(state), ['custom-a'])
  })

  it('刷新失败保留上次在途数及顺序，恢复后重新排序', async () => {
    const state = await setup()
    api.getOccupancy = async () => snapshot({ 'custom-z': 9, [CHATGPT]: 2 })
    await state.loadOccupancy()
    const previousOrder = displayedKeys(state)
    api.getOccupancy = async () => { throw new Error('网络异常') }
    await state.loadOccupancy()
    assert.deepEqual(displayedKeys(state), previousOrder)
    assert.equal(state.channelConcurrent('custom-z'), 9)
    api.getOccupancy = async () => snapshot({})
    await state.loadOccupancy()
    assert.deepEqual(displayedKeys(state), BASE_ORDER)
  })
})

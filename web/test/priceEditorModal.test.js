import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const key of ['window', 'document', 'navigator', 'Document', 'HTMLElement', 'Element', 'Node', 'SVGElement', 'Event']) {
  Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true })
}
const { compile, createApp, h, nextTick, ref, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const originalFetch = globalThis.fetch
async function settle() { await nextTick(); await delay(25); await nextTick() }
const modelIds = () => [...document.querySelectorAll('.model-option-copy code')].map(element => element.textContent)

const fixture = () => ({
  id: 'subscription', name: '订阅提供商', kind: 'provider',
  models: ['allowed-a', 'allowed-b', 'disabled-only', 'unauthorized'],
  enabledModels: ['allowed-a', 'allowed-b'],
  modelMappings: ['allowed-a', 'allowed-b', 'disabled-only', 'unauthorized', 'dormant-manual']
    .map(upstreamModel => ({ upstreamModel, canonicalModelId: 'public/' + upstreamModel })),
})

describe('计费弹窗的订阅模型授权范围', () => {
  let server, modal, app, currentChannel, calls

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const loaded = (await server.ssrLoadModule('/src/components/PriceEditorModal.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/PriceEditorModal.vue', import.meta.url), 'utf8'))
    const children = {
      Modal: { props: ['title'], setup(_, { slots }) { return () => h('section', [slots.default?.(), h('footer', slots.footer?.())]) } },
      PriceFields: { props: ['form'], render: () => null },
      Icon: { props: ['name', 'size'], render: () => null },
    }
    modal = {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: children,
      setup(props, context) { return { ...loaded.setup(props, context), ...children } },
    }
  })

  beforeEach(() => {
    calls = []
    globalThis.fetch = async (url, options = {}) => {
      calls.push({ url, method: options.method || 'GET' })
      return new Response(JSON.stringify({ data: [{ id: 'allowed-a', authorized: true }] }), { status: 200 })
    }
  })
  afterEach(() => { app?.unmount(); app = null; document.body.innerHTML = ''; globalThis.fetch = originalFetch })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount(channel = fixture(), prices = []) {
    currentChannel = ref(channel)
    const host = document.createElement('div')
    document.body.appendChild(host)
    app = createApp({ render: () => h(modal, { channels: [currentChannel.value], prices }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
  }

  it('直接使用启用授权范围，不请求单个订阅账号的上游目录', async () => {
    await mount()
    assert.deepEqual(modelIds(), ['allowed-a', 'allowed-b'])
    assert.deepEqual(calls, [])
    assert.match(document.querySelector('.model-selected-bar').textContent, /allowed-a/)
  })

  it('全部账号停用后不展示完整目录，同时保留已保存单价的编辑入口', async () => {
    await mount({ ...fixture(), enabledModels: [] }, [
      { id: 'old-price', channelId: 'subscription', upstreamModel: 'disabled-only', currency: 'USD', pricingMode: 'token', inputPricePerMillion: '1', outputPricePerMillion: '2' },
    ])
    assert.deepEqual(modelIds(), [])
    assert.deepEqual(calls, [])
    assert.ok(document.querySelector('#price-upstream-model'))
    document.querySelector('.price-configured-main').click()
    await settle()
    assert.equal(document.querySelector('#price-upstream-model').value, 'disabled-only')
    assert.ok(document.querySelector('.price-configured-item.active'))
  })

  it('启用授权范围变化时移除候选，不从保留的完整映射中重新补回', async () => {
    await mount()
    currentChannel.value = { ...fixture(), enabledModels: ['allowed-b'] }
    await settle()
    assert.deepEqual(modelIds(), ['allowed-b'])
    assert.match(document.querySelector('.model-selected-bar').textContent, /allowed-b/)
    assert.deepEqual(calls, [])
  })

  it('自定义 API 仍执行原有的上游发现并保留配置中缺失于发现结果的模型', async () => {
    await mount({ id: 'custom', name: '自定义 API', kind: 'custom', models: ['allowed-a', 'configured-only'], modelMappings: [] })
    assert.deepEqual(modelIds(), ['allowed-a', 'configured-only'])
    assert.deepEqual(calls, [{ url: '/admin/api/channels/custom/models', method: 'GET' }])
  })
})

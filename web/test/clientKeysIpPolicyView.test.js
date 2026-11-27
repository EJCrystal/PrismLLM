import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'SVGElement', 'Event', 'MutationObserver']) {
  globalThis[name] = dom.window[name]
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
const runtime = await import('vue')
const { createApp, h, nextTick, ssrContextKey } = runtime
const { parse } = await import('@vue/compiler-sfc')
const { compile } = await import('@vue/compiler-dom')
const { transform } = await import('esbuild')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const originalFetch = globalThis.fetch

const unrestricted = () => ({ channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'unrestricted', keys: [] }, models: { mode: 'unrestricted', ids: [] } })
function fixture(ipPolicy = { allowlist: ['203.0.113.0/24', '2001:db8::/32'], denylist: ['203.0.113.9'] }) {
  return {
    id: 'key-1', name: 'scoped-client', keyPrefix: 'sk-gw-test', enabled: true, expiresAt: null,
    dailyTokenLimit: null, dailyUsdLimit: null, maxConcurrentRequests: null, requestsPerMinute: null,
    quotaUsage: { period: 0, tokens: 0, usdCost: '0', concurrentRequests: 0 },
    createdAt: 0, rotatedAt: null, revokedAt: null, accessPolicy: unrestricted(), ipPolicy,
  }
}

describe('调用方密钥页面 IP 规则交互', () => {
  let server, component, app, requests, records
  const settle = async () => { await nextTick(); await delay(35); await nextTick() }
  const button = label => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === label || node.getAttribute('aria-label') === label || node.getAttribute('title') === label)
  async function click(label) {
    assert.ok(button(label), 'missing button: ' + label)
    button(label).click()
    await settle()
  }
  async function input(selector, value) {
    const element = document.querySelector(selector)
    assert.ok(element, selector)
    element.value = value
    element.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
  }
  before(async () => {
    server = await createServer({ root: new URL('..', import.meta.url).pathname, configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const loaded = (await server.ssrLoadModule('/src/components/ClientKeysView.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/ClientKeysView.vue', import.meta.url), 'utf8'))
    const compiled = compile(descriptor.template.content, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
    const { code } = await transform(compiled.code, { loader: 'ts' })
    const empty = { render: () => null }
    const components = {
      Icon: empty, CopyButton: empty, ClientKeyAccessFields: empty, ErrorState: empty, TablePlaceholder: empty,
      ActionMenu: { setup: (_, { slots }) => () => h('div', slots.default?.()) },
      Modal: { props: ['title'], setup: (props, { slots }) => () => h('section', { role: 'dialog', 'aria-label': props.title }, [slots.default?.(), slots.footer?.()]) },
    }
    component = { ...loaded, ssrRender: undefined, render: new Function('Vue', code)(runtime), components, setup(props, context) { return { ...loaded.setup(props, context), ...components } } }
  })
  afterEach(() => { app?.unmount(); app = null; document.body.innerHTML = ''; globalThis.fetch = originalFetch })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount(keys = [fixture()]) {
    records = keys
    requests = []
    globalThis.fetch = async (url, options = {}) => {
      const method = options.method ?? 'GET'
      const body = options.body ? JSON.parse(options.body) : null
      requests.push({ url, method, body })
      let result = {}
      if (url === '/admin/api/client-keys' && method === 'GET') result = records
      else if (method === 'PUT') result = { ...records[0], ...body }
      else if (method === 'POST') result = { ...fixture(), ...body, key: 'sk-gw-once' }
      return new Response(JSON.stringify(result), { status: method === 'POST' ? 201 : 200, headers: { 'Content-Type': 'application/json' } })
    }
    const container = document.createElement('div')
    document.body.append(container)
    app = createApp({ render: () => h(component) })
    app.directive('scroll-hint', {})
    app.provide(ssrContextKey, {})
    app.mount(container)
    await settle()
  }

  it('编辑时逐行回显并完整提交两份名单', async () => {
    await mount()
    assert.match(document.body.textContent, /IP 白名单 2 · IP 黑名单 1/)
    await click('编辑')
    assert.equal(document.querySelector('#client-key-ip-allowlist').value, '203.0.113.0/24\n2001:db8::/32')
    assert.equal(document.querySelector('#client-key-ip-denylist').value, '203.0.113.9')
    await input('#client-key-ip-allowlist', '198.51.100.0/24\n::1')
    await input('#client-key-ip-denylist', '198.51.100.9，198.51.100.10')
    await click('保存修改')
    assert.deepEqual(requests.find(request => request.method === 'PUT').body.ipPolicy, {
      allowlist: ['198.51.100.0/24', '::1'], denylist: ['198.51.100.9', '198.51.100.10'],
    })
  })

  it('启停省略 IP 策略，避免覆盖已有规则', async () => {
    await mount()
    await click('停用')
    const update = requests.find(request => request.method === 'PUT').body
    assert.equal(update.enabled, false)
    assert.equal(Object.hasOwn(update, 'ipPolicy'), false)
  })

  it('新建时清空上次编辑草稿，空输入显式清除限制', async () => {
    await mount()
    await click('编辑')
    await click('取消')
    await click('新建密钥')
    assert.equal(document.querySelector('#client-key-ip-allowlist').value, '')
    assert.equal(document.querySelector('#client-key-ip-denylist').value, '')
    await input('input[placeholder="例如 search-service"]', 'new-client')
    await click('创建密钥')
    assert.deepEqual(requests.find(request => request.method === 'POST').body.ipPolicy, { allowlist: [], denylist: [] })
  })

  it('旧响应没有 IP 策略时仍能打开编辑表单', async () => {
    const old = fixture()
    delete old.ipPolicy
    await mount([old])
    await click('编辑')
    assert.equal(document.querySelector('#client-key-ip-allowlist').value, '')
    assert.equal(document.querySelector('#client-key-ip-denylist').value, '')
  })
})

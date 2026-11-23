import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://127.0.0.1/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Document', 'Node', 'NodeFilter', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLIFrameElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent', 'CustomEvent']) globalThis[name] = dom.window[name]
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}
dom.window.HTMLElement.prototype.scrollIntoView = () => {}
const originalFetch = globalThis.fetch
const originalConfirm = window.confirm
const { createApp, h, nextTick, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { compile: compileTemplate } = await import('@vue/compiler-dom')
const { transform } = await import('esbuild')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const runtime = await import('vue')

const modelId = 'vendor/model'
const entryA = { channelId: 'channel-a', credentialType: 'static', credentialId: 'key-a', channelName: '渠道 A', credentialName: '主密钥', upstreamModels: ['upstream-a'], protocols: ['chat_completions'], selectable: true, reason: null }
const entryB = { channelId: 'channel-b', credentialType: 'oauth', credentialId: 'account-b', channelName: '渠道 B', credentialName: '订阅账号 B', upstreamModels: ['upstream-b'], protocols: ['responses'], selectable: true, reason: null }
const clone = value => structuredClone(value)
const entryKey = entry => JSON.stringify([entry.channelId, entry.credentialType, entry.credentialId])
const makeFixture = () => ({
  detail: { modelId, revision: 5, configured: true, entries: [clone(entryA), clone(entryB)], availableCredentials: [clone(entryA), clone(entryB)] },
  logoutCalls: 0,
  pendingPut: null,
})
function createFetch(fixture) {
  return async (input, init = {}) => {
    const url = new URL(String(input), window.location.origin)
    const method = init.method || 'GET'
    if (url.pathname === '/admin/api/me' && method === 'GET') return new Response(JSON.stringify({ username: 'admin', avatarDataUrl: null }))
    if (url.pathname === '/admin/api/logout' && method === 'POST') {
      fixture.logoutCalls++
      return new Response('null')
    }
    if (url.pathname === '/admin/api/model-routes' && method === 'GET') return new Response(JSON.stringify({ items: [{ modelId, configured: true, entryCount: 2, availableCount: 2, revision: fixture.detail.revision }], total: 1, offset: 0, limit: 100 }))
    if (url.pathname === '/admin/api/client-keys' && method === 'GET') return new Response(JSON.stringify([]))
    if (url.pathname === '/admin/api/model-routes/detail' && method === 'GET') return new Response(JSON.stringify(clone(fixture.detail)))
    if (url.pathname === '/admin/api/model-routes' && method === 'PUT') {
      const body = JSON.parse(init.body)
      const complete = () => {
        fixture.detail = { ...fixture.detail, entries: body.entries.map(identity => clone([entryA, entryB].find(entry => entryKey(entry) === entryKey(identity)))), revision: fixture.detail.revision + 1, configured: true }
        return new Response(JSON.stringify(clone(fixture.detail)))
      }
      if (fixture.pendingPut) return new Promise(resolve => { fixture.pendingPut.resolve = () => resolve(complete()) })
      return complete()
    }
    throw new Error(`Unexpected synthetic request ${method} ${url.pathname}`)
  }
}
async function loadClientComponent(server, name, components = {}) {
  const component = (await server.ssrLoadModule('/src/' + (name === 'App' ? 'App.vue' : 'components/' + name + '.vue'))).default
  const path = name === 'App' ? '../src/App.vue' : '../src/components/' + name + '.vue'
  const { descriptor } = parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
  const compiled = compileTemplate(descriptor.template.content, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
  const { code } = await transform(compiled.code, { loader: 'ts' })
  return { ...component, ssrRender: undefined, render: new Function('Vue', code)(runtime), components, setup(props, context) { return { ...component.setup(props, context), ...components } } }
}
async function settle() { await nextTick(); await delay(35); await nextTick() }
const text = node => node?.textContent.replace(/\s+/g, ' ').trim()

describe('App navigation protects model route drafts', () => {
  let server
  let appComponent
  let routeComponent
  let app
  let host
  let fixture
  let confirmResult
  let confirmCalls
  let fetchMock
  const button = (scope, label) => {
    const found = [...scope.querySelectorAll('button')].find(node => text(node) === label)
    assert.ok(found, `Expected button ${label}`)
    return found
  }
  async function mount() {
    fixture = makeFixture()
    confirmResult = false
    confirmCalls = []
    window.confirm = message => { confirmCalls.push(message); return confirmResult }
    fetchMock = createFetch(fixture)
    globalThis.fetch = window.fetch = fetchMock
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(appComponent)
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    assert.equal(text(host.querySelector('h1')), '提供商')
    button(host.querySelector('nav'), '模型路由').click()
    await settle()
    assert.equal(text(host.querySelector('h1')), '模型路由')
    assert.equal(host.querySelectorAll('[data-route-entry]').length, 2)
  }
  function makeDraft() {
    const row = [...host.querySelectorAll('[data-route-entry]')].find(node => node.dataset.routeEntry === entryKey(entryA))
    row.querySelector('button[data-action="down"]').click()
  }
  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, envFile: false, cacheDir: join(tmpdir(), 'prism-app-navigation-tests'), plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, proxy: {} }, appType: 'custom' })
    const icon = await loadClientComponent(server, 'Icon')
    const errorState = await loadClientComponent(server, 'ErrorState', { Icon: icon })
    routeComponent = await loadClientComponent(server, 'ModelRoutesView', { Icon: icon, ErrorState: errorState })
    const inert = { render: () => h('div') }
    const components = {
      Icon: icon,
      ToastStack: inert,
      LoginView: inert,
      ChannelsView: inert,
      ModelRoutesView: routeComponent,
      ClientKeysView: inert,
      LogsView: inert,
      UsageReportsView: inert,
      AdminAuditLogsView: inert,
      SystemSettingsView: inert,
      AccountSettingsModal: inert,
      ThemeToggle: inert,
      InfoPopover: inert,
    }
    appComponent = await loadClientComponent(server, 'App', components)
  })
  afterEach(async () => {
    app?.unmount(); app = null
    await settle()
    host?.remove(); host = null
    globalThis.fetch = window.fetch = originalFetch
    window.confirm = originalConfirm
  })
  after(async () => { await server?.close(); dom.window.close() })

  it('keeps the dirty route view when sidebar navigation is canceled and leaves only after confirmation', async () => {
    await mount()
    makeDraft()
    await nextTick()
    assert.match(text(host.querySelector('.route-save-bar')), /有未保存修改/)
    button(host.querySelector('nav'), '提供商').click()
    await settle()
    assert.equal(text(host.querySelector('h1')), '模型路由')
    assert.equal(host.querySelector('.route-editor') !== null, true)
    assert.equal(confirmCalls.length, 1)
    assert.match(confirmCalls[0], /未保存.*离开页面将丢弃修改/)

    confirmResult = true
    button(host.querySelector('nav'), '提供商').click()
    await settle()
    assert.equal(text(host.querySelector('h1')), '提供商')
    assert.equal(host.querySelector('.route-editor'), null)
    assert.equal(confirmCalls.length, 2)
  })

  it('protects dirty drafts on explicit logout with the same confirmation', async () => {
    await mount()
    makeDraft()
    await nextTick()
    button(host, '退出登录').click()
    await settle()
    assert.equal(text(host.querySelector('h1')), '模型路由')
    assert.ok(host.querySelector('.route-editor'))
    assert.equal(fixture.logoutCalls, 0)
    assert.equal(confirmCalls.length, 1)

    confirmResult = true
    button(host, '退出登录').click()
    await settle()
    assert.equal(host.querySelector('.app-shell'), null)
    assert.equal(fixture.logoutCalls, 1)
    assert.equal(confirmCalls.length, 2)
  })

  it('blocks sidebar switching and logout while a route save is pending', async () => {
    await mount()
    makeDraft()
    await nextTick()
    fixture.pendingPut = {}
    button(host, '保存路由').click()
    await nextTick()
    assert.match(text(host.querySelector('.route-save-bar')), /保存中/)

    button(host.querySelector('nav'), '提供商').click()
    button(host, '退出登录').click()
    await settle()
    assert.equal(text(host.querySelector('h1')), '模型路由')
    assert.ok(host.querySelector('.route-editor'))
    assert.equal(fixture.logoutCalls, 0)
    assert.equal(confirmCalls.length, 0, 'Busy navigation is blocked without asking to discard')

    fixture.pendingPut.resolve()
    fixture.pendingPut = null
    await settle()
    assert.match(text(host.querySelector('.route-save-bar')), /模型路由已保存/)
    button(host.querySelector('nav'), '提供商').click()
    await settle()
    assert.equal(text(host.querySelector('h1')), '提供商')
  })
})

import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true })
for (const key of ['window', 'document', 'navigator', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'Element', 'Node', 'SVGElement', 'Event', 'CustomEvent', 'MutationObserver']) {
  Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true })
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.HTMLElement.prototype.scrollTo = () => {}
dom.window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
const { compile, createApp, nextTick, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const headless = await import('@headlessui/vue')
const originalFetch = globalThis.fetch
const response = (body, status = 200) => new Response(JSON.stringify(body), { status })
const button = text => [...document.querySelectorAll('button')].find(element => element.textContent.trim() === text)
const fixture = (id, extra = {}) => ({
  id, requestId: 'req-' + id, createdAt: Date.now(), protocol: 'chat_completions', upstreamProtocol: 'chat_completions',
  stream: false, alias: 'body-test', statusCode: 200, totalMs: 20, firstTokenMs: null, usageKnown: false,
  priceTiers: [], attempts: [], clientIp: '127.0.0.1', requestBodyCaptured: true,
  requestBodyTruncated: false, requestBodySizeBytes: 42, requestBodySizeExact: true, ...extra,
})
async function settle() { await nextTick(); await delay(25); await nextTick() }
async function submit() {
  document.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
}

describe('request body logging controls and details', () => {
  let server, modal, logs, app, state, policy, saved, emitted, bodyCalls, bodyResponse, failSave, toasts

  async function component(name, children = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: { ...headless, ...children }, directives: { scrollHint: {} },
      setup(props, context) { return { ...loaded.setup(props, context), ...children } },
    }
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const icon = await component('Icon')
    const modalBase = await component('Modal', { Icon: icon })
    modal = await component('RetentionSettingsModal', { Icon: icon, Modal: modalBase })
    const stub = { render: () => null }
    logs = await component('LogsView', {
      Icon: icon, CopyButton: stub, CursorLoadMore: stub, RequestLogListItem: stub,
      TablePlaceholder: stub, ErrorState: stub, FilterSelect: stub, InfoPopover: stub,
      ProtocolEndpoints: stub, ProtocolIcon: stub,
    })
    toasts = (await server.ssrLoadModule('/src/toast.ts')).toasts
  })

  beforeEach(() => {
    policy = { cleanupEnabled: false, requestBodyLoggingEnabled: false }
    saved = []; emitted = []; bodyCalls = []; failSave = false
    bodyResponse = async () => response({ body: 'saved body' })
    toasts.splice(0)
    globalThis.fetch = async (url, init = {}) => {
      const path = String(url).replace('/admin/api', '')
      if (path === '/request-log-retention') {
        const payload = JSON.parse(init.body)
        saved.push(payload)
        if (failSave) return response({ error: { message: '保存失败测试' } }, 500)
        return response({ ...policy, ...payload })
      }
      if (/^\/request-logs\/\d+\/body$/.test(path)) {
        bodyCalls.push(path)
        return bodyResponse(path)
      }
      if (path.startsWith('/request-logs/filter-options')) return response({ channels: [], models: [], statuses: [] })
      if (path.startsWith('/request-logs?')) return response([fixture(1), fixture(2)])
      assert.fail('Unexpected API request: ' + path)
    }
  })

  afterEach(async () => { app?.unmount(); app = null; state = null; await settle(); document.body.innerHTML = '' })
  after(async () => { globalThis.fetch = originalFetch; await server.close(); dom.window.close() })

  async function mount(view, props = {}) {
    const root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({
      ...view,
      setup(componentProps, context) { state = view.setup(componentProps, context); return state },
    }, props)
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(root)
    await settle()
  }

  it('defaults body capture off and saves both independent switches with a privacy warning', async () => {
    await mount(modal, { policy, onSaved: () => emitted.push('saved'), onClose: () => emitted.push('close') })
    const switches = document.querySelectorAll('form input[type="checkbox"]')
    assert.equal(switches.length, 2)
    assert.equal(switches[0].checked, false)
    assert.match(document.body.textContent, /不会自动脱敏/)
    assert.match(document.body.textContent, /64 KiB/)
    assert.match(document.body.textContent, /随请求日志一起清理/)
    switches[0].click()
    await settle()
    await submit()
    assert.deepEqual(saved, [{ cleanupEnabled: false, requestBodyLoggingEnabled: true }])
    assert.deepEqual(emitted, ['saved', 'close'])
  })

  it('failed saves preserve the chosen switches and allow retry', async () => {
    policy.requestBodyLoggingEnabled = true
    await mount(modal, { policy, onSaved: () => emitted.push('saved'), onClose: () => emitted.push('close') })
    const switches = document.querySelectorAll('form input[type="checkbox"]')
    switches[1].click()
    await settle()
    failSave = true
    await submit()
    assert.deepEqual(emitted, [])
    assert.equal(switches[0].checked, true)
    assert.equal(switches[1].checked, true)
    assert.equal(toasts.at(-1).message, '保存失败测试')
    failSave = false
    await submit()
    assert.deepEqual(saved.at(-1), { cleanupEnabled: true, requestBodyLoggingEnabled: true })
    assert.deepEqual(emitted, ['saved', 'close'])
  })

  it('loads bodies only on click and discards late responses after changing logs', async () => {
    let finishFirst
    bodyResponse = path => path.includes('/1/')
      ? new Promise(resolve => { finishFirst = resolve })
      : response({ body: '<img src=x onerror="alert(1)">PRIVATE_SECOND_BODY' })
    await mount(logs)
    state.selected.value = fixture(1)
    await settle()
    assert.equal(bodyCalls.length, 0)
    button('查看请求正文').click()
    await settle()
    assert.deepEqual(bodyCalls, ['/request-logs/1/body'])
    state.selected.value = fixture(2)
    await settle()
    assert.equal(state.requestBody.value, null)
    button('查看请求正文').click()
    await settle()
    assert.match(document.querySelector('.detail-code').textContent, /<img src=x/)
    assert.equal(document.querySelector('.detail-code img'), null)
    finishFirst(response({ body: 'PRIVATE_FIRST_BODY' }))
    await settle()
    assert.match(state.requestBody.value, /PRIVATE_SECOND_BODY/)
    assert.doesNotMatch(document.body.textContent, /PRIVATE_FIRST_BODY/)
    state.closeDetail()
    await settle()
    assert.equal(state.requestBody.value, null)
    assert.doesNotMatch(document.body.textContent, /PRIVATE_SECOND_BODY/)
  })

  it('shows truncated lower-bound sizes and allows failed body reads to be retried', async () => {
    await mount(logs)
    state.selected.value = fixture(1, { requestBodyTruncated: true, requestBodySizeBytes: 12, requestBodySizeExact: false })
    await settle()
    assert.match(document.body.textContent, /至少 12 字节（未读完）/)
    assert.match(document.body.textContent, /仅保留部分正文/)
    bodyResponse = async () => response({ error: { message: '该正文已经清理' } }, 404)
    button('查看请求正文').click()
    await settle()
    assert.match(document.body.textContent, /该正文已经清理/)
    bodyResponse = async () => response({ body: '' })
    button('重试读取正文').click()
    await settle()
    assert.equal(state.requestBody.value, '')
    assert.match(document.body.textContent, /（空正文）/)
    assert.equal(state.requestBodyError.value, '')
  })

  it('never requests a body for historical or uncaptured logs', async () => {
    await mount(logs)
    state.selected.value = fixture(1, { requestBodyCaptured: false, requestBodySizeBytes: null })
    await settle()
    assert.match(document.body.textContent, /未记录正文/)
    assert.match(document.body.textContent, /未读取或历史未记录/)
    assert.equal(button('查看请求正文'), undefined)
    await state.loadRequestBody()
    assert.equal(bodyCalls.length, 0)
  })
})

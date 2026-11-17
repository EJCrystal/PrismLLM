import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
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
const { createApp, nextTick, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { compile: compileTemplate } = await import('@vue/compiler-dom')
const { transform } = await import('esbuild')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const runtime = await import('vue')

const staticA = { channelId: 'channel-a', credentialType: 'static', credentialId: 'key-a', channelName: '渠道 A', credentialName: '主密钥', upstreamModels: ['upstream-a', 'alias-a'], protocols: ['chat_completions'], selectable: true, reason: null }
const oauthB = { channelId: 'channel-b', credentialType: 'oauth', credentialId: 'account-b', channelName: '渠道 B', credentialName: '订阅账号 B', upstreamModels: ['upstream-b'], protocols: ['responses'], selectable: false, reason: '账号需要重新授权' }
const staticC = { channelId: 'channel-c', credentialType: 'static', credentialId: 'key-c', channelName: '渠道 C', credentialName: '备用密钥', upstreamModels: ['upstream-c'], protocols: ['messages'], selectable: true, reason: null }
const oauthD = { channelId: 'channel-d', credentialType: 'oauth', credentialId: 'account-d', channelName: '渠道 D', credentialName: '不可选账号', upstreamModels: ['upstream-d'], protocols: ['responses'], selectable: false, reason: '模型未授权' }
const clone = value => structuredClone(value)
const modelA = 'a/vendor model'
const modelB = 'b/model'
function createFixture() {
  return {
    list: [
      { modelId: modelB, configured: false, entryCount: 0, availableCount: 1, revision: 0 },
      { modelId: modelA, configured: true, entryCount: 2, availableCount: 4, revision: 5 },
    ],
    details: new Map([
      [modelA, { modelId: modelA, revision: 5, configured: true, entries: [clone(staticA), clone(oauthB)], availableCredentials: [clone(staticA), clone(oauthB), clone(staticC), clone(oauthD)] }],
      [modelB, { modelId: modelB, revision: 0, configured: false, entries: [], availableCredentials: [clone(staticC)] }],
    ]),
    clientKeys: [
      {
        id: 'caller-a', name: '调用方 A', keyPrefix: 'prism_a', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'denylist', ids: ['channel-b'] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-b', name: '调用方 B', keyPrefix: 'prism_b', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'denylist', ids: [modelA] },
        },
      },
      {
        id: 'caller-c', name: '调用方 C', keyPrefix: 'prism_c', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-b', keyId: 'account-b', credentialType: 'oauth' }] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-d', name: '调用方 D', keyPrefix: 'prism_d', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-a', credentialType: 'static' }] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-e', name: '调用方 E', keyPrefix: 'prism_e', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'allowlist', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-f', name: '调用方 F', keyPrefix: 'prism_f', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'allowlist', ids: [modelB] },
        },
      },
      {
        id: 'caller-g', name: '调用方 G', keyPrefix: 'prism_g', enabled: true, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'allowlist', ids: ['channel-a'] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-disabled', name: '调用方 停用', keyPrefix: 'prism_disabled', enabled: false, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-expired', name: '调用方 过期', keyPrefix: 'prism_expired', enabled: true, expiresAt: 1, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-expiring', name: '调用方 即将过期', keyPrefix: 'prism_expiring', enabled: true, expiresAt: Date.now() + 300, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
      {
        id: 'caller-expiring-selected', name: '调用方 已选即将过期', keyPrefix: 'prism_expiring_selected', enabled: true, expiresAt: Date.now() + 1200, revokedAt: null,
        accessPolicy: {
          channels: { mode: 'unrestricted', ids: [] },
          channelKeys: { mode: 'unrestricted', keys: [] },
          models: { mode: 'unrestricted', ids: [] },
        },
      },
    ],
    writes: [],
    requests: [],
    conflictNextWrite: false,
    failNextWrite: false,
  }
}
function createFetch(fixture) {
  return async (input, init = {}) => {
    const url = new URL(String(input), window.location.origin)
    const method = init.method || 'GET'
    const request = { method, path: url.pathname + url.search, body: init.body ? JSON.parse(init.body) : null }
    fixture.requests.push(request)
    if (url.pathname === '/admin/api/model-routes' && method === 'GET') {
      const q = (url.searchParams.get('q') || '').toLowerCase()
      const offset = Number(url.searchParams.get('offset') || 0)
      const limit = Number(url.searchParams.get('limit') || 100)
      const all = fixture.list.filter(item => item.modelId.toLowerCase().includes(q))
      return new Response(JSON.stringify({ items: all.slice(offset, offset + limit), total: all.length, offset, limit }))
    }
    if (url.pathname === '/admin/api/client-keys' && method === 'GET') {
      return new Response(JSON.stringify(clone(fixture.clientKeys)))
    }
    if (url.pathname === '/admin/api/model-routes/detail' && method === 'GET') {
      const detail = fixture.details.get(url.searchParams.get('modelId'))
      if (!detail) return new Response(JSON.stringify({ error: { message: 'model not found' } }), { status: 404 })
      return new Response(JSON.stringify(clone(detail)))
    }
    if (url.pathname === '/admin/api/model-routes' && method === 'PUT') {
      const payload = JSON.parse(init.body)
      fixture.writes.push(payload)
      if (fixture.failNextWrite) {
        fixture.failNextWrite = false
        return new Response(JSON.stringify({ error: { code: 'synthetic_save_failed', message: '合成保存失败' } }), { status: 500 })
      }
      if (fixture.conflictNextWrite) {
        fixture.conflictNextWrite = false
        return new Response(JSON.stringify({ error: { code: 'model_route_revision_conflict', message: '路由版本冲突' } }), { status: 409 })
      }
      const current = fixture.details.get(payload.modelId)
      if (payload.expectedRevision !== current.revision) return new Response(JSON.stringify({ error: { code: 'model_route_revision_conflict', message: '路由版本冲突' } }), { status: 409 })
      const metadata = new Map([...current.entries, ...current.availableCredentials].map(entry => [JSON.stringify([entry.channelId, entry.credentialType, entry.credentialId]), entry]))
      const entries = payload.entries.map(identity => clone(metadata.get(JSON.stringify([identity.channelId, identity.credentialType, identity.credentialId]))))
      const updated = { ...current, configured: true, revision: current.revision + 1, entries }
      fixture.details.set(payload.modelId, updated)
      fixture.list = fixture.list.map(item => item.modelId === payload.modelId ? { ...item, configured: true, entryCount: entries.length, revision: updated.revision } : item)
      return new Response(JSON.stringify(clone(updated)))
    }
    throw new Error(`Unexpected API request ${method} ${url.pathname}`)
  }
}

async function loadClientComponent(server, name, components = {}) {
  const component = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
  const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
  const compiled = compileTemplate(descriptor.template.content, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
  const { code } = await transform(compiled.code, { loader: 'ts' })
  return {
    ...component,
    ssrRender: undefined,
    render: new Function('Vue', code)(runtime),
    components,
    setup(props, context) { return { ...component.setup(props, context), ...components } },
  }
}
async function settle() { await nextTick(); await delay(35); await nextTick() }
const text = node => node?.textContent.replace(/\s+/g, ' ').trim()
const keyFor = item => JSON.stringify([item.channelId, item.credentialType, item.credentialId])
function dragEvent(node, type, dataTransfer) {
  const event = new Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'dataTransfer', { value: dataTransfer })
  node.dispatchEvent(event)
  return event
}
function dragData() {
  const data = new Map()
  return { effectAllowed: 'none', dropEffect: 'none', setData: (type, value) => data.set(type, value), getData: type => data.get(type) || '' }
}

describe('ModelRoutesView mounted credential-order editor', () => {
  let server
  let view
  let app
  let host
  let fixture
  let fetchMock
  const entries = () => [...host.querySelectorAll('[data-route-entry]')]
  const entryIds = () => entries().map(node => node.dataset.routeEntry)
  const button = name => {
    const found = [...host.querySelectorAll('button')].find(node => text(node) === name)
    assert.ok(found, `Expected button ${name}`)
    return found
  }
  const entryRow = key => entries().find(node => node.dataset.routeEntry === key)
  const availableRow = key => [...host.querySelectorAll('[data-available-credential]')].find(node => node.dataset.availableCredential === key)
  const entryButton = (name, key) => entryRow(key)?.querySelector(`button[data-action="${name}"]`)
  const search = async (label, value) => {
    const input = host.querySelector(`[aria-label="${label}"]`)
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
    if (label === '搜索对外模型') await delay(300)
    await settle()
  }
  const selectPerspective = async value => {
    await delay(250)
    await settle()
    const select = host.querySelector('select[aria-label="调用方视角"]')
    assert.ok(select, '缺少调用方视角选择器')
    assert.equal(select.disabled, false, '调用方视角选择器仍禁用')
    if (value) assert.ok(select.querySelector(`option[value="${value}"]`), '缺少视角选项：' + value)
    select.selectedIndex = [...select.querySelectorAll('option')].findIndex(option => option.value === value)
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
  }
  async function mount() {
    fixture = createFixture()
    fetchMock = createFetch(fixture)
    globalThis.fetch = window.fetch = fetchMock
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => runtime.h(view) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    assert.equal(host.querySelector('[data-route-entry]')?.dataset.routeEntry, keyFor(staticA))
    assert.deepEqual(fixture.requests.slice(0, 2).map(request => request.method), ['GET', 'GET'])
  }
  before(async () => {
    server = await createServer({
      root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, envFile: false,
      cacheDir: join(tmpdir(), 'prism-model-routes-view-tests'), plugins: [vue()],
      optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, proxy: {} }, appType: 'custom',
    })
    const icon = await loadClientComponent(server, 'Icon')
    const errorState = await loadClientComponent(server, 'ErrorState', { Icon: icon })
    view = await loadClientComponent(server, 'ModelRoutesView', { Icon: icon, ErrorState: errorState })
  })
  beforeEach(() => { window.confirm = () => { throw new Error('Unexpected confirmation in route view test') } })
  afterEach(async () => {
    app?.unmount(); app = null
    await settle()
    host?.remove(); host = null
    globalThis.fetch = window.fetch = originalFetch
    window.confirm = originalConfirm
  })
  after(async () => { await server?.close(); dom.window.close() })

  it('previews global model routes through a caller key scope without editing', async () => {
    await mount()
    const options = [...host.querySelectorAll('select[aria-label="调用方视角"] option')].map(option => option.value)
    assert.ok(!options.includes('caller-disabled'), '停用密钥不应进入视角选择')
    assert.ok(!options.includes('caller-expired'), '过期密钥不应进入视角选择')

    host.querySelector(`[data-model-id="${modelB}"]`).click()
    await settle()
    assert.equal(button('保存路由').disabled, false)
    await selectPerspective('caller-a')
    assert.equal(button('保存路由').disabled, true, '视角模式必须阻止本可保存的空路由')
    assert.equal(fixture.writes.length, 0)

    host.querySelector(`[data-model-id="${modelA}"]`).click()
    await settle()
    const perspective = text(host.querySelector('.route-perspective'))
    assert.match(perspective, /只读视角/)
    assert.match(text(host.querySelector('.route-perspective-note')), /只读预览/)
    assert.ok(perspective.includes(`${modelA}：全局 2 条，当前视角可用 1 条`), perspective)
    const denied = entryRow(keyFor(oauthB))
    assert.match(text(denied), /调用方排除/)
    assert.match(text(denied), /调用方渠道黑名单/)
    assert.equal(entryButton('remove', keyFor(oauthB)), null)
    assert.equal(host.querySelector('.route-add-section'), null)

    await selectPerspective('caller-b')
    const deniedModel = [...host.querySelectorAll('.route-model')].find(node => node.dataset.modelId === modelA)
    assert.match(text(deniedModel), /调用方拒绝/)
    assert.match(text(entryRow(keyFor(staticA))), /调用方模型黑名单/)

    await selectPerspective('caller-c')
    assert.match(text(entryRow(keyFor(oauthB))), /调用方 OAuth 账号黑名单/)

    await selectPerspective('caller-d')
    assert.match(text(entryRow(keyFor(staticA))), /调用方静态密钥黑名单/)

    await selectPerspective('caller-e')
    assert.match(text(entryRow(keyFor(staticA))), /调用方未允许该静态密钥/)
    assert.match(text(entryRow(keyFor(oauthB))), /调用方未允许该 OAuth 账号/)

    await selectPerspective('caller-f')
    assert.match(text(entryRow(keyFor(staticA))), /调用方未允许该模型/)

    await selectPerspective('caller-g')
    assert.match(text(entryRow(keyFor(oauthB))), /调用方未允许该渠道/)

    await selectPerspective('')
    assert.ok(entryButton('remove', keyFor(oauthB)))
    assert.ok(host.querySelector('.route-add-section'))
  })

  it('perspective preview uses the saved route while preserving a dirty draft', async () => {
    await mount()
    entryButton('down', keyFor(staticA)).click()
    await settle()
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])

    await selectPerspective('caller-a')
    assert.deepEqual(entryIds(), [keyFor(staticA), keyFor(oauthB)])
    assert.match(text(host.querySelector('.route-perspective')), /全局 2 条，当前视角可用 1 条/)

    await selectPerspective('')
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
  })

  it('removes a caller key from the perspective selector when it expires after loading', async () => {
    await mount()
    assert.ok(host.querySelector('select[aria-label="调用方视角"] option[value="caller-expiring"]'))
    await delay(450)
    await settle()
    assert.equal(host.querySelector('select[aria-label="调用方视角"] option[value="caller-expiring"]'), null)
  })

  it('clears the active perspective when the selected caller key expires', async () => {
    await mount()
    await selectPerspective('caller-expiring-selected')
    assert.match(text(host.querySelector('.route-perspective')), /只读视角/)
    await delay(1100)
    await settle()
    assert.doesNotMatch(text(host.querySelector('.route-perspective')), /只读视角/)
    assert.match(text(host.querySelector('.route-perspective')), /全局模式/)
    assert.equal(host.querySelector('select[aria-label="调用方视角"] option[value="caller-expiring-selected"]'), null)
  })

  it('searches and sorts public model IDs, and renders unavailable stored entries with reasons', async () => {
    await mount()
    assert.deepEqual([...host.querySelectorAll('.route-model')].map(node => node.dataset.modelId), [modelA, modelB])
    assert.equal(text(host.querySelector(`[data-model-id="${modelB}"] .badge`)), '待配置')
    assert.match(text(host.querySelector('.route-entry-list')), /上游模型：upstream-a、alias-a/)
    assert.match(text(entryRow(keyFor(oauthB))), /不可用.*账号需要重新授权/)
    const unavailable = availableRow(keyFor(oauthD))
    assert.ok(unavailable)
    assert.equal(unavailable.querySelector('button').disabled, true)
    await search('搜索对外模型', 'b/model')
    assert.deepEqual([...host.querySelectorAll('.route-model')].map(node => node.dataset.modelId), [modelB])
    assert.ok(fixture.requests.some(request => request.path.includes('q=b%2Fmodel') || new URL(request.path, 'http://localhost').searchParams.get('q') === modelB))
  })

  it('reorders with accessible controls and saves only identity fields with expected revision', async () => {
    await mount()
    const first = entryButton('down', keyFor(staticA))
    first.focus()
    first.click()
    await settle()
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
    assert.equal(document.activeElement?.getAttribute('aria-label'), '上移 渠道 A / 主密钥')
    button('保存路由').click()
    await settle()
    assert.deepEqual(fixture.writes[0], {
      modelId: modelA,
      expectedRevision: 5,
      entries: [
        { channelId: 'channel-b', credentialType: 'oauth', credentialId: 'account-b' },
        { channelId: 'channel-a', credentialType: 'static', credentialId: 'key-a' },
      ],
    })
    assert.equal('upstreamModel' in fixture.writes[0].entries[0], false)
    assert.equal(text(host.querySelector('.route-save-bar')), '模型路由已保存撤销修改保存路由')
  })

  it('reorders native drag gestures in either direction using current credential identities', async () => {
    await mount()
    availableRow(keyFor(staticC)).querySelector('button').click()
    await settle()
    const data = dragData()
    assert.equal(entryRow(keyFor(oauthB)).draggable, true, 'Stored unavailable entries can still move')
    dragEvent(entryRow(keyFor(staticA)), 'dragstart', data)
    assert.equal(data.effectAllowed, 'move')
    assert.equal(data.getData('text/plain'), keyFor(staticA))
    assert.equal(dragEvent(entryRow(keyFor(staticC)), 'dragover', data).defaultPrevented, true)
    dragEvent(entryRow(keyFor(staticC)), 'drop', data)
    await settle()
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticC), keyFor(staticA)])
    assert.match(text(host.querySelector('.sr-only[aria-live="polite"]')), /主密钥 已移至第 3 位/)
    assert.equal(host.querySelector('.is-dragging, .is-drag-over'), null)

    dragEvent(entryRow(keyFor(staticA)), 'dragstart', data)
    // Change the intervening order before dropping: no captured row index may be used.
    entryButton('down', keyFor(oauthB)).click()
    dragEvent(entryRow(keyFor(oauthB)), 'drop', data)
    await settle()
    assert.deepEqual(entryIds(), [keyFor(staticC), keyFor(staticA), keyFor(oauthB)])
    button('保存路由').click()
    await settle()
    assert.deepEqual(fixture.writes[0].entries.map(keyFor), entryIds())
  })

  it('disables native dragging and ignores drag events while a save is pending', async () => {
    await mount()
    entryButton('down', keyFor(staticA)).click()
    await settle()
    let finishSave
    globalThis.fetch = window.fetch = (input, init = {}) => {
      if (init.method === 'PUT') return new Promise(resolve => { finishSave = () => resolve(fetchMock(input, init)) })
      return fetchMock(input, init)
    }
    button('保存路由').click()
    await nextTick()
    try {
      assert.ok(finishSave)
      assert.ok(entries().every(node => node.draggable === false))
      const data = dragData()
      const start = dragEvent(entryRow(keyFor(staticA)), 'dragstart', data)
      assert.equal(start.defaultPrevented, true)
      dragEvent(entryRow(keyFor(oauthB)), 'dragover', data)
      dragEvent(entryRow(keyFor(oauthB)), 'drop', data)
      await nextTick()
      assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
    } finally {
      finishSave?.()
      await settle()
    }
    assert.ok(entries().every(node => node.draggable === true))
    assert.deepEqual(fixture.writes[0].entries.map(keyFor), [keyFor(oauthB), keyFor(staticA)])
  })

  it('adds selectable credentials, removes entries, and explicitly saves an empty order', async () => {
    await mount()
    availableRow(keyFor(staticC)).querySelector('button').click()
    await settle()
    assert.equal(entryIds().length, 3)
    entryButton('remove', keyFor(staticC)).click()
    await settle()
    assert.equal(entryIds().length, 2)
    for (const item of [...entries()]) item.querySelector('button[data-action="remove"]').click()
    await settle()
    assert.match(text(host.querySelector('.route-empty-order')), /模型将无法接收请求/)
    assert.match(text(host.querySelector('.callout.warning')), /模型将停止接收请求/)
    button('保存路由').click()
    await settle()
    assert.deepEqual(fixture.writes[0], { modelId: modelA, expectedRevision: 5, entries: [] })
    assert.equal(fixture.details.get(modelA).entries.length, 0)
  })

  it('preserves a draft on revision conflict and requires an explicit latest-revision choice', async () => {
    await mount()
    entryButton('down', keyFor(staticA)).click()
    await settle()
    const latest = fixture.details.get(modelA)
    latest.revision = 6
    latest.entries = [clone(staticC), clone(staticA), clone(oauthB)]
    fixture.conflictNextWrite = true
    button('保存路由').click()
    await settle()
    assert.match(text(host.querySelector('.route-conflict')), /本次未保存/)
    assert.match(text(host.querySelector('.route-conflict')), /服务器版本 6/)
    assert.match(text(host.querySelector('.route-conflict-order')), /备用密钥/)
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
    assert.equal(button('保存路由').disabled, true)
    assert.equal(fixture.writes.length, 1)
    button('已核对，保留草稿并采用版本 6').click()
    await settle()
    assert.equal(host.querySelector('.route-conflict'), null)
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
    assert.equal(button('保存路由').disabled, false)
    button('保存路由').click()
    await settle()
    assert.equal(fixture.writes.length, 2)
    assert.equal(fixture.writes[1].expectedRevision, 6)
    assert.deepEqual(fixture.writes[1].entries, fixture.writes[0].entries)
    assert.equal(fixture.details.get(modelA).revision, 7)
  })

  it('keeps unconfigured routes empty and requires explicit credential selection', async () => {
    await mount()
    host.querySelector(`[data-model-id="${modelB}"]`).click()
    await settle()
    assert.deepEqual(entryIds(), [])
    assert.match(text(host.querySelector('.route-empty-order')), /待配置，请添加凭据/)
    assert.equal(fixture.writes.length, 0)
    availableRow(keyFor(staticC)).querySelector('button').click()
    await settle()
    button('保存路由').click()
    await settle()
    assert.deepEqual(fixture.writes[0], { modelId: modelB, expectedRevision: 0, entries: [{ channelId: staticC.channelId, credentialType: staticC.credentialType, credentialId: staticC.credentialId }] })
    assert.equal(fixture.details.get(modelB).configured, true)
  })

  it('explicitly saves an empty route for an unconfigured model without selecting default credentials', async () => {
    await mount()
    host.querySelector(`[data-model-id="${modelB}"]`).click()
    await settle()
    assert.deepEqual(entryIds(), [])
    assert.equal(fixture.writes.length, 0)
    assert.equal(button('保存路由').disabled, false)
    assert.match(text(host.querySelector('.callout.warning')), /模型将停止接收请求/)
    button('保存路由').click()
    await settle()
    assert.deepEqual(fixture.writes[0], { modelId: modelB, expectedRevision: 0, entries: [] })
    assert.equal(fixture.details.get(modelB).configured, true)
    assert.deepEqual(entryIds(), [])
    assert.equal(button('保存路由').disabled, true)
    assert.match(text(host.querySelector('.route-empty-order')), /尚未选择路由凭据/)
  })

  it('loads the latest saved order only when the administrator explicitly discards the draft', async () => {
    await mount()
    entryButton('down', keyFor(staticA)).click()
    await settle()
    const latest = fixture.details.get(modelA)
    latest.revision = 6
    latest.entries = [clone(staticC), clone(staticA)]
    fixture.conflictNextWrite = true
    button('保存路由').click()
    await settle()
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
    button('载入最新顺序，丢弃草稿').click()
    await settle()
    assert.deepEqual(entryIds(), [keyFor(staticC), keyFor(staticA)])
    assert.equal(host.querySelector('.route-conflict'), null)
    assert.equal(button('保存路由').disabled, true)
    assert.equal(fixture.writes.length, 1, 'Conflict recovery never silently resubmits')
  })

  it('retains the draft after a save failure and retries against the same known revision', async () => {
    await mount()
    entryButton('down', keyFor(staticA)).click()
    await settle()
    fixture.failNextWrite = true
    button('保存路由').click()
    await settle()
    assert.match(text(host.querySelector('[role="alert"]')), /合成保存失败.*草稿已保留/)
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticA)])
    assert.equal(button('保存路由').disabled, false)
    button('保存路由').click()
    await settle()
    assert.equal(fixture.writes.length, 2)
    assert.deepEqual(fixture.writes[1], fixture.writes[0])
  })

  it('supports double-click position editing with auto-shift, top button, and selectable-only filter', async () => {
    await mount()
    availableRow(keyFor(staticC)).querySelector('button').click()
    await settle()
    assert.deepEqual(entryIds(), [keyFor(staticA), keyFor(oauthB), keyFor(staticC)])

    // Double-click position 3 -> input 1 -> Enter moves staticC to #1 and shifts others down
    const posBadge = entryRow(keyFor(staticC)).querySelector('.route-position')
    posBadge.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    await settle()
    const posInput = entryRow(keyFor(staticC)).querySelector('input[data-action="position-input"]')
    assert.ok(posInput, '双击序号后应显示位次输入框')
    posInput.value = '1'
    posInput.dispatchEvent(new Event('input', { bubbles: true }))
    posInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await settle()
    assert.deepEqual(entryIds(), [keyFor(staticC), keyFor(staticA), keyFor(oauthB)])
    assert.equal(text(entryRow(keyFor(staticC)).querySelector('.route-position')), '1')
    assert.equal(text(entryRow(keyFor(staticA)).querySelector('.route-position')), '2')
    assert.equal(text(entryRow(keyFor(oauthB)).querySelector('.route-position')), '3')

    // Escape cancels editing without changing order
    entryRow(keyFor(oauthB)).querySelector('.route-position').dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    await settle()
    const cancelInput = entryRow(keyFor(oauthB)).querySelector('input[data-action="position-input"]')
    cancelInput.value = '1'
    cancelInput.dispatchEvent(new Event('input', { bubbles: true }))
    cancelInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await settle()
    assert.deepEqual(entryIds(), [keyFor(staticC), keyFor(staticA), keyFor(oauthB)])

    // Top button moves oauthB to #1
    entryButton('top', keyFor(oauthB)).click()
    await settle()
    assert.deepEqual(entryIds(), [keyFor(oauthB), keyFor(staticC), keyFor(staticA)])

    // Selectable-only checkbox hides unavailable oauthD from available list
    assert.ok(availableRow(keyFor(oauthD)))
    const filterCheckbox = host.querySelector('.route-available-filter input[type="checkbox"]')
    assert.ok(filterCheckbox)
    filterCheckbox.checked = true
    filterCheckbox.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
    assert.equal(availableRow(keyFor(oauthD)), undefined)

    // Narrow/mobile tabs toggle between selected pane and add pane
    const tabs = [...host.querySelectorAll('.route-mobile-tab')]
    assert.equal(tabs.length, 2)
    assert.equal(host.querySelector('.route-selected-pane').classList.contains('is-mobile-hidden'), false)
    assert.equal(host.querySelector('.route-add-section').classList.contains('is-mobile-hidden'), true)
    tabs[1].click()
    await settle()
    assert.equal(host.querySelector('.route-selected-pane').classList.contains('is-mobile-hidden'), true)
    assert.equal(host.querySelector('.route-add-section').classList.contains('is-mobile-hidden'), false)
  })
})

import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Node', 'NodeFilter', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLIFrameElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent', 'CustomEvent']) globalThis[name] = dom.window[name]
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}
const { compile, createApp, nextTick, ssrContextKey } = await import('vue')
const headless = await import('@headlessui/vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const originalFetch = globalThis.fetch
const realSetTimeout = window.setTimeout.bind(window)
const realClearTimeout = window.clearTimeout.bind(window)
const events = ['channel.unhealthy', 'channel.recovered', 'key.disabled', 'key.recovered', 'attempt.consecutive_failures', 'attempt.recovered']
const fixtureTarget = (id = 'target-one', extra = {}) => ({
  id, name: id === 'target-one' ? '值班群' : '飞书备用', type: 'webhook', enabled: true,
  events: [...events], channelIds: [], revision: 1, updatedAt: 1,
  templateTitle: null, templateBody: null,
  credentials: {
    urlConfigured: true, bearerTokenConfigured: false, signingSecretConfigured: false,
    urlUnreadable: false, bearerTokenUnreadable: false, signingSecretUnreadable: false,
  },
  latestDelivery: null,
  ...extra,
})
const fixtureDelivery = (index = 1, extra = {}) => ({
  id: 'delivery-' + index, eventId: 'event-' + index, targetId: 'target-one', targetName: '值班群',
  eventType: 'notification.test', status: 'pending', attempts: 0, nextAttemptAt: 1_800_000_000_000,
  createdAt: 1_800_000_000_000, updatedAt: 1_800_000_000_000, lastStatusCode: null, errorCode: null, ...extra,
})
const response = (data, status = 200) => new Response(JSON.stringify(data), { status })
const failed = message => response({ error: { code: 'fixture_failure', message } }, 400)

async function settle() { await nextTick(); await delay(15); await nextTick() }
function button(text, scope = document) { return [...scope.querySelectorAll('button')].find(node => node.textContent.trim() === text) }
function targetRow(id = 'target-one') { return document.querySelector('[data-target-id="' + id + '"]') }
async function input(element, value) { assert.ok(element); element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })); await settle() }
async function changeSelect(element, value) { element.value = value; element.dispatchEvent(new Event('change', { bubbles: true })); await settle() }
function fieldInput(label, scope = document) { return [...scope.querySelectorAll('label.field')].find(node => node.querySelector('span')?.textContent === label)?.querySelector('input,select,textarea') }

describe('notification settings modal interactions', () => {
  let server, modal, systemView, app, state, editorState, calls, savedSettings, savedTargets, historyItems, handler, emitted
  let nextTimerId = 1_000_000
  const timers = new Map()

  async function component(name, children = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: { ...headless, ...children },
      setup(props, context) {
        const result = { ...loaded.setup(props, context), ...children }
        if (name === 'NotificationSettingsModal') state = result
        if (name === 'NotificationTargetEditor') editorState = result
        return result
      },
    }
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const icon = await component('Icon')
    const modalBase = await component('Modal', { Icon: icon })
    const secret = await component('SecretInput', { Icon: icon })
    const editor = await component('NotificationTargetEditor', { Icon: icon, SecretInput: secret })
    modal = await component('NotificationSettingsModal', { Icon: icon, Modal: modalBase, NotificationTargetEditor: editor })
    systemView = await component('SystemSettingsView', {
      Icon: icon, NotificationSettingsModal: modal,
      ModelTestSettingsModal: { render: () => null },
      UpstreamRequestSettingsModal: { render: () => null },
      WebSearchSettingsModal: { render: () => null },
      KeyAutoDisableSettingsModal: { render: () => null },
      RetentionSettingsModal: { render: () => null },
    })
  })

  beforeEach(() => {
    calls = []; emitted = []; historyItems = []; handler = null; state = null; editorState = null
    savedSettings = { enabled: false, consecutiveFailureThreshold: 5, updatedAt: 0 }
    savedTargets = [fixtureTarget(), fixtureTarget('target-two', { type: 'feishu', enabled: false, credentials: { urlConfigured: false, bearerTokenConfigured: false, signingSecretConfigured: false, urlUnreadable: false, bearerTokenUnreadable: false, signingSecretUnreadable: false } })]
    window.confirm = () => true
    window.setTimeout = (callback, ms, ...args) => {
      if (ms !== 1200 && ms !== 30_000) return realSetTimeout(callback, ms, ...args)
      const id = nextTimerId++; timers.set(id, { callback, ms }); return id
    }
    window.clearTimeout = id => { if (!timers.delete(id)) realClearTimeout(id) }
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method || 'GET'
      assert.ok(String(url).startsWith('/admin/api/'), 'Mocks must never send an external notification')
      const call = { url: String(url), method, body: init.body ? JSON.parse(init.body) : null, signal: init.signal }
      calls.push(call)
      const custom = handler ? await handler(call) : undefined
      if (custom) return custom
      const parsed = new URL(String(url), 'http://localhost')
      const path = parsed.pathname.replace('/admin/api', '')
      if (path === '/notification-settings') {
        if (method === 'PUT') savedSettings = { ...call.body, updatedAt: 2 }
        return response(savedSettings)
      }
      if (path === '/notification-targets') {
        if (method === 'POST') {
          const written = call.body.credentials || {}
          const target = fixtureTarget('target-created', {
            ...call.body,
            credentials: {
              urlConfigured: Boolean(written.url),
              bearerTokenConfigured: Boolean(written.bearerToken),
              signingSecretConfigured: Boolean(written.signingSecret),
              urlUnreadable: false, bearerTokenUnreadable: false, signingSecretUnreadable: false,
            },
          })
          savedTargets.push(target)
          return response(target, 201)
        }
        return response(savedTargets)
      }
      if (path.startsWith('/notification-targets/')) {
        const id = decodeURIComponent(path.split('/')[2])
        if (path.endsWith('/test')) return response({ deliveryId: 'delivery-1', eventId: 'event-1' }, 202)
        if (method === 'DELETE') { savedTargets = savedTargets.filter(target => target.id !== id); return new Response(null, { status: 204 }) }
        if (method === 'PUT') {
          const previous = savedTargets.find(target => target.id === id)
          const written = call.body.credentials || {}
          const target = {
            ...previous, ...call.body, revision: 2, updatedAt: 2,
            credentials: {
              ...previous.credentials,
              urlConfigured: previous.credentials.urlConfigured || Boolean(written.url),
              bearerTokenConfigured: previous.credentials.bearerTokenConfigured || Boolean(written.bearerToken),
              signingSecretConfigured: previous.credentials.signingSecretConfigured || Boolean(written.signingSecret),
            },
          }
          savedTargets = savedTargets.map(item => item.id === id ? target : item)
          return response(target)
        }
      }
      if (path === '/notification-deliveries') {
        const page = Number(parsed.searchParams.get('page'))
        const size = Number(parsed.searchParams.get('pageSize'))
        const items = historyItems.filter(item => item.targetId === parsed.searchParams.get('targetId'))
        return response({ items: items.slice((page - 1) * size, page * size), total: items.length, page, pageSize: size })
      }
      if (path === '/channels') return response([{ id: 'channel-one', name: '渠道一', enabled: true, credential: 'FIXTURE_SECRET_MUST_NOT_RENDER' }, { id: 'channel-two', name: '渠道二', enabled: false }])
      if (path === '/model-test-settings') return response({ defaultPrompt: 'test', updatedAt: 0 })
      if (path === '/upstream-request-settings') return response({ profiles: [], overrides: { headers: {}, body: {}, protocols: {} }, updatedAt: 0, headerCatalog: {} })
      if (path === '/web-search-settings') return response({ enabled: false, provider: 'tavily', apiKeySet: false, source: 'settings', updatedAt: 0 })
      if (path === '/key-auto-disable-settings') return response({ enabled: false, rules: [], updatedAt: 0 })
      if (path === '/request-log-retention') return response({ cleanupEnabled: false })
      throw new Error('Unexpected fixture request: ' + method + ' ' + url)
    }
  })

  afterEach(async () => {
    app?.unmount(); app = null
    await settle()
    window.setTimeout = realSetTimeout
    window.clearTimeout = realClearTimeout
    assert.equal(timers.size, 0, 'Polling and deadline timers must be cleared')
    document.body.innerHTML = ''
    globalThis.fetch = originalFetch
  })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount(view = modal) {
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(view, { onSaved: () => emitted.push('saved'), onClose: () => emitted.push('close') })
    app.provide(ssrContextKey, { modules: new Set() }); app.mount(host); await settle()
  }
  async function tickPoll() {
    const item = [...timers].find(([, timer]) => timer.ms === 1200)
    assert.ok(item, 'Expected one scheduled delivery poll')
    timers.delete(item[0]); item[1].callback(); await settle()
  }
  const writes = () => calls.filter(call => call.method !== 'GET')

  it('loads default settings and multiple targets with credential flags and disabled tests', async () => {
    await mount()
    assert.equal(document.querySelector('.notification-global-form input[type="checkbox"]').checked, false)
    assert.equal(document.querySelector('input[type="number"]').value, '5')
    assert.equal(document.querySelectorAll('.notification-target-row').length, 2)
    assert.match(targetRow('target-two').textContent, /飞书机器人.*已停用/s)
    assert.match(targetRow('target-two').textContent, /URL 未配置（必需）/)
    assert.equal(button('发送测试', targetRow()).disabled, true)
    assert.equal(writes().length, 0)
    assert.ok(document.querySelector('.modal.mobile-fullscreen'))
    assert.equal(calls.some(call => call.url.includes('client-keys')), false)
  })

  it('validates integer thresholds and saves only global settings', async () => {
    await mount()
    const threshold = document.querySelector('input[type="number"]')
    for (const value of ['1.5', '0', '101', '']) {
      await input(threshold, value)
      assert.equal(button('保存全局设置').disabled, true)
      assert.match(document.querySelector('.notification-global-actions').textContent, /整数/)
    }
    await input(threshold, '10')
    document.querySelector('.notification-global-form input[type="checkbox"]').click(); await settle()
    button('保存全局设置').click(); await settle()
    assert.deepEqual(writes().map(call => [call.method, call.url, call.body]), [['PUT', '/admin/api/notification-settings', { enabled: true, consecutiveFailureThreshold: 10 }]])
    assert.deepEqual(emitted, ['saved'])
    assert.equal(button('发送测试', targetRow()).disabled, false)
  })

  it('creates a disabled env-less target, preserves explicit events and channels, and never tests on save', async () => {
    await mount()
    button('添加目标').click(); await settle()
    const editor = document.querySelector('.notification-target-editor')
    await input(fieldInput('目标名称', editor), '  新值班群  ')
    await changeSelect(fieldInput('目标类型', editor), 'feishu')
    await input(fieldInput('卡片标题', editor), '【{{severity}}】{{channel}}')
    await input(fieldInput('卡片正文', editor), '**渠道** {{channel}}\n原因：{{reason}}')
    editor.querySelector('.switch-row input').click()
    for (const event of [...editor.querySelectorAll('.notification-event-grid input')].slice(1)) { event.click(); await settle() }
    editor.querySelector('.notification-all-channels input').click(); await settle()
    editor.querySelector('.notification-channel-list input').click(); await settle()
    const create = button('创建目标', editor)
    assert.equal(create.disabled, false)
    create.click(); create.click(); await settle()
    assert.equal(writes().length, 1)
    assert.deepEqual(writes()[0].body, {
      name: '新值班群', type: 'feishu', enabled: false, events: ['channel.unhealthy'], channelIds: ['channel-one'],
      templateTitle: '【{{severity}}】{{channel}}', templateBody: '**渠道** {{channel}}\n原因：{{reason}}',
    })
    assert.equal(Object.hasOwn(writes()[0].body, 'revision'), false)
    assert.equal(document.querySelector('.notification-target-editor'), null)
    assert.equal(document.querySelectorAll('.notification-target-row').length, 3)
    assert.equal(calls.some(call => call.url.endsWith('/test')), false)
    assert.equal(document.body.innerHTML.includes('FIXTURE_SECRET_MUST_NOT_RENDER'), false)
  })

  it('edits a target and submits only supplied credentials without echoing stored values', async () => {
    await mount()
    button('编辑', targetRow()).click(); await settle()
    const editor = document.querySelector('.notification-target-editor')
    assert.match(editor.textContent, /已保存，留空保持不变/)
    assert.equal(fieldInput('接收端 URL', editor).value, '')
    const secrets = [...editor.querySelectorAll('.notification-credential-row input[type="password"]')]
    assert.equal(secrets.length, 2)
    assert.equal(secrets[0].value, '')
    assert.equal(secrets[1].value, '')
    await input(fieldInput('目标名称', editor), '修改后的值班群')
    await input(fieldInput('接收端 URL', editor), 'https://fixture.invalid/never-send')
    button('保存目标', editor).click(); await settle()
    assert.equal(writes().length, 1)
    assert.equal(writes()[0].method, 'PUT')
    assert.equal(writes()[0].url, '/admin/api/notification-targets/target-one')
    assert.equal(writes()[0].body.name, '修改后的值班群')
    assert.deepEqual(writes()[0].body.credentials, { url: 'https://fixture.invalid/never-send' })
    assert.equal(writes()[0].body.revision, fixtureTarget().revision)
    assert.match(targetRow().textContent, /修改后的值班群/)
    assert.equal(calls.some(call => call.url.endsWith('/test')), false)
  })

  it('clears a stored credential only after the clear flag is checked', async () => {
    await mount()
    button('编辑', targetRow()).click(); await settle()
    const editor = document.querySelector('.notification-target-editor')
    const urlInput = fieldInput('接收端 URL', editor)
    const clearUrl = editor.querySelectorAll('.notification-clear input')[0]
    assert.equal(clearUrl.disabled, false)
    await input(urlInput, 'https://fixture.invalid/temporary')
    assert.equal(clearUrl.disabled, true)
    await input(urlInput, '')
    clearUrl.click(); await settle()
    assert.equal(urlInput.disabled, true)
    button('保存目标', editor).click(); await settle()
    assert.deepEqual(writes()[0].body.credentials, { clearUrl: true })
  })

  it('shows unreadable stored credentials and blocks test delivery until they are re-entered', async () => {
    savedSettings.enabled = true
    savedTargets[0].credentials = { ...savedTargets[0].credentials, urlConfigured: false, urlUnreadable: true }
    await mount()
    assert.match(targetRow().textContent, /URL 无法解密，请重填/)
    assert.match(targetRow().textContent, /目标凭据无法解密，请在编辑器中重新填写/)
    assert.equal(button('发送测试', targetRow()).disabled, true)
  })

  it('preserves unknown channel selections through load failure and permits explicit removal after retry', async () => {
    savedTargets[0].channelIds = ['channel-one', 'deleted-channel']
    handler = call => call.url === '/admin/api/channels' ? failed('目录不可用') : undefined
    await mount()
    button('编辑', targetRow()).click(); await settle()
    assert.deepEqual([...editorState.draft.channelIds], ['channel-one', 'deleted-channel'])
    assert.equal(button('保存目标').disabled, true)
    assert.match(document.querySelector('.notification-target-editor').textContent, /已有渠道选择已保留/)
    handler = null
    button('重新加载渠道').click(); await settle()
    const stale = document.querySelector('.notification-channel-choice.is-stale')
    assert.match(stale.textContent, /deleted-channel/)
    assert.equal(stale.querySelector('input').checked, true)
    stale.querySelector('input').click(); await settle()
    assert.equal(button('保存目标').disabled, false)
    button('保存目标').click(); await settle()
    assert.deepEqual(writes()[0].body.channelIds, ['channel-one'])
  })

  it('confirms deletion and clears the deleted target history', async () => {
    await mount()
    button('投递记录', targetRow()).click(); await settle()
    window.confirm = () => false
    button('删除', targetRow()).click(); await settle()
    assert.equal(writes().length, 0)
    window.confirm = () => true
    button('删除', targetRow()).click(); await settle()
    assert.deepEqual(writes().map(call => [call.method, call.url]), [['DELETE', '/admin/api/notification-targets/target-one']])
    assert.equal(targetRow(), null)
    assert.equal(document.querySelector('.notification-history'), null)
  })

  it('paginates a target history and preserves the previous page through errors and retries', async () => {
    historyItems = Array.from({ length: 21 }, (_, index) => fixtureDelivery(index + 1, { status: 'failed', attempts: 3, lastStatusCode: 503, errorCode: 'upstream_error' }))
    await mount()
    button('投递记录', targetRow()).click(); await settle()
    assert.equal(document.querySelectorAll('.notification-delivery-row').length, 20)
    assert.match(document.querySelector('.notification-history').textContent, /HTTP 503.*upstream_error/s)
    handler = call => call.url.includes('notification-deliveries') && new URL(call.url, 'http://localhost').searchParams.get('page') === '2' ? failed('历史读取失败') : undefined
    button('下一页').click(); await settle()
    assert.equal(document.querySelectorAll('.notification-delivery-row').length, 20)
    assert.match(document.querySelector('.notification-history').textContent, /历史读取失败/)
    handler = null
    button('重试读取记录').click(); await settle()
    assert.equal(document.querySelectorAll('.notification-delivery-row').length, 1)
    assert.match(document.querySelector('.notification-history-pagination').textContent, /第 2 页 · 共 21 条/)
    const query = new URL(calls.filter(call => call.url.includes('notification-deliveries')).at(-1).url, 'http://localhost').searchParams
    assert.equal(query.get('targetId'), 'target-one'); assert.equal(query.get('pageSize'), '20'); assert.equal(query.get('page'), '2')
    assert.equal(button('下一页').disabled, true)
  })

  it('ignores an older target history response after switching targets', async () => {
    let finishOld
    handler = call => call.url.includes('notification-deliveries') && call.url.includes('target-one') ? new Promise(resolve => { finishOld = resolve }) : undefined
    await mount()
    button('投递记录', targetRow()).click(); await settle()
    const oldCall = calls.filter(call => call.url.includes('notification-deliveries')).at(-1)
    button('投递记录', targetRow('target-two')).click(); await settle()
    assert.equal(oldCall.signal.aborted, true)
    finishOld(response({ items: [fixtureDelivery()], total: 1, page: 1, pageSize: 20 })); await settle()
    assert.match(document.querySelector('.notification-history').textContent, /飞书备用 的投递记录/)
    assert.equal(document.querySelectorAll('.notification-delivery-row').length, 0)
  })

  it('enqueues one test for a double click and shows terminal delivery failure', async () => {
    savedSettings.enabled = true
    historyItems = [fixtureDelivery()]
    await mount()
    const test = button('发送测试', targetRow())
    test.click(); test.click(); await settle()
    assert.equal(calls.filter(call => call.url.endsWith('/test')).length, 1)
    assert.match(document.querySelector('.notification-test-result').textContent, /delivery-1.*event-1/s)
    await tickPoll()
    assert.equal(state.testingTargetId.value, 'target-one')
    historyItems = [fixtureDelivery(1, { status: 'failed', attempts: 2, lastStatusCode: 502, errorCode: 'delivery_failed' })]
    await tickPoll()
    assert.equal(state.testingTargetId.value, null)
    assert.match(document.querySelector('.notification-test-result').textContent, /投递失败.*2 次尝试.*HTTP 502.*delivery_failed/s)
    assert.equal([...timers.values()].filter(timer => timer.ms === 1200).length, 0)
    assert.equal(document.querySelector('.notification-status').dataset.status, 'failed')
  })

  it('keeps a polled terminal delivery when an older pending history response arrives later', async () => {
    savedSettings.enabled = true
    const pending = fixtureDelivery(1, { updatedAt: 1_800_000_000_002 })
    const succeeded = fixtureDelivery(1, { status: 'succeeded', attempts: 1, updatedAt: 1_800_000_000_002 })
    let finishHistory
    let reads = 0
    handler = call => {
      if (!call.url.includes('notification-deliveries')) return undefined
      reads += 1
      if (reads === 1) return new Promise(resolve => { finishHistory = resolve })
      return response({ items: [succeeded], total: 1, page: 1, pageSize: 20 })
    }
    await mount()
    button('发送测试', targetRow()).click(); await settle()
    assert.equal(typeof finishHistory, 'function')
    await tickPoll()
    assert.equal(state.testDelivery.value.status, 'succeeded')
    finishHistory(response({ items: [pending], total: 1, page: 1, pageSize: 20 }))
    await settle()
    assert.equal(state.testDelivery.value.status, 'succeeded')
    assert.match(document.querySelector('.notification-test-result').textContent, /测试通知投递成功/)
    assert.equal(document.querySelector('.notification-status').dataset.status, 'succeeded')
    assert.match(targetRow().querySelector('.notification-latest-delivery').textContent, /投递成功/)
  })

  it('bounds automatic polling and leaves the queued event available for manual refresh', async () => {
    savedSettings.enabled = true
    historyItems = [fixtureDelivery()]
    await mount()
    button('发送测试', targetRow()).click(); await settle()
    for (let attempt = 0; attempt < 10; attempt += 1) await tickPoll()
    assert.equal(state.testingTargetId.value, null)
    assert.match(document.querySelector('.notification-test-result').textContent, /已停止自动查询/)
    assert.equal(calls.filter(call => call.url.includes('notification-deliveries')).length, 11)
    assert.equal(timers.size, 0)
  })

  it('aborts test and history requests and stops future polls when unmounted', async () => {
    savedSettings.enabled = true
    let pollCall
    let reads = 0
    handler = call => {
      if (!call.url.includes('notification-deliveries')) return undefined
      reads += 1
      if (reads === 1) return response({ items: [], total: 0, page: 1, pageSize: 20 })
      pollCall = call
      return new Promise((_, reject) => call.signal.addEventListener('abort', () => reject(new Error('fixture abort')), { once: true }))
    }
    await mount()
    button('发送测试', targetRow()).click(); await settle()
    await tickPoll()
    assert.ok(pollCall)
    app.unmount(); app = null; await settle()
    assert.equal(pollCall.signal.aborted, true)
    assert.equal(calls.find(call => call.url.endsWith('/test')).signal.aborted, true)
    assert.equal(timers.size, 0)
    assert.equal(reads, 2)
  })

  it('reports a timed-out enqueue as uncertain instead of silently sending another test', async () => {
    savedSettings.enabled = true
    handler = call => call.url.endsWith('/test') ? new Promise((_, reject) => call.signal.addEventListener('abort', () => reject(new Error('fixture abort')), { once: true })) : undefined
    await mount()
    button('发送测试', targetRow()).click(); await settle()
    const deadline = [...timers].find(([, timer]) => timer.ms === 30_000)
    assert.ok(deadline); timers.delete(deadline[0]); deadline[1].callback(); await settle()
    assert.match(document.querySelector('.notification-test-result').textContent, /入队结果未知/)
    assert.equal(calls.filter(call => call.url.endsWith('/test')).length, 1)
  })

  it('keeps the credential draft when a concurrent target update conflicts', async () => {
    handler = call => call.method === 'PUT'
      ? response({ error: { code: 'notification_target_changed', message: '通知目标已变化，请刷新后重试' } }, 409)
      : undefined
    await mount()
    button('编辑', targetRow()).click(); await settle()
    const editor = document.querySelector('.notification-target-editor')
    await input(fieldInput('目标名称', editor), '冲突草稿')
    await input(fieldInput('接收端 URL', editor), 'https://fixture.invalid/conflict')
    button('保存目标', editor).click(); await settle()
    assert.equal(document.querySelector('.notification-target-editor'), editor)
    assert.equal(fieldInput('目标名称', editor).value, '冲突草稿')
    assert.equal(fieldInput('接收端 URL', editor).value, 'https://fixture.invalid/conflict')
    assert.match(editor.textContent, /已变化，请刷新后重试/)
    assert.equal(writes().length, 1)
  })

  it('opens notifications from system settings and refreshes the saved row state', async () => {
    await mount(systemView)
    const row = [...document.querySelectorAll('.system-config-setting')].find(item => item.textContent.includes('运行通知'))
    assert.ok(row); assert.match(row.textContent, /已关闭.*5 次连续失败阈值/s)
    button('编辑', row).click(); await settle()
    document.querySelector('.notification-global-form input[type="checkbox"]').click(); await settle()
    button('保存全局设置').click(); await settle()
    assert.match(row.textContent, /已启用.*5 次连续失败阈值/s)
  })
})

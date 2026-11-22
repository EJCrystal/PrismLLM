import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true })
for (const key of ['window', 'document', 'navigator', 'HTMLElement', 'Element', 'Node', 'SVGElement', 'Event', 'CustomEvent', 'MutationObserver']) {
  Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true })
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}
const { compile, createApp, nextTick, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const headless = await import('@headlessui/vue')
const originalFetch = globalThis.fetch
const response = (body, status = 200) => new Response(JSON.stringify(body), { status })
const rule = extra => ({ id: 'rule-one', name: '编码助手', enabled: true, scope: 'global', protocols: [], channelIds: [], priority: 0, action: 'replace', prompt: 'original prompt', ...extra })
const button = text => [...document.querySelectorAll('button')].find(element => element.textContent.trim() === text)
const field = text => [...document.querySelectorAll('label.field')].find(element => element.querySelector('span')?.textContent === text)?.querySelector('input,select,textarea')
async function settle() { await nextTick(); await delay(25); await nextTick() }
async function input(element, value, event = 'input') {
  assert.ok(element)
  element.value = value
  element.dispatchEvent(new Event(event, { bubbles: true }))
  await settle()
}
async function check(element) { assert.ok(element); element.click(); await settle() }
async function submit() {
  document.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
}

describe('系统提示词配置弹窗', () => {
  let server, modal, systemView, app, configuration, emitted, calls, failLoad, failSave

  async function component(name, children = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: { ...headless, ...children },
      setup(props, context) { return { ...loaded.setup(props, context), ...children } },
    }
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const icon = await component('Icon')
    const modalBase = await component('Modal', { Icon: icon })
    modal = await component('SystemPromptSettingsModal', { Modal: modalBase })
    systemView = await component('SystemSettingsView', {
      Icon: icon, SystemPromptSettingsModal: modal,
      ModelTestSettingsModal: { render: () => null },
      UpstreamRequestSettingsModal: { render: () => null },
      WebSearchSettingsModal: { render: () => null },
      KeyAutoDisableSettingsModal: { render: () => null },
      NotificationSettingsModal: { render: () => null },
      RetentionSettingsModal: { render: () => null },
    })
  })

  beforeEach(() => {
    configuration = { enabled: false, rules: [], updatedAt: 0 }
    calls = []; emitted = []; failLoad = false; failSave = false
    window.confirm = () => true
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      assert.ok(String(url).startsWith('/admin/api/'))
      if (String(url).endsWith('/channels')) return response([{ id: 'channel-one', name: '渠道一' }, { id: 'channel-two', name: '渠道二' }])
      const path = String(url).replace('/admin/api', '')
      const fixtures = {
        '/model-test-settings': { defaultPrompt: 'test', updatedAt: 0 },
        '/upstream-request-settings': { overrides: { headers: {}, body: {}, protocols: {} }, profiles: [], updatedAt: 0, headerCatalog: { lastError: null, profiles: [], checkedAt: 0, refreshing: false, autoRefreshEnabled: false, pins: {} } },
        '/web-search-settings': { enabled: false, provider: 'tavily', apiKeySource: 'none', source: 'settings', maxResults: 3, maxRounds: 2, updatedAt: 0 },
        '/key-auto-disable-settings': { enabled: false, rules: [], updatedAt: 0 },
        '/notification-settings': { enabled: false, consecutiveFailureThreshold: 5, updatedAt: 0 },
        '/request-log-retention': { cleanupEnabled: false, updatedAt: 0 },
      }
      if (path in fixtures) return response(fixtures[path])
      assert.ok(String(url).endsWith('/system-prompt-settings'))
      if (method === 'GET') return failLoad ? response({ error: { message: '加载失败测试' } }, 500) : response(configuration)
      const payload = JSON.parse(init.body)
      calls.push(payload)
      if (failSave) return response({ error: { message: '保存失败测试' } }, 400)
      configuration = { ...payload, updatedAt: 2 }
      return response(configuration)
    }
  })

  afterEach(async () => { app?.unmount(); app = null; await settle(); document.body.innerHTML = '' })
  after(async () => { globalThis.fetch = originalFetch; await server.close(); dom.window.close() })

  async function mount(view = modal) {
    const root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(view, { onSaved: () => emitted.push('saved'), onClose: () => emitted.push('close') })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(root)
    await settle()
  }

  it('从关闭状态编辑规则并保存多个协议和多个渠道，正文原样提交', async () => {
    await mount()
    assert.match(document.body.textContent, /当前已关闭/)
    await check(document.querySelector('form > label input'))
    button('添加规则').click(); await settle()
    await input(field('规则名称'), 'Antigravity 助手')
    await input(field('作用域'), 'channel_protocols', 'change')
    await check(document.querySelector('input[value="gemini"]'))
    await check(document.querySelector('input[value="messages"]'))
    await check(document.querySelector('input[value="channel-one"]'))
    await check(document.querySelector('input[value="channel-two"]'))
    await input(field('系统提示词'), '  exact prompt\n中文正文  ')
    await submit()
    assert.equal(calls.length, 1)
    assert.equal(calls[0].enabled, true)
    assert.deepEqual(calls[0].rules[0].protocols, ['gemini', 'messages'])
    assert.deepEqual(calls[0].rules[0].channelIds, ['channel-one', 'channel-two'])
    assert.equal(calls[0].rules[0].prompt, '  exact prompt\n中文正文  ')
    assert.deepEqual(emitted, ['saved', 'close'])
  })

  it('保存失败保留草稿并允许重试，不覆盖规则可以没有正文', async () => {
    configuration = { enabled: true, rules: [rule({ prompt: '' })], updatedAt: 1 }
    await mount()
    await submit()
    assert.equal(calls.length, 0)
    assert.match(document.body.textContent, /系统提示词不能为空/)
    await input(field('应用方式'), 'preserve', 'change')
    failSave = true
    await submit()
    assert.match(document.body.textContent, /保存失败测试/)
    assert.deepEqual(emitted, [])
    assert.equal(field('规则名称').value, '编码助手')
    assert.equal(field('应用方式').value, 'preserve')
    failSave = false
    await submit()
    assert.equal(configuration.rules[0].action, 'preserve')
    assert.deepEqual(emitted, ['saved', 'close'])
  })

  it('已删除渠道不会变成全局规则，切换作用域清理目标后可保存', async () => {
    configuration = { enabled: true, rules: [rule({ scope: 'channels', channelIds: ['deleted-channel'] })], updatedAt: 1 }
    await mount()
    assert.match(document.body.textContent, /已删除渠道/)
    await submit()
    assert.equal(calls.length, 0)
    await input(field('作用域'), 'global', 'change')
    await submit()
    assert.deepEqual(configuration.rules[0].channelIds, [])
    assert.equal(configuration.rules[0].scope, 'global')
  })

  it('加载失败支持重新加载，删除与列表顺序仅在保存后提交', async () => {
    failLoad = true
    await mount()
    assert.match(document.body.textContent, /加载失败测试/)
    failLoad = false
    configuration.rules = [rule(), rule({ id: 'rule-two', name: '另一条规则' })]
    button('重新加载').click(); await settle()
    button('下移').click(); await settle()
    await submit()
    assert.equal(configuration.rules[0].id, 'rule-two')
    button('删除规则').click(); await settle()
    await submit()
    assert.equal(configuration.rules.length, 1)
  })

  it('系统配置集中展示状态，保存后刷新摘要且不暴露提示词正文', async () => {
    configuration = { enabled: false, rules: [rule({ prompt: 'PRIVATE_PROMPT_SHOULD_NOT_APPEAR_IN_OVERVIEW' })], updatedAt: 1 }
    await mount(systemView)
    for (let attempt = 0; attempt < 30 && document.body.textContent.includes('加载中…'); attempt++) await settle()
    const row = [...document.querySelectorAll('.system-config-setting')].find(element => element.querySelector('h3')?.textContent === '系统提示词')
    assert.ok(row)
    assert.match(row.textContent, /已关闭 · 1 条规则/)
    assert.ok(!document.body.textContent.includes('PRIVATE_PROMPT_SHOULD_NOT_APPEAR_IN_OVERVIEW'))
    row.querySelector('button').click(); await settle()
    await check(document.querySelector('form > label input'))
    await submit()
    for (let attempt = 0; attempt < 30 && document.querySelector('form'); attempt++) await settle()
    await settle()
    assert.match(row.textContent, /已启用 · 1 条规则/)
    assert.equal(document.querySelector('form'), null)
    assert.ok(!document.body.textContent.includes('PRIVATE_PROMPT_SHOULD_NOT_APPEAR_IN_OVERVIEW'))
  })
})

import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/' })
for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'SVGElement', 'Event']) globalThis[name] = dom.window[name]
const { compile, createApp, nextTick, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const originalFetch = globalThis.fetch
const originalTimeout = globalThis.setTimeout
const originalClearTimeout = globalThis.clearTimeout
const fixture = (extra = {}) => ({ enabled: false, proxy: 'http://127.0.0.1:7897', status: 'DISABLED', missingDependencies: [], error: null, updatedAt: 0, ...extra })
const response = (data, status = 200) => new Response(JSON.stringify(data), { status })
async function settle() { await nextTick(); await delay(10); await nextTick() }
function button(text) { return [...document.querySelectorAll('button')].find(node => node.textContent.trim() === text) }

describe('agy 管理台按需准备', () => {
  let server, component, app, settings, calls, timers, saved
  let timerId = 100_000

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const loaded = (await server.ssrLoadModule('/src/components/AntigravityAgySettingsModal.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/AntigravityAgySettingsModal.vue', import.meta.url), 'utf8'))
    const Modal = { props: ['title'], template: '<section><h2>{{ title }}</h2><slot/><footer><slot name="footer"/></footer></section>' }
    component = {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: { Modal },
      setup(props, context) { return { ...loaded.setup(props, context), Modal } },
    }
  })

  beforeEach(() => {
    settings = fixture(); calls = []; timers = new Map(); saved = 0
    globalThis.setTimeout = (callback, timeout, ...args) => {
      if (timeout !== 2000) return originalTimeout(callback, timeout, ...args)
      const id = timerId++; timers.set(id, callback); return id
    }
    globalThis.clearTimeout = id => { if (!timers.delete(id)) originalClearTimeout(id) }
    globalThis.fetch = async (url, options = {}) => {
      assert.equal(String(url), '/admin/api/antigravity-agy-settings')
      calls.push({ method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null })
      if (options.method === 'PUT') settings = fixture({ ...JSON.parse(options.body), status: 'INSTALLING', missingDependencies: ['Python 3.11+', 'Bubblewrap'], updatedAt: 1 })
      return response(settings)
    }
  })

  afterEach(() => {
    app?.unmount(); app = null
    globalThis.fetch = originalFetch
    globalThis.setTimeout = originalTimeout
    globalThis.clearTimeout = originalClearTimeout
    document.body.innerHTML = ''
  })
  after(async () => { await server.close(); dom.window.close() })

  async function mount() {
    const target = document.createElement('div'); document.body.append(target)
    app = createApp(component, { onSaved: () => { saved++ } })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(target); await settle()
  }

  it('默认关闭仅查询，开启后保存并展示缺失依赖，再轮询就绪', async () => {
    await mount()
    assert.deepEqual(calls, [{ method: 'GET', body: null }])
    assert.match(document.body.textContent, /已关闭/)
    const toggle = document.querySelector('input[type="checkbox"]')
    toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true }))
    button('保存设置').click(); await settle()
    assert.deepEqual(calls[1], { method: 'PUT', body: { enabled: true, proxy: 'http://127.0.0.1:7897' } })
    assert.match(document.body.textContent, /正在下载安装缺失依赖/)
    assert.match(document.body.textContent, /Python 3.11\+、Bubblewrap/)
    settings = fixture({ enabled: true, status: 'READY', missingDependencies: ['Python 3.11+', 'Bubblewrap'] })
    const callback = timers.values().next().value; timers.clear(); callback(); await settle()
    assert.match(document.body.textContent, /运行环境已就绪/)
    assert.match(document.body.textContent, /本次已补齐的依赖/)
    assert.ok(saved >= 2)
  })

  it('缺少权限时显示原因与重试入口，关闭弹窗不会取消服务端准备', async () => {
    settings = fixture({ enabled: true, status: 'FAILED', error: '服务用户没有系统安装权限', missingDependencies: ['Bubblewrap'] })
    await mount()
    assert.match(document.body.textContent, /没有系统安装权限/)
    button('重新检查并准备').click(); await settle()
    assert.equal(calls[1].body.enabled, true)
    assert.equal(timers.size, 1)
    app.unmount(); app = null
    assert.equal(timers.size, 0)
    assert.equal(calls.filter(call => call.method !== 'GET' && call.method !== 'PUT').length, 0)
  })

  for (const scenario of [
    { name: '停止期间重新开启返回 409', initial: { status: 'STOPPING' }, failure: () => response({ message: '运行环境正在停止' }, 409), terminal: { status: 'DISABLED' }, status: /已关闭/ },
    { name: '准备期间保存遇到网络错误', initial: { enabled: true, status: 'INSTALLING' }, failure: () => { throw new TypeError('网络连接中断') }, terminal: { enabled: true, status: 'READY' }, status: /运行环境已就绪/ },
  ]) {
    it(scenario.name + '后继续轮询，不重复提交保存', async () => {
      settings = fixture(scenario.initial)
      const fetch = globalThis.fetch
      globalThis.fetch = async (url, options = {}) => {
        if (options.method === 'PUT') {
          calls.push({ method: 'PUT', body: JSON.parse(options.body) })
          return scenario.failure()
        }
        return fetch(url, options)
      }
      await mount()
      const toggle = document.querySelector('input[type="checkbox"]')
      toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true }))
      button('保存设置').click(); await settle()
      assert.ok(document.querySelector('[role="alert"]'))
      assert.equal(timers.size, 1)
      settings = fixture(scenario.terminal)
      const callback = timers.values().next().value; timers.clear(); callback(); await settle()
      assert.match(document.body.textContent, scenario.status)
      assert.equal(document.querySelector('input[type="text"]').disabled, false)
      assert.equal(timers.size, 0)
      assert.equal(calls.filter(call => call.method === 'PUT').length, 1)
    })
  }

  it('准备期间轮询失败后继续读取直到终态', async () => {
    settings = fixture({ enabled: true, status: 'INSTALLING' })
    await mount()
    const fetch = globalThis.fetch
    let failed = false
    globalThis.fetch = async (url, options = {}) => {
      if (!failed) { failed = true; throw new TypeError('网络连接中断') }
      return fetch(url, options)
    }
    const first = timers.values().next().value; timers.clear(); first(); await settle()
    assert.match(document.querySelector('[role="alert"]').textContent, /无法连接网关/)
    assert.equal(timers.size, 1)
    settings = fixture({ enabled: true, status: 'READY' })
    const second = timers.values().next().value; timers.clear(); second(); await settle()
    assert.match(document.body.textContent, /运行环境已就绪/)
    assert.equal(timers.size, 0)
  })

  it('保存成功后忽略旧轮询的迟到错误', async () => {
    settings = fixture({ enabled: true, status: 'INSTALLING' })
    await mount()
    let rejectRead
    const pending = new Promise((resolve, reject) => { rejectRead = reject })
    globalThis.fetch = async (url, options = {}) => options.method === 'PUT'
      ? response(fixture({ enabled: true, status: 'READY' }))
      : pending
    const callback = timers.values().next().value; timers.clear(); callback(); await settle()
    button('保存设置').click(); await settle()
    assert.match(document.body.textContent, /运行环境已就绪/)
    rejectRead(new TypeError('过期的网络错误')); await settle()
    assert.equal(document.querySelector('[role="alert"]'), null)
    assert.equal(timers.size, 0)
  })

  it('关闭弹窗后迟到的保存失败不会恢复轮询', async () => {
    settings = fixture({ status: 'STOPPING' })
    await mount()
    let rejectSave
    globalThis.fetch = () => new Promise((resolve, reject) => { rejectSave = reject })
    button('保存设置').click(); await settle()
    app.unmount(); app = null
    rejectSave(new TypeError('网络连接中断')); await settle()
    assert.equal(timers.size, 0)
  })
})

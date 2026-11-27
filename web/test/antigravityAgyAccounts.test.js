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
const response = data => new Response(JSON.stringify(data), { status: 200 })
async function settle() { await nextTick(); await delay(10); await nextTick() }
function button(text) { return [...document.querySelectorAll('button')].find(node => node.textContent.trim() === text) }

describe('agy 账号区准备状态跟踪', () => {
  let server, component, app, settings, configured, calls, timers
  let timerId = 200_000

  async function loadComponent(name, children = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: children,
      setup(props, context) { return { ...loaded.setup(props, context), ...children } },
    }
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const Modal = { props: ['title'], template: '<section><h2>{{ title }}</h2><slot/><footer><slot name="footer"/></footer></section>' }
    const modal = await loadComponent('AntigravityAgySettingsModal', { Modal })
    component = await loadComponent('AntigravityAgyAccounts', { AntigravityAgySettingsModal: modal })
  })

  beforeEach(() => {
    settings = { enabled: false, proxy: 'http://127.0.0.1:7897', status: 'DISABLED', missingDependencies: [], error: null, updatedAt: 0 }
    configured = false; calls = []; timers = new Map()
    globalThis.setTimeout = (callback, timeout, ...args) => {
      if (timeout !== 2000) return originalTimeout(callback, timeout, ...args)
      const id = timerId++; timers.set(id, callback); return id
    }
    globalThis.clearTimeout = id => { if (!timers.delete(id)) originalClearTimeout(id) }
    globalThis.fetch = async (url, options = {}) => {
      const path = String(url), method = options.method || 'GET'
      calls.push({ path, method })
      if (path === '/admin/api/antigravity/agy/accounts' && method === 'GET') return response({ configured, accounts: [] })
      assert.equal(path, '/admin/api/antigravity-agy-settings')
      if (method === 'PUT') settings = { ...settings, ...JSON.parse(options.body), status: 'INSTALLING' }
      else assert.equal(method, 'GET')
      return response(settings)
    }
  })

  afterEach(() => {
    app?.unmount(); app = null
    assert.equal(timers.size, 0)
    globalThis.fetch = originalFetch
    globalThis.setTimeout = originalTimeout
    globalThis.clearTimeout = originalClearTimeout
    document.body.innerHTML = ''
  })
  after(async () => { await server.close(); dom.window.close() })

  async function mount() {
    const target = document.createElement('div'); document.body.append(target)
    app = createApp(component)
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(target); await settle()
  }
  async function tick() {
    assert.equal(timers.size, 1)
    const callback = timers.values().next().value; timers.clear(); callback(); await settle()
  }

  async function startWatching() {
    button('配置运行环境').click(); await settle()
    button('关闭').click(); await settle()
  }

  it('保存后立即关闭弹窗，后台随后就绪时解锁新增账号且不重复保存', async () => {
    await mount()
    button('配置运行环境').click(); await settle()
    const toggle = document.querySelector('input[type="checkbox"]')
    toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true }))
    button('保存设置').click(); await settle()
    button('关闭').click(); await settle()
    assert.equal(document.querySelector('input[type="checkbox"]'), null)
    settings.status = 'READY'; configured = true
    await tick()
    assert.equal(button('新增 CLI 账号').disabled, false)
    assert.equal(timers.size, 0)
    assert.equal(calls.filter(call => call.method === 'PUT').length, 1)
  })

  for (const unmountParent of [false, true]) {
    it('保存响应未返回时' + (unmountParent ? '离开账号区不会重新读取或计时' : '关闭弹窗仍由账号区接管后续状态'), async () => {
      await mount()
      button('配置运行环境').click(); await settle()
      const fetch = globalThis.fetch
      let resolveSave
      globalThis.fetch = (url, options = {}) => options.method === 'PUT'
        ? new Promise(resolve => { calls.push({ path: String(url), method: 'PUT' }); resolveSave = resolve })
        : fetch(url, options)
      const toggle = document.querySelector('input[type="checkbox"]')
      toggle.checked = true; toggle.dispatchEvent(new Event('change', { bubbles: true }))
      button('保存设置').click(); await settle()
      if (unmountParent) { app.unmount(); app = null }
      else { button('关闭').click(); await settle() }
      const before = calls.length
      settings = { ...settings, enabled: true, status: 'INSTALLING' }
      resolveSave(response(settings)); await settle()
      if (unmountParent) {
        assert.equal(calls.length, before)
        assert.equal(timers.size, 0)
      } else {
        settings.status = 'READY'; configured = true
        await tick()
        assert.equal(button('新增 CLI 账号').disabled, false)
        assert.equal(timers.size, 0)
      }
      assert.equal(calls.filter(call => call.method === 'PUT').length, 1)
    })
  }

  for (const status of ['CHECKING', 'INSTALLING', 'PREPARING', 'STOPPING']) {
    for (const terminal of ['READY', 'FAILED', 'DISABLED']) {
      it(status + ' 在弹窗关闭时持续读取，到 ' + terminal + ' 后停止', async () => {
        settings.status = status
        await mount()
        await startWatching()
        settings.status = terminal; configured = terminal === 'READY'
        await tick()
        assert.equal(button('新增 CLI 账号').disabled, !configured)
        assert.equal(timers.size, 0)
        assert.ok(calls.every(call => call.method === 'GET'))
      })
    }
  }

  for (const failedPath of ['/admin/api/antigravity/agy/accounts', '/admin/api/antigravity-agy-settings']) {
    it(failedPath + ' 读取失败后仍继续准备状态观察', async () => {
      settings.status = 'INSTALLING'
      await mount()
      await startWatching()
      const fetch = globalThis.fetch
      let failed = false
      globalThis.fetch = (url, options) => {
        if (!failed && String(url) === failedPath) { failed = true; return Promise.reject(new TypeError('网络中断')) }
        return fetch(url, options)
      }
      await tick()
      assert.match(document.querySelector('[role="alert"]').textContent, /无法连接网关/)
      settings.status = 'READY'; configured = true
      await tick()
      assert.equal(button('新增 CLI 账号').disabled, false)
      assert.equal(timers.size, 0)
    })
  }

  it('账号列表尚未同步 READY 时继续读取而不提前放行', async () => {
    settings.status = 'READY'
    await mount()
    assert.equal(button('新增 CLI 账号').disabled, true)
    await startWatching()
    configured = true
    await tick()
    assert.equal(button('新增 CLI 账号').disabled, false)
    assert.equal(timers.size, 0)
  })

  it('账号区卸载后忽略迟到读取且不再发起设置读取或计时', async () => {
    settings.status = 'INSTALLING'
    await mount()
    await startWatching()
    let resolveRead
    globalThis.fetch = () => new Promise(resolve => { resolveRead = resolve })
    const callback = timers.values().next().value; timers.clear(); callback(); await settle()
    app.unmount(); app = null
    let lateReads = 0
    globalThis.fetch = async () => { lateReads++; return response(settings) }
    resolveRead(response({ configured: false, accounts: [] })); await settle()
    assert.equal(lateReads, 0)
    assert.equal(timers.size, 0)
  })

  for (const failed of [false, true]) {
    it('新读取确认就绪后忽略旧账号读取的迟到' + (failed ? '失败' : '成功'), async () => {
      settings.status = 'INSTALLING'
      await mount()
      await startWatching()
      const fetch = globalThis.fetch
      let resolveRead, rejectRead, pending = true
      globalThis.fetch = (url, options) => {
        if (pending && String(url) === '/admin/api/antigravity/agy/accounts') {
          pending = false
          return new Promise((resolve, reject) => { resolveRead = resolve; rejectRead = reject })
        }
        return fetch(url, options)
      }
      const callback = timers.values().next().value; timers.clear(); callback(); await settle()
      settings.status = 'READY'; configured = true
      button('配置运行环境').click(); await settle()
      assert.equal(button('新增 CLI 账号').disabled, false)
      if (failed) rejectRead(new TypeError('旧读取失败'))
      else resolveRead(response({ configured: false, accounts: [] }))
      await settle()
      assert.equal(button('新增 CLI 账号').disabled, false)
      assert.equal(document.querySelector('[role="alert"]'), null)
      assert.equal(timers.size, 0)
    })
  }

  it('默认关闭不保留后台计时器，不发起任何安装或登录请求', async () => {
    await mount()
    assert.equal(button('新增 CLI 账号').disabled, true)
    assert.equal(timers.size, 0)
    assert.ok(calls.every(call => call.method === 'GET'))
  })
})

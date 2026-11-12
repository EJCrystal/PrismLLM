import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLIFrameElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent']) {
  globalThis[name] = dom.window[name]
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}

const { compile, createApp, h, nextTick, ssrContextKey } = await import('vue')
const headless = await import('@headlessui/vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default

describe('searchable select inside a modal', () => {
  let server
  let modal
  let select
  let app

  async function loadClientComponent(name) {
    const component = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...component,
      ssrRender: undefined,
      render: compile(descriptor.template.content),
      components: { ...headless, Icon: { render: () => h('span') } },
      setup(props, context) { return { ...component.setup(props, context) } },
    }
  }

  async function settle() {
    await nextTick()
    await delay(40)
    await nextTick()
  }

  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    modal = await loadClientComponent('Modal')
    select = await loadClientComponent('SearchSelect')
  })

  after(async () => {
    app?.unmount()
    await server?.close()
    dom.window.close()
  })

  for (const compact of [false, true]) {
    it('registers ' + (compact ? 'compact' : 'default') + ' options in the dialog focus scope and closes only the options with Escape', async () => {
      const host = document.createElement('div')
      document.body.append(host)
      let closed = 0
      app = createApp({
        render: () => h(modal, { title: '测试模型', full: true, onClose: () => closed++ }, {
          default: () => h(select, {
            modelValue: 'model-1', label: '模型', compact,
            options: [{ value: 'model-1', label: '测试模型', hint: '1 个可用凭据' }],
          }),
        }),
      })
      app.provide(ssrContextKey, { modules: new Set() })
      app.mount(host)
      await settle()
      const trigger = document.querySelector('.search-select-trigger')
      trigger.getBoundingClientRect = () => ({ top: 200, bottom: 244, left: 16, right: 346, width: 330, height: 44 })
      trigger.click()
      await settle()
      const panel = document.querySelector('.search-select-panel')
      assert.ok(panel)
      assert.equal(panel.style.position, 'fixed')
      assert.notEqual(panel.style.visibility, 'hidden')
      assert.ok(panel.closest('[data-headlessui-portal]'))
      assert.ok(panel.closest('[role="dialog"]'))
      assert.equal(panel.closest('[inert]'), null)
      assert.match(panel.textContent, /测试模型.*1 个可用凭据/)
      const input = panel.querySelector('input')
      input.focus()
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      await settle()
      assert.equal(document.querySelector('.search-select-panel'), null)
      assert.equal(closed, 0)
      app.unmount()
      app = null
      host.remove()
      await settle()
    })
  }
})

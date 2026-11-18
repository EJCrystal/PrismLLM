import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  url: 'http://localhost/',
  pretendToBeVisual: true,
})
for (const name of [
  'window',
  'document',
  'Node',
  'NodeFilter',
  'Element',
  'HTMLElement',
  'HTMLButtonElement',
  'SVGElement',
  'Event',
  'MouseEvent',
  'KeyboardEvent',
]) {
  globalThis[name] = dom.window[name]
}
globalThis.PointerEvent = dom.window.PointerEvent || dom.window.MouseEvent

const { compile, createApp, h, nextTick, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default

describe('ThemeToggle keyboard accessibility & focus management', () => {
  let server
  let themeToggle
  let useThemeModule
  let app
  let host

  async function loadClientComponent(name, components = {}) {
    const component = (await server.ssrLoadModule(`/src/components/${name}.vue`)).default
    const { descriptor } = parse(
      readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8'),
    )
    return {
      ...component,
      ssrRender: undefined,
      render: compile(descriptor.template.content),
      components,
      setup(props, context) {
        return { ...component.setup(props, context), ...components }
      },
    }
  }

  async function settle() {
    await nextTick()
    await delay(20)
    await nextTick()
  }

  function dispatchKey(target, key) {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  }

  function dispatchPointerDown(target) {
    target.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }))
  }

  before(async () => {
    server = await createServer({
      root: fileURLToPath(new URL('../', import.meta.url)),
      configFile: false,
      plugins: [vue()],
      optimizeDeps: { noDiscovery: true, include: [] },
      server: { middlewareMode: true, hmr: false, ws: false },
      appType: 'custom',
    })
    useThemeModule = await server.ssrLoadModule('/src/composables/useTheme.ts')
    const icon = await loadClientComponent('Icon')
    const paletteIcon = await loadClientComponent('ThemePaletteIcon')
    themeToggle = await loadClientComponent('ThemeToggle', {
      Icon: icon,
      ThemePaletteIcon: paletteIcon,
    })
  })

  afterEach(async () => {
    app?.unmount()
    app = null
    host?.remove()
    host = null
    useThemeModule?.useTheme().setTheme('light')
    useThemeModule?.useTheme().setPalette('gemini')
    await settle()
  })

  after(async () => {
    await server?.close()
  })

  async function mountToggle() {
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(themeToggle) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    const trigger = host.querySelector('.theme-capsule-btn')
    assert.ok(trigger)
    return { trigger }
  }

  it('Enter / ArrowDown 打开菜单时聚焦当前选中项，并支持方向键循环与 Home/End 导航', async () => {
    useThemeModule.useTheme().setPalette('cyan')
    const { trigger } = await mountToggle()

    trigger.focus()
    dispatchKey(trigger, 'Enter')
    await settle()

    const menu = host.querySelector('.theme-menu')
    assert.ok(menu)
    const items = [...menu.querySelectorAll('[role="menuitemradio"]')]
    assert.equal(items.length, 6)
    assert.equal(document.activeElement, items[2])

    dispatchKey(document.activeElement, 'ArrowDown')
    await settle()
    assert.equal(document.activeElement, items[3])

    dispatchKey(document.activeElement, 'End')
    await settle()
    assert.equal(document.activeElement, items[5])

    dispatchKey(document.activeElement, 'ArrowDown')
    await settle()
    assert.equal(document.activeElement, items[0])

    dispatchKey(document.activeElement, 'ArrowUp')
    await settle()
    assert.equal(document.activeElement, items[5])

    dispatchKey(document.activeElement, 'Home')
    await settle()
    assert.equal(document.activeElement, items[0])
  })

  it('Enter 选定配色以及 Escape 关闭菜单时将焦点恢复到胶囊触发器', async () => {
    const { trigger } = await mountToggle()

    trigger.focus()
    dispatchKey(trigger, 'ArrowDown')
    await settle()

    const items = [...host.querySelectorAll('[role="menuitemradio"]')]
    assert.equal(document.activeElement, items[0])

    dispatchKey(document.activeElement, 'Escape')
    await settle()
    assert.equal(host.querySelector('.theme-menu'), null)
    assert.equal(document.activeElement, trigger)

    dispatchKey(trigger, 'Enter')
    await settle()
    const reopenedItems = [...host.querySelectorAll('[role="menuitemradio"]')]
    dispatchKey(reopenedItems[0], 'ArrowDown')
    await settle()
    assert.equal(document.activeElement, reopenedItems[1])

    dispatchKey(reopenedItems[1], 'Enter')
    await settle()
    assert.equal(host.querySelector('.theme-menu'), null)
    assert.equal(document.documentElement.getAttribute('data-palette'), 'sky')
    assert.equal(document.activeElement, trigger)
  })

  it('鼠标点击选定配色（detail=1）后也将焦点恢复到胶囊触发器而非落回 body', async () => {
    const { trigger } = await mountToggle()

    trigger.dispatchEvent(new MouseEvent('click', { detail: 1, bubbles: true, cancelable: true }))
    await settle()

    const items = [...host.querySelectorAll('[role="menuitemradio"]')]
    assert.equal(items.length, 6)
    assert.equal(document.activeElement, items[0])

    items[3].dispatchEvent(new MouseEvent('click', { detail: 1, bubbles: true, cancelable: true }))
    await settle()

    assert.equal(host.querySelector('.theme-menu'), null)
    assert.equal(document.documentElement.getAttribute('data-palette'), 'orange')
    assert.equal(document.activeElement, trigger)
  })

  it('外部指针关闭菜单时不强行抢回触发器焦点', async () => {
    const { trigger } = await mountToggle()
    const outsideButton = document.createElement('button')
    outsideButton.type = 'button'
    outsideButton.textContent = 'Outside'
    document.body.append(outsideButton)

    try {
      trigger.click()
      await settle()
      assert.ok(host.querySelector('.theme-menu'))

      outsideButton.focus()
      dispatchPointerDown(outsideButton)
      await settle()

      assert.equal(host.querySelector('.theme-menu'), null)
      assert.equal(document.activeElement, outsideButton)
      assert.notEqual(document.activeElement, trigger)
    } finally {
      outsideButton.remove()
    }
  })
})

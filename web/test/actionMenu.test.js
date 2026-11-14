import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Node', 'NodeFilter', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLIFrameElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent']) {
  globalThis[name] = dom.window[name]
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}

const { compile, createApp, h, nextTick, ref, ssrContextKey, unref } = await import('vue')
const headless = await import('@headlessui/vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const initialVisualViewport = Object.getOwnPropertyDescriptor(window, 'visualViewport')

const groupedItems = [
  { id: 'authorization-disabled', label: 'Authorization unavailable', group: 'Authorization', disabled: true, title: 'Unavailable for this mock account' },
  { id: 'reauthorize', label: 'Reauthorize', group: 'Authorization', icon: 'refresh' },
  { id: 'settings', label: 'Account settings', group: 'Account', icon: 'gear' },
  { id: 'maintenance-disabled', label: 'Refresh unavailable', group: 'Maintenance', disabled: true },
  { id: 'refresh', label: 'Refresh credential', group: 'Maintenance', icon: 'refresh' },
  { id: 'delete', label: 'Delete account', group: 'Danger zone', icon: 'trash', danger: true, title: 'Delete this mock account' },
]

describe('action menu groups and keyboard interaction', () => {
  let server
  let actionMenu
  let icon
  let app
  let host

  async function loadClientComponent(name, components = {}) {
    const component = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...component,
      ssrRender: undefined,
      render: compile(descriptor.template.content),
      components: { ...headless, ...components },
      setup(props, context) { return { ...component.setup(props, context), ...components } },
    }
  }

  async function settle() {
    await nextTick()
    await delay(40)
    await nextTick()
  }

  function key(target, value) {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true }))
  }

  function panel() {
    return document.querySelector('.floating-menu')
  }

  function mockMenuHeight(t, measure) {
    const prototype = dom.window.HTMLElement.prototype
    const descriptor = Object.getOwnPropertyDescriptor(prototype, 'offsetHeight')
    assert.ok(descriptor?.get)
    const reads = []
    Object.defineProperty(prototype, 'offsetHeight', {
      ...descriptor,
      get() {
        if (!this.classList.contains('floating-menu')) return descriptor.get.call(this)
        const height = measure(this)
        reads.push({ element: this, height, connected: this.isConnected })
        return height
      },
    })
    t.after(() => Object.defineProperty(prototype, 'offsetHeight', descriptor))
    return reads
  }

  function activeItem() {
    const menu = panel()
    return document.getElementById(menu.getAttribute('aria-activedescendant'))
  }

  async function mountMenu(items, props = {}) {
    const selected = []
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(actionMenu, { label: 'Account actions', items: unref(items), ...props, onSelect: id => selected.push(id) }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    const trigger = host.querySelector('button')
    trigger.getBoundingClientRect = () => ({ top: 200, bottom: 244, left: 100, right: 300, width: 200, height: 44 })
    return { trigger, selected }
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    icon = await loadClientComponent('Icon')
    actionMenu = await loadClientComponent('ActionMenu', { Icon: icon })
  })

  afterEach(async () => {
    app?.unmount()
    app = null
    host?.remove()
    host = null
    if (initialVisualViewport) Object.defineProperty(window, 'visualViewport', initialVisualViewport)
    else delete window.visualViewport
    await settle()
  })

  after(async () => {
    await server?.close()
    dom.window.close()
  })

  it('labels contiguous groups without registering headings and skips headings and disabled items with arrows', async () => {
    const { trigger, selected } = await mountMenu(groupedItems, { compact: true })
    assert.equal(panel(), null)
    trigger.focus()
    key(trigger, 'ArrowDown')
    await settle()

    const menu = panel()
    assert.ok(menu)
    assert.equal(menu.getAttribute('role'), 'menu')
    assert.equal(menu.style.position, 'fixed')
    assert.notEqual(menu.style.visibility, 'hidden')
    assert.equal(document.activeElement, menu)
    assert.equal(menu.getAttribute('aria-labelledby'), trigger.id)
    const headings = [...menu.querySelectorAll('.floating-menu-group-heading')]
    assert.deepEqual(headings.map(heading => heading.textContent), ['Authorization', 'Account', 'Maintenance', 'Danger zone'])
    assert.equal(menu.querySelectorAll('[role="separator"]').length, 3)
    assert.equal(menu.querySelectorAll('[role="menuitem"]').length, groupedItems.length)
    for (const heading of headings) {
      assert.equal(heading.getAttribute('role'), 'heading')
      assert.equal(heading.getAttribute('aria-level'), '3')
      assert.equal(heading.hasAttribute('tabindex'), false)
      assert.equal(heading.parentElement.getAttribute('role'), 'group')
      assert.equal(heading.parentElement.getAttribute('aria-labelledby'), heading.id)
    }
    for (const divider of menu.querySelectorAll('.floating-menu-group-divider')) {
      assert.equal(divider.getAttribute('role'), 'separator')
      assert.equal(divider.getAttribute('aria-orientation'), 'horizontal')
      assert.equal(divider.hasAttribute('tabindex'), false)
    }
    const disabled = menu.querySelector('[role="menuitem"][aria-disabled="true"]')
    assert.equal(disabled.disabled, true)
    assert.equal(disabled.title, 'Unavailable for this mock account')
    assert.equal(activeItem().textContent, 'Reauthorize')
    assert.equal(activeItem().classList.contains('active'), true)
    headings[1].click()
    disabled.click()
    await settle()
    assert.deepEqual(selected, [])
    assert.equal(panel(), menu)

    for (const label of ['Account settings', 'Refresh credential', 'Delete account']) {
      key(menu, 'ArrowDown')
      await settle()
      assert.equal(activeItem().textContent, label)
    }
    key(menu, 'ArrowUp')
    await settle()
    assert.equal(activeItem().textContent, 'Refresh credential')
    key(menu, 'ArrowDown')
    await settle()
    assert.equal(activeItem().classList.contains('danger'), true)
    assert.equal(activeItem().title, 'Delete this mock account')
    key(menu, 'Enter')
    await settle()
    assert.deepEqual(selected, ['delete'])
    assert.equal(panel(), null)
    assert.equal(trigger.getAttribute('aria-expanded'), 'false')
  })

  for (const openKey of ['ArrowDown', 'ArrowUp', 'Enter', ' ']) {
    it('opens with ' + (openKey === ' ' ? 'Space' : openKey) + ' and restores trigger focus on Escape without emitting a selection', async () => {
      const items = [...groupedItems, { id: 'last-disabled', label: 'Delete unavailable', group: 'Danger zone', disabled: true }]
      const { trigger, selected } = await mountMenu(items)
      trigger.focus()
      key(trigger, openKey)
      await settle()
      const menu = panel()
      assert.ok(menu)
      assert.equal(document.activeElement, menu)
      assert.equal(menu.style.position, 'fixed')
      assert.notEqual(menu.style.visibility, 'hidden')
      assert.equal(activeItem().textContent, openKey === 'ArrowUp' ? 'Delete account' : 'Reauthorize')
      key(menu, 'Escape')
      await settle()
      assert.equal(panel(), null)
      assert.equal(document.activeElement, trigger)
      assert.equal(trigger.getAttribute('aria-expanded'), 'false')
      assert.deepEqual(selected, [])
    })
  }

  for (const opening of ['click', 'ArrowDown', 'ArrowUp', 'Enter', ' ']) {
    it('measures the mounted panel on first open with ' + (opening === ' ' ? 'Space' : opening) + ' without a viewport event', async t => {
      const viewport = new dom.window.EventTarget()
      Object.assign(viewport, { width: 800, height: 500, offsetLeft: 0, offsetTop: 0 })
      Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
      const reads = mockMenuHeight(t, menu => {
        assert.equal(menu.querySelectorAll('[role="menuitem"]').length, 2)
        assert.equal(menu.querySelectorAll('.floating-menu-group-heading').length, 2)
        return 300
      })
      const { trigger, selected } = await mountMenu([
        { id: 'one', label: 'One', group: 'First group' },
        { id: 'two', label: 'Two', group: 'Second group' },
      ])
      trigger.getBoundingClientRect = () => ({ top: 300, bottom: 344, left: 100, right: 300, width: 200, height: 44 })
      assert.equal(panel(), null)
      assert.equal(trigger.offsetHeight, 0, 'Non-menu elements retain the JSDOM getter')
      assert.equal(reads.length, 0)

      trigger.focus()
      if (opening === 'click') trigger.click()
      else key(trigger, opening)
      await settle()

      const menu = panel()
      assert.ok(menu)
      assert.ok(reads.length > 0, 'Opening must read offsetHeight after the teleported panel mounts')
      assert.ok(reads.every(read => read.element === menu && read.connected && read.height === 300))
      // The grouped estimate is 169px (top 123px); the measured 300px clamps to top 12px.
      assert.equal(menu.style.top, '12px')
      assert.equal(menu.style.position, 'fixed')
      assert.notEqual(menu.style.visibility, 'hidden')
      if (opening !== 'click') assert.equal(document.activeElement, menu)
      assert.deepEqual(selected, [])
    })
  }

  it('remeasures deep and replacement item updates while open, preserves focus and IDs, and only repositions closed changes on reopen', async t => {
    const viewport = new dom.window.EventTarget()
    Object.assign(viewport, { width: 800, height: 500, offsetLeft: 0, offsetTop: 0 })
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    const rowHeights = new Map([
      ['One', 40], ['Two', 40], ['Two with long wrapped details', 180],
      ['Two with shorter wrapped details', 150], ['Three', 80],
    ])
    // Derive the mocked height from the rendered labels, so reading before the DOM update is observable.
    const reads = mockMenuHeight(t, menu => [...menu.querySelectorAll('[role="menuitem"]')].reduce((height, item) => {
      assert.ok(rowHeights.has(item.textContent))
      return height + rowHeights.get(item.textContent)
    }, 0))
    const items = ref([
      { id: 'one', label: 'One', group: 'First group' },
      { id: 'two', label: 'Two', group: 'Second group' },
    ])
    const { trigger, selected } = await mountMenu(items)
    let anchorReads = 0
    trigger.getBoundingClientRect = () => {
      anchorReads++
      return { top: 300, bottom: 344, left: 100, right: 300, width: 200, height: 44 }
    }
    assert.equal(anchorReads, 0)
    assert.equal(reads.length, 0)
    trigger.focus()
    key(trigger, 'ArrowDown')
    await settle()
    const menu = panel()
    assert.ok(menu)
    assert.equal(menu.style.top, '352px')
    assert.ok(reads.length > 0)
    assert.ok(reads.every(read => read.element === menu && read.connected && read.height === 80))
    key(menu, 'ArrowDown')
    await settle()
    const focusedItem = activeItem()
    assert.equal(focusedItem.textContent, 'Two')
    const accessibilityState = () => ({
      triggerId: trigger.id,
      panelId: menu.id,
      labelledBy: menu.getAttribute('aria-labelledby'),
      controls: trigger.getAttribute('aria-controls'),
      activeId: menu.getAttribute('aria-activedescendant'),
      itemIds: [...menu.querySelectorAll('[role="menuitem"]')].slice(0, 2).map(item => item.id),
      headingIds: [...menu.querySelectorAll('.floating-menu-group-heading')].slice(0, 2).map(heading => heading.id),
    })
    const originalState = accessibilityState()
    const assertOpenState = () => {
      assert.equal(panel(), menu)
      assert.equal(document.activeElement, menu)
      assert.equal(activeItem(), focusedItem)
      assert.deepEqual(accessibilityState(), originalState)
      assert.deepEqual(selected, [])
    }

    let previousReads = reads.length
    items.value[1].label = 'Two with long wrapped details'
    await settle()
    assert.equal(focusedItem.textContent, 'Two with long wrapped details')
    assert.ok(reads.length > previousReads, 'A deep item mutation must trigger measurement without resize')
    assert.ok(reads.slice(previousReads).every(read => read.element === menu && read.connected && read.height === 220), 'Post-update reads must see the rendered new label')
    assert.equal(menu.style.top, '72px')
    assertOpenState()

    previousReads = reads.length
    items.value = [...items.value, { id: 'three', label: 'Three', group: 'Third group' }]
    await settle()
    assert.equal(menu.querySelectorAll('[role="menuitem"]').length, 3)
    assert.ok(reads.length > previousReads, 'Replacing the items array must trigger measurement without resize')
    assert.ok(reads.slice(previousReads).every(read => read.element === menu && read.connected && read.height === 300))
    assert.equal(menu.style.top, '12px')
    assertOpenState()

    key(menu, 'Escape')
    await settle()
    assert.equal(panel(), null)
    assert.equal(document.activeElement, trigger)
    const closedAnchorReads = anchorReads
    const closedPanelReads = reads.length
    items.value[1].label = 'Two with shorter wrapped details'
    items.value.pop()
    await settle()
    assert.equal(anchorReads, closedAnchorReads, 'Deep changes while closed must not place the anchor')
    assert.equal(reads.length, closedPanelReads)
    items.value = items.value.map(item => ({ ...item, title: 'Updated while closed' }))
    await settle()
    assert.equal(anchorReads, closedAnchorReads, 'Replacement changes while closed must not place the anchor')
    assert.equal(reads.length, closedPanelReads)
    assert.equal(panel(), null)
    assert.equal(document.activeElement, trigger)
    assert.equal(trigger.getAttribute('aria-expanded'), 'false')
    assert.deepEqual(selected, [])

    key(trigger, 'ArrowDown')
    await settle()
    const reopenedMenu = panel()
    assert.ok(reopenedMenu)
    assert.notEqual(reopenedMenu, menu)
    assert.equal(reopenedMenu.style.top, '102px', 'Reopen must use the latest measured 190px, not the original estimate or prior open height')
    assert.ok(reads.length > closedPanelReads)
    assert.ok(reads.slice(closedPanelReads).every(read => read.element === reopenedMenu && read.connected && read.height === 190))
    assert.equal(document.activeElement, reopenedMenu)
    assert.equal(trigger.id, originalState.triggerId)
    assert.deepEqual([...reopenedMenu.querySelectorAll('[role="menuitem"]')].map(item => [item.textContent, item.title]), [
      ['One', 'Updated while closed'], ['Two with shorter wrapped details', 'Updated while closed'],
    ])
    key(reopenedMenu, 'ArrowDown')
    await settle()
    assert.equal(activeItem().textContent, 'Two with shorter wrapped details')
    key(reopenedMenu, 'Enter')
    await settle()
    assert.deepEqual(selected, ['two'], 'Label and array changes must preserve the emitted action ID')
    assert.equal(panel(), null)
  })

  for (const update of ['splice', 'replace']) {
    it('preserves keyboard selection when removing a grouped first item with ' + update, async () => {
      const items = ref([
        { id: 'a', label: 'First action', group: 'Actions' },
        { id: 'b', label: 'Remaining action', group: 'Actions' },
        { id: 'separator-action', label: 'Separate action', group: 'Other' },
        { id: 'repeated-action', label: 'Repeated group action', group: 'Actions' },
      ])
      const { trigger, selected } = await mountMenu(items)
      trigger.focus()
      key(trigger, 'ArrowDown')
      await settle()
      const menu = panel()
      key(menu, 'ArrowDown')
      await settle()
      const remainingItem = activeItem()
      assert.equal(remainingItem.textContent, 'Remaining action')
      const activeId = menu.getAttribute('aria-activedescendant')

      if (update === 'splice') items.value.splice(0, 1)
      else items.value = items.value.filter(item => item.id !== 'a')
      await settle()
      const updatedItem = [...menu.querySelectorAll('[role="menuitem"]')].find(item => item.textContent === 'Remaining action')
      const updatedActiveId = menu.getAttribute('aria-activedescendant')
      assert.equal(panel(), menu)
      assert.equal(document.activeElement, menu)
      assert.deepEqual([...menu.querySelectorAll('[role="heading"]')].map(heading => heading.textContent), ['Actions', 'Other', 'Actions'])
      assert.equal(menu.querySelectorAll('[role="separator"]').length, 2)
      key(menu, 'Enter')
      await settle()

      assert.deepEqual(selected, ['b'])
      assert.equal(updatedItem, remainingItem, 'Removing a sibling must not remount the remaining MenuItem')
      assert.equal(updatedActiveId, activeId)
      assert.equal(panel(), null)
      assert.equal(document.activeElement, trigger)
    })
  }

  for (const group of ['Repeated', undefined]) {
    it('preserves a surviving ' + (group ?? 'unnamed') + ' group when an earlier matching group disappears', async () => {
      const items = ref([
        { id: 'a', label: 'Earlier action', group },
        { id: 'other', label: 'Other action', group: 'Other' },
        { id: 'b', label: 'Remaining action', group },
      ])
      const { trigger, selected } = await mountMenu(items)
      trigger.focus()
      key(trigger, 'ArrowUp')
      await settle()
      const menu = panel()
      const remainingItem = activeItem()
      assert.equal(remainingItem.textContent, 'Remaining action')
      const activeId = menu.getAttribute('aria-activedescendant')

      items.value.shift()
      await settle()
      const updatedItem = [...menu.querySelectorAll('[role="menuitem"]')].find(item => item.textContent === 'Remaining action')
      const updatedActiveId = menu.getAttribute('aria-activedescendant')
      assert.equal(panel(), menu)
      assert.equal(document.activeElement, menu)
      assert.equal(menu.querySelectorAll('[role="group"]').length, 2)
      key(menu, 'Enter')
      await settle()

      assert.deepEqual(selected, ['b'])
      assert.equal(updatedItem, remainingItem)
      assert.equal(updatedActiveId, activeId)
      assert.equal(panel(), null)
      assert.equal(document.activeElement, trigger)
    })
  }

  it('preserves keyboard selection when a new first item joins the same group', async () => {
    const items = ref([{ id: 'b', label: 'Remaining action', group: 'Actions' }])
    const { trigger, selected } = await mountMenu(items)
    trigger.focus()
    key(trigger, 'ArrowDown')
    await settle()
    const menu = panel()
    const remainingItem = activeItem()
    assert.equal(remainingItem.textContent, 'Remaining action')

    items.value.unshift({ id: 'a', label: 'New first action', group: 'Actions' })
    await settle()
    assert.equal(activeItem(), remainingItem)
    assert.equal(document.activeElement, menu)
    assert.deepEqual([...menu.querySelectorAll('[role="menuitem"]')].map(item => item.textContent), ['New first action', 'Remaining action'])
    key(menu, 'Enter')
    await settle()
    assert.deepEqual(selected, ['b'])
    assert.equal(panel(), null)
  })

  it('keeps split groups distinct without losing the surviving first group selection', async () => {
    const items = ref([
      { id: 'a', label: 'First action', group: 'Actions' },
      { id: 'b', label: 'Middle action', group: 'Actions' },
      { id: 'c', label: 'Last action', group: 'Actions' },
    ])
    const { trigger, selected } = await mountMenu(items)
    trigger.focus()
    key(trigger, 'ArrowDown')
    await settle()
    const menu = panel()
    const firstItem = activeItem()

    items.value[1].group = 'Other'
    await settle()
    assert.equal(activeItem(), firstItem)
    assert.deepEqual([...menu.querySelectorAll('[role="menuitem"]')].map(item => item.textContent), ['First action', 'Middle action', 'Last action'])
    assert.deepEqual([...menu.querySelectorAll('[role="heading"]')].map(heading => heading.textContent), ['Actions', 'Other', 'Actions'])
    assert.equal(new Set([...menu.querySelectorAll('[role="menuitem"]')].map(item => item.id)).size, 3)
    assert.equal(menu.querySelectorAll('[role="separator"]').length, 2)
    key(menu, 'Enter')
    await settle()
    assert.deepEqual(selected, ['a'])
    assert.equal(panel(), null)
  })

  it('keeps ungrouped arrays flat and preserves disabled, title, danger, and emitted IDs', async () => {
    const { trigger, selected } = await mountMenu([
      { id: 'edit', label: 'Edit mock account', icon: 'edit' },
      { id: 'disabled', label: 'Disabled action', disabled: true, title: 'Unavailable' },
      { id: 'delete-account-id', label: 'Delete mock account', danger: true, title: 'Delete confirmation required' },
    ])
    trigger.click()
    await settle()
    const menu = panel()
    assert.ok(menu)
    assert.equal(menu.querySelector('.floating-menu-group-heading'), null)
    assert.equal(menu.querySelector('[role="heading"], [role="separator"], [role="group"]'), null)
    assert.equal(menu.children.length, 3)
    assert.equal(menu.children[0].getAttribute('role'), 'menuitem')
    assert.equal(menu.children[1].disabled, true)
    assert.equal(menu.children[1].title, 'Unavailable')
    menu.children[1].click()
    assert.deepEqual(selected, [])
    const danger = menu.children[2]
    assert.equal(danger.classList.contains('danger'), true)
    assert.equal(danger.title, 'Delete confirmation required')
    danger.click()
    await settle()
    assert.deepEqual(selected, ['delete-account-id'])
    assert.equal(panel(), null)
  })

  it('preserves order and repeats headings only at boundaries, including unnamed groups', async () => {
    const { trigger } = await mountMenu([
      { id: 'first', label: 'First', group: 'Repeated' },
      { id: 'second', label: 'Second', group: 'Repeated' },
      { id: 'unnamed', label: 'Unnamed' },
      { id: 'third', label: 'Third', group: 'Repeated' },
    ])
    trigger.click()
    await settle()
    const menu = panel()
    assert.deepEqual([...menu.querySelectorAll('[role="menuitem"]')].map(item => item.textContent), ['First', 'Second', 'Unnamed', 'Third'])
    const headings = [...menu.querySelectorAll('[role="heading"]')]
    assert.deepEqual(headings.map(heading => heading.textContent), ['Repeated', 'Repeated'])
    assert.notEqual(headings[0].id, headings[1].id)
    assert.equal(menu.querySelectorAll('[role="separator"]').length, 2)
    const groups = [...menu.querySelectorAll('[role="group"]')]
    assert.equal(groups.length, 3)
    assert.equal(groups[1].hasAttribute('aria-labelledby'), false)
  })

  it('accounts for grouped chrome in the estimate and uses the registered panel height on viewport changes', async () => {
    const viewport = new dom.window.EventTarget()
    Object.assign(viewport, { width: 800, height: 500, offsetLeft: 0, offsetTop: 0 })
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    const { trigger, selected } = await mountMenu([
      { id: 'one', label: 'One', group: 'First group' },
      { id: 'two', label: 'Two', group: 'Second group' },
    ])
    let measured = 0
    trigger.getBoundingClientRect = () => {
      measured++
      return { top: 300, bottom: 344, left: 100, right: 300, width: 200, height: 44 }
    }
    await settle()
    assert.equal(measured, 0)
    trigger.click()
    await settle()
    const menu = panel()
    assert.ok(menu)
    // JSDOM has no layout: 2 rows + 2 headings + 1 divider = 169px estimated height.
    assert.equal(menu.style.top, '123px')
    Object.defineProperty(menu, 'offsetHeight', { configurable: true, value: 80 })
    viewport.dispatchEvent(new Event('resize'))
    await settle()
    assert.equal(menu.style.top, '352px')
    Object.defineProperty(menu, 'offsetHeight', { configurable: true, value: 220 })
    viewport.dispatchEvent(new Event('scroll'))
    await settle()
    assert.equal(menu.style.top, '72px')
    key(menu, 'Escape')
    await settle()
    const measuredWhileOpen = measured
    viewport.dispatchEvent(new Event('resize'))
    await settle()
    assert.equal(measured, measuredWhileOpen)
    assert.deepEqual(selected, [])
    app.unmount()
    app = null
    viewport.dispatchEvent(new Event('scroll'))
    await settle()
    assert.equal(measured, measuredWhileOpen)
  })

  it('does not open a disabled menu trigger', async () => {
    const { trigger, selected } = await mountMenu(groupedItems, { disabled: true })
    assert.equal(trigger.disabled, true)
    trigger.click()
    key(trigger, 'ArrowDown')
    await settle()
    assert.equal(panel(), null)
    assert.deepEqual(selected, [])
  })

  it('renders a separate toothed gear icon without changing the existing settings icon', async () => {
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h('div', ['gear', 'settings', 'channels'].map(name => h(icon, { name, size: 17, 'data-icon': name }))) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    const paths = name => [...host.querySelectorAll(`[data-icon="${name}"] path`)].map(path => path.getAttribute('d'))
    assert.equal(paths('gear').length, 2)
    assert.match(paths('gear')[0], /L14 21h-4/)
    assert.equal(paths('gear')[1], 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z')
    assert.notDeepEqual(paths('gear'), paths('settings'))
    assert.notDeepEqual(paths('gear'), paths('channels'))
    assert.deepEqual(paths('settings'), ['M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8', 'M12 2v3', 'M12 19v3', 'M2 12h3', 'M19 12h3', 'M5 5l2 2', 'M17 17l2 2', 'M5 19l2-2', 'M17 7l2-2'])
    assert.equal(host.querySelector('[data-icon="gear"]').getAttribute('aria-hidden'), 'true')
  })
})

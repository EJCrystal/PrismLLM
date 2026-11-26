import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'
import { createOAuthAccountsFixture } from './fixtures/oauth-accounts/accounts.js'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://127.0.0.1/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Document', 'Node', 'NodeFilter', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLIFrameElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent']) {
  globalThis[name] = dom.window[name]
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}
dom.window.HTMLElement.prototype.scrollIntoView = () => {}

const originalFetch = globalThis.fetch
const noUnmockedFetch = async () => { throw new Error('OAuthProviderMappingsPanel test forbids native network access') }
globalThis.fetch = window.fetch = noUnmockedFetch

const vueRuntime = await import('vue')
const { createApp, h, nextTick, ssrContextKey } = vueRuntime
const headless = await import('@headlessui/vue')
const { parse } = await import('@vue/compiler-sfc')
const { compile: compileTemplate } = await import('@vue/compiler-dom')
const { transform } = await import('esbuild')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default

async function loadClientComponent(server, name, components = {}) {
  const component = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
  const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
  const compiled = compileTemplate(descriptor.template.content, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
  const { code } = await transform(compiled.code, { loader: 'ts' })
  return {
    ...component,
    ssrRender: undefined,
    render: new Function('Vue', code)(vueRuntime),
    components: { ...headless, ...components },
    setup(props, context) { return { ...component.setup(props, context), ...components } },
  }
}

async function settle() {
  await nextTick()
  await delay(40)
  await nextTick()
}

const text = node => node?.textContent.replace(/\s+/g, ' ').trim()

function createProviderMappingFetch(fixture = createOAuthAccountsFixture(), origin = 'http://127.0.0.1') {
  const requests = []
  let conflictNextSave = false
  const respond = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
  const error = (message, status = 400, code = 'fixture_validation') => respond({ error: { code, message } }, status)

  const fetch = async (input, init = {}) => {
    const requestInput = typeof input === 'object' && input !== null && 'url' in input ? input : null
    const rawUrl = requestInput ? requestInput.url : String(input)
    const method = (init.method || requestInput?.method || 'GET').toUpperCase()
    const url = new URL(rawUrl, origin)
    const bodyText = init.body ?? (requestInput ? await requestInput.clone().text() : '')
    const body = bodyText ? JSON.parse(bodyText) : null
    requests.push({ method, path: url.pathname, body })

    if (method === 'POST' && url.pathname === '/admin/api/model-suggestions') {
      return respond({
        loaded: true,
        error: null,
        data: body.models.map(model => ({
          model,
          candidates: [
            {
              id: model,
              canonicalId: `catalog/${model}`,
              provider: 'Fixture catalog',
              providerId: 'fixture',
              developer: 'Synthetic developer',
              developerId: 'fixture',
            },
          ],
        })),
      })
    }

    const match = /^\/admin\/api\/oauth-providers\/([a-z0-9-]+)\/model-mappings$/.exec(url.pathname)
    if (!match || !fixture.providerMappings[match[1]]) {
      return error('Unexpected route ' + method + ' ' + url.pathname, 404)
    }
    const resource = fixture.providerMappings[match[1]]
    if (method === 'GET') {
      return respond(resource)
    }
    if (method === 'PUT') {
      if (conflictNextSave) {
        conflictNextSave = false
        resource.revision += 1
        const first = body.changes[0]
        const existing = resource.mappings.find(item => item.upstreamModel === first.upstreamModel)
        if (existing) {
          existing.mode = 'manual'
          existing.mappingSource = 'manual'
          existing.canonicalModelId = 'other-admin/updated-id'
          existing.publicModelId = 'other-admin/updated-id'
        }
        return error('共享模型映射已变化，请刷新后再保存', 409, 'model_mapping_revision_conflict')
      }
      if (body.expectedRevision !== resource.revision) {
        return error('共享模型映射已变化，请刷新后再保存', 409, 'model_mapping_revision_conflict')
      }
      for (const change of body.changes) {
        let row = resource.mappings.find(item => item.upstreamModel === change.upstreamModel)
        if (!row) {
          row = {
            upstreamModel: change.upstreamModel,
            automaticCanonicalModelId: null,
            canonicalModelId: null,
            publicModelId: change.upstreamModel,
            mode: change.mode,
            mappingSource: 'manual',
            catalogActive: false,
          }
          resource.mappings.push(row)
        }
        row.mode = change.mode
        row.mappingSource = change.mode === 'auto' ? null : 'manual'
        row.canonicalModelId = change.mode === 'manual'
          ? change.canonicalModelId
          : change.mode === 'auto'
            ? row.automaticCanonicalModelId
            : null
        row.publicModelId = row.canonicalModelId ?? row.upstreamModel
      }
      resource.revision += 1
      return respond(resource)
    }
    return error('Unsupported method', 405)
  }

  return {
    fetch,
    requests,
    fixture,
    triggerConflictNextSave() { conflictNextSave = true },
  }
}

describe('OAuthProviderMappingsPanel layout and interactions', () => {
  let server
  let panelComponent
  let modalComponent
  let toastStack
  let toasts
  let app
  let host
  let mock

  const dialog = () => document.querySelector('[role="dialog"]')
  const row = model => dialog()?.querySelector(`[data-provider-mapping-model="${model}"]`)
  const rows = () => [...(dialog()?.querySelectorAll('[data-provider-mapping-model]') ?? [])]
  const buttonNamed = (root, label) => [...root.querySelectorAll('button')].find(item => text(item) === label)
  async function input(node, value) {
    assert.ok(node, 'Expected input node')
    node.value = value
    node.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
  }
  async function mountPanel({ provider = 'antigravity', providerLabel = 'Antigravity', fixture = createOAuthAccountsFixture() } = {}) {
    mock = createProviderMappingFetch(fixture)
    globalThis.fetch = window.fetch = mock.fetch
    host = document.createElement('div')
    document.body.append(host)
    let savedCount = 0
    app = createApp({
      render: () => h('div', [
        h(modalComponent, { title: providerLabel, eyebrow: '官方订阅提供商', roomy: true, bodyScrollable: false }, {
          default: () => h(panelComponent, {
            provider,
            providerLabel,
            channels: fixture.channels,
            onSaved: () => { savedCount += 1 },
          }),
        }),
        h(toastStack),
      ]),
    })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    return { getSavedCount: () => savedCount }
  }

  before(async () => {
    server = await createServer({
      root: fileURLToPath(new URL('../', import.meta.url)),
      configFile: false,
      envFile: false,
      cacheDir: join(tmpdir(), 'prism-oauth-provider-mappings-panel-tests'),
      plugins: [vue()],
      optimizeDeps: { noDiscovery: true, include: [] },
      server: { middlewareMode: true, host: '127.0.0.1', proxy: {}, hmr: false },
      appType: 'custom',
    })
    const icon = await loadClientComponent(server, 'Icon')
    modalComponent = await loadClientComponent(server, 'Modal', { Icon: icon })
    const searchSelect = await loadClientComponent(server, 'SearchSelect', { Icon: icon })
    const mappingInput = await loadClientComponent(server, 'OAuthMappingInput', { SearchSelect: searchSelect })
    panelComponent = await loadClientComponent(server, 'OAuthProviderMappingsPanel', {
      Icon: icon,
      OAuthMappingInput: mappingInput,
    })
    toastStack = await loadClientComponent(server, 'ToastStack')
    toasts = (await server.ssrLoadModule('/src/toast.ts')).toasts
  })

  afterEach(async () => {
    app?.unmount()
    app = null
    await settle()
    host?.remove()
    host = null
    mock = null
    toasts?.splice(0)
    globalThis.fetch = window.fetch = noUnmockedFetch
  })

  after(async () => {
    await server?.close()
    dom.window.close()
    globalThis.fetch = originalFetch
  })

  it('declares self-contained four-column table styles and drawer scroll containment', () => {
    const panelStyles = parse(readFileSync(new URL('../src/components/OAuthProviderMappingsPanel.vue', import.meta.url), 'utf8')).descriptor.styles.map(s => s.content).join('\n')
    const drawerSource = readFileSync(new URL('../src/components/OAuthProviderDrawer.vue', import.meta.url), 'utf8')
    const drawerStyles = parse(drawerSource).descriptor.styles.map(s => s.content).join('\n')

    assert.match(panelStyles, /\.provider-mapping-table\s*\{[^}]*--provider-mapping-columns:/)
    assert.match(panelStyles, /\.provider-mapping-head,\s*\.provider-mapping-row\s*\{[^}]*grid-template-columns:\s*var\(--provider-mapping-columns\)/)
    assert.match(panelStyles, /\.provider-mapping-rows\s*\{[^}]*flex:\s*1 1 auto;[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto/)
    assert.match(drawerSource, /:body-scrollable="activeTab !== 'mappings'"/)
    assert.match(drawerStyles, /\.provider-drawer-pane-mappings\s*\{[^}]*min-height:\s*0/)
    assert.match(drawerStyles, /\.channel-status-toggle\s*\{/)
    assert.match(drawerStyles, /\.channel-status-track\s*\{/)
    assert.doesNotMatch(drawerStyles, /var\(--surface-subtle|var\(--surface[,)]/)
  })

  it('renders manual rows inline with SearchSelect picker and fills chosen candidate without duplicate readout', async () => {
    await mountPanel()
    const manualRow = row('claude-opus-4-5-thinking')
    assert.ok(manualRow)
    assert.equal(manualRow.querySelector('.provider-mapping-public-readout'), null)
    const wrapper = manualRow.querySelector('.oauth-mapping-input.is-inline')
    assert.ok(wrapper)
    const textInput = wrapper.querySelector('input')
    assert.equal(textInput.value, 'custom/opus')
    const trigger = wrapper.querySelector('.search-select-trigger.is-picker-trigger')
    assert.ok(trigger)
    assert.equal(text(trigger), '候选')

    trigger.getBoundingClientRect = () => ({ top: 200, bottom: 232, left: 300, right: 360, width: 60, height: 32 })
    trigger.click()
    await settle()
    const panel = document.querySelector('.search-select-panel')
    assert.ok(panel)
    const searchInput = panel.querySelector('input[type="search"]')
    await input(searchInput, 'catalog/claude-opus-4-5-thinking')
    const option = [...panel.querySelectorAll('[role="option"]')].find(item => text(item.querySelector('strong')) === 'catalog/claude-opus-4-5-thinking')
    assert.ok(option)
    option.click()
    await settle()
    assert.equal(textInput.value, 'catalog/claude-opus-4-5-thinking')
    assert.match(text(manualRow.querySelector('.provider-source-text')), /待保存/)
  })

  it('collapses add-mapping form by default, filters custom mappings, supports add/restore/undo/discard', async () => {
    await mountPanel()
    assert.ok(dialog())
    assert.equal(dialog().querySelector('[aria-label="新增上游模型映射"]'), null)
    assert.equal(rows().length, 6)

    // 切换到「自定义」筛选，仅展示手工/自定义行
    const customChip = buttonNamed(dialog(), '自定义 2')
    assert.ok(customChip)
    customChip.click()
    await settle()
    assert.deepEqual(rows().map(item => item.dataset.providerMappingModel), ['claude-opus-4-5-thinking', 'gemini-3-flash'])

    // 切回全部，输入不匹配的搜索词后再通过工具栏新增，搜索词应自动清空让新行可见
    buttonNamed(dialog(), '全部 6').click()
    await settle()
    await input(dialog().querySelector('[aria-label="搜索共享模型映射"]'), 'gemini')
    assert.equal(rows().length, 4)

    dialog().querySelector('.provider-add-toggle').click()
    await settle()
    const addField = dialog().querySelector('[aria-label="新增上游模型映射"]')
    assert.ok(addField)
    await input(addField, 'claude-sonnet-4-6')
    buttonNamed(dialog(), '添加').click()
    await settle()

    assert.equal(dialog().querySelector('[aria-label="新增上游模型映射"]'), null)
    assert.equal(dialog().querySelector('[aria-label="搜索共享模型映射"]').value, '')
    const addedRow = row('claude-sonnet-4-6')
    assert.ok(addedRow)
    assert.match(text(addedRow.querySelector('.provider-source-text')), /新增草稿/)

    // 恢复自动与撤销、放弃全部草稿
    buttonNamed(row('claude-opus-4-5-thinking'), '恢复自动').click()
    await settle()
    assert.equal(row('claude-opus-4-5-thinking').querySelector('select').value, 'auto')
    buttonNamed(row('claude-opus-4-5-thinking'), '撤销').click()
    await settle()
    assert.equal(row('claude-opus-4-5-thinking').querySelector('select').value, 'manual')

    buttonNamed(dialog(), '放弃全部草稿').click()
    await settle()
    assert.equal(row('claude-sonnet-4-6'), null)
    assert.equal(buttonNamed(dialog(), '放弃全部草稿'), undefined)
  })

  it('saves mapping changes with expectedRevision and handles 409 conflict confirmation flow', async () => {
    const { getSavedCount } = await mountPanel()
    assert.ok(dialog())

    const sonnetRow = row('claude-sonnet-4-5')
    const modeSelect = sonnetRow.querySelector('select')
    modeSelect.value = 'manual'
    modeSelect.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
    await input(sonnetRow.querySelector('.oauth-mapping-input input'), 'anthropic/sonnet-4.5')

    buttonNamed(dialog(), '保存映射修改（1）').click()
    await settle()

    const putRequests = mock.requests.filter(req => req.method === 'PUT')
    assert.equal(putRequests.length, 1)
    assert.equal(putRequests[0].path, '/admin/api/oauth-providers/antigravity/model-mappings')
    assert.deepEqual(putRequests[0].body, {
      expectedRevision: 1,
      changes: [{ upstreamModel: 'claude-sonnet-4-5', mode: 'manual', canonicalModelId: 'anthropic/sonnet-4.5' }],
    })
    assert.equal(getSavedCount(), 1)
    assert.equal(mock.fixture.providerMappings.antigravity.revision, 2)

    // 触发 409 冲突并验证确认复选框解锁保存
    await input(row('claude-sonnet-4-5').querySelector('.oauth-mapping-input input'), 'anthropic/sonnet-conflict')
    mock.triggerConflictNextSave()
    buttonNamed(dialog(), '保存映射修改（1）').click()
    await settle()

    assert.match(text(dialog().querySelector('.callout.warning')), /共享映射冲突/)
    assert.match(text(row('claude-sonnet-4-5').querySelector('.provider-conflict-baseline')), /other-admin\/updated-id/)
    const saveButton = buttonNamed(dialog(), '保存映射修改（1）')
    assert.equal(saveButton.disabled, true)

    const confirmBox = dialog().querySelector('.provider-conflict-confirm input[type="checkbox"]')
    assert.ok(confirmBox)
    confirmBox.click()
    await settle()
    assert.equal(saveButton.disabled, false)

    saveButton.click()
    await settle()
    assert.equal(getSavedCount(), 2)
    assert.equal(mock.fixture.providerMappings.antigravity.revision, 4)
  })
})

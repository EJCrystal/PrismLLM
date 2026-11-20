import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'
import { clonePluginData, makeExecution, makeField, makeOfficialPluginFixture, makePluginFixture, makeTarget } from './fixtures/plugin.js'

// Same Vite SSR-module + actual client-template/JSDOM convention as appNavigation tests.
// Everything below is mock-only UI data. No ZIP/JAR/catalogue trust or JVM acceptance is claimed.
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://127.0.0.1/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Document', 'Node', 'NodeFilter', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLTextAreaElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent', 'CustomEvent']) globalThis[name] = dom.window[name]
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window)
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.scrollTo = () => {}
dom.window.HTMLElement.prototype.scrollTo = () => {}
dom.window.HTMLElement.prototype.scrollIntoView = () => {}
const originalFetch = globalThis.fetch
const originalConfirm = window.confirm
const runtime = await import('vue')
const { createApp, h, nextTick, reactive, ref, ssrContextKey } = runtime
const { parse } = await import('@vue/compiler-sfc')
const { compile: compileTemplate } = await import('@vue/compiler-dom')
const { transform } = await import('esbuild')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const text = node => node?.textContent.replace(/\s+/g, ' ').trim() ?? ''
const genericMethods = ['listPluginCatalog', 'listPlugins', 'getPlugin', 'getPluginContributions', 'installPlugin', 'setPluginEnabled', 'configurePlugin', 'uninstallPlugin', 'listPluginTargets', 'invokePlugin']

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
async function settle() { await nextTick(); await delay(5); await nextTick() }
async function waitFor(predicate, label = 'reactive update') {
  for (let attempt = 0; attempt < 100; attempt++) {
    await settle()
    if (predicate()) return
  }
  assert.fail(`Timed out waiting for ${label}`)
}
function button(scope, label) {
  const found = [...scope.querySelectorAll('button')].find(node => text(node) === label)
  assert.ok(found, `Expected button ${label}; got ${text(scope)}`)
  return found
}
function fact(scope, label) {
  const term = [...scope.querySelectorAll('.plugin-market-facts dt')].find(node => text(node) === label)
  assert.ok(term, `Expected runtime fact ${label}`)
  return text(term.nextElementSibling)
}
function pluginItem(scope, id) {
  const item = [...scope.querySelectorAll('.plugin-market-item')].find(node => text(node.querySelector('.plugin-market-item-id')) === id)
  assert.ok(item, `Expected plugin list item ${id}`)
  return item
}
function field(scope, label) {
  const found = [...scope.querySelectorAll('label')].find(node => text(node.querySelector('span')).replace(/\s*\*$/, '') === label)
  assert.ok(found, `Expected field ${label}`)
  const input = found.querySelector('input,select,textarea')
  assert.ok(input, `Expected control for ${label}`)
  return input
}
async function change(input, value) {
  if (input.type === 'checkbox') input.checked = value
  else input.value = String(value)
  input.dispatchEvent(new Event(input.tagName === 'SELECT' || input.type === 'checkbox' ? 'change' : 'input', { bubbles: true }))
  await nextTick()
}

// Keep dialog mechanics/legacy APIs outside this suite, while rendering the real slots and tabs.
// Their viewport, scroll and read-only record coverage remains in testWorkbench.test.js.
const modalStub = {
  props: ['title'], emits: ['close'],
  setup(props, { slots, emit }) { return () => h('section', { class: 'synthetic-dialog', 'aria-label': props.title }, [
    h('button', { type: 'button', onClick: () => emit('close') }, '关闭模拟窗口'),
    slots['title-extra']?.(), slots.subhead?.(), slots.default?.(), slots.footer?.(),
  ]) },
}
const legacyStub = kind => ({
  render: () => h('div', { 'data-legacy-view': kind }, kind === 'iq'
    ? [h('input', { type: 'text', 'aria-label': '智商提示词' })]
    : `legacy ${kind}`),
})

describe('descriptor-driven plugin UI (synthetic API data only)', () => {
  let server, api, originals, toasts, ApiError, modelTestTargetKey, PLUGIN_STATE_CHANGED
  let captures, calls, mock, confirmResult, confirmCalls, recordMounts
  const components = new Map()
  const mounted = []
  let diagnosticHostSetups = 0
  const channel = { id: 'channel-1', name: '合成渠道', enabled: true }

  async function clientComponent(name, children = {}) {
    const component = (await server.ssrLoadModule(`/src/components/${name}.vue`)).default
    const { descriptor } = parse(readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8'))
    const compiled = compileTemplate(descriptor.template.content, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
    const { code } = await transform(compiled.code, { loader: 'ts' })
    const setup = component.setup
    // Adapt the loaded module object in memory, so computed dynamic component references also use
    // the actual compiled client template. No production Vue/source file is rewritten.
    component.ssrRender = undefined
    component.render = new Function('Vue', code)(runtime)
    component.components = children
    component.setup = (props, context) => {
      const state = setup(props, context)
      if (name === 'PluginDiagnosticHost') diagnosticHostSetups++
      if (name === 'ChannelTestDrawer') {
        state.components.iq = legacyStub('iq')
        state.components.fingerprint = legacyStub('fingerprint')
      }
      captures.set(name, state)
      const descriptors = Object.getOwnPropertyDescriptors(state)
      delete descriptors.__isScriptSetup
      return Object.assign(Object.defineProperties({}, descriptors), children)
    }
    components.set(name, component)
    return component
  }

  function setFixtures(...fixtures) {
    mock.catalog = fixtures.map(fixture => clonePluginData(fixture.catalog))
    mock.states = new Map(fixtures.map(fixture => [fixture.state.id, clonePluginData(fixture.state)]))
    mock.contributions = fixtures.flatMap(fixture => clonePluginData(fixture.contributions))
  }
  function stateFor(id) {
    const state = mock.states.get(id)
    assert.ok(state, `Missing synthetic state ${id}`)
    return clonePluginData(state)
  }
  function responseFor(call, overrides = {}) {
    const target = mock.targets.find(item => item.channelId === call.payload.context.id && item.model === call.payload.values.model)
      ?? makeTarget({ channelId: call.payload.context.id, model: call.payload.values.model })
    return makeExecution({ pluginId: call.id, activeVersion: call.payload.expectedActiveVersion, context: call.payload.context, values: call.payload.values, target, ...overrides })
  }
  function setState(id, changes) {
    const next = { ...stateFor(id), ...clonePluginData(changes) }
    mock.states.set(id, next)
    return clonePluginData(next)
  }
  function installHooks() {
    api.listPluginCatalog = options => {
      calls.catalog.push(options)
      return mock.listCatalog ? mock.listCatalog(options) : Promise.resolve(clonePluginData(mock.catalog))
    }
    api.listPlugins = options => {
      calls.plugins.push(options)
      return mock.listPlugins ? mock.listPlugins(options) : Promise.resolve(clonePluginData([...mock.states.values()]))
    }
    api.getPlugin = async (id, options = {}) => {
      calls.state.push({ id, options })
      return mock.getState ? mock.getState(id, options) : stateFor(id)
    }
    api.getPluginContributions = options => {
      calls.contributions.push(options)
      const available = mock.contributions.filter(item => {
        const state = mock.states.get(item.pluginId)
        return state?.admissionEnabled && state.activeVersion === item.activeVersion && state.activeUi?.entries.some(entry => entry.id === item.entry.id)
      })
      return mock.listContributions ? mock.listContributions(options, clonePluginData(available)) : Promise.resolve(clonePluginData(available))
    }
    api.listPluginTargets = async (id, context, options = {}) => {
      calls.targets.push({ id, context: context ? clonePluginData(context) : undefined, options })
      return mock.getTargets ? mock.getTargets(id, context, options) : clonePluginData(mock.targets)
    }
    api.invokePlugin = async (id, payload, options = {}) => {
      const call = { id, payload: clonePluginData(payload), options }
      calls.invocations.push(call)
      return mock.invoke ? mock.invoke(call) : responseFor(call)
    }
    api.installPlugin = async (id, payload) => {
      calls.install.push({ id, payload: clonePluginData(payload) })
      const item = mock.catalog.find(item => item.manifest.id === id && item.manifest.version === payload.version)
      assert.ok(item)
      const old = mock.states.get(id)
      const next = {
        id, displayName: item.manifest.displayName, description: item.manifest.description, source: item.source,
        installed: true, installedVersion: payload.version, activeVersion: old?.activeVersion ?? null,
        revision: old ? old.revision + 1 : 0,
        desiredEnabled: old?.installed ? old.desiredEnabled : false, admissionEnabled: old?.admissionEnabled ?? false,
        requiresRestart: !!old?.desiredEnabled, status: old?.desiredEnabled ? 'pending-restart' : 'disabled', loadError: null,
        permissions: [...item.manifest.permissions], approvedPermissions: [...payload.approvedPermissions],
        configuration: old?.configuration ?? Object.fromEntries(item.manifest.configuration.map(field => [field.key, field.defaultValue])),
        activeConfiguration: old?.activeConfiguration ?? null, configurationFields: clonePluginData(item.manifest.configuration), activeUi: old?.activeUi ?? null,
      }
      mock.states.set(id, next)
      return clonePluginData(next)
    }
    api.setPluginEnabled = async (id, payload) => {
      calls.enabled.push({ id, payload: clonePluginData(payload) })
      const state = stateFor(id)
      if (payload.enabled && (payload.expectedRevision !== state.revision || payload.installedVersion !== state.installedVersion)) {
        throw new ApiError('installation changed; refresh and confirm again', 409, 'PLUGIN_REVISION_CONFLICT')
      }
      return setState(id, { desiredEnabled: payload.enabled, admissionEnabled: false, requiresRestart: payload.enabled,
        revision: state.revision + 1, status: payload.enabled ? 'pending-restart' : 'disabled', activeUi: null,
        ...(payload.approvedPermissions ? { approvedPermissions: payload.approvedPermissions } : {}) })
    }
    api.configurePlugin = async (id, payload) => {
      calls.configuration.push({ id, payload: clonePluginData(payload) })
      if (mock.configure) return mock.configure(id, payload)
      const state = stateFor(id)
      if (payload.expectedRevision !== state.revision || payload.installedVersion !== state.installedVersion) {
        throw new ApiError('configuration changed; refresh before saving', 409, 'PLUGIN_REVISION_CONFLICT')
      }
      return setState(id, { configuration: payload.values, revision: state.revision + 1,
        activeConfiguration: state.activeVersion === state.installedVersion ? payload.values : state.activeConfiguration })
    }
    api.uninstallPlugin = async id => {
      calls.uninstall.push(id)
      return setState(id, { installed: false, desiredEnabled: false, admissionEnabled: false, requiresRestart: false, status: 'uninstalled', activeUi: null })
    }
    for (const name of ['testChannelModel', 'testOAuthAccountModel']) api[name] = async () => {
      calls.legacy.push(name)
      throw new Error(`Plugin UI must not dispatch legacy helper ${name}`)
    }
  }

  async function mount(name, props = {}) {
    const container = document.createElement('div')
    document.body.append(container)
    const properties = reactive({ ...props })
    const app = createApp({ render: () => h(components.get(name), { ...properties }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(container)
    const entry = { app, container, state: captures.get(name), setProps: async patch => { Object.assign(properties, patch); await settle() },
      unmount() { app.unmount(); container.remove() } }
    mounted.push(entry)
    await settle()
    return entry
  }
  async function hostFor(fixture = makePluginFixture(), props = {}) {
    setFixtures(fixture)
    const entry = await mount('PluginDiagnosticHost', { contribution: fixture.contribution, context: { kind: 'channel', id: channel.id }, ...props })
    await waitFor(() => !entry.state.loading.value, 'plugin resources')
    return entry
  }

  async function mountDiagnosticSurface(name, fixture = makeOfficialPluginFixture()) {
    setFixtures(fixture)
    captures.delete('PluginDiagnosticHost')
    const account = { id: 'account-1', name: 'Synthetic account', accountEmail: 'test@example.invalid', provider: 'chatgpt' }
    mock.targets = [makeTarget(name === 'OAuthModelTestModal'
      ? { channelId: account.id, protocols: ['responses'], credentials: [{ id: account.id, name: '账号原生授权', priority: 1 }] }
      : {})]
    const entry = await mount(name, name === 'OAuthModelTestModal' ? { account, models: ['test-model'] }
      : name === 'PluginMarketplaceView' ? {} : { channel })
    if (name === 'PluginMarketplaceView') {
      await waitFor(() => !entry.state.loading.value)
      button(entry.container, fixture.pageContribution.entry.label).click()
    }
    await waitFor(() => captures.has('PluginDiagnosticHost'), `${name} diagnostic mount`)
    return entry
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, envFile: false,
      cacheDir: join(tmpdir(), 'prism-plugin-ui-tests-' + process.pid), plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] },
      server: { middlewareMode: true, hmr: false, proxy: {} }, appType: 'custom' })
    api = (await server.ssrLoadModule('/src/api.ts')).api
    originals = Object.fromEntries([...genericMethods, 'testChannelModel', 'testOAuthAccountModel'].map(name => [name, api[name]]))
    toasts = (await server.ssrLoadModule('/src/toast.ts')).toasts
    ApiError = (await server.ssrLoadModule('/src/types/api.ts')).ApiError
    modelTestTargetKey = (await server.ssrLoadModule('/src/utils/modelTest.ts')).modelTestTargetKey
    PLUGIN_STATE_CHANGED = (await server.ssrLoadModule('/src/utils/pluginForm.ts')).PLUGIN_STATE_CHANGED
    const icon = await clientComponent('Icon')
    const empty = await clientComponent('TestEmptyState', { Icon: icon })
    const workbench = await clientComponent('TestWorkbench')
    const input = await clientComponent('PluginFieldInput')
    const result = await clientComponent('PluginDiagnosticResult')
    const diagnostic = await clientComponent('PluginDiagnosticHost', { Icon: icon, PluginFieldInput: input, PluginDiagnosticResult: result, TestEmptyState: empty, TestWorkbench: workbench })
    await clientComponent('PluginMarketplaceView', { Icon: icon, Modal: modalStub, PluginDiagnosticHost: diagnostic, PluginFieldInput: input, TestEmptyState: empty })
    await clientComponent('ChannelConnectivityPanel', { PluginDiagnosticHost: diagnostic, TestEmptyState: empty })
    await clientComponent('OAuthModelTestModal', { Modal: modalStub, PluginDiagnosticHost: diagnostic, TestEmptyState: empty })
    const records = { props: ['channelId', 'initialRecord'], setup() { recordMounts++; return () => h('div', { 'data-legacy-view': 'records' }, 'legacy records') } }
    await clientComponent('ChannelTestDrawer', { Icon: icon, Modal: modalStub, PluginDiagnosticHost: diagnostic, TestRecordsView: records })
  })
  beforeEach(() => {
    captures = new Map()
    calls = { catalog: [], plugins: [], state: [], contributions: [], targets: [], invocations: [], install: [], enabled: [], configuration: [], uninstall: [], legacy: [], fetch: [] }
    mock = { catalog: [], states: new Map(), contributions: [], targets: [makeTarget()] }
    confirmResult = false; confirmCalls = []; recordMounts = 0; diagnosticHostSetups = 0
    window.confirm = message => { confirmCalls.push(message); return confirmResult }
    globalThis.fetch = window.fetch = async (...args) => { calls.fetch.push(args); throw new Error('Plugin UI tests forbid unmocked network') }
    toasts.splice(0)
    setFixtures(makePluginFixture())
    installHooks()
  })
  afterEach(async () => {
    while (mounted.length) mounted.pop().unmount()
    await settle()
    toasts.splice(0)
    Object.assign(api, originals)
    globalThis.fetch = window.fetch = originalFetch
    window.confirm = originalConfirm
  })
  after(async () => { await server?.close(); dom.window.close() })

  it('renders and invokes every supported field kind for an unknown plugin without a per-ID component', async () => {
    const fixture = makePluginFixture()
    const { container, state } = await hostFor(fixture)
    assert.equal(state.loadError.value, '')
    assert.equal(field(container, '观察标签').type, 'text')
    assert.equal(field(container, '观察备注').tagName, 'TEXTAREA')
    assert.equal(field(container, '详细报告').type, 'checkbox')
    assert.equal(field(container, '样本数量').type, 'number')
    assert.equal(field(container, '报告风格').tagName, 'SELECT')
    for (const label of ['模型', '上游凭据', '协议', '检测范围']) assert.equal(field(container, label).tagName, 'SELECT')
    await change(field(container, '观察标签'), 'unknown plugin input')
    await change(field(container, '详细报告'), true)
    await change(field(container, '样本数量'), 3)
    await change(field(container, '报告风格'), 'full')
    button(container, fixture.contribution.entry.action.label).click()
    await waitFor(() => calls.invocations.length === 1 && !state.running.value)
    const call = calls.invocations[0]
    assert.equal(call.id, 'acme.synthetic')
    assert.equal(call.payload.actionId, fixture.contribution.entry.action.id)
    assert.deepEqual(call.payload.context, { kind: 'channel', id: channel.id })
    assert.equal(call.payload.expectedActiveVersion, '1.0.0')
    assert.equal(call.payload.values.verbose, true)
    assert.equal(call.payload.values.samples, 3)
    assert.equal(call.payload.values.style, 'full')
    assert.equal(call.payload.values.label, 'unknown plugin input')
    assert.equal(container.querySelectorAll('.plugin-result').length, 1)
    assert.equal(calls.legacy.length, 0)
    assert.equal(calls.fetch.length, 0)
  })

  for (const surface of ['channel', 'oauth-account', 'global page']) {
    it(`uses the complete generic API parsers with exact plugin and ${surface} resource URLs`, async () => {
      const fixture = makePluginFixture()
      const context = surface === 'global page' ? undefined : { kind: surface, id: `${surface} /one+?&#目标` }
      const contribution = surface === 'channel' ? fixture.contribution : surface === 'oauth-account' ? fixture.accountContribution : fixture.pageContribution
      setFixtures(fixture)
      mock.targets = [makeTarget({ channelId: context?.id ?? channel.id, ...(surface === 'oauth-account'
        ? { credentials: [{ id: context.id, name: '账号原生授权', priority: 1 }] } : {}) })]
      for (const name of ['getPlugin', 'listPluginTargets', 'invokePlugin']) api[name] = originals[name]
      const pluginUrl = `/admin/api/plugins/${encodeURIComponent(fixture.manifest.id)}`
      const resourceUrl = `${pluginUrl}/resources/model-test-targets${context ? '?' + new URLSearchParams({ contextKind: context.kind, contextId: context.id }) : ''}`
      const requests = []
      globalThis.fetch = window.fetch = async (input, init = {}) => {
        const url = new URL(String(input), window.location.origin)
        const method = init.method ?? 'GET'
        const path = url.pathname + url.search
        requests.push({ method, path })
        let value
        if (method === 'GET' && path === pluginUrl) value = fixture.state
        else if (method === 'GET' && path === resourceUrl) value = mock.targets
        else if (method === 'POST' && path === `${pluginUrl}/invocations`) {
          const payload = JSON.parse(init.body)
          assert.deepEqual(payload.context, context ?? { kind: 'channel', id: channel.id })
          value = makeExecution({ pluginId: fixture.manifest.id, activeVersion: payload.expectedActiveVersion, context: payload.context, values: payload.values, target: mock.targets[0] })
        } else assert.fail(`Unexpected mock request ${method} ${path}`)
        return new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
      }
      const entry = await mount('PluginDiagnosticHost', { contribution, context })
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.state.loadError.value, '')
      await entry.state.run(); await settle()
      assert.equal(entry.state.rows.value[0].error, '')
      assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
      assert.deepEqual(requests, [
        { method: 'GET', path: pluginUrl }, { method: 'GET', path: resourceUrl },
        { method: 'GET', path: pluginUrl }, { method: 'POST', path: `${pluginUrl}/invocations` },
      ])
    })
  }

  it('keeps scalar zero false and empty-number null and resets text to the active configuration', async () => {
    const numberEvents = []
    const number = await mount('PluginFieldInput', { field: makeField('factor', '比例', 'number', { min: 0, max: 5 }), modelValue: 2, 'onUpdate:modelValue': value => numberEvents.push(value) })
    await change(field(number.container, '比例'), 0)
    await change(field(number.container, '比例'), '')
    assert.deepEqual(numberEvents, [0, null])
    const booleanEvents = []
    const toggle = await mount('PluginFieldInput', { field: makeField('flag', '开关', 'boolean'), modelValue: true, 'onUpdate:modelValue': value => booleanEvents.push(value) })
    await change(field(toggle.container, '开关'), false)
    assert.deepEqual(booleanEvents, [false])
    const fixture = makePluginFixture({ state: { configuration: { defaultPrompt: 'pending default', threshold: 0.5 }, activeConfiguration: { defaultPrompt: 'active default', threshold: 0.5 } } })
    const entry = await hostFor(fixture)
    assert.equal(field(entry.container, '本次提示词').value, 'active default')
    await change(field(entry.container, '本次提示词'), 'draft override')
    const meta = field(entry.container, '本次提示词').closest('label').nextElementSibling
    button(meta, '恢复默认').click(); await settle()
    assert.equal(field(entry.container, '本次提示词').value, 'active default')
  })

  it('clears an optional enum to null but never adds a blank choice to required enums', async () => {
    const events = []
    const optional = await mount('PluginFieldInput', {
      field: makeField('mode', '可选模式', 'select', { options: [{ value: 'brief', label: '简洁' } ] }),
      modelValue: 'brief', 'onUpdate:modelValue': value => events.push(value),
    })
    const optionalSelect = field(optional.container, '可选模式')
    assert.equal([...optionalSelect.options].some(option => option.value === '' && option.textContent === '未设置'), true)
    await change(optionalSelect, '')
    assert.deepEqual(events, [null])

    const required = await mount('PluginFieldInput', {
      field: makeField('requiredMode', '必选模式', 'select', { required: true, options: [{ value: 'brief', label: '简洁' }] }),
      modelValue: 'brief',
    })
    assert.equal([...field(required.container, '必选模式').options].some(option => option.value === ''), false)
  })

  it('submits untouched optional number and select fields as null while preserving explicit defaults', async () => {
    const optionalFields = [
      makeField('optionalCount', '可选数量', 'number'),
      makeField('optionalMode', '可选模式', 'select', { options: [{ value: 'brief', label: '简洁' }] }),
      makeField('zero', '默认零', 'number', { defaultValue: 0 }),
      makeField('flag', '默认关闭', 'boolean', { defaultValue: false }),
      makeField('mode', '默认模式', 'select', { defaultValue: 'brief', options: [{ value: 'brief', label: '简洁' }] }),
    ]
    const fixture = makePluginFixture({ extraFields: optionalFields, configurationFields: [
      makeField('label', '配置标签', 'text', { defaultValue: 'original' }), ...optionalFields,
    ], state: {
      configuration: { label: 'original', zero: 0, flag: false },
      activeConfiguration: { label: 'original', zero: 0, flag: false },
    } })
    const diagnostic = await hostFor(fixture)
    await diagnostic.state.run(); await settle()
    const expected = { optionalCount: null, optionalMode: null, zero: 0, flag: false, mode: 'brief' }
    for (const [key, value] of Object.entries(expected)) assert.equal(calls.invocations[0].payload.values[key], value, key)
    diagnostic.unmount(); mounted.splice(mounted.indexOf(diagnostic), 1)
    const market = await mount('PluginMarketplaceView')
    await waitFor(() => !market.state.loading.value)
    await change(field(market.container, '配置标签'), 'changed')
    button(market.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 1 && !market.state.busy.value)
    assert.deepEqual(calls.configuration[0].payload.values, { label: 'changed', ...expected })
  })

  it('blocks optional bad numeric input even when its DOM value is empty and accepts a real clear', async () => {
    const fixture = makePluginFixture({ extraFields: [makeField('optionalCount', '可选数量', 'number')] })
    const entry = await hostFor(fixture)
    const input = field(entry.container, '可选数量')
    input.value = ''
    let badInput = true
    Object.defineProperty(input, 'validity', { configurable: true, get: () => ({ badInput }) })
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    assert.equal(Number.isNaN(entry.state.form.optionalCount), true)
    assert.match(entry.state.formError.value, /可选数量必须是数字/)
    assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, true)
    await entry.state.run()
    assert.equal(calls.invocations.length, 0)
    badInput = false
    await change(input, '')
    assert.equal(entry.state.form.optionalCount, null)
    assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, false)
    await entry.state.run(); await settle()
    assert.equal(calls.invocations[0].payload.values.optionalCount, null)
  })

  it('preserves native bad-number drafts through model echoes and syncs external numeric resets', async () => {
    const fixture = makePluginFixture({ extraFields: [makeField('optionalCount', '可选数量', 'number')] })
    const entry = await hostFor(fixture)
    const input = field(entry.container, '可选数量')
    const valueProperty = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
    const writes = []
    let badInput = false
    Object.defineProperty(input, 'value', {
      configurable: true,
      get() { return valueProperty.get.call(this) },
      set(value) {
        writes.push(String(value))
        badInput = false // Like a browser assignment, this erases the native unfinished draft.
        valueProperty.set.call(this, value)
      },
    })
    Object.defineProperty(input, 'validity', { configurable: true, get: () => ({ badInput }) })
    for (const draft of ['-', '1e']) {
      // Native number controls expose an empty DOM value for these incomplete edits.
      valueProperty.set.call(input, '')
      badInput = true
      input.dispatchEvent(new Event('input', { bubbles: true }))
      await settle()
      entry.state.form.label = `unrelated render during ${draft}`
      await settle()
      assert.deepEqual(writes, [], `${draft} must not be overwritten by a model echo or render`)
      assert.equal(input.validity.badInput, true)
      assert.equal(Number.isNaN(entry.state.form.optionalCount), true)
      assert.match(entry.state.formError.value, /可选数量必须是数字/)
      assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, true)
      await entry.state.run()
    }
    assert.equal(calls.invocations.length, 0)
    badInput = false
    valueProperty.set.call(input, '1e2')
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    assert.equal(entry.state.form.optionalCount, 100)
    assert.equal(input.value, '1e2', 'a valid exponent draft is also preserved on echo')
    assert.deepEqual(writes, [])
    entry.state.form.optionalCount = 3
    await settle()
    assert.equal(input.value, '3')
    assert.deepEqual(writes, ['3'])
    valueProperty.set.call(input, '')
    badInput = true
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    entry.state.form.optionalCount = null
    await settle()
    assert.equal(input.value, '')
    assert.equal(input.validity.badInput, false)
    assert.deepEqual(writes, ['3', ''])
    assert.equal(entry.state.formError.value, '')
    assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, false)
  })

  it('uses real scalar events and descriptor validation to block invalid diagnostics', async () => {
    const entry = await hostFor()
    const runButton = () => entry.container.querySelector('.test-workbench-actions .button.primary')
    assert.equal(entry.state.form.verbose, false)
    assert.equal(entry.state.form.samples, 2)
    assert.equal(field(entry.container, '观察标签').required, true)
    assert.equal(field(entry.container, '观察标签').maxLength, 80)
    assert.equal(field(entry.container, '样本数量').min, '1')
    assert.equal(field(entry.container, '样本数量').max, '5')
    const input = field(entry.container, '观察标签')
    assert.match(text(entry.container.querySelector(`[id="${input.getAttribute('aria-describedby')}"]`)), /普通文本/)
    await change(input, '   ')
    assert.equal(runButton().disabled, true)
    assert.match(entry.state.formError.value, /观察标签/)
    await entry.state.run()
    await change(input, 'valid label')
    for (const value of [0, 6, '']) {
      await change(field(entry.container, '样本数量'), value)
      assert.equal(runButton().disabled, true)
      assert.match(entry.state.formError.value, /样本数量/)
      await entry.state.run()
    }
    assert.equal(entry.state.form.samples, null)
    await change(field(entry.container, '样本数量'), 2)
    entry.state.form.style = 'undeclared-option'
    await nextTick()
    assert.equal(runButton().disabled, true)
    assert.match(entry.state.formError.value, /报告风格/)
    await entry.state.run()
    assert.equal(calls.invocations.length, 0)
    await change(field(entry.container, '报告风格'), 'full')
    assert.equal(runButton().disabled, false)
    await entry.state.run(); await settle()
    assert.equal(calls.invocations[0].payload.values.verbose, false)
    assert.equal(calls.invocations[0].payload.values.samples, 2)
  })

  it('binds a single global-page invocation to the selected model protocol and opaque credential', async () => {
    const fixture = makePluginFixture()
    mock.targets = [makeTarget({ model: 'model-a' }), makeTarget({ channelId: 'channel-2', model: 'model-b', protocols: ['gemini', 'responses'], credentials: [
      { id: 'default-b', name: 'Default B', priority: 1 }, { id: 'selected-b', name: 'Selected B', priority: 2 },
    ] })]
    const entry = await hostFor(fixture, { contribution: fixture.pageContribution, context: undefined, initialModel: 'model-b' })
    assert.equal(entry.state.selectedTargetKey.value, modelTestTargetKey('channel-2', 'model-b'))
    await change(field(entry.container, '模型'), modelTestTargetKey(channel.id, 'model-a'))
    await change(field(entry.container, '模型'), modelTestTargetKey('channel-2', 'model-b'))
    await change(field(entry.container, '上游凭据'), 'selected-b')
    await change(field(entry.container, '协议'), 'responses')
    await entry.state.run(); await settle()
    assert.equal(calls.invocations.length, 1)
    assert.deepEqual(calls.invocations[0].payload.context, { kind: 'channel', id: 'channel-2' })
    assert.equal(calls.invocations[0].payload.actionId, fixture.pageContribution.entry.action.id)
    assert.equal(calls.invocations[0].payload.values.model, 'model-b')
    assert.equal(calls.invocations[0].payload.values.protocol, 'responses')
    assert.equal(calls.invocations[0].payload.values.keyId, 'selected-b')
    assert.equal(calls.invocations[0].payload.values.scope, 'single')
    assert.equal(entry.state.rows.value[0].keyName, 'Selected B')
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
  })

  it('keeps active-old diagnostics separate from desired-new configuration metadata', async () => {
    const old = makePluginFixture({ version: '1.0.0' })
    const next = makePluginFixture({ version: '2.0.0',
      configurationFields: [makeField('futurePrompt', '新版本配置', 'textarea', { required: true, defaultValue: 'new default', maxLength: 4000 })],
      extraFields: [makeField('futureField', '未来诊断参数', 'text', { defaultValue: 'future descriptor default' })],
    })
    next.state = { ...next.state, activeVersion: '1.0.0', activeUi: clonePluginData(old.ui), activeConfiguration: { defaultPrompt: 'old running configuration', threshold: 0.5 }, configuration: { futurePrompt: 'pending new configuration' }, requiresRestart: true, status: 'pending-restart' }
    setFixtures(old, next)
    mock.states.set(next.state.id, clonePluginData(next.state))
    mock.contributions = old.contributions
    const host = await mount('PluginDiagnosticHost', { contribution: old.contribution, context: { kind: 'channel', id: channel.id } })
    await waitFor(() => !host.state.loading.value)
    assert.equal(host.state.form.prompt, 'old running configuration')
    assert.equal(host.state.fields.value.some(field => field.key === 'futureField'), false)
    assert.equal(host.state.fields.value.some(field => field.key === 'label'), true)
    await host.state.run(); await settle()
    assert.equal(calls.invocations[0].payload.expectedActiveVersion, '1.0.0')
    assert.equal(calls.invocations[0].payload.values.prompt, 'old running configuration')
    host.unmount(); mounted.splice(mounted.indexOf(host), 1)
    const market = await mount('PluginMarketplaceView')
    await waitFor(() => !market.state.loading.value)
    assert.ok(field(market.container, '新版本配置'))
    assert.match(text(market.container.querySelector('.plugin-market-restart')), /重启/)
    assert.equal(fact(market.container, '安装选择'), '2.0.0')
    assert.equal(fact(market.container, '当前生效'), '1.0.0')
    assert.equal(market.state.pageEntries.value[0].action.id, old.pageContribution.entry.action.id)
    market.state.diagnosticEntryId.value = old.pageContribution.entry.id
    await settle()
    assert.equal(market.state.diagnosticContribution.value.activeVersion, '1.0.0')
    assert.deepEqual(market.state.diagnosticContribution.value.entry, old.pageContribution.entry)
    await change(field(market.container, '新版本配置'), 'saved future configuration')
    button(market.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 1 && !market.state.busy.value)
    assert.deepEqual(calls.configuration[0].payload.values, { futurePrompt: 'saved future configuration' })
    assert.equal(market.state.selectedState.value.activeConfiguration.defaultPrompt, 'old running configuration')
    assert.equal(market.state.diagnosticContribution.value.activeVersion, '1.0.0')
    assert.equal(captures.get('PluginDiagnosticHost').defaultValues.value.prompt, 'old running configuration')
  })

  it('refreshes same-version defaults for future inputs without changing a captured invocation', async () => {
    const fixture = makePluginFixture()
    const entry = await hostFor(fixture)
    const oldPrompt = entry.state.form.prompt
    const pending = deferred()
    mock.invoke = () => pending.promise
    const run = entry.state.run()
    await waitFor(() => calls.invocations.length === 1)
    const oldCall = calls.invocations[0]
    const newConfiguration = { defaultPrompt: 'new active default', threshold: 0.75 }
    setState(fixture.manifest.id, { configuration: newConfiguration, activeConfiguration: newConfiguration })
    window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
    await waitFor(() => !entry.state.loading.value && entry.state.defaultValues.value.prompt === newConfiguration.defaultPrompt)
    assert.equal(entry.state.form.prompt, newConfiguration.defaultPrompt, 'Unedited future input follows the active configuration')
    assert.equal(oldCall.payload.values.prompt, oldPrompt)
    pending.resolve(responseFor(oldCall))
    await run; await settle()
    const meta = field(entry.container, '本次提示词').closest('label').nextElementSibling
    button(meta, '恢复默认').click(); await settle()
    assert.equal(entry.state.form.prompt, newConfiguration.defaultPrompt)
    mock.invoke = undefined
    await entry.state.run(); await settle()
    assert.equal(calls.invocations[1].payload.values.prompt, newConfiguration.defaultPrompt)
    assert.equal(oldCall.payload.values.prompt, oldPrompt)
  })

  for (const edited of [false, true]) {
    it(`uses the configuration confirmed by the run preflight while ${edited ? 'preserving explicit edits' : 'refreshing untouched defaults'}`, async () => {
      const fixture = makePluginFixture()
      const entry = await hostFor(fixture)
      if (edited) await change(field(entry.container, '本次提示词'), 'explicit request value')
      const updated = { defaultPrompt: 'default changed by another session', threshold: 0.9 }
      mock.getState = id => Promise.resolve(setState(id, { activeConfiguration: updated, configuration: updated }))
      await entry.state.run()
      await settle()
      assert.equal(calls.invocations.length, 1)
      assert.equal(calls.invocations[0].payload.values.prompt, edited ? 'explicit request value' : updated.defaultPrompt)
      mock.getState = undefined
    })
  }

  for (const edited of [false, true]) {
    it(`refreshes untouched diagnostic defaults while ${edited ? 'retaining explicit zero, false and prompt edits' : 'following all active defaults'}`, async () => {
      const fixture = makePluginFixture({ extraFields: [
        makeField('threshold', '本次阈值', 'number', { defaultValue: 0.5, min: 0, max: 1 }),
        makeField('flag', '本次开关', 'boolean', { defaultValue: true }),
        makeField('label', '本次标签', 'text', { defaultValue: 'old label' }),
      ] })
      const entry = await hostFor(fixture)
      if (edited) {
        await change(field(entry.container, '本次提示词'), 'explicit draft')
        await change(field(entry.container, '本次阈值'), 0)
        await change(field(entry.container, '本次开关'), false)
      }
      const updated = { defaultPrompt: 'fresh default', threshold: 0.8, flag: true, label: 'fresh label' }
      setState(fixture.manifest.id, { activeConfiguration: updated })
      await entry.state.load(false); await settle()
      assert.equal(entry.state.form.prompt, edited ? 'explicit draft' : updated.defaultPrompt)
      assert.equal(entry.state.form.threshold, edited ? 0 : updated.threshold)
      assert.equal(entry.state.form.flag, edited ? false : updated.flag)
      assert.equal(entry.state.form.label, 'fresh label')
      const prompt = fixture.contribution.entry.action.fields.find(item => item.key === 'prompt')
      entry.state.resetField(prompt)
      setState(fixture.manifest.id, { activeConfiguration: { ...updated, defaultPrompt: 'newer default' } })
      await entry.state.load(false); await settle()
      assert.equal(entry.state.form.prompt, 'newer default', 'Reset removes the local override and resumes following defaults')
      await entry.state.run(); await settle()
      assert.equal(calls.invocations.at(-1).payload.values.prompt, 'newer default')
    })
  }

  it('uses the newest observed revision when an older run preflight returns late', async () => {
    const fixture = makePluginFixture()
    const entry = await hostFor(fixture)
    const stale = { ...stateFor(fixture.manifest.id), revision: 1 }
    const latestConfiguration = { defaultPrompt: 'newer observed default', threshold: 0.9 }
    const latest = { ...stateFor(fixture.manifest.id), revision: 2, activeConfiguration: latestConfiguration, configuration: latestConfiguration }
    const pendingPreflight = deferred()
    mock.getState = () => pendingPreflight.promise
    const initialReads = calls.state.length
    const run = entry.state.run()
    await waitFor(() => calls.state.length > initialReads)

    mock.getState = () => Promise.resolve(clonePluginData(latest))
    await entry.state.load(false)
    assert.equal(entry.state.form.prompt, latestConfiguration.defaultPrompt)
    pendingPreflight.resolve(stale)
    await run

    assert.equal(calls.invocations.length, 1)
    assert.equal(calls.invocations[0].payload.values.prompt, latestConfiguration.defaultPrompt)
    assert.equal(entry.state.form.prompt, latestConfiguration.defaultPrompt)
  })

  it('preserves same-context diagnostic edits when re-reading after a background refresh failure', async () => {
    const entry = await hostFor()
    await change(field(entry.container, '本次提示词'), 'unsent retry draft')
    mock.getState = () => Promise.reject(new Error('temporary refresh failure'))
    await entry.state.load(false); await settle()
    assert.match(entry.state.loadError.value, /temporary refresh failure/)
    mock.getState = undefined
    button(entry.container, '重新读取').click()
    await waitFor(() => !entry.state.loading.value && !entry.state.loadError.value)
    assert.equal(field(entry.container, '本次提示词').value, 'unsent retry draft')
    await entry.state.run(); await settle()
    assert.equal(calls.invocations.at(-1).payload.values.prompt, 'unsent retry draft')
  })

  for (const refresh of ['pending', 'failed']) {
    it(`keeps stop available and suppresses queued work during a ${refresh} background refresh`, async () => {
      mock.targets = Array.from({ length: 4 }, (_, index) => makeTarget({ model: `model-${index}` }))
      const entry = await hostFor()
      await change(field(entry.container, '检测范围'), 'all')
      const upstream = deferred()
      mock.invoke = () => upstream.promise
      const run = entry.state.run()
      await waitFor(() => calls.invocations.length === 2)
      const discovery = deferred()
      mock.getState = () => discovery.promise
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => entry.state.loading.value)
      if (refresh === 'failed') {
        discovery.reject(new Error('background failure while batch runs'))
        await waitFor(() => !entry.state.loading.value && !!entry.state.loadError.value)
      }
      button(entry.container, '停止等待').click(); await settle()
      assert.equal(entry.state.running.value, false)
      assert.equal(calls.invocations.length, 2)
      if (refresh === 'pending') discovery.resolve(stateFor('acme.synthetic'))
      upstream.resolve(responseFor(calls.invocations[0]))
      await run; await settle()
      assert.equal(calls.invocations.length, 2, 'Stopping while config is unavailable cannot send the remaining batch')
      assert.equal(entry.state.rows.value.filter(row => row.waitingStopped).length, 4)
    })
  }

  for (const surface of ['ChannelTestDrawer', 'ChannelConnectivityPanel', 'OAuthModelTestModal']) {
    it(`prunes unavailable historical versions before showing the replacement on ${surface}`, async () => {
      const fixture = makeOfficialPluginFixture()
      const entry = await mountDiagnosticSurface(surface, fixture)
      await waitFor(() => captures.get('PluginDiagnosticHost').loading.value === false)
      const contribution = surface === 'OAuthModelTestModal' ? fixture.accountContribution : fixture.contribution
      entry.state.markUnavailable({ pluginId: contribution.pluginId, activeVersion: contribution.activeVersion, entryId: contribution.entry.id })
      await settle()
      assert.equal(entry.state.invalidContributions.value.size, 1)
      const next = makeOfficialPluginFixture({ version: '2.0.0' })
      mock.states.set(next.state.id, clonePluginData(next.state))
      mock.contributions = clonePluginData(next.contributions)
      await entry.state.load(true); await settle()
      assert.equal(entry.state.invalidContributions.value.size, 0)
      assert.doesNotMatch(text(entry.container), /此生效版本.*已不可用/)
      if (surface === 'ChannelTestDrawer') button(entry.container, next.contribution.entry.label).click()
      await waitFor(() => captures.get('PluginDiagnosticHost').props.contribution.activeVersion === '2.0.0' &&
        captures.get('PluginDiagnosticHost').loading.value === false)
      await captures.get('PluginDiagnosticHost').run(); await settle()
      assert.equal(calls.invocations.at(-1).payload.expectedActiveVersion, '2.0.0')
    })

    it(`does not let a late confirmation clear a newer unavailability event on ${surface}`, async () => {
      const fixture = makeOfficialPluginFixture()
      const entry = await mountDiagnosticSurface(surface, fixture)
      await waitFor(() => captures.get('PluginDiagnosticHost').loading.value === false)
      const contribution = surface === 'OAuthModelTestModal' ? fixture.accountContribution : fixture.contribution
      const unavailable = { pluginId: contribution.pluginId, activeVersion: contribution.activeVersion, entryId: contribution.entry.id }
      entry.state.markUnavailable(unavailable); await settle()
      const other = makePluginFixture({ id: 'example.concurrent-unavailable' })
      if (surface === 'ChannelTestDrawer') {
        mock.states.set(other.state.id, clonePluginData(other.state))
        mock.contributions.push(clonePluginData(other.contribution))
      }
      const live = deferred()
      mock.getState = () => live.promise
      const reads = calls.state.length
      const loading = entry.state.load(true)
      await waitFor(() => calls.state.length === reads + 1)
      entry.state.markUnavailable(surface === 'ChannelTestDrawer' ? {
        pluginId: other.manifest.id, activeVersion: other.manifest.version, entryId: other.contribution.entry.id,
      } : unavailable); await settle()
      live.resolve(clonePluginData(fixture.state))
      await loading; await settle()
      assert.equal(entry.state.invalidContributions.value.size, surface === 'ChannelTestDrawer' ? 2 : 1)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
    })
  }

  it('commits a new drawer list before slow rechecks and isolates a failed entry from other recoveries', async () => {
    const removed = makePluginFixture({ id: 'example.removed' })
    const failing = makePluginFixture({ id: 'example.failing' })
    const recovered = makePluginFixture({ id: 'example.recovered' })
    setFixtures(removed, failing, recovered)
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => captures.get('PluginDiagnosticHost').loading.value === false)
    for (const fixture of [failing, recovered]) entry.state.markUnavailable({
      pluginId: fixture.manifest.id, activeVersion: '1.0.0', entryId: fixture.contribution.entry.id,
    })
    await settle()
    const removedTab = entry.container.querySelector('[role=tab][aria-selected=true]')
    removedTab.focus()
    setState(removed.manifest.id, { admissionEnabled: false })
    const live = deferred()
    mock.getState = id => id === failing.manifest.id ? live.promise : stateFor(id)
    const failingReads = calls.state.filter(call => call.id === failing.manifest.id).length
    const loading = entry.state.load(true)
    await waitFor(() => calls.state.filter(call => call.id === failing.manifest.id).length > failingReads)
    assert.equal(entry.state.contributions.value.some(item => item.pluginId === removed.manifest.id), false)
    assert.equal(removedTab.isConnected, false, 'An already removed plugin must leave before a slow recheck settles')
    assert.equal(document.activeElement.textContent, '智商')
    live.reject(new Error('isolated confirmation failure'))
    await loading; await settle()
    const failingKey = entry.state.contributionVersionKey(failing.contribution)
    const recoveredKey = entry.state.contributionVersionKey(recovered.contribution)
    assert.equal(entry.state.invalidContributions.value.has(failingKey), true)
    assert.equal(entry.state.invalidContributions.value.has(recoveredKey), false)
    assert.equal(entry.state.availableContributions.value.some(item => item.pluginId === recovered.manifest.id), true)
    assert.equal(entry.state.availableContributions.value.some(item => item.pluginId === failing.manifest.id), false)
    assert.match(entry.state.loadError.value, /isolated confirmation failure/)
    mock.getState = undefined
    await entry.state.load(true); await settle()
    assert.equal(entry.state.invalidContributions.value.size, 0)
    assert.equal(entry.state.loadError.value, '')
  })

  it('preserves the pre-resolved account model and protocol subset without a credential selector', async () => {
    const fixture = makePluginFixture()
    mock.targets = [
      makeTarget({ channelId: 'account-1', model: 'allowed-model', protocols: ['chat_completions', 'responses'], credentials: [{ id: 'account-1', name: '账号原生授权', priority: 1 }] }),
      makeTarget({ channelId: 'account-1', model: 'not-in-pre-resolved-list', protocols: ['responses'] }),
      makeTarget({ channelId: 'account-1', model: 'no-supported-protocol', protocols: ['gemini'] }),
      makeTarget({ channelId: 'other-account', model: 'allowed-model', protocols: ['responses'] }),
    ]
    const entry = await hostFor(fixture, { contribution: fixture.accountContribution, context: { kind: 'oauth-account', id: 'account-1' }, accountModels: ['allowed-model', 'no-supported-protocol'], accountProtocols: ['responses'] })
    assert.deepEqual(entry.state.targets.value.map(target => target.model), ['allowed-model'])
    assert.deepEqual([...field(entry.container, '协议').options].map(option => option.value), ['responses'])
    assert.equal([...entry.container.querySelectorAll('label')].some(label => text(label).includes('上游凭据')), false)
    assert.equal(Object.hasOwn(entry.state.form, 'keyId'), false)
    await entry.state.run(); await settle()
    const request = calls.invocations[0].payload
    assert.deepEqual(request.context, { kind: 'oauth-account', id: 'account-1' })
    assert.equal(request.values.model, 'allowed-model')
    assert.equal(request.values.protocol, 'responses')
    assert.equal(Object.hasOwn(request.values, 'keyId'), false)
    assert.equal(Object.hasOwn(request.values, 'scope'), false)
  })

  it('keeps official channel and account wrappers thin and descriptor-driven', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    const wrapper = await mount('ChannelConnectivityPanel', { channel })
    await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
    await captures.get('PluginDiagnosticHost').run(); await settle()
    assert.equal(calls.invocations[0].id, 'prism.connectivity')
    assert.equal(calls.invocations[0].payload.actionId, fixture.contribution.entry.action.id)
    wrapper.unmount(); mounted.splice(mounted.indexOf(wrapper), 1)
    const unknown = makePluginFixture()
    setFixtures(unknown)
    mock.targets = [makeTarget({ channelId: 'account-1', model: 'pre-resolved', protocols: ['responses', 'gemini'], credentials: [{ id: 'account-1', name: '账号原生授权', priority: 1 }] })]
    const account = { id: 'account-1', name: 'Synthetic account', accountEmail: 'test@example.invalid', provider: 'chatgpt' }
    const modal = await mount('OAuthModelTestModal', { account, models: ['pre-resolved'] })
    await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
    const state = captures.get('PluginDiagnosticHost')
    assert.deepEqual(state.targets.value[0].protocols, ['responses'])
    assert.equal(modal.container.querySelectorAll('input[type="password"]').length, 0)
    assert.equal([...modal.container.querySelectorAll('label')].some(label => text(label).includes('上游凭据')), false)
    await state.run(); await settle()
    assert.equal(calls.invocations.at(-1).id, unknown.manifest.id)
    assert.equal(calls.invocations.at(-1).payload.actionId, unknown.accountContribution.entry.action.id)
    assert.deepEqual(calls.legacy, [])
  })

  for (const completion of ['response', 'error']) {
    it(`keeps the channel host invocation draft and result through a deferred contribution refresh ${completion}`, async () => {
      const fixture = makeOfficialPluginFixture()
      const entry = await mountDiagnosticSurface('ChannelConnectivityPanel', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const hostElement = entry.container.querySelector('.plugin-diagnostic-host')
      const hostKey = entry.state.hostKey.value
      const setups = diagnosticHostSetups
      const prompt = 'retain my channel diagnostic draft'
      await change(field(entry.container, '本次提示词'), prompt)
      const invocation = deferred()
      mock.invoke = () => invocation.promise
      const run = host.run()
      await waitFor(() => calls.invocations.length === 1)
      const call = calls.invocations[0]
      const discovery = deferred()
      mock.listContributions = () => discovery.promise
      const reads = calls.contributions.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && !host.loading.value)
      assert.equal(entry.state.loading.value, true)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.state.hostKey.value, hostKey)
      assert.equal(call.options.signal.aborted, false)
      assert.equal(host.running.value, true)
      assert.equal(host.form.prompt, prompt)
      invocation.resolve(responseFor(call))
      await run; await settle()
      const resultElement = entry.container.querySelector('.plugin-result')
      assert.ok(resultElement, 'the invocation completes while contribution discovery is pending')
      if (completion === 'response') discovery.resolve(clonePluginData(fixture.contributions))
      else discovery.reject(new Error('background channel discovery unavailable'))
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
      assert.equal(entry.container.querySelector('.plugin-result'), resultElement)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.state.hostKey.value, hostKey)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(field(entry.container, '本次提示词').value, prompt)
      assert.equal(host.rows.value[0].execution.outcome.success, true)
      assert.equal(call.options.signal.aborted, false)
      if (completion === 'error') {
        const alert = entry.container.querySelector('[role="alert"]')
        assert.match(text(alert), /background channel discovery unavailable/)
        assert.equal(hostElement.contains(alert), false)
        const retry = deferred()
        mock.listContributions = () => retry.promise
        button(alert, '重试').click()
        await waitFor(() => entry.state.loading.value)
        assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
        retry.resolve(clonePluginData(fixture.contributions))
        await waitFor(() => !entry.state.loading.value)
        assert.equal(entry.state.loadError.value, '')
        assert.equal(entry.container.querySelector('.plugin-result'), resultElement)
        assert.equal(diagnosticHostSetups, setups, 'retry does not remount a valid entry')
      }
    })
  }

  for (const changeKind of ['entry', 'version']) {
    it(`remounts the channel diagnostic only after a deferred refresh confirms a changed ${changeKind}`, async () => {
      const fixture = makeOfficialPluginFixture()
      const entry = await mountDiagnosticSurface('ChannelConnectivityPanel', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const setups = diagnosticHostSetups
      const oldKey = entry.state.hostKey.value
      const invocation = deferred()
      mock.invoke = () => invocation.promise
      const run = host.run()
      await waitFor(() => calls.invocations.length === 1)
      const call = calls.invocations[0]
      const next = makeOfficialPluginFixture({ version: changeKind === 'version' ? '2.0.0' : '1.0.0' })
      if (changeKind === 'entry') {
        next.contribution.entry.id = 'replacement-channel'
        next.state.activeUi.entries[0].id = 'replacement-channel'
      }
      const discovery = deferred()
      mock.listContributions = () => discovery.promise
      const reload = entry.state.load()
      await settle()
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(call.options.signal.aborted, false)
      mock.states.set(next.state.id, clonePluginData(next.state))
      discovery.resolve(clonePluginData(next.contributions))
      await reload
      await waitFor(() => diagnosticHostSetups === setups + 1 && !captures.get('PluginDiagnosticHost').loading.value)
      assert.notEqual(entry.state.hostKey.value, oldKey)
      assert.equal(call.options.signal.aborted, true)
      const replacement = captures.get('PluginDiagnosticHost')
      assert.equal(replacement.props.contribution.entry.id, next.contribution.entry.id)
      assert.equal(replacement.props.contribution.activeVersion, next.contribution.activeVersion)
      invocation.resolve(responseFor(call))
      await run; await settle()
      assert.equal(replacement.rows.value.length, 0)
      assert.equal(entry.container.querySelector('.plugin-result'), null)
    })
  }

  for (const failureStage of ['contributions', 'state']) {
    it(`recovers an invalid channel entry only after deferred event discovery and live confirmation, preserving invalid on ${failureStage} errors`, async () => {
      const fixture = makeOfficialPluginFixture()
      const entry = await mountDiagnosticSurface('ChannelConnectivityPanel', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const oldKey = entry.state.hostKey.value
      const key = entry.state.contributionKey(fixture.contribution)
      const setups = diagnosticHostSetups
      mock.getTargets = () => Promise.reject(new ApiError('disabled', 409, 'PLUGIN_DISABLED'))
      await host.load(); await settle()
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
      assert.match(text(entry.container), /诊断入口已失效/)
      mock.getTargets = undefined

      const failed = deferred()
      if (failureStage === 'contributions') mock.listContributions = () => failed.promise
      else mock.getState = () => failed.promise
      let reads = calls.contributions.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(diagnosticHostSetups, setups)
      failed.reject(new Error(`channel ${failureStage} discovery unavailable`))
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(diagnosticHostSetups, setups)
      assert.match(text(entry.container.querySelector('[role="alert"]')), /discovery unavailable/)

      const discovery = deferred()
      const live = deferred()
      mock.listContributions = () => discovery.promise
      mock.getState = () => live.promise
      reads = calls.contributions.length
      const stateReads = calls.state.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(diagnosticHostSetups, setups)
      discovery.resolve(clonePluginData(fixture.contributions))
      await waitFor(() => calls.state.length === stateReads + 1)
      assert.equal(entry.state.loading.value, true)
      assert.equal(entry.state.invalidContributions.value.has(key), true, 'discovery alone cannot restore an unconfirmed action')
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
      assert.equal(diagnosticHostSetups, setups)
      live.resolve(clonePluginData(fixture.state))
      await waitFor(() => diagnosticHostSetups === setups + 1 && !captures.get('PluginDiagnosticHost').loading.value)
      assert.notEqual(captures.get('PluginDiagnosticHost'), host)
      assert.notEqual(entry.state.hostKey.value, oldKey)
      assert.equal(entry.state.invalidContributions.value.size, 0)
      assert.equal(entry.state.diagnosticGenerations.value.get(key), 1)
      assert.equal(entry.state.loadError.value, '')
      assert.equal(captures.get('PluginDiagnosticHost').props.contribution.entry.action.id, fixture.contribution.entry.action.id)
    })
  }

  for (const mismatch of ['plugin', 'version', 'action', 'disabled', 'entry', 'placement', 'context', 'absent']) {
    it(`keeps the invalid channel entry excluded when refreshed live confirmation has a different ${mismatch}`, async () => {
      const fixture = makeOfficialPluginFixture()
      const entry = await mountDiagnosticSurface('ChannelConnectivityPanel', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const setups = diagnosticHostSetups
      mock.getTargets = () => Promise.reject(new ApiError('disabled', 409, 'PLUGIN_DISABLED'))
      await host.load(); await settle()
      mock.getTargets = undefined
      const live = clonePluginData(fixture.state)
      if (mismatch === 'plugin') live.id = 'other.plugin'
      if (mismatch === 'version') live.activeVersion = '2.0.0'
      if (mismatch === 'action') live.activeUi.entries[0].action.id = 'other-action'
      if (mismatch === 'disabled') live.admissionEnabled = false
      if (mismatch === 'entry') live.activeUi.entries[0].id = 'other-entry'
      if (mismatch === 'placement') live.activeUi.entries[0].placement = 'plugin-page'
      if (mismatch === 'context') live.activeUi.entries[0].contexts = ['oauth-account']
      mock.getState = () => live
      if (mismatch === 'absent') mock.listContributions = () => Promise.resolve([])
      const reads = calls.contributions.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && !entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.size, mismatch === 'absent' ? 0 : 1)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
    })
  }

  it('keeps the current channel host running while an event recovers an earlier invalid entry', async () => {
    const fixture = makeOfficialPluginFixture()
    const entry = await mountDiagnosticSurface('ChannelConnectivityPanel', fixture)
    await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
    const host = captures.get('PluginDiagnosticHost')
    const hostElement = entry.container.querySelector('.plugin-diagnostic-host')
    const oldKey = entry.state.hostKey.value
    const setups = diagnosticHostSetups
    const other = clonePluginData(fixture.contribution)
    other.entry.id = 'another-channel-entry'
    fixture.contributions.unshift(other)
    fixture.state.activeUi.entries.push(clonePluginData(other.entry))
    mock.states.set(fixture.state.id, clonePluginData(fixture.state))
    entry.state.markUnavailable({ pluginId: other.pluginId, activeVersion: other.activeVersion, entryId: other.entry.id })
    await change(field(entry.container, '本次提示词'), 'retain valid channel draft')
    const invocation = deferred()
    mock.invoke = () => invocation.promise
    const run = host.run()
    await waitFor(() => calls.invocations.length === 1)
    const call = calls.invocations[0]
    const discovery = deferred()
    mock.listContributions = () => discovery.promise
    const reads = calls.contributions.length
    window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
    await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value)
    assert.equal(entry.state.invalidContributions.value.size, 1)
    discovery.resolve(clonePluginData(fixture.contributions))
    await waitFor(() => !entry.state.loading.value)
    assert.equal(entry.state.invalidContributions.value.size, 0)
    assert.equal(entry.state.diagnosticGenerations.value.get(entry.state.contributionKey(other)), 1)
    assert.equal(entry.state.hostKey.value, oldKey)
    assert.equal(captures.get('PluginDiagnosticHost'), host)
    assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
    assert.equal(diagnosticHostSetups, setups)
    assert.equal(call.options.signal.aborted, false)
    assert.equal(host.running.value, true)
    invocation.resolve(responseFor(call))
    await run; await settle()
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
    assert.equal(field(entry.container, '本次提示词').value, 'retain valid channel draft')
  })

  for (const completion of ['response', 'error']) {
    it(`keeps the OAuth host invocation draft and result through a deferred contribution refresh ${completion}`, async () => {
      const fixture = makePluginFixture()
      const entry = await mountDiagnosticSurface('OAuthModelTestModal', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const hostElement = entry.container.querySelector('.plugin-diagnostic-host')
      const hostKey = entry.state.hostKey.value
      const setups = diagnosticHostSetups
      const prompt = 'retain my account diagnostic draft'
      await change(field(entry.container, '本次提示词'), prompt)
      const invocation = deferred()
      mock.invoke = () => invocation.promise
      const run = host.run()
      await waitFor(() => calls.invocations.length === 1)
      const call = calls.invocations[0]
      const discovery = deferred()
      mock.listContributions = () => discovery.promise
      const reads = calls.contributions.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && !host.loading.value)
      assert.equal(entry.state.loading.value, true)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.state.hostKey.value, hostKey)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(call.options.signal.aborted, false)
      assert.equal(host.running.value, true)
      assert.equal(host.form.prompt, prompt)
      invocation.resolve(responseFor(call))
      await run; await settle()
      const resultElement = entry.container.querySelector('.plugin-result')
      assert.ok(resultElement, 'the invocation can complete while contribution discovery is pending')
      if (completion === 'response') discovery.resolve(clonePluginData(fixture.contributions))
      else discovery.reject(new Error('background contribution discovery unavailable'))
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
      assert.equal(entry.container.querySelector('.plugin-result'), resultElement)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.state.hostKey.value, hostKey)
      assert.equal(field(entry.container, '本次提示词').value, prompt)
      assert.equal(host.rows.value[0].execution.outcome.success, true)
      assert.equal(call.options.signal.aborted, false)
      if (completion === 'error') {
        const alert = entry.container.querySelector('[role="alert"]')
        assert.match(text(alert), /background contribution discovery unavailable/)
        assert.equal(hostElement.contains(alert), false, 'discovery failures are shown alongside the existing host')
        const retry = deferred()
        mock.listContributions = () => retry.promise
        button(alert, '重试').click()
        await waitFor(() => entry.state.loading.value)
        assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
        retry.resolve(clonePluginData(fixture.contributions))
        await waitFor(() => !entry.state.loading.value)
        assert.equal(entry.state.loadError.value, '')
        assert.equal(entry.container.querySelector('.plugin-result'), resultElement)
        assert.equal(diagnosticHostSetups, setups, 'manual retry does not remount a valid entry')
      }
    })
  }

  for (const changeKind of ['entry', 'version']) {
    it(`remounts the OAuth diagnostic only after a deferred refresh confirms a changed ${changeKind}`, async () => {
      const fixture = makePluginFixture()
      const entry = await mountDiagnosticSurface('OAuthModelTestModal', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const setups = diagnosticHostSetups
      const oldKey = entry.state.hostKey.value
      const invocation = deferred()
      mock.invoke = () => invocation.promise
      const run = host.run()
      await waitFor(() => calls.invocations.length === 1)
      const call = calls.invocations[0]
      const next = makePluginFixture({ version: changeKind === 'version' ? '2.0.0' : '1.0.0' })
      if (changeKind === 'entry') {
        next.accountContribution.entry.id = 'replacement-account'
        next.state.activeUi.entries[1].id = 'replacement-account'
      }
      const discovery = deferred()
      mock.listContributions = () => discovery.promise
      const reload = entry.state.load()
      await settle()
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(call.options.signal.aborted, false)
      mock.states.set(next.state.id, clonePluginData(next.state))
      discovery.resolve(clonePluginData(next.contributions))
      await reload
      await waitFor(() => diagnosticHostSetups === setups + 1 && !captures.get('PluginDiagnosticHost').loading.value)
      assert.notEqual(entry.state.hostKey.value, oldKey)
      assert.equal(call.options.signal.aborted, true)
      const replacement = captures.get('PluginDiagnosticHost')
      assert.equal(replacement.props.contribution.entry.id, next.accountContribution.entry.id)
      assert.equal(replacement.props.contribution.activeVersion, next.accountContribution.activeVersion)
      invocation.resolve(responseFor(call))
      await run; await settle()
      assert.equal(replacement.rows.value.length, 0, 'the old invocation cannot populate the replacement host')
      assert.equal(entry.container.querySelector('.plugin-result'), null)
    })
  }

  for (const failureStage of ['contributions', 'state']) {
    it(`recovers an invalid OAuth entry only after deferred event discovery and live confirmation, preserving invalid on ${failureStage} errors`, async () => {
      const fixture = makePluginFixture()
      const entry = await mountDiagnosticSurface('OAuthModelTestModal', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const oldKey = entry.state.hostKey.value
      const key = entry.state.contributionVersionKey(fixture.accountContribution)
      const setups = diagnosticHostSetups
      mock.getTargets = () => Promise.reject(new ApiError('disabled', 409, 'PLUGIN_DISABLED'))
      await host.load(); await settle()
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
      assert.match(text(entry.container), /诊断入口已失效/)
      mock.getTargets = undefined

      const failed = deferred()
      if (failureStage === 'contributions') mock.listContributions = () => failed.promise
      else mock.getState = () => failed.promise
      let reads = calls.contributions.length
      const failedStateReads = calls.state.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value)
      if (failureStage === 'state') await waitFor(() => calls.state.length === failedStateReads + 1)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(diagnosticHostSetups, setups)
      failed.reject(new Error(`OAuth ${failureStage} discovery unavailable`))
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(diagnosticHostSetups, setups)
      assert.match(text(entry.container.querySelector('[role="alert"]')), /discovery unavailable/)

      const discovery = deferred()
      const live = deferred()
      mock.listContributions = () => discovery.promise
      mock.getState = () => live.promise
      reads = calls.contributions.length
      const stateReads = calls.state.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(diagnosticHostSetups, setups)
      discovery.resolve(clonePluginData(fixture.contributions))
      await waitFor(() => calls.state.length === stateReads + 1)
      assert.equal(calls.state.at(-1).id, fixture.manifest.id)
      assert.equal(entry.state.loading.value, true)
      assert.equal(entry.state.invalidContributions.value.has(key), true, 'discovery alone cannot restore an unconfirmed OAuth action')
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
      assert.equal(diagnosticHostSetups, setups)
      live.resolve(clonePluginData(fixture.state))
      await waitFor(() => diagnosticHostSetups === setups + 1 && !captures.get('PluginDiagnosticHost').loading.value)
      assert.notEqual(captures.get('PluginDiagnosticHost'), host)
      assert.notEqual(entry.state.hostKey.value, oldKey)
      assert.equal(entry.state.invalidContributions.value.size, 0)
      assert.equal(entry.state.diagnosticGenerations.value.get(key), 1)
      assert.equal(entry.state.loadError.value, '')
      assert.equal(captures.get('PluginDiagnosticHost').props.contribution.entry.action.id, fixture.accountContribution.entry.action.id)
    })
  }

  for (const mismatch of ['plugin', 'version', 'action', 'disabled', 'entry', 'placement', 'context', 'absent']) {
    it(`keeps the invalid OAuth entry excluded when deferred live confirmation has a different ${mismatch}`, async () => {
      const fixture = makePluginFixture()
      const entry = await mountDiagnosticSurface('OAuthModelTestModal', fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const setups = diagnosticHostSetups
      const key = entry.state.contributionVersionKey(fixture.accountContribution)
      mock.getTargets = () => Promise.reject(new ApiError('disabled', 409, 'PLUGIN_DISABLED'))
      await host.load(); await settle()
      mock.getTargets = undefined
      const liveState = clonePluginData(fixture.state)
      const liveEntry = liveState.activeUi.entries.find(item => item.id === fixture.accountContribution.entry.id)
      if (mismatch === 'plugin') liveState.id = 'other.plugin'
      if (mismatch === 'version') liveState.activeVersion = '2.0.0'
      if (mismatch === 'action') liveEntry.action.id = 'other-action'
      if (mismatch === 'disabled') liveState.admissionEnabled = false
      if (mismatch === 'entry') liveEntry.id = 'other-entry'
      if (mismatch === 'placement') liveEntry.placement = 'channel-tests'
      if (mismatch === 'context') liveEntry.contexts = ['channel']
      const discovery = deferred()
      const live = deferred()
      mock.listContributions = () => discovery.promise
      mock.getState = () => live.promise
      const reads = calls.contributions.length
      const stateReads = calls.state.length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value)
      discovery.resolve(mismatch === 'absent' ? [] : clonePluginData(fixture.contributions))
      if (mismatch !== 'absent') {
        await waitFor(() => calls.state.length === stateReads + 1)
        assert.equal(entry.state.invalidContributions.value.has(key), true)
        assert.equal(diagnosticHostSetups, setups)
        live.resolve(liveState)
      }
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(key), mismatch !== 'absent')
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), null)
      assert.equal(entry.state.loadError.value, '')
      if (mismatch === 'absent') assert.equal(calls.state.length, stateReads)
    })
  }

  for (const completion of ['response', 'error']) {
    it(`keeps the current OAuth host invocation draft and result while deferred event recovery of another invalid entry returns ${completion}`, async () => {
      const first = makeOfficialPluginFixture()
      const second = makePluginFixture()
      const entry = await mountDiagnosticSurface('OAuthModelTestModal', first)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      const host = captures.get('PluginDiagnosticHost')
      const hostElement = entry.container.querySelector('.plugin-diagnostic-host')
      const hostKey = entry.state.hostKey.value
      const setups = diagnosticHostSetups
      setFixtures(first, second)
      await entry.state.load()
      const invalidKey = entry.state.contributionVersionKey(second.accountContribution)
      entry.state.markUnavailable({ pluginId: second.manifest.id, activeVersion: second.accountContribution.activeVersion, entryId: second.accountContribution.entry.id })
      const prompt = 'retain my OAuth draft while another entry recovers'
      await change(field(entry.container, '本次提示词'), prompt)
      const invocation = deferred()
      mock.invoke = () => invocation.promise
      const run = host.run()
      await waitFor(() => calls.invocations.length === 1)
      const call = calls.invocations[0]
      const discovery = deferred()
      const live = deferred()
      mock.listContributions = () => discovery.promise
      mock.getState = id => id === second.manifest.id ? live.promise : stateFor(id)
      const reads = calls.contributions.length
      const stateReads = calls.state.filter(item => item.id === second.manifest.id).length
      window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED))
      await waitFor(() => calls.contributions.length === reads + 1 && entry.state.loading.value && !host.loading.value)
      assert.equal(entry.state.invalidContributions.value.has(invalidKey), true)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(call.options.signal.aborted, false)
      assert.equal(host.running.value, true)
      discovery.resolve(clonePluginData(mock.contributions))
      await waitFor(() => calls.state.filter(item => item.id === second.manifest.id).length === stateReads + 1)
      assert.equal(entry.state.loading.value, true)
      assert.equal(entry.state.invalidContributions.value.has(invalidKey), true)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(call.options.signal.aborted, false)
      assert.equal(host.running.value, true)
      invocation.resolve(responseFor(call))
      await run; await settle()
      const resultElement = entry.container.querySelector('.plugin-result')
      assert.ok(resultElement, 'the current invocation completes while the other entry awaits live confirmation')
      if (completion === 'response') live.resolve(clonePluginData(second.state))
      else live.reject(new Error('OAuth invalid-entry live confirmation unavailable'))
      await waitFor(() => !entry.state.loading.value)
      assert.equal(captures.get('PluginDiagnosticHost'), host)
      assert.equal(entry.container.querySelector('.plugin-diagnostic-host'), hostElement)
      assert.equal(entry.container.querySelector('.plugin-result'), resultElement)
      assert.equal(diagnosticHostSetups, setups)
      assert.equal(entry.state.hostKey.value, hostKey)
      assert.equal(field(entry.container, '本次提示词').value, prompt)
      assert.equal(call.options.signal.aborted, false)
      assert.equal(host.rows.value[0].execution.outcome.success, true)
      assert.equal(entry.state.invalidContributions.value.has(invalidKey), completion === 'error')
      assert.equal(entry.state.diagnosticGenerations.value.size, completion === 'response' ? 1 : 0)
      if (completion === 'response') assert.equal(entry.state.diagnosticGenerations.value.get(invalidKey), 1)
      else assert.match(text(entry.container.querySelector('[role="alert"]')), /live confirmation unavailable/)
    })
  }

  it('keeps the current OAuth diagnostic running when manually rechecking another invalid entry', async () => {
    const first = makeOfficialPluginFixture()
    const second = makePluginFixture()
    const entry = await mountDiagnosticSurface('OAuthModelTestModal', first)
    await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
    const host = captures.get('PluginDiagnosticHost')
    const setups = diagnosticHostSetups
    const hostKey = entry.state.hostKey.value
    setFixtures(first, second)
    await entry.state.load()
    entry.state.markUnavailable({ pluginId: second.manifest.id, activeVersion: second.accountContribution.activeVersion, entryId: second.accountContribution.entry.id })
    await change(field(entry.container, '本次提示词'), 'keep the current OAuth draft')
    const invocation = deferred()
    mock.invoke = () => invocation.promise
    const run = host.run()
    await waitFor(() => calls.invocations.length === 1)
    const call = calls.invocations[0]
    const discovery = deferred()
    mock.listContributions = () => discovery.promise
    const reload = entry.state.load(true)
    await settle()
    assert.equal(call.options.signal.aborted, false)
    assert.equal(entry.state.invalidContributions.value.size, 1, 'rechecks keep invalid entries excluded until discovery succeeds')
    discovery.resolve(clonePluginData(mock.contributions))
    await reload; await settle()
    assert.equal(captures.get('PluginDiagnosticHost'), host)
    assert.equal(diagnosticHostSetups, setups)
    assert.equal(entry.state.hostKey.value, hostKey)
    assert.equal(entry.state.diagnosticGenerations.value.get(entry.state.contributionVersionKey(second.accountContribution)), 1)
    assert.equal(entry.state.diagnosticGenerations.value.size, 1)
    assert.equal(call.options.signal.aborted, false)
    assert.equal(host.running.value, true)
    invocation.resolve(responseFor(call))
    await run; await settle()
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
    assert.equal(field(entry.container, '本次提示词').value, 'keep the current OAuth draft')
  })

  it('only notifies confirmed unavailable codes once and leaves ordinary failures and PLUGIN_BUSY retryable', async () => {
    for (const [code, expected] of [['network', 0], ['PLUGIN_BUSY', 0], ['PLUGIN_DISABLED', 1], ['PLUGIN_ACTIVE_VERSION_CONFLICT', 1]]) {
      const fixture = makePluginFixture()
      const unavailable = []
      const entry = await hostFor(fixture, { onUnavailable: value => unavailable.push(value) })
      const firstInvocation = calls.invocations.length
      const pending = deferred()
      mock.invoke = () => pending.promise
      const run = entry.state.run()
      await waitFor(() => calls.invocations.length === firstInvocation + 1)
      pending.reject(code === 'network' ? new Error('temporary resource failure') : new ApiError('plugin response', code.includes('BUSY') ? 503 : 409, code))
      await run; await settle()
      assert.equal(unavailable.length, expected)
      assert.equal(entry.state.rows.value[0]?.error.includes('plugin response') ?? false, code !== 'network')
      if (!expected) {
        assert.equal(entry.state.rows.value[0].error, code === 'network' ? 'temporary resource failure' : 'plugin response')
        assert.match(text(entry.container.querySelector('.plugin-diagnostic-pending')), code === 'network' ? /temporary resource failure/ : /plugin response/)
        assert.ok(entry.state.rows.value.length > 0, 'ordinary failures remain in the host result view')
        assert.equal(entry.state.loadError.value, '')
        mock.invoke = call => responseFor(call)
        button(entry.container, fixture.contribution.entry.action.label).click()
        await waitFor(() => calls.invocations.length === firstInvocation + 2 && !entry.state.running.value)
        assert.equal(entry.state.rows.value[0].error, '')
        assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
      } else {
        assert.deepEqual(unavailable[0], { pluginId: fixture.manifest.id, activeVersion: '1.0.0', entryId: fixture.contribution.entry.id })
      }
      entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
    }
  })

  it('notifies wrappers when confirmed unavailable is returned by invocation preflight', async () => {
    const fixture = makePluginFixture()
    const unavailable = []
    const entry = await hostFor(fixture, { onUnavailable: value => unavailable.push(value) })
    mock.getState = () => Promise.reject(new ApiError('entry no longer available', 409, 'PLUGIN_ACTION_NOT_AVAILABLE'))
    await entry.state.run(); await settle()
    assert.deepEqual(unavailable, [{ pluginId: fixture.manifest.id, activeVersion: '1.0.0', entryId: fixture.contribution.entry.id }])
    assert.match(entry.state.runError.value, /entry no longer available/)
    assert.equal(calls.invocations.length, 0)
  })

  it('keeps target-load errors in the channel and account wrappers until manual retry', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    const channelSetupBaseline = diagnosticHostSetups
    const firstTargetLoad = deferred()
    mock.getTargets = () => calls.targets.length === 1 ? firstTargetLoad.promise : Promise.resolve(clonePluginData(mock.targets))
    const channelWrapper = await mount('ChannelConnectivityPanel', { channel })
    await waitFor(() => calls.targets.length === 1)
    firstTargetLoad.reject(new Error('resource endpoint temporarily unavailable'))
    await waitFor(() => !channelWrapper.state.loading.value && !captures.get('PluginDiagnosticHost').loading.value)
    assert.equal(text(channelWrapper.container.querySelector('.plugin-diagnostic-load-error')), 'resource endpoint temporarily unavailable重新读取')
    assert.equal(diagnosticHostSetups, 1)
    button(channelWrapper.container, '重新读取').click(); await waitFor(() => calls.targets.length === 2 && !captures.get('PluginDiagnosticHost').loading.value)
    assert.equal(diagnosticHostSetups, 1, 'ordinary target errors do not remount the diagnostic host')
    assert.equal(channelWrapper.container.querySelector('.plugin-diagnostic-load-error'), null)
    assert.deepEqual([...field(channelWrapper.container, '模型').options].map(option => option.textContent), ['test-model'])
    assert.equal(channelWrapper.container.querySelector('.test-workbench-actions .button.primary').disabled, false)
    channelWrapper.unmount(); mounted.splice(mounted.indexOf(channelWrapper), 1)

    setFixtures(fixture)
    const account = { id: 'account-1', name: 'Synthetic account', accountEmail: 'test@example.invalid', provider: 'chatgpt' }
    const accountSetupBaseline = diagnosticHostSetups
    const accountContributionBaseline = calls.contributions.length
    mock.getTargets = () => Promise.reject(new Error('resource endpoint temporarily unavailable'))
    const accountWrapper = await mount('OAuthModelTestModal', { account, models: ['test-model'] })
    await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
    assert.equal(diagnosticHostSetups, accountSetupBaseline + 1)
    assert.match(text(accountWrapper.container), /resource endpoint temporarily unavailable/)
    assert.equal(calls.contributions.length, accountContributionBaseline + 1, 'host resource error must not trigger contribution reload')
    accountWrapper.unmount(); mounted.splice(mounted.indexOf(accountWrapper), 1)
  })

  it('removes only the invalid contribution version and lets the user manually recheck wrappers', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    mock.getTargets = () => Promise.reject(new ApiError('disabled', 409, 'PLUGIN_DISABLED'))
    const wrapper = await mount('ChannelConnectivityPanel', { channel })
    await waitFor(() => wrapper.container.textContent.includes('诊断入口已失效'))
    assert.equal(diagnosticHostSetups, 1)
    assert.equal(calls.contributions.length, 1)
    setState(fixture.manifest.id, { activeUi: fixture.ui })
    mock.getTargets = undefined
    button(wrapper.container, '重新检查').click()
    await waitFor(() => diagnosticHostSetups === 2 && !captures.get('PluginDiagnosticHost').loading.value)
    assert.equal(calls.contributions.length, 2)
    wrapper.unmount(); mounted.splice(mounted.indexOf(wrapper), 1)
  })

  it('aborts host resource reads and ignores plugin events while a KeepAlive tab is inactive', async () => {
    const fixture = makePluginFixture()
    const firstState = deferred()
    const firstTargets = deferred()
    let stateReads = 0
    let targetReads = 0
    mock.getState = (_id, options) => {
      stateReads++
      return stateReads === 1 ? (mock.firstStateOptions = options, firstState.promise) : stateFor(fixture.manifest.id)
    }
    mock.getTargets = (_id, _context, options) => {
      targetReads++
      return targetReads === 1 ? (mock.firstTargetOptions = options, firstTargets.promise) : clonePluginData(mock.targets)
    }
    setFixtures(fixture)
    const container = document.createElement('div')
    document.body.append(container)
    const visible = ref(true)
    const context = ref({ kind: 'channel', id: channel.id })
    const initialModel = ref(undefined)
    const app = createApp({ render: () => h('div', [h('button', { onClick: () => { visible.value = !visible.value } }, 'toggle'), h(runtime.KeepAlive, null, {
      default: () => visible.value ? h(components.get('PluginDiagnosticHost'), { contribution: fixture.contribution, context: context.value, initialModel: initialModel.value, onUnavailable: () => { mock.unavailable = (mock.unavailable ?? 0) + 1 } }) : null,
    })]) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(container)
    const entry = { app, container, state: captures.get('PluginDiagnosticHost'), unmount() { app.unmount(); container.remove() } }
    mounted.push(entry)
    await waitFor(() => stateReads === 1 && targetReads === 1)
    button(container, 'toggle').click(); await settle()
    assert.equal(mock.firstStateOptions.signal.aborted, true)
    assert.equal(mock.firstTargetOptions.signal.aborted, true)
    button(container, 'toggle').click(); await waitFor(() => stateReads === 2 && targetReads === 2)
    assert.equal(entry.state.loading.value, false)
    assert.equal(entry.state.form.model, 'test-model')
    await change(field(container, '本次提示词'), 'custom prompt from the old activation context')
    const pendingInvocation = deferred()
    let invocationCall
    mock.invoke = call => { invocationCall = call; return pendingInvocation.promise }
    const invocationsBeforeRun = calls.invocations.length
    const pendingRun = entry.state.run()
    await waitFor(() => calls.invocations.length === invocationsBeforeRun + 1)
    assert.equal(invocationCall.payload.values.prompt, 'custom prompt from the old activation context')
    const readsAtActivation = stateReads
    visible.value = false; await settle()
    assert.equal(calls.invocations.at(-1).options.signal.aborted, true, 'deactivation aborts the pending invocation')
    pendingInvocation.resolve(responseFor(invocationCall))
    await pendingRun; await settle()
    assert.equal(entry.state.rows.value[0]?.execution, null, 'late execution callbacks are ignored while inactive')
    context.value = { kind: 'channel', id: 'channel-2' }
    initialModel.value = 'recovered-model'
    mock.targets = [makeTarget({ channelId: 'channel-2', model: 'recovered-model' })]
    mock.states.set(fixture.manifest.id, clonePluginData(fixture.state))
    await settle()
    window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED)); await settle()
    assert.equal(stateReads, readsAtActivation, 'state events while inactive do not reload resources')
    firstState.resolve(fixture.state)
    firstTargets.resolve([makeTarget()])
    await settle()
    assert.equal(entry.state.loadError.value, '')
    assert.equal(mock.unavailable ?? 0, 0)
    button(container, 'toggle').click(); await waitFor(() => stateReads === readsAtActivation + 1 && targetReads === 3 && !entry.state.loading.value)
    assert.equal(entry.state.form.model, 'recovered-model')
    assert.equal(entry.state.form.prompt, '请只回复 OK', 'activation with changed context resets the prompt from the latest active default')
    assert.equal(entry.state.selectedTargetKey.value, modelTestTargetKey('channel-2', 'recovered-model'))
    entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
  })

  it('renders real no-credential targets as empty and retains the usable-credential comparison', async () => {
    const fixture = makePluginFixture()
    mock.targets = [makeTarget({ protocols: ['responses'], credentials: [] })]
    const empty = await hostFor(fixture)
    assert.equal(empty.state.state.value.admissionEnabled, true)
    assert.equal(empty.state.targets.value.length, 0)
    assert.match(text(empty.container), /没有可测试的模型/)
    assert.equal(empty.container.querySelector('.test-workbench-actions'), null)
    empty.unmount(); mounted.splice(mounted.indexOf(empty), 1)
    mock.targets = [makeTarget({ protocols: ['responses'], credentials: [{ id: 'usable', name: '可用凭据', priority: 1 }] })]
    const usable = await hostFor(fixture)
    assert.equal(usable.state.targets.value.length, 1)
    assert.ok(usable.container.querySelector('.test-workbench-actions .button.primary'))
  })

  it('removes confirmed stale entries across actual mounted surfaces, excludes only that version, and allows same-version recheck', async () => {
    const fixture = makeOfficialPluginFixture()
    for (const surface of ['ChannelConnectivityPanel', 'OAuthModelTestModal', 'PluginMarketplaceView', 'ChannelTestDrawer']) {
      const entry = await mountDiagnosticSurface(surface, fixture)
      await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value, `${surface} host resources`)
      const host = captures.get('PluginDiagnosticHost')
      const staleVersion = fixture.contribution.activeVersion
      const initialSetups = diagnosticHostSetups
      const staleHost = host
      mock.getTargets = () => Promise.reject(new ApiError('entry no longer available', 409, 'PLUGIN_ACTION_NOT_AVAILABLE'))
      await staleHost.load()
      await settle()
      assert.equal(diagnosticHostSetups, initialSetups)
      if (surface === 'ChannelTestDrawer') {
        assert.equal(entry.state.activeTab.value, 'iq')
        assert.equal(entry.state.tabs.value.some(tab => tab.id === 'plugin:prism.connectivity:connectivity-channel'), false)
        mock.getTargets = undefined
        button(entry.container, '重新检查').click()
        await waitFor(() => entry.state.tabs.value.some(tab => tab.id === 'plugin:prism.connectivity:connectivity-channel'))
        button(entry.container, '连通性').click()
        await waitFor(() => diagnosticHostSetups === initialSetups + 1)
        await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
      } else {
        await waitFor(() => surface === 'OAuthModelTestModal'
          ? entry.container.textContent.includes('诊断入口已失效')
          : surface === 'ChannelConnectivityPanel'
            ? entry.container.textContent.includes('诊断入口已失效')
            : entry.container.textContent.includes('入口已不可用'))
        if (surface === 'PluginMarketplaceView') {
          mock.getTargets = undefined
          setState(fixture.manifest.id, { activeUi: clonePluginData(fixture.ui) })
          button(entry.container, '重新检查').click()
          await waitFor(() => diagnosticHostSetups === initialSetups + 1)
        } else {
          mock.getTargets = undefined
          button(entry.container, '重新检查').click()
          await waitFor(() => diagnosticHostSetups === initialSetups + 1)
        }
        await waitFor(() => !captures.get('PluginDiagnosticHost').loading.value)
        assert.equal(captures.get('PluginDiagnosticHost').props.contribution.activeVersion, staleVersion)
      }
      assert.ok(calls.contributions.length > 0)
      entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
    }
  })

  for (const unavailable of ['disabled', 'version', 'entry']) {
    it('does not restore a channel drawer contribution from a stale list when live ' + unavailable + ' admission disagrees', async () => {
      const fixture = makeOfficialPluginFixture()
      setFixtures(fixture)
      const entry = await mount('ChannelTestDrawer', { channel })
      await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
      const contribution = fixture.contribution
      const key = contribution.pluginId + ':' + contribution.activeVersion + ':' + contribution.entry.id
      const tabKey = 'plugin:' + contribution.pluginId + ':' + contribution.entry.id
      const discovery = deferred()
      const live = deferred()
      mock.listContributions = () => discovery.promise
      mock.getState = () => live.promise
      const reads = calls.state.length
      const loading = entry.state.load(true)
      await settle()
      entry.state.markUnavailable({ pluginId: contribution.pluginId, activeVersion: contribution.activeVersion, entryId: contribution.entry.id })
      await settle()
      discovery.resolve(clonePluginData(fixture.contributions))
      await waitFor(() => calls.state.length === reads + 1)
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(entry.state.tabs.value.some(tab => tab.id === tabKey), false)
      const changes = unavailable === 'disabled' ? { admissionEnabled: false }
        : unavailable === 'version' ? { activeVersion: '2.0.0' }
        : { activeUi: { ...fixture.ui, entries: fixture.ui.entries.filter(item => item.id !== contribution.entry.id) } }
      live.resolve({ ...clonePluginData(fixture.state), ...changes })
      await loading; await settle()
      assert.equal(entry.state.invalidContributions.value.has(key), true)
      assert.equal(entry.state.tabs.value.some(tab => tab.id === tabKey), false)
      assert.equal(entry.state.diagnosticGenerations.value.size, 0)

      mock.listContributions = undefined
      mock.getState = undefined
      const retry = button(entry.container, '重新检查')
      retry.focus()
      retry.click()
      await waitFor(() => entry.state.invalidContributions.value.size === 0)
      assert.equal(entry.state.tabs.value.some(tab => tab.id === tabKey), true)
      assert.equal(entry.state.diagnosticGenerations.value.get(key), 1)
      assert.equal(document.activeElement, entry.container.querySelector('[role="tab"][aria-selected="true"]'))
    })
  }

  it('restores focus to the selected channel drawer tab after a successful list retry removes its button', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    mock.listContributions = () => Promise.reject(new Error('temporary contributions failure'))
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => entry.state.loadError.value !== '')
    const retry = button(entry.container, '重试')
    retry.focus()
    mock.listContributions = undefined
    retry.click()
    await waitFor(() => entry.state.loadError.value === '' && entry.state.tabs.value.length > 3)
    assert.equal(retry.isConnected, false)
    assert.equal(document.activeElement, entry.container.querySelector('[role="tab"][aria-selected="true"]'))
  })

  it('keeps keyboard focus on the replacement channel drawer retry after another discovery failure', async () => {
    setFixtures(makeOfficialPluginFixture())
    mock.listContributions = () => Promise.reject(new Error('initial list failure'))
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => entry.state.loadError.value !== '')
    const retry = button(entry.container, '重试')
    retry.focus()
    mock.listContributions = () => Promise.reject(new Error('retry list failure'))
    retry.click()
    await waitFor(() => entry.state.loadError.value === 'retry list failure')
    assert.equal(document.activeElement, button(entry.container, '重试'))
  })

  it('does not steal focus if the user leaves a pending channel drawer retry', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    mock.listContributions = () => Promise.reject(new Error('initial list failure'))
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => entry.state.loadError.value !== '')
    const pending = deferred()
    mock.listContributions = () => pending.promise
    const retry = button(entry.container, '重试')
    retry.focus()
    retry.click()
    await settle()
    const destination = button(entry.container, '指纹')
    destination.focus()
    pending.resolve(clonePluginData(fixture.contributions))
    await waitFor(() => entry.state.tabs.value.length > 3)
    assert.equal(document.activeElement, destination)
  })

  it('keeps ordinary list failures mounted with host-level manual retry', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    const failedTargets = deferred()
    mock.getTargets = () => calls.targets.length === 1 ? failedTargets.promise : clonePluginData(mock.targets)
    const entry = await mount('ChannelConnectivityPanel', { channel })
    await waitFor(() => calls.targets.length === 1)
    failedTargets.reject(new Error('temporary model-resource failure'))
    await waitFor(() => entry.container.querySelector('.plugin-diagnostic-load-error'))
    assert.equal(diagnosticHostSetups, 1)
    assert.equal(calls.contributions.length, 1)
    button(entry.container, '重新读取').click()
    await waitFor(() => calls.targets.length === 2 && !captures.get('PluginDiagnosticHost').loading.value)
    assert.equal(diagnosticHostSetups, 1)
    assert.equal(entry.container.querySelector('.plugin-diagnostic-load-error'), null)
    assert.deepEqual([...field(entry.container, '模型').options].map(option => option.textContent), ['test-model'])
    assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, false)
  })

  it('recovers probe capacity after PLUGIN_BUSY and can start two fresh workers', async () => {
    const fixture = makePluginFixture()
    mock.targets = [0, 1].map(index => makeTarget({ model: `capacity-${index}` }))
    const entry = await hostFor(fixture)
    let callsMade = 0
    mock.invoke = () => {
      callsMade++
      if (callsMade === 1) return Promise.reject(new ApiError('capacity busy', 503, 'PLUGIN_BUSY'))
      return Promise.resolve(responseFor(calls.invocations.at(-1)))
    }
    entry.state.form.scope = 'single'
    await entry.state.run(); await settle()
    assert.equal(entry.state.rows.value[0].error, 'capacity busy')
    assert.equal(calls.invocations.length, 1)
    entry.state.form.scope = 'all'
    const pending = []
    mock.invoke = call => { const item = deferred(); pending.push({ ...item, call }); return item.promise }
    const retry = entry.state.run()
    await waitFor(() => pending.length === 2)
    assert.equal(calls.invocations.length, 3)
    pending.forEach(item => item.resolve(responseFor(item.call)))
    await retry; await settle()
    assert.equal(entry.state.rows.value.filter(row => row.execution).length, 2)
    assert.equal(entry.state.rows.value.filter(row => row.error).length, 0)
  })

  for (const code of ['PLUGIN_DISABLED', 'PLUGIN_ACTIVE_VERSION_CONFLICT']) {
    it(`stops queued batch targets and aborts in-flight work on ${code}`, async () => {
      mock.targets = [0, 1, 2, 3].map(index => makeTarget({ model: `unavailable-batch-${index}` }))
      const pending = []
      const abortedAtNotification = []
      const entry = await hostFor(makePluginFixture(), {
        onUnavailable: () => abortedAtNotification.push(pending[1]?.call.options.signal.aborted),
      })
      await change(field(entry.container, '检测范围'), 'all')
      mock.invoke = call => {
        // Settle extra calls so an unexpected dispatch reaches the count assertion.
        if (pending.length >= 2) return responseFor(call)
        const result = deferred()
        pending.push({ ...result, call })
        return result.promise
      }
      const invocationStart = calls.invocations.length
      const run = entry.state.run()
      await waitFor(() => pending.length === 2)
      assert.equal(calls.invocations.length - invocationStart, 2)
      const [first, second] = pending
      assert.equal(second.call.options.signal.aborted, false)
      const failure = `admission rejected: ${code}`
      first.reject(new ApiError(failure, 409, code))
      await waitFor(() => abortedAtNotification.length > 0, 'unavailable notification')
      assert.deepEqual(abortedAtNotification, [true], 'in-flight work is aborted before the parent is notified')
      assert.equal(calls.invocations.length - invocationStart, 2, 'the first worker cannot claim another queued target')
      const [failed, inFlight, ...queued] = entry.state.rows.value
      assert.equal(failed.error, failure)
      assert.equal(failed.waitingStopped, false, 'the original failure remains visible')
      assert.equal(failed.running, false)
      assert.equal(inFlight.waitingStopped, true)
      assert.match(inFlight.error, /已发给上游的请求可能继续执行/)
      assert.equal(queued.length, 2)
      assert.ok(queued.every(row => row.waitingStopped && /此项尚未发送/.test(row.error)))
      assert.equal(entry.state.running.value, false)

      second.resolve(responseFor(second.call))
      await run; await settle()
      assert.equal(calls.invocations.length - invocationStart, 2, 'second worker completion does not dispatch another target')
      assert.equal(entry.state.rows.value.find(row => row.key === inFlight.key)?.execution, null, 'the current row ignores the late success')
      assert.equal(entry.container.querySelectorAll('.plugin-result').length, 0, 'the late success is not rendered')
      assert.equal(toasts.length, 0, 'an interrupted batch does not publish a summary')
    })
  }

  for (const [interaction, expectedTab] of [
    ['IQ control focus', '智商'],
    ['IQ control input', '智商'],
    ['IQ tab focus', '智商'],
    ['records tab click', '记录'],
    ['no tab or panel interaction', null],
  ]) {
    it(`honors ${interaction} while the first contribution discovery is pending`, async () => {
      const fixture = makeOfficialPluginFixture()
      setFixtures(fixture)
      const pending = deferred()
      mock.listContributions = () => pending.promise
      const { container, state } = await mount('ChannelTestDrawer', { channel })
      await waitFor(() => calls.contributions.length === 1)
      const iq = button(container, '智商')
      const records = button(container, '记录')
      const control = container.querySelector('[data-legacy-view="iq"] input')
      const selectedTab = () => container.querySelector('[role="tab"][aria-selected="true"]')
      assert.equal(selectedTab(), iq)
      assert.ok(control)
      const close = button(container, '关闭模拟窗口')
      close.focus() // Simulate the modal's initial focus, outside the tablist and panel.

      if (interaction === 'IQ control focus') control.focus()
      if (interaction === 'IQ control input') {
        await change(control, 'draft prompt')
        assert.equal(document.activeElement, close, 'input alone protects the draft without a focus event')
      }
      if (interaction === 'IQ tab focus') iq.focus()
      if (interaction === 'records tab click') records.click()

      pending.resolve(clonePluginData(fixture.contributions))
      await waitFor(() => state.initialized && container.querySelectorAll('[role="tab"]').length === 4)
      assert.equal(selectedTab(), button(container, expectedTab ?? fixture.contribution.entry.label))
      if (interaction === 'IQ control focus') assert.equal(document.activeElement, control)
      if (interaction === 'IQ control input') {
        assert.equal(container.querySelector('[data-legacy-view="iq"] input'), control)
        assert.equal(control.value, 'draft prompt')
      }
    })
  }

  for (const discovery of ['arrival', 'removal']) {
    for (const [key, from, target] of [
      ['ArrowRight', '智商', '指纹'], ['ArrowLeft', '智商', '记录'],
      ['Home', '记录', '智商'], ['End', '智商', '记录'],
    ]) {
      it(`focuses the selected tab by ID when contribution ${discovery} interleaves with ${key}`, async () => {
        const fixture = makeOfficialPluginFixture()
        setFixtures(fixture)
        const pending = deferred()
        if (discovery === 'arrival') mock.listContributions = () => pending.promise
        const entry = await mount('ChannelTestDrawer', { channel })
        const source = button(entry.container, from)
        source.click(); await settle()
        source.focus()
        const previousOrder = [...entry.container.querySelectorAll('[role=tab]')].map(tab => tab.id)
        let refresh
        if (discovery === 'removal') {
          mock.listContributions = () => pending.promise
          refresh = entry.state.load()
        }
        pending.resolve(discovery === 'arrival' ? clonePluginData(fixture.contributions) : [])
        await Promise.resolve()
        assert.deepEqual([...entry.container.querySelectorAll('[role=tab]')].map(tab => tab.id), previousOrder)
        source.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
        const targetRemoved = discovery === 'removal' && ['ArrowLeft', 'Home'].includes(key)
        const selected = button(entry.container, targetRemoved ? fixture.contribution.entry.label : target)
        assert.equal(`${entry.state.tabId}-${entry.state.activeTab.value}`, selected.id)
        await refresh; await settle()
        assert.notDeepEqual([...entry.container.querySelectorAll('[role=tab]')].map(tab => tab.id), previousOrder)
        const destination = targetRemoved ? button(entry.container, '智商') : selected
        assert.equal(entry.container.querySelector('[role=tab][aria-selected=true]'), destination)
        assert.equal(document.activeElement, destination)
        assert.equal(destination.tabIndex, 0)
        if (targetRemoved) assert.equal(selected.isConnected, false)
      })
    }
  }

  it('does not focus a superseded keyboard navigation target after the render tick', async () => {
    const entry = await mount('ChannelTestDrawer', { channel })
    const source = button(entry.container, '智商')
    source.click(); await settle()
    source.focus()
    const focused = []
    for (const tab of entry.container.querySelectorAll('[role=tab]')) {
      const focus = tab.focus.bind(tab)
      tab.focus = () => { focused.push(tab.id); focus() }
    }
    source.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    source.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }))
    await settle()
    const selected = button(entry.container, '记录')
    assert.equal(entry.container.querySelector('[role=tab][aria-selected=true]'), selected)
    assert.deepEqual(focused, [selected.id])
    assert.equal(document.activeElement, selected)
  })

  it('does not focus a keyboard navigation target after the drawer unmounts', async () => {
    const entry = await mount('ChannelTestDrawer', { channel })
    let focusCalls = 0
    for (const tab of entry.container.querySelectorAll('[role=tab]')) tab.focus = () => { focusCalls++ }
    button(entry.container, '智商').dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }))
    entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
    await settle()
    assert.equal(focusCalls, 0)
  })

  it('keeps first-discovery interactions scoped to their own drawer', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    const discoveries = [deferred(), deferred()]
    let request = 0
    mock.listContributions = () => discoveries[request++].promise
    const untouched = await mount('ChannelTestDrawer', { channel })
    const interacting = await mount('ChannelTestDrawer', { channel: { ...channel, id: 'channel-2' } })
    const control = interacting.container.querySelector('[data-legacy-view="iq"] input')
    control.focus()
    await change(control, 'draft in another drawer')
    discoveries.forEach(pending => pending.resolve(clonePluginData(fixture.contributions)))
    await waitFor(() => untouched.state.initialized && interacting.state.initialized)
    assert.equal(text(untouched.container.querySelector('[role="tab"][aria-selected="true"]')), fixture.contribution.entry.label)
    assert.equal(text(interacting.container.querySelector('[role="tab"][aria-selected="true"]')), '智商')
    assert.equal(document.activeElement, control)
    assert.equal(control.value, 'draft in another drawer')
  })

  it('does not count programmatic fallback focus as a user tab selection', async () => {
    const fixture = makeOfficialPluginFixture()
    setFixtures(fixture)
    const { container, state } = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
    assert.equal(state.userSelectedTab, false)
    field(container, '模型').focus()
    mock.contributions = []
    await state.load()
    await settle()
    assert.equal(state.activeTab.value, 'iq')
    assert.equal(document.activeElement, button(container, '智商'))
    assert.equal(state.userSelectedTab, false, 'automatic focus restoration must not record a tab selection')
  })

  it('renders missing resources and permits manual diagnostics on a disabled channel', async () => {
    const fixture = makePluginFixture()
    mock.targets = [makeTarget({ credentials: [] }), makeTarget({ model: 'no-protocol', protocols: [] })]
    const empty = await hostFor(fixture)
    assert.match(text(empty.container), /没有可测试的模型/)
    assert.equal(empty.container.querySelector('.test-workbench-actions'), null)
    await empty.state.run()
    assert.equal(calls.invocations.length, 0)
    empty.unmount(); mounted.splice(mounted.indexOf(empty), 1)
    mock.targets = [makeTarget({ channelEnabled: false })]
    const disabled = await hostFor(fixture, { channelEnabled: false })
    assert.match(text(disabled.container), /渠道已停用.*仍可.*诊断/)
    assert.equal(disabled.container.querySelector('.test-workbench-actions .button.primary').disabled, false)
    await disabled.state.run(); await settle()
    assert.equal(calls.invocations.length, 1)
  })

  for (const admission of ['disabled', 'new-version']) {
    it(`rejects ${admission} admission before dispatching any invocation`, async () => {
      const fixture = makePluginFixture()
      const unavailable = []
      const entry = await hostFor(fixture, { onUnavailable: () => unavailable.push(true) })
      setState(fixture.manifest.id, admission === 'disabled'
        ? { admissionEnabled: false, desiredEnabled: false, status: 'disabled', activeUi: null }
        : { activeVersion: '2.0.0' })
      await entry.state.run(); await settle()
      assert.equal(calls.invocations.length, 0)
      assert.equal(unavailable.length, 1)
      assert.match(entry.state.runError.value, /停用|版本/)
      assert.equal(toasts.at(-1).type, 'error')
    })
  }

  it('defaults single diagnostics to a compatible model while keeping skipped batch targets', async () => {
    const fields = makePluginFixture().contribution.entry.action.fields
    fields.find(item => item.kind === 'protocol').options = [{ value: 'responses', label: 'Responses' }]
    const fixture = makePluginFixture({ channelFields: fields })
    mock.targets = [
      makeTarget({ model: 'no-shared-protocol', protocols: ['gemini'] }),
      makeTarget({ model: 'shared-protocol', protocols: ['chat_completions', 'responses'] }),
      makeTarget({ model: 'other-shared-protocol', protocols: ['responses'] }),
    ]
    const entry = await hostFor(fixture, { initialModel: 'no-shared-protocol' })
    const model = field(entry.container, '模型')
    assert.deepEqual([...model.options].map(option => option.textContent), ['shared-protocol', 'other-shared-protocol'])
    assert.equal(model.value, modelTestTargetKey(channel.id, 'shared-protocol'))
    assert.deepEqual([...field(entry.container, '协议').options].map(option => option.value), ['responses'])
    assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, false)
    await entry.state.run(); await settle()
    assert.deepEqual(calls.invocations.map(call => call.payload.values.model), ['shared-protocol'])

    const scope = field(entry.container, '检测范围')
    assert.match([...scope.options].find(option => option.value === 'all').textContent, /（3）/)
    await change(scope, 'all')
    assert.deepEqual([...field(entry.container, '协议').options].map(option => option.value), ['responses'])
    button(entry.container, '诊断全部模型').click()
    await waitFor(() => calls.invocations.length === 3 && !entry.state.running.value)
    assert.deepEqual(calls.invocations.slice(1).map(call => call.payload.values.model), ['shared-protocol', 'other-shared-protocol'])
    assert.ok(calls.invocations.every(call => call.payload.values.protocol === 'responses' && call.payload.values.scope === 'single'))
    const skipped = [...entry.container.querySelectorAll('.plugin-diagnostic-pending')].find(row => text(row).includes('no-shared-protocol'))
    assert.match(text(skipped), /未发送/)
    assert.match(text(skipped), /协议/)
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 2)

    await change(field(entry.container, '检测范围'), 'single')
    await change(field(entry.container, '本次提示词'), 'preserved prompt')
    mock.targets[1].protocols = ['gemini']
    await entry.state.load(false); await settle()
    assert.equal(field(entry.container, '模型').value, modelTestTargetKey(channel.id, 'other-shared-protocol'))
    assert.equal(entry.state.form.prompt, 'preserved prompt')

    mock.targets[2].protocols = ['gemini']
    await entry.state.load(false); await settle()
    assert.equal(entry.state.selectedTargetKey.value, '')
    assert.equal(entry.state.form.protocol, '')
    assert.match(text(entry.container.querySelector('[role="status"]')), /协议.*不兼容.*没有可执行的模型/)
    assert.equal(entry.container.querySelector('.test-workbench-actions .button.primary').disabled, true)
    await entry.state.run()
    assert.equal(calls.invocations.length, 3)
  })

  it('orchestrates all targets with at most two workers and publishes incremental reactive DOM progress', async () => {
    const fixture = makePluginFixture()
    mock.targets = [0, 1, 2, 3].map(index => makeTarget({ model: `batch-${index}`, credentials: [{ id: `default-${index}`, name: `Default ${index}`, priority: 1 }, { id: `secondary-${index}`, name: `Secondary ${index}`, priority: 2 }] }))
    const entry = await hostFor(fixture)
    entry.state.form.keyId = 'secondary-0'
    await change(field(entry.container, '检测范围'), 'all')
    let inFlight = 0, peak = 0
    const pending = []
    mock.invoke = call => {
      const result = deferred()
      inFlight++; peak = Math.max(peak, inFlight)
      pending.push({ ...result, call })
      return result.promise.finally(() => { inFlight-- })
    }
    const run = entry.state.run()
    await waitFor(() => pending.length === 2)
    assert.equal(entry.container.querySelectorAll('.plugin-diagnostic-pending').length, 4)
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 0)
    assert.equal(field(entry.container, '协议').disabled, true)
    pending[0].resolve(responseFor(pending[0].call))
    await waitFor(() => pending.length === 3 && entry.container.querySelectorAll('.plugin-result').length === 1)
    assert.equal(entry.state.running.value, true)
    assert.equal(entry.container.querySelectorAll('.plugin-diagnostic-pending').length, 3)
    pending[1].resolve(responseFor(pending[1].call, { outcome: { success: false, statusCode: 401 } }))
    await waitFor(() => pending.length === 4 && entry.container.querySelectorAll('.plugin-result').length === 2)
    pending[2].resolve(responseFor(pending[2].call))
    pending[3].resolve(responseFor(pending[3].call))
    await run; await settle()
    assert.equal(peak, 2)
    assert.equal(calls.invocations.length, 4)
    assert.deepEqual(calls.invocations.map(call => call.payload.values.keyId), ['default-0', 'default-1', 'default-2', 'default-3'])
    assert.ok(calls.invocations.every(call => call.payload.values.scope === 'single'))
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 4)
    assert.equal(entry.container.querySelectorAll('.plugin-diagnostic-pending').length, 0)
    assert.equal(entry.state.running.value, false)
    assert.match(toasts.at(-1).message, /3 个探测成功，1 个失败/)
  })

  it('freezes input context version and target credentials before awaiting the preflight response', async () => {
    const fixture = makePluginFixture()
    mock.targets = [makeTarget({ credentials: [{ id: 'original-key', name: 'Original credential', priority: 1 }] })]
    const entry = await hostFor(fixture)
    await change(field(entry.container, '本次提示词'), 'original prompt')
    await change(field(entry.container, '观察标签'), 'original label')
    const preflight = deferred()
    mock.getState = () => preflight.promise
    const run = entry.state.run()
    await waitFor(() => calls.state.length === 2)
    entry.state.form.prompt = 'mutated prompt'
    entry.state.form.label = 'mutated label'
    entry.state.form.protocol = 'responses'
    entry.state.form.keyId = 'new-key'
    entry.state.targets.value[0].protocols.splice(0, 2, 'responses')
    entry.state.targets.value[0].credentials[0].id = 'new-key'
    entry.state.targets.value[0].credentials[0].name = 'Mutated credential'
    mock.invoke = call => makeExecution({ pluginId: call.id, activeVersion: call.payload.expectedActiveVersion, context: call.payload.context, values: call.payload.values, target: makeTarget({ credentials: [{ id: 'original-key', name: 'Original credential', priority: 1 }] }) })
    preflight.resolve(stateFor(fixture.manifest.id))
    await run; await settle()
    const payload = calls.invocations[0].payload
    assert.deepEqual(payload.context, { kind: 'channel', id: channel.id })
    assert.equal(payload.expectedActiveVersion, '1.0.0')
    assert.equal(payload.values.prompt, 'original prompt')
    assert.equal(payload.values.label, 'original label')
    assert.equal(payload.values.protocol, 'chat_completions')
    assert.equal(payload.values.keyId, 'original-key')
    assert.equal(entry.state.rows.value[0].keyName, 'Original credential')
    assert.equal(entry.state.rows.value[0].error, '')
  })

  it('stops waiting without claiming upstream cancellation and never starts queued targets afterward', async () => {
    mock.targets = [0, 1, 2].map(index => makeTarget({ model: `stop-${index}` }))
    const entry = await hostFor()
    entry.state.form.scope = 'all'
    const pending = []
    mock.invoke = call => { const result = deferred(); pending.push({ ...result, call }); return result.promise }
    const run = entry.state.run()
    await waitFor(() => pending.length === 2)
    button(entry.container, '停止等待').click(); await settle()
    assert.equal(entry.state.running.value, false)
    assert.ok(pending.every(item => item.call.options.signal.aborted))
    assert.match(text(entry.container), /已发给上游的请求可能继续执行/)
    assert.match(text(entry.container), /此项尚未发送/)
    assert.ok(entry.state.rows.value.every(row => row.waitingStopped && !row.execution))
    assert.equal(toasts.length, 0)
    // A fresh run may finish while earlier remote requests ignore their abort signal.
    entry.state.form.scope = 'single'
    await change(field(entry.container, '本次提示词'), 'fresh after stop')
    mock.invoke = undefined
    await entry.state.run(); await settle()
    assert.equal(calls.invocations.length, 3)
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
    assert.equal(toasts.length, 1)
    const freshExecution = clonePluginData(entry.state.rows.value[0].execution)
    pending[0].resolve(responseFor(pending[0].call))
    pending[1].reject(new ApiError('late stopped failure', 409, 'stale'))
    await run; await settle()
    assert.equal(pending.length, 2, 'The stopped run never starts its third queued target')
    assert.equal(calls.invocations.length, 3)
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
    assert.deepEqual(clonePluginData(entry.state.rows.value[0].execution), freshExecution)
    assert.equal(entry.state.rows.value[0].values.prompt, 'fresh after stop')
    assert.equal(toasts.length, 1, 'Late completions do not publish another toast')
  })

  for (const completion of ['response', 'error']) {
    it(`suppresses a stale ${completion} and toast after switching plugin context/version`, async () => {
      const old = makePluginFixture()
      const unavailable = []
      const entry = await hostFor(old, { onUnavailable: () => unavailable.push(true) })
      const late = deferred()
      mock.invoke = () => late.promise
      const run = entry.state.run()
      await waitFor(() => calls.invocations.length === 1)
      const oldCall = calls.invocations[0]
      const next = makePluginFixture({ id: 'acme.other', version: '2.0.0' })
      mock.states.set(next.manifest.id, clonePluginData(next.state))
      mock.targets = [makeTarget({ channelId: 'channel-2', model: 'new-context-model' })]
      await entry.setProps({ contribution: next.contribution, context: { kind: 'channel', id: 'channel-2' } })
      await waitFor(() => !entry.state.loading.value)
      assert.equal(oldCall.options.signal.aborted, true)
      if (completion === 'response') late.resolve(makeExecution({ pluginId: oldCall.id, context: oldCall.payload.context, values: oldCall.payload.values }))
      else late.reject(new ApiError('PRIVATE_STALE_ERROR', 409, 'stale'))
      await run; await settle()
      assert.equal(entry.state.rows.value.length, 0)
      assert.equal(entry.state.loadError.value, '')
      assert.equal(entry.state.runError.value, '')
      assert.equal(entry.state.form.model, 'new-context-model')
      assert.equal(entry.container.querySelectorAll('.plugin-result').length, 0)
      assert.equal(toasts.length, 0)
      assert.equal(unavailable.length, 0)
      assert.doesNotMatch(text(entry.container), /PRIVATE_STALE_ERROR/)
    })
  }

  for (const changeKind of ['plugin', 'version', 'entry', 'context kind', 'context ID', 'account models', 'account protocols', 'initial model']) {
    it(`clears the previous run error while a changed ${changeKind} loads new diagnostic resources`, async () => {
      const fixture = makePluginFixture()
      const context = { kind: 'oauth-account', id: 'account-1' }
      const accountTargets = id => ['test-model', 'other-model'].map(model => makeTarget({
        channelId: id, model, credentials: [{ id, name: '账号原生授权', priority: 1 }],
      }))
      mock.targets = accountTargets(context.id)
      const entry = await hostFor(fixture, { contribution: fixture.accountContribution, context,
        accountModels: ['test-model', 'other-model'], accountProtocols: ['responses', 'chat_completions'], initialModel: 'test-model' })
      mock.getState = () => Promise.reject(new Error('previous context preflight failed'))
      await entry.state.run(); await settle()
      assert.equal(entry.state.runError.value, 'previous context preflight failed')
      assert.match(text(entry.container.querySelector('[role=alert]')), /previous context preflight failed/)
      assert.equal(calls.invocations.length, 0)
      assert.equal(toasts.length, 1)
      mock.getState = undefined
      const patch = {}
      if (['plugin', 'version', 'entry'].includes(changeKind)) {
        const next = makePluginFixture({ id: changeKind === 'plugin' ? 'acme.other' : fixture.manifest.id,
          version: changeKind === 'version' ? '2.0.0' : fixture.manifest.version })
        if (changeKind === 'entry') {
          next.accountContribution.entry.id = 'replacement-account-entry'
          next.state.activeUi.entries[1].id = 'replacement-account-entry'
        }
        mock.states.set(next.manifest.id, clonePluginData(next.state))
        patch.contribution = next.accountContribution
      }
      if (changeKind === 'context kind') {
        patch.context = { ...context, kind: 'channel' }
        patch.contribution = fixture.contribution
      }
      if (changeKind === 'context ID') patch.context = { ...context, id: 'account-2' }
      if (changeKind === 'account models') patch.accountModels = ['other-model']
      if (changeKind === 'account protocols') patch.accountProtocols = ['responses']
      if (changeKind === 'initial model') patch.initialModel = 'other-model'
      const resources = deferred()
      mock.getTargets = () => resources.promise
      await entry.setProps(patch)
      assert.equal(entry.state.loading.value, true)
      assert.equal(entry.state.runError.value, '')
      assert.equal(entry.state.rows.value.length, 0)
      assert.equal(entry.state.selectedTargetKey.value, '')
      resources.resolve(accountTargets(patch.context?.id ?? context.id))
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.state.loadError.value, '')
      assert.equal(entry.state.runError.value, '')
      assert.equal(entry.state.canRun.value, true)
      assert.equal(entry.state.form.model, ['account models', 'initial model'].includes(changeKind) ? 'other-model' : 'test-model')
      assert.doesNotMatch(text(entry.container), /previous context preflight failed/)
      assert.equal(toasts.length, 1, 'changing context does not emit another failure')
    })
  }

  for (const changed of [false, true]) {
    it(`${changed ? 'clears' : 'retains'} a previous run error when KeepAlive reactivates ${changed ? 'a changed' : 'the same'} context`, async () => {
      const fixture = makePluginFixture()
      setFixtures(fixture)
      const container = document.createElement('div')
      document.body.append(container)
      const visible = ref(true)
      const context = ref({ kind: 'channel', id: channel.id })
      const app = createApp({ render: () => h(runtime.KeepAlive, null, {
        default: () => visible.value ? h(components.get('PluginDiagnosticHost'), { contribution: fixture.contribution, context: context.value }) : null,
      }) })
      app.provide(ssrContextKey, { modules: new Set() })
      app.mount(container)
      const entry = { app, container, state: captures.get('PluginDiagnosticHost'), unmount() { app.unmount(); container.remove() } }
      mounted.push(entry)
      await waitFor(() => !entry.state.loading.value)
      mock.getState = () => Promise.reject(new Error('previous activation preflight failed'))
      await entry.state.run(); await settle()
      assert.equal(entry.state.runError.value, 'previous activation preflight failed')
      mock.getState = undefined
      visible.value = false; await settle()
      const reads = calls.state.length
      if (changed) {
        context.value = { kind: 'channel', id: 'channel-2' }
        mock.targets = [makeTarget({ channelId: 'channel-2', model: 'new-context-model' })]
      }
      await settle()
      assert.equal(calls.state.length, reads, 'hidden hosts do not reload on a prop change')
      visible.value = true
      await waitFor(() => calls.state.length > reads && !entry.state.loading.value)
      assert.equal(entry.state.runError.value, changed ? '' : 'previous activation preflight failed')
      assert.equal(entry.state.form.model, changed ? 'new-context-model' : 'test-model')
      assert.equal(entry.state.canRun.value, true)
      if (changed) assert.doesNotMatch(text(container), /previous activation preflight failed/)
      else assert.match(text(container.querySelector('[role=alert]')), /previous activation preflight failed/)
      assert.equal(toasts.length, 1)
    })
  }

  for (const completion of ['response', 'error']) {
    it(`suppresses a late resource-load ${completion} after switching context`, async () => {
      const fixture = makePluginFixture()
      const next = makePluginFixture({ id: 'acme.new-context', version: '2.0.0' })
      setFixtures(fixture, next)
      const stateResponse = deferred()
      const targetsResponse = deferred()
      const unavailable = []
      mock.getState = id => id === fixture.manifest.id ? stateResponse.promise : stateFor(id)
      mock.getTargets = id => id === fixture.manifest.id ? targetsResponse.promise : [makeTarget({ channelId: 'channel-2', model: 'fresh-resource' })]
      const entry = await mount('PluginDiagnosticHost', { contribution: fixture.contribution, context: { kind: 'channel', id: channel.id }, onUnavailable: () => unavailable.push(true) })
      assert.equal(entry.state.loading.value, true)
      const oldStateCall = calls.state[0]
      const oldTargetsCall = calls.targets[0]
      await entry.setProps({ contribution: next.contribution, context: { kind: 'channel', id: 'channel-2' } })
      await waitFor(() => !entry.state.loading.value)
      assert.equal(oldStateCall.options.signal.aborted, true)
      assert.equal(oldTargetsCall.options.signal.aborted, true)
      targetsResponse.resolve([makeTarget({ model: 'stale-resource' })])
      if (completion === 'response') stateResponse.resolve(fixture.state)
      else stateResponse.reject(new ApiError('STALE_RESOURCE_ERROR', 503))
      await settle()
      assert.equal(entry.state.state.value.id, next.manifest.id)
      assert.equal(entry.state.form.model, 'fresh-resource')
      assert.deepEqual(entry.state.targets.value.map(target => target.model), ['fresh-resource'])
      assert.equal(entry.state.loadError.value, '')
      assert.equal(toasts.length, 0)
      assert.equal(unavailable.length, 0)
      assert.doesNotMatch(text(entry.container), /stale-resource|STALE_RESOURCE_ERROR/)
    })
  }

  it('suppresses late load and invocation errors after unmount', async () => {
    const fixture = makePluginFixture()
    const entry = await hostFor(fixture)
    const late = deferred()
    mock.invoke = () => late.promise
    const run = entry.state.run()
    await waitFor(() => calls.invocations.length === 1)
    entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
    assert.equal(calls.invocations[0].options.signal.aborted, true)
    late.reject(new ApiError('UNMOUNTED_ERROR', 503))
    await run; await settle()
    assert.equal(toasts.length, 0)
    const loadCallsAfterUnmount = calls.state.length
    window.dispatchEvent(new Event(PLUGIN_STATE_CHANGED)); await settle()
    assert.equal(calls.state.length, loadCallsAfterUnmount, 'Unmount removes the plugin-change listener')
    const load = deferred()
    mock.getState = () => load.promise
    const second = await mount('PluginDiagnosticHost', { contribution: fixture.contribution, context: { kind: 'channel', id: channel.id } })
    second.unmount(); mounted.splice(mounted.indexOf(second), 1)
    load.reject(new ApiError('UNMOUNTED_LOAD', 503))
    await settle()
    assert.equal(second.state.loadError.value, '')
    assert.equal(toasts.length, 0)
  })

  for (const [binding, mismatch] of [
    ['plugin', execution => { execution.pluginId = 'acme.wrong-plugin' }],
    ['version', execution => { execution.activeVersion = '2.0.0' }],
    ['context kind', execution => { execution.target.kind = 'oauth-account' }],
    ['context', execution => { execution.target.id = 'wrong-channel' }],
    ['model', execution => { execution.target.model = 'wrong-model' }],
    ['protocol', execution => { execution.target.protocol = 'responses' }],
    ['credential', execution => { execution.target.credentialId = 'wrong-credential' }],
  ]) {
    it(`discards a mismatched ${binding} response instead of rendering or retrying it`, async () => {
      const entry = await hostFor()
      mock.invoke = call => { const execution = responseFor(call); mismatch(execution); return execution }
      await entry.state.run(); await settle()
      assert.equal(calls.invocations.length, 1)
      assert.equal(entry.container.querySelectorAll('.plugin-result').length, 0)
      assert.match(entry.state.rows.value[0].error, /不一致.*丢弃/)
      assert.equal(toasts.at(-1).type, 'error')
    })
  }

  it('shows context-specific guidance for channel and OAuth 401 403 and 404 results', async () => {
    for (const [kind, statusCode, title, hint] of [
      ['channel', 401, '渠道凭据认证失败', '替换该凭据'],
      ['channel', 403, '渠道凭据无权调用这个模型', '所选凭据的上游权限'],
      ['channel', 404, '渠道未找到这个模型', '渠道模型配置'],
      ['oauth-account', 401, '账号凭据已失效', '重新授权'],
      ['oauth-account', 403, '账号无权调用这个模型', '套餐'],
      ['oauth-account', 404, '该账号无法调用这个模型', '重新同步模型目录'],
    ]) {
      const execution = makeExecution({ context: { kind, id: kind === 'channel' ? channel.id : 'account-1' }, outcome: { success: false, statusCode, errorMessage: 'upstream rejection' } })
      const entry = await mount('PluginDiagnosticResult', { execution })
      assert.equal(text(entry.container.querySelector('.plugin-result-error strong')), title)
      assert.match(text(entry.container.querySelector('.plugin-result-error p')), new RegExp(hint))
      assert.equal(text(entry.container.querySelector('.plugin-result-error code')), 'upstream rejection')
    }
  })

  it('escapes report response metrics and upstream-401 details as literal text', async () => {
    const payload = '<img src=x onerror="globalThis.injected=true"><script>globalThis.injected=true</script>'
    const execution = makeExecution({ outcome: { success: false, statusCode: 401, responseText: payload, errorMessage: payload, upstreamReportedModel: payload },
      report: { title: payload, text: `report ${payload}`, notices: [payload], metrics: [{ label: payload, value: payload }] } })
    const entry = await mount('PluginDiagnosticResult', { execution })
    assert.equal(entry.container.querySelectorAll('img,script').length, 0)
    assert.equal(entry.container.querySelector('.plugin-report-title').textContent, payload)
    assert.equal(entry.container.querySelector('pre').textContent, payload)
    assert.equal(entry.container.querySelectorAll('pre')[1].textContent, `report ${payload}`)
    assert.match(text(entry.container), /HTTP 401/)
    assert.equal(globalThis.injected, undefined)
  })

  it('keeps a different active diagnostic running when rechecking a stale entry', async () => {
    const first = makeOfficialPluginFixture()
    const second = makePluginFixture()
    setFixtures(first, second)
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
    const host = captures.get('PluginDiagnosticHost')
    button(entry.container, '合成诊断').click()
    await waitFor(() => diagnosticHostSetups === 2 && !captures.get('PluginDiagnosticHost').loading.value)
    captures.get('PluginDiagnosticHost').form.prompt = 'stale cached input for B'
    button(entry.container, '连通性').click()
    await waitFor(() => !host.loading.value)
    entry.state.markUnavailable({ pluginId: second.manifest.id, activeVersion: second.contribution.activeVersion, entryId: second.contribution.entry.id })
    await settle()
    const setups = diagnosticHostSetups
    const pending = deferred()
    mock.invoke = () => pending.promise
    const run = host.run()
    await waitFor(() => calls.invocations.length === 1)
    const call = calls.invocations[0]
    button(entry.container, '重新检查').click()
    await waitFor(() => entry.state.tabs.value.some(tab => tab.id === `plugin:${second.manifest.id}:${second.contribution.entry.id}`))
    assert.equal(diagnosticHostSetups, setups)
    assert.equal(call.options.signal.aborted, false)
    assert.equal(host.running.value, true)
    pending.resolve(responseFor(call))
    await run; await settle()
    assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
    button(entry.container, '合成诊断').click()
    await waitFor(() => diagnosticHostSetups === setups + 1 && !captures.get('PluginDiagnosticHost').loading.value)
    assert.notEqual(captures.get('PluginDiagnosticHost').form.prompt, 'stale cached input for B')
  })

  it('keeps an upgraded contribution tab selected and restores its tab focus after the panel remounts', async () => {
    const first = makeOfficialPluginFixture({ version: '1.0.0' })
    const upgraded = makeOfficialPluginFixture({ version: '2.0.0' })
    setFixtures(first)
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
    const tabKey = `plugin:${first.manifest.id}:${first.contribution.entry.id}`
    assert.equal(entry.state.activeTab.value, tabKey)
    const focusedPanelControl = field(entry.container, '模型')
    focusedPanelControl.focus()
    assert.equal(document.activeElement, focusedPanelControl)
    const initialSetups = diagnosticHostSetups

    mock.states.set(upgraded.state.id, clonePluginData(upgraded.state))
    mock.contributions = clonePluginData(upgraded.contributions)
    await entry.state.load()
    await waitFor(() => diagnosticHostSetups === initialSetups + 1 && !captures.get('PluginDiagnosticHost').loading.value)

    const upgradedTab = [...entry.container.querySelectorAll('[role="tab"]')].find(tab => tab.id.endsWith(`-${tabKey}`))
    assert.ok(upgradedTab)
    assert.equal(entry.state.activeTab.value, tabKey)
    assert.equal(captures.get('PluginDiagnosticHost').props.contribution.activeVersion, '2.0.0')
    assert.equal(document.activeElement, upgradedTab)
    assert.equal(entry.state.activeKey.value, `${tabKey}:${channel.id}:2.0.0:0`)
  })

  for (const focusLocation of ['tab', 'panel', 'elsewhere']) {
    it(`moves focus after an active diagnostic disappears when focus is ${focusLocation}`, async () => {
      const fixture = makeOfficialPluginFixture()
      setFixtures(fixture)
      const entry = await mount('ChannelTestDrawer', { channel })
      await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
      const activeTab = entry.container.querySelector('[role="tab"][aria-selected="true"]')
      const iq = [...entry.container.querySelectorAll('[role="tab"]')].find(tab => text(tab) === '智商')
      let scrollOptions = null
      iq.scrollIntoView = options => { scrollOptions = options }
      const focused = focusLocation === 'tab' ? activeTab : focusLocation === 'panel'
        ? field(entry.container, '模型') : button(entry.container, '关闭模拟窗口')
      focused.focus()
      assert.equal(document.activeElement, focused)
      entry.state.markUnavailable({ pluginId: fixture.manifest.id, activeVersion: fixture.contribution.activeVersion, entryId: fixture.contribution.entry.id })
      await settle()
      assert.equal(document.activeElement, focusLocation === 'elsewhere' ? focused : iq)
      assert.equal(entry.state.activeTab.value, 'iq')
      if (focusLocation !== 'elsewhere') assert.deepEqual(scrollOptions, { block: 'nearest', inline: 'nearest' })
    })
  }

  for (const removal of ['notification', 'refresh']) {
    for (const focusLocation of ['removed-tab', 'active-panel', 'elsewhere']) {
      it(`preserves the active diagnostic when ${removal} removes another tab with focus at ${focusLocation}`, async () => {
        const first = makeOfficialPluginFixture()
        const second = makePluginFixture()
        setFixtures(first, second)
        const entry = await mount('ChannelTestDrawer', { channel })
        await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
        const host = captures.get('PluginDiagnosticHost')
        const setups = diagnosticHostSetups
        const activeKey = entry.state.activeKey.value
        await change(field(entry.container, '本次提示词'), 'retain the active diagnostic draft')
        const pending = deferred()
        mock.invoke = () => pending.promise
        const run = host.run()
        await waitFor(() => calls.invocations.length === 1)
        const call = calls.invocations[0]
        const activeTab = entry.container.querySelector('[role="tab"][aria-selected="true"]')
        const removedTab = [...entry.container.querySelectorAll('[role="tab"]')].find(tab => text(tab) === '合成诊断')
        const focused = focusLocation === 'removed-tab' ? removedTab : focusLocation === 'active-panel'
          ? entry.container.querySelector('[role="tabpanel"]') : button(entry.container, '关闭模拟窗口')
        focused.focus()
        assert.equal(document.activeElement, focused)
        if (removal === 'notification') {
          entry.state.markUnavailable({ pluginId: second.manifest.id, activeVersion: second.contribution.activeVersion, entryId: second.contribution.entry.id })
        } else {
          const discovery = deferred()
          mock.listContributions = () => discovery.promise
          const reload = entry.state.load()
          await settle()
          assert.equal(document.activeElement, focused)
          assert.equal(call.options.signal.aborted, false, 'discovery must preserve the active invocation while pending')
          discovery.resolve(clonePluginData(first.contributions))
          await reload
        }
        await settle()
        assert.equal(removedTab.isConnected, false)
        assert.equal(document.activeElement, focusLocation === 'removed-tab' ? activeTab : focused)
        assert.equal(entry.state.activeTab.value, `plugin:${first.manifest.id}:${first.contribution.entry.id}`)
        assert.equal(entry.state.activeKey.value, activeKey)
        assert.equal(captures.get('PluginDiagnosticHost'), host)
        assert.equal(diagnosticHostSetups, setups)
        assert.equal(host.form.prompt, 'retain the active diagnostic draft')
        assert.equal(call.options.signal.aborted, false)
        assert.equal(host.running.value, true)
        pending.resolve(responseFor(call))
        await run; await settle()
        assert.equal(entry.container.querySelectorAll('.plugin-result').length, 1)
      })
    }
  }

  it('navigates real dynamic tabs and keeps legacy views available when connectivity disappears', async () => {
    const official = makeOfficialPluginFixture()
    const unknown = makePluginFixture()
    setFixtures(official, unknown)
    const entry = await mount('ChannelTestDrawer', { channel })
    await waitFor(() => entry.state.tabs.value.length === 5 && entry.container.querySelectorAll('[role="tab"]').length === 5)
    const tabs = () => [...entry.container.querySelectorAll('[role="tab"]')]
    assert.deepEqual(tabs().map(tab => text(tab)), ['连通性', '合成诊断', '智商', '指纹', '记录'])
    tabs()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true })); await settle()
    assert.equal(document.activeElement, tabs().at(-1))
    assert.equal(entry.state.activeTab.value, 'records')
    assert.equal(recordMounts, 1)
    tabs().at(-1).dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true })); await settle()
    assert.equal(recordMounts, 1)
    tabs().at(-1).dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true })); await settle()
    assert.equal(document.activeElement, tabs()[0])
    tabs()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await settle()
    assert.equal(text(tabs()[1]), '合成诊断')
    assert.equal(document.activeElement, tabs()[1])
    mock.contributions = []
    await entry.state.load(); await settle()
    assert.deepEqual(tabs().map(tab => text(tab)), ['智商', '指纹', '记录'])
    assert.match(text(entry.container), /智商、指纹与记录仍可使用/)
    assert.ok(entry.container.querySelector('[data-legacy-view="iq"]'))
    tabs()[1].click(); await settle()
    assert.ok(entry.container.querySelector('[data-legacy-view="fingerprint"]'))
    tabs()[2].click(); await settle()
    assert.ok(entry.container.querySelector('[data-legacy-view="records"]'))
    assert.equal(recordMounts, 2)
  })

  it('restores the marketplace diagnostic trigger after the rendered modal closes', async () => {
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    const trigger = button(entry.container, entry.state.pageEntries.value[0].label)
    trigger.focus()
    trigger.click()
    await waitFor(() => entry.container.querySelector('.synthetic-dialog'))
    const close = button(entry.container, '关闭模拟窗口')
    close.focus()
    assert.equal(document.activeElement, close)
    close.click()
    await settle()
    assert.equal(entry.container.querySelector('.synthetic-dialog'), null)
    assert.equal(document.activeElement, trigger)
  })

  it('rechecks the current marketplace entry and keeps missing or failed rechecks closed', async () => {
    const first = makeOfficialPluginFixture()
    const next = makeOfficialPluginFixture({ version: '2.0.0' })
    setFixtures(first)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    button(entry.container, first.pageContribution.entry.label).click()
    await waitFor(() => captures.get('PluginDiagnosticHost')?.loading.value === false)
    const initialHost = captures.get('PluginDiagnosticHost')
    const initialClose = button(entry.container, '关闭模拟窗口')
    initialClose.focus()
    assert.equal(document.activeElement, initialClose)
    mock.states.set(first.manifest.id, clonePluginData(next.state))
    await initialHost.load(); await settle()
    assert.equal(entry.container.querySelector('.synthetic-dialog'), null)
    assert.equal(document.activeElement, button(entry.container, '重新检查'))
    assert.doesNotMatch(text(entry.container), /关闭弹窗后重试/)
    const pending = deferred()
    mock.listPlugins = () => pending.promise
    const setups = diagnosticHostSetups
    button(entry.container, '重新检查').click()
    await waitFor(() => calls.plugins.length === 2)
    assert.equal(entry.container.querySelector('.synthetic-dialog'), null, 'stale entry remains closed until refresh completes')
    pending.resolve([clonePluginData(next.state)])
    await waitFor(() => diagnosticHostSetups === setups + 1 && !captures.get('PluginDiagnosticHost').loading.value)
    assert.equal(captures.get('PluginDiagnosticHost').props.contribution.activeVersion, '2.0.0')
    assert.deepEqual(captures.get('PluginDiagnosticHost').props.contribution.entry, next.pageContribution.entry)

    const host = captures.get('PluginDiagnosticHost')
    const missingState = { ...next.state, activeUi: { ...next.ui, entries: next.ui.entries.filter(item => item.id !== next.pageContribution.entry.id) } }
    mock.states.set(first.manifest.id, clonePluginData(missingState))
    mock.listPlugins = () => Promise.resolve([clonePluginData(missingState)])
    await host.load(); await settle()
    const beforeMissing = diagnosticHostSetups
    button(entry.container, '重新检查').click()
    await waitFor(() => calls.plugins.length === 3 && !entry.state.loading.value)
    assert.equal(diagnosticHostSetups, beforeMissing)
    assert.equal(entry.container.querySelector('.synthetic-dialog'), null)
    assert.match(text(entry.container), /入口.*不可用|入口.*不存在/)

    mock.listPlugins = () => Promise.reject(new Error('recheck unavailable'))
    button(entry.container, '重新检查').click()
    await waitFor(() => calls.plugins.length === 4 && !entry.state.loading.value)
    assert.equal(diagnosticHostSetups, beforeMissing)
    assert.equal(entry.container.querySelector('.synthetic-dialog'), null)
    assert.match(text(entry.container), /recheck unavailable/)
  })

  it('does not restore diagnostic focus after the marketplace has unmounted', async () => {
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    const trigger = button(entry.container, entry.state.pageEntries.value[0].label)
    trigger.focus()
    trigger.click()
    await waitFor(() => entry.container.querySelector('.synthetic-dialog'))
    const close = button(entry.container, '关闭模拟窗口')
    close.focus()
    let focusCalls = 0
    trigger.focus = () => { focusCalls++ }
    close.click()
    entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
    await settle()
    assert.equal(focusCalls, 0)
  })

  it('requires complete grants and displays authoritative install enable disable and uninstall states without hot enable', async () => {
    const fixture = makePluginFixture()
    setFixtures(fixture); mock.states.delete(fixture.manifest.id)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    assert.equal(button(entry.container, '安装此版本').disabled, true)
    const grants = [...entry.container.querySelectorAll('.plugin-market-permissions input')]
    await change(grants[0], true)
    assert.equal(button(entry.container, '安装此版本').disabled, true)
    await change(grants[1], true)
    button(entry.container, '安装此版本').click()
    await waitFor(() => calls.install.length === 1 && !entry.state.busy.value)
    assert.deepEqual(calls.install[0], { id: fixture.manifest.id, payload: { version: '1.0.0', expectedSha256: fixture.catalog.sha256, approvedPermissions: fixture.manifest.permissions } })
    assert.equal(text(entry.container.querySelector('.plugin-market-detail-head .badge')), '已禁用')
    assert.equal(fact(entry.container, '当前生效'), '无可执行入口')
    const installedGrants = [...entry.container.querySelectorAll('.plugin-market-permissions input')]
    await change(installedGrants[1], false)
    assert.equal(button(entry.container, '启用（重启生效）').disabled, true)
    entry.state.toggleEnabled()
    assert.equal(calls.enabled.length, 0, 'Enable requires all declared capabilities, not a partial grant')
    await change(installedGrants[1], true)
    button(entry.container, '启用（重启生效）').click()
    await waitFor(() => calls.enabled.length === 1 && !entry.state.busy.value)
    assert.deepEqual(calls.enabled[0].payload, {
      enabled: true, installedVersion: '1.0.0', expectedRevision: 0, approvedPermissions: fixture.manifest.permissions,
    })
    assert.equal(entry.state.selectedState.value.admissionEnabled, false)
    assert.equal(entry.state.selectedState.value.activeVersion, null)
    assert.equal(entry.state.pageEntries.value.length, 0)
    assert.match(text(entry.container), /待重启/)
    assert.ok(entry.container.querySelector('.plugin-market-restart'))
    button(entry.container, '禁用').click()
    await waitFor(() => calls.enabled.length === 2 && !entry.state.busy.value)
    assert.deepEqual(calls.enabled[1].payload, { enabled: false })
    confirmResult = true
    button(entry.container, '卸载').click()
    await waitFor(() => calls.uninstall.length === 1 && !entry.state.busy.value)
    assert.equal(entry.state.selectedState.value.installed, false)
    assert.equal(entry.state.selectedState.value.installedVersion, '1.0.0')
    assert.match(text(entry.container), /已卸载，保留历史选择/)
    assert.deepEqual(entry.state.selectedState.value.configuration, fixture.state.configuration)
  })

  it('requires a fresh installation revision after an enable conflict', async () => {
    const fixture = makePluginFixture({ state: {
      activeVersion: null, desiredEnabled: false, admissionEnabled: false, status: 'disabled',
      activeConfiguration: null, activeUi: null,
    } })
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    setState(fixture.manifest.id, { revision: 1 })
    button(entry.container, '启用（重启生效）').click()
    await waitFor(() => calls.enabled.length === 1 && !entry.state.busy.value)
    assert.equal(calls.enabled[0].payload.expectedRevision, 0)
    assert.equal(button(entry.container, '启用（重启生效）').disabled, true)
    assert.match(toasts.at(-1).message, /installation changed/)

    button(entry.container, '刷新').click()
    await waitFor(() => calls.plugins.length === 2 && !entry.state.loading.value)
    assert.equal(entry.state.selectedState.value.revision, 1)
    assert.equal(button(entry.container, '启用（重启生效）').disabled, false)
    button(entry.container, '启用（重启生效）').click()
    await waitFor(() => calls.enabled.length === 2 && !entry.state.busy.value)
    assert.equal(calls.enabled[1].payload.expectedRevision, 1)
    assert.equal(entry.state.selectedState.value.desiredEnabled, true)
  })

  it('keeps configuration prompt fields on their own keys and treats a clean empty snapshot as unchanged', async () => {
    const fixture = makePluginFixture({
      configurationFields: [
        makeField('prompt', '配置 prompt', 'text', { defaultValue: 'config default' }),
        makeField('defaultPrompt', '诊断默认提示词', 'text', { defaultValue: 'action alias source' }),
      ],
      state: {
        configuration: { prompt: 'saved config value', defaultPrompt: 'saved action alias value' },
        activeConfiguration: { prompt: 'saved config value', defaultPrompt: 'saved action alias value' },
      },
    })
    setFixtures(fixture)
    const marketplace = await mount('PluginMarketplaceView')
    await waitFor(() => !marketplace.state.loading.value)
    assert.equal(marketplace.state.dirty.value, false)
    assert.deepEqual(marketplace.state.originalConfiguration.value, { prompt: 'saved config value', defaultPrompt: 'saved action alias value' })
    assert.equal(field(marketplace.container, '配置 prompt').value, 'saved config value')
    assert.equal(marketplace.state.canLeave(), true)
    assert.equal(confirmCalls.length, 0)

    const diagnostic = await hostFor(fixture)
    assert.equal(diagnostic.state.defaultValues.value.prompt, 'saved action alias value')
  })

  it('treats initial config as clean if the initial list request fails', async () => {
    mock.listCatalog = () => Promise.reject(new Error('initial list unavailable'))
    mock.listPlugins = () => Promise.reject(new Error('initial list unavailable'))
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    assert.deepEqual(entry.state.originalConfiguration.value, {})
    assert.equal(entry.state.dirty.value, false)
    assert.equal(entry.state.canLeave(), true)
    assert.equal(confirmCalls.length, 0)
  })

  it('blocks installation and version selection until installation state is successfully read', async () => {
    const first = makePluginFixture({ state: { installed: false, installedVersion: null, desiredEnabled: false, admissionEnabled: false } })
    const second = makePluginFixture({ version: '2.0.0' })
    setFixtures(first)
    mock.catalog.push(clonePluginData(second.catalog))
    mock.listPlugins = () => Promise.reject(new Error('installation state unreadable'))
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    const version = field(entry.container, '目录版本')
    assert.equal(version.disabled, true)
    assert.match(text(entry.container), /安装状态尚未读取成功/)
    for (const permission of entry.container.querySelectorAll('.plugin-market-permissions input')) await change(permission, true)
    assert.equal(entry.state.canInstall.value, false)
    assert.equal(button(entry.container, '安装此版本').disabled, true)
    entry.state.install()
    assert.equal(calls.install.length, 0)

    mock.listPlugins = undefined
    await entry.state.load(); await settle()
    assert.equal(version.disabled, false)
    for (const permission of entry.container.querySelectorAll('.plugin-market-permissions input')) await change(permission, true)
    assert.equal(entry.state.canInstall.value, true)
    button(entry.container, '安装此版本').click()
    await waitFor(() => calls.install.length === 1 && !entry.state.busy.value)
    assert.equal(entry.state.selectedState.value.installed, true)
  })

  it('blocks enable disable and uninstall after installation state refresh fails until a successful read', async () => {
    for (const desiredEnabled of [true, false]) {
      const fixture = makePluginFixture({ state: { desiredEnabled } })
      setFixtures(fixture)
      const entry = await mount('PluginMarketplaceView')
      await waitFor(() => !entry.state.loading.value)
      mock.listPlugins = () => Promise.reject(new Error('installation refresh unavailable'))
      await entry.state.load(); await settle()
      const label = desiredEnabled ? '禁用' : '启用（重启生效）'
      assert.equal(button(entry.container, label).disabled, true)
      assert.equal(button(entry.container, '卸载').disabled, true)
      const enabledCalls = calls.enabled.length
      const uninstallCalls = calls.uninstall.length
      const prompts = confirmCalls.length
      entry.state.toggleEnabled()
      entry.state.uninstall()
      assert.equal(calls.enabled.length, enabledCalls)
      assert.equal(calls.uninstall.length, uninstallCalls)
      assert.equal(confirmCalls.length, prompts, 'unknown state does not prompt for a mutation')
      mock.listPlugins = undefined
      await entry.state.load(); await settle()
      assert.equal(button(entry.container, label).disabled, false)
      assert.equal(button(entry.container, '卸载').disabled, false)
    }
  })

  for (const [failureKind, makeFailure] of [
    ['network', () => new Error('mutation response lost')],
    ['server', () => new ApiError('mutation result unknown', 503, 'upstream_error')],
    ['invalid response', () => new ApiError('mutation result invalid', 200, 'invalid_response')],
  ]) {
    it('blocks every mutation after an uncertain ' + failureKind + ' until authoritative state is read', async () => {
      for (const operation of ['install', 'disable', 'enable', 'uninstall', 'configuration']) {
        const fixture = makePluginFixture({ state: operation === 'install'
          ? { installed: false, installedVersion: null, activeVersion: null, desiredEnabled: false, admissionEnabled: false, requiresRestart: false, status: 'uninstalled' }
          : operation === 'enable' ? { desiredEnabled: false, admissionEnabled: false, status: 'disabled' } : {} })
        setFixtures(fixture)
        const entry = await mount('PluginMarketplaceView')
        await waitFor(() => !entry.state.loading.value)
        const method = { install: 'installPlugin', disable: 'setPluginEnabled', enable: 'setPluginEnabled', uninstall: 'uninstallPlugin', configuration: 'configurePlugin' }[operation]
        const original = api[method]
        api[method] = async (...args) => { await original(...args); throw makeFailure() }
        confirmResult = true
        try {
          if (operation === 'install') { assert.equal(entry.state.canInstall.value, true); entry.state.install() }
          else if (operation === 'configuration') {
            await change(field(entry.container, '默认提示词'), 'preserved uncertain draft')
            button(entry.container, '保存配置').click()
          } else button(entry.container, operation === 'disable' ? '禁用' : operation === 'enable' ? '启用（重启生效）' : '卸载').click()
          await waitFor(() => !entry.state.busy.value && !entry.state.installationStateKnown.value)
          assert.match(text(entry.container), /无法确认写入结果/)
          assert.equal(field(entry.container, '目录版本').disabled, true)
          const counts = () => [calls.install.length, calls.enabled.length, calls.uninstall.length, calls.configuration.length]
          const before = counts()
          entry.state.install()
          entry.state.toggleEnabled()
          entry.state.uninstall()
          entry.state.saveConfiguration()
          await settle()
          assert.deepEqual(counts(), before)
          mock.listPlugins = () => Promise.reject(new Error('authoritative state unavailable'))
          await entry.state.load(); await settle()
          assert.equal(entry.state.installationStateKnown.value, false)
          if (operation === 'configuration') assert.equal(entry.state.configuration.defaultPrompt, 'preserved uncertain draft')
          mock.listPlugins = undefined
          await entry.state.load(); await settle()
          assert.equal(entry.state.installationStateKnown.value, true)
          assert.deepEqual(clonePluginData(entry.state.selectedState.value), stateFor(fixture.manifest.id))
          assert.deepEqual(counts(), before)
        } finally {
          api[method] = original
          mock.listPlugins = undefined
          entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
        }
      }
    })
  }

  it('treats an uninstall revision conflict as unknown installation state without rejecting a clean draft', async () => {
    const fixture = makePluginFixture()
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    api.uninstallPlugin = async id => { calls.uninstall.push(id); throw new ApiError('installation changed', 409, 'PLUGIN_REVISION_CONFLICT') }
    confirmResult = true
    button(entry.container, '卸载').click()
    await waitFor(() => calls.uninstall.length === 1 && !entry.state.busy.value)
    assert.equal(entry.state.draftRejected.value, false)
    assert.equal(entry.state.draftConflict.value, false)
    assert.equal(entry.state.dirty.value, false)
    assert.match(text(entry.container), /安装状态已变化，请重新读取/)
    assert.equal(field(entry.container, '目录版本').disabled, true)
    await entry.state.load(); await settle()
    await change(field(entry.container, '默认提示词'), 'fresh edit after uninstall conflict')
    button(entry.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 1 && !entry.state.busy.value)
    assert.equal(entry.state.draftConflict.value, false)
    assert.equal(entry.state.configuration.defaultPrompt, 'fresh edit after uninstall conflict')
  })

  it('keeps a conflicting configuration draft but blocks installation actions until the upgraded selection is reread', async () => {
    const fixture = makePluginFixture({ state: { revision: 3 } })
    const next = makePluginFixture({ version: '2.0.0', state: { revision: 4 } })
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'local draft from version one')
    mock.states.set(fixture.manifest.id, clonePluginData(next.state))
    mock.catalog.push(clonePluginData(next.catalog))
    button(entry.container, '保存配置').click()
    await waitFor(() => !entry.state.busy.value && entry.state.draftRejected.value)
    assert.equal(entry.state.installationStateKnown.value, false)
    assert.equal(entry.state.configuration.defaultPrompt, 'local draft from version one')
    assert.equal(button(entry.container, '禁用').disabled, true)
    assert.equal(button(entry.container, '卸载').disabled, true)
    assert.equal(field(entry.container, '目录版本').disabled, true)
    const before = [calls.install.length, calls.enabled.length, calls.uninstall.length, calls.configuration.length]
    entry.state.install(); entry.state.toggleEnabled(); entry.state.uninstall(); entry.state.saveConfiguration()
    await settle()
    assert.deepEqual([calls.install.length, calls.enabled.length, calls.uninstall.length, calls.configuration.length], before)
    mock.listPlugins = () => Promise.reject(new Error('state reread unavailable'))
    await entry.state.load(); await settle()
    assert.equal(entry.state.installationStateKnown.value, false)
    assert.equal(entry.state.configuration.defaultPrompt, 'local draft from version one')
    mock.listPlugins = undefined
    await entry.state.load(); await settle()
    assert.equal(entry.state.installationStateKnown.value, true)
    assert.equal(entry.state.selectedState.value.installedVersion, '2.0.0')
    assert.equal(entry.state.draftRejected.value, true)
    assert.equal(entry.state.configuration.defaultPrompt, 'local draft from version one')
    button(entry.container, '放弃旧草稿并载入当前配置').click()
    await waitFor(() => !entry.state.draftConflict.value)
    assert.equal(entry.state.draftInstalledVersion.value, '2.0.0')
  })

  it('adopts the server revision for two consecutive configuration saves without a refresh', async () => {
    const fixture = makePluginFixture({ state: { revision: 7 } })
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    for (const [index, value] of ['first saved configuration', 'second saved configuration'].entries()) {
      await change(field(entry.container, '默认提示词'), value)
      button(entry.container, '保存配置').click()
      await waitFor(() => calls.configuration.length === index + 1 && !entry.state.busy.value)
      assert.equal(calls.configuration[index].payload.expectedRevision, 7 + index)
      assert.equal(entry.state.draftRevision.value, 8 + index)
      assert.equal(entry.state.draftConflict.value, false)
      assert.equal(entry.state.dirty.value, false)
    }
    assert.equal(calls.plugins.length, 1, 'saving twice does not require a list refresh')
    assert.equal(stateFor(fixture.manifest.id).revision, 9)
    assert.equal(stateFor(fixture.manifest.id).configuration.defaultPrompt, 'second saved configuration')
  })

  it('cancels and invalidates list requests during mutations and blocks retry while busy', async () => {
    const first = makePluginFixture()
    const second = makePluginFixture({ id: 'acme.second', displayName: 'Second plugin' })
    setFixtures(first, second)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    const lateCatalog = deferred()
    const latePlugins = deferred()
    mock.listCatalog = () => lateCatalog.promise
    mock.listPlugins = () => latePlugins.promise
    const refresh = entry.state.load()
    await waitFor(() => calls.catalog.length === 2 && calls.plugins.length === 2)
    const staleCatalogOptions = calls.catalog.at(-1)
    const stalePluginsOptions = calls.plugins.at(-1)
    const operation = deferred()
    api.setPluginEnabled = () => operation.promise
    entry.state.toggleEnabled()
    assert.equal(staleCatalogOptions.signal.aborted, true)
    assert.equal(stalePluginsOptions.signal.aborted, true)
    assert.equal(entry.state.busy.value, true)
    const requestCount = calls.catalog.length
    await entry.state.load()
    assert.equal(calls.catalog.length, requestCount, 'retry does not start a list request while a mutation is pending')
    lateCatalog.resolve([])
    latePlugins.resolve([])
    await refresh
    assert.equal(entry.state.selectedState.value.id, first.manifest.id)
    operation.resolve(setState(first.manifest.id, { desiredEnabled: false, admissionEnabled: false, status: 'disabled', activeUi: null }))
    await waitFor(() => !entry.state.busy.value)
    assert.equal(entry.state.selectedState.value.desiredEnabled, false)
    assert.equal(entry.state.items.value.some(item => item.id === second.manifest.id), true)
  })

  it('saves configuration without a discard prompt and protects unsaved drafts across selection and canLeave', async () => {
    const first = makePluginFixture()
    const second = makePluginFixture({ id: 'acme.second', displayName: 'Second unknown plugin' })
    setFixtures(first, second)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'draft configuration')
    assert.equal(entry.state.dirty.value, true)
    assert.equal(entry.state.canLeave(), false)
    assert.equal(confirmCalls.length, 1)
    pluginItem(entry.container, second.manifest.id).click()
    await settle()
    assert.equal(entry.state.selectedId.value, first.manifest.id)
    assert.equal(entry.state.configuration.defaultPrompt, 'draft configuration')
    const prompts = confirmCalls.length
    button(entry.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 1 && !entry.state.busy.value)
    assert.equal(confirmCalls.length, prompts, 'Saving is not a discard operation')
    assert.equal(calls.configuration[0].payload.values.defaultPrompt, 'draft configuration')
    assert.equal(entry.state.dirty.value, false)
    assert.equal(entry.state.selectedState.value.activeConfiguration.defaultPrompt, 'draft configuration')
    assert.equal(entry.state.canLeave(), true)
    entry.state.selectPlugin(second.manifest.id); await settle()
    assert.equal(entry.state.selectedId.value, second.manifest.id)
    assert.equal(entry.state.configuration.defaultPrompt, second.state.configuration.defaultPrompt)
  })

  it('preserves a dirty configuration on refresh and leaves only after actual discard confirmation', async () => {
    const first = makePluginFixture()
    const second = makePluginFixture({ id: 'acme.other-draft', displayName: 'Other draft target' })
    setFixtures(first, second)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'local dirty draft')
    const original = entry.state.originalConfiguration.value
    setState(first.manifest.id, { configuration: { defaultPrompt: 'remote refreshed configuration', threshold: 0.8 } })
    button(entry.container, '刷新').click()
    await waitFor(() => calls.plugins.length === 2 && !entry.state.loading.value)
    assert.equal(confirmCalls.length, 0, 'Refresh does not discard or confirm the local draft')
    assert.equal(entry.state.configuration.defaultPrompt, 'local dirty draft')
    assert.equal(entry.state.originalConfiguration.value, original)
    assert.equal(entry.state.dirty.value, true)
    assert.equal(entry.state.selectedState.value.configuration.defaultPrompt, 'remote refreshed configuration')
    pluginItem(entry.container, second.manifest.id).click(); await settle()
    assert.equal(entry.state.selectedId.value, first.manifest.id)
    assert.match(confirmCalls.at(-1), /插件配置尚未保存/)
    confirmResult = true
    pluginItem(entry.container, second.manifest.id).click(); await settle()
    assert.equal(entry.state.selectedId.value, second.manifest.id)
    assert.equal(entry.state.configuration.defaultPrompt, second.state.configuration.defaultPrompt)
    assert.equal(entry.state.dirty.value, false)
    assert.equal(confirmCalls.length, 2)
  })

  for (const nativeDraft of ['-', '1e']) {
    it(`treats an unfinished marketplace number ${nativeDraft} as dirty against null and protects undo refresh and switching`, async () => {
      const first = makePluginFixture({ configurationFields: [makeField('optionalCount', '可选配置数量', 'number')] })
      const second = makePluginFixture({ id: 'acme.other-number-draft' })
      setFixtures(first, second)
      const entry = await mount('PluginMarketplaceView')
      await waitFor(() => !entry.state.loading.value)
      assert.equal(entry.state.configuration.optionalCount, null)
      assert.equal(entry.state.dirty.value, false)
      assert.equal(button(entry.container, '撤销修改').disabled, true)
      const original = entry.state.originalConfiguration.value
      const input = field(entry.container, '可选配置数量')
      const valueProperty = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
      const writes = []
      let badInput = false
      Object.defineProperty(input, 'value', {
        configurable: true,
        get() { return valueProperty.get.call(this) },
        set(value) {
          writes.push(String(value))
          badInput = false
          valueProperty.set.call(this, value)
        },
      })
      Object.defineProperty(input, 'validity', { configurable: true, get: () => ({ badInput }) })
      const enterNativeDraft = async () => {
        // As with the existing scalar tests, model the browser's empty value + badInput
        // for an unfinished native number; the control itself still owns the raw draft.
        valueProperty.set.call(input, '')
        badInput = true
        input.dispatchEvent(new Event('input', { bubbles: true }))
        await settle()
      }
      await enterNativeDraft()
      assert.equal(Number.isNaN(entry.state.configuration.optionalCount), true)
      assert.equal(entry.state.dirty.value, true, `${nativeDraft} must not compare equal to the saved null`)
      assert.equal(entry.state.originalConfiguration.value, original)
      assert.match(entry.state.configurationError.value, /可选配置数量必须是数字/)
      assert.equal(button(entry.container, '保存配置').disabled, true)
      assert.equal(button(entry.container, '撤销修改').disabled, false)
      entry.state.saveConfiguration()
      assert.equal(calls.configuration.length, 0)
      assert.deepEqual(writes, [], 'the dirty check must not erase the native draft')
      assert.equal(entry.state.canLeave(), false)
      pluginItem(entry.container, second.manifest.id).click(); await settle()
      assert.equal(entry.state.selectedId.value, first.manifest.id)
      assert.equal(Number.isNaN(entry.state.configuration.optionalCount), true)
      assert.equal(confirmCalls.length, 2)
      button(entry.container, '刷新').click()
      await waitFor(() => calls.plugins.length === 2 && !entry.state.loading.value)
      assert.equal(entry.state.originalConfiguration.value, original)
      assert.equal(Number.isNaN(entry.state.configuration.optionalCount), true)
      assert.equal(entry.state.dirty.value, true)
      assert.equal(input.validity.badInput, true)
      assert.deepEqual(writes, [], 'a refresh preserves an invalid numeric draft')
      button(entry.container, '撤销修改').click(); await settle()
      assert.equal(entry.state.configuration.optionalCount, null)
      assert.equal(entry.state.dirty.value, false)
      assert.equal(entry.state.configurationError.value, '')
      assert.equal(input.validity.badInput, false)
      assert.deepEqual(writes, [''])
      assert.equal(button(entry.container, '撤销修改').disabled, true)
      assert.equal(entry.state.canLeave(), true)
      assert.equal(confirmCalls.length, 2)

      writes.length = 0
      await enterNativeDraft()
      assert.equal(entry.state.dirty.value, true)
      badInput = false
      await change(input, '')
      assert.equal(entry.state.configuration.optionalCount, null)
      assert.equal(entry.state.dirty.value, false, 'an actual clear returns to the saved null')
      assert.equal(entry.state.configurationError.value, '')
      writes.length = 0
      await enterNativeDraft()
      confirmResult = true
      pluginItem(entry.container, second.manifest.id).click(); await settle()
      assert.equal(confirmCalls.length, 3)
      assert.equal(entry.state.selectedId.value, second.manifest.id)
      assert.equal(entry.state.dirty.value, false)
      assert.equal(entry.state.configuration.defaultPrompt, second.state.configuration.defaultPrompt)
      assert.equal(calls.configuration.length, 0)
    })
  }

  it('blocks a draft from being saved to a different installed version until explicitly discarded', async () => {
    const first = makePluginFixture({ version: '1.0.0' })
    const next = makePluginFixture({ version: '2.0.0', configurationFields: [
      makeField('defaultPrompt', '新版本提示词', 'textarea', { defaultValue: 'fresh default' }),
      makeField('newOnly', '新版本字段', 'text', { defaultValue: 'new value' }),
    ] })
    setFixtures(first)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'draft for version one')
    mock.catalog.push(clonePluginData(next.catalog))
    mock.states.set(first.manifest.id, clonePluginData(next.state))
    button(entry.container, '刷新').click()
    await waitFor(() => calls.plugins.length === 2 && !entry.state.loading.value)
    assert.equal(entry.state.configuration.defaultPrompt, 'draft for version one')
    assert.equal(field(entry.container, '默认提示词').value, 'draft for version one')
    assert.equal([...entry.container.querySelectorAll('label')].some(label => text(label).includes('新版本字段')), false)
    assert.match(text(entry.container), /草稿.*1\.0\.0.*2\.0\.0/)
    assert.equal(button(entry.container, '保存配置').disabled, true)
    entry.state.saveConfiguration()
    assert.equal(calls.configuration.length, 0)
    assert.equal(entry.state.canLeave(), false)
    button(entry.container, '放弃旧草稿并载入当前配置').click(); await settle()
    assert.equal(entry.state.dirty.value, false)
    assert.equal(field(entry.container, '新版本提示词').value, 'fresh default')
    assert.equal(field(entry.container, '新版本字段').value, 'new value')
    await change(field(entry.container, '新版本提示词'), 'explicit version two edit')
    button(entry.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 1 && !entry.state.busy.value)
    assert.deepEqual(calls.configuration[0].payload.values, { defaultPrompt: 'explicit version two edit', newOnly: 'new value' })
  })

  for (const catalogVersion of ['2.0.0', '1.0.0']) {
    it(`reenables a disabled staged installed version with installed grants when catalogue offers ${catalogVersion}`, async () => {
      const installed = makePluginFixture({
        version: '1.0.0', permissions: ['diagnostics.oauth-account.probe'], state: {
          activeVersion: null, desiredEnabled: false, admissionEnabled: false, status: 'disabled',
          approvedPermissions: [], activeConfiguration: null, activeUi: null,
        },
      })
      const offered = makePluginFixture({ version: catalogVersion, permissions: ['diagnostics.channel.probe'] })
      setFixtures(installed)
      mock.catalog = [clonePluginData(offered.catalog)]
      const entry = await mount('PluginMarketplaceView')
      await waitFor(() => !entry.state.loading.value)
      const selector = field(entry.container, '目录版本')
      assert.equal(entry.state.selectedVersion.value, installed.state.installedVersion)
      assert.equal(selector.value, installed.state.installedVersion)
      assert.deepEqual([...selector.options].map(option => option.value), catalogVersion === '1.0.0' ? ['1.0.0'] : ['1.0.0', catalogVersion])
      assert.deepEqual(entry.state.versions.value.map(item => item.manifest.version), [catalogVersion], 'the catalogue retains its backend order and contains no fabricated packages')
      assert.deepEqual(entry.state.permissions.value, installed.state.permissions, 'reenabling uses the installed permissions even if the same catalogue version has changed')
      assert.equal(entry.state.currentVersionSelected.value, true)
      assert.equal(entry.state.canInstall.value, false)
      const grants = () => [...entry.container.querySelectorAll('.plugin-market-permissions input')]
      assert.deepEqual(grants().map(input => input.value), installed.state.permissions)
      assert.equal(button(entry.container, '启用（重启生效）').disabled, true)
      entry.state.toggleEnabled()
      assert.equal(calls.enabled.length, 0)

      if (catalogVersion !== installed.state.installedVersion) {
        assert.equal(entry.state.selectedCatalog.value, undefined)
        assert.equal(entry.container.querySelector('.plugin-market-digest'), null)
        assert.equal([...entry.container.querySelectorAll('button')].some(node => /安装此版本|选择此版本/.test(text(node))), false)
        await change(selector, catalogVersion)
        assert.deepEqual(entry.state.permissions.value, offered.manifest.permissions)
        assert.equal(entry.container.querySelector('.plugin-market-digest code').textContent, offered.catalog.sha256)
        await change(grants()[0], true)
        assert.equal(entry.state.canInstall.value, true)
        assert.equal(button(entry.container, '启用（重启生效）').disabled, true)
        entry.state.toggleEnabled()
        assert.equal(calls.enabled.length, 0, 'approving another catalogue version cannot enable the installed package')
        await change(selector, installed.state.installedVersion)
        assert.deepEqual(entry.state.permissions.value, installed.state.permissions)
        assert.equal(grants()[0].checked, false, 'changing versions resets pending capability grants')
        assert.equal(entry.state.canInstall.value, false)
      }

      await change(grants()[0], true)
      assert.equal(button(entry.container, '启用（重启生效）').disabled, false)
      button(entry.container, '启用（重启生效）').click()
      await waitFor(() => calls.enabled.length === 1 && !entry.state.busy.value)
      assert.deepEqual(calls.enabled[0], { id: installed.manifest.id, payload: {
        enabled: true, installedVersion: installed.state.installedVersion, expectedRevision: installed.state.revision,
        approvedPermissions: installed.state.permissions,
      } })
      assert.equal(calls.install.length, 0, 'reenabling the staged package never reinstalls from the catalogue')
      assert.equal(entry.state.selectedVersion.value, installed.state.installedVersion)
      assert.equal(selector.value, installed.state.installedVersion)
      assert.equal(entry.state.selectedState.value.activeVersion, null)
      assert.equal(entry.state.selectedState.value.admissionEnabled, false)
      assert.equal(entry.state.selectedState.value.desiredEnabled, true)
      assert.match(text(entry.container), /待重启/)
    })
  }

  it('keeps the backend SemVer order and offers installed controls when catalogue reads fail', async () => {
    const fixture = makePluginFixture({ version: '2.0.0' })
    setFixtures(fixture)
    mock.catalog = ['2.0.0', '2.0.0-rc.10', '2.0.0-rc.2', '1.9.0'].map(version => makePluginFixture({ version }).catalog)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    assert.deepEqual(entry.state.versions.value.map(item => item.manifest.version), mock.catalog.map(item => item.manifest.version))
    mock.listCatalog = () => Promise.reject(new Error('catalogue offline'))
    await entry.state.load(); await settle()
    assert.match(text(entry.container), /目录读取失败.*catalogue offline/)
    assert.equal(entry.state.selectedState.value.id, fixture.manifest.id)
    assert.equal(entry.state.canInstall.value, false)
    assert.equal(button(entry.container, '禁用').disabled, false)
    button(entry.container, '禁用').click()
    await waitFor(() => calls.enabled.length === 1 && !entry.state.busy.value)
    confirmResult = true
    button(entry.container, '卸载').click()
    await waitFor(() => calls.uninstall.length === 1 && !entry.state.busy.value)
  })

  for (const remaining of ['catalogue', 'installation record', 'empty']) {
    for (const firstResponse of ['catalogue', 'installation state']) {
      it(`reconciles a removed catalogue-only selection to ${remaining} after ${firstResponse} resolves first`, async () => {
        const removed = makePluginFixture()
        const replacement = makePluginFixture({ id: 'acme.remaining' })
        setFixtures(removed)
        mock.states.clear()
        const entry = await mount('PluginMarketplaceView')
        await waitFor(() => !entry.state.loading.value)
        assert.equal(entry.state.selectedId.value, removed.manifest.id)
        assert.equal(entry.state.selectedState.value, undefined)
        const catalogue = deferred()
        const installations = deferred()
        mock.listCatalog = () => catalogue.promise
        mock.listPlugins = () => installations.promise
        const refresh = entry.state.load()
        const nextCatalog = remaining === 'catalogue' ? [replacement.catalog] : []
        const nextStates = remaining === 'installation record' ? [replacement.state] : []
        if (firstResponse === 'catalogue') catalogue.resolve(nextCatalog)
        else installations.resolve(nextStates)
        await settle()
        assert.equal(entry.state.loading.value, true)
        assert.equal(entry.state.selectedId.value, removed.manifest.id, 'one response cannot prove the selection disappeared')
        if (firstResponse === 'catalogue') installations.resolve(nextStates)
        else catalogue.resolve(nextCatalog)
        await refresh; await settle()
        assert.equal(entry.state.selectedId.value, remaining === 'empty' ? '' : replacement.manifest.id)
        assert.equal(entry.state.installationStateKnown.value, true)
        if (remaining !== 'installation record') assert.doesNotMatch(text(entry.container), /安装记录仍在.*当前可信目录没有这个插件包/)
        if (remaining === 'empty') {
          assert.equal(entry.container.querySelector('.plugin-market-detail'), null)
          assert.match(text(entry.container), /选择一个插件/)
        } else {
          assert.equal(entry.container.querySelector('.plugin-market-detail-head code').textContent, replacement.manifest.id)
          assert.equal(pluginItem(entry.container, replacement.manifest.id).getAttribute('aria-pressed'), 'true')
          assert.equal(entry.state.selectedState.value?.id, remaining === 'installation record' ? replacement.manifest.id : undefined)
        }
      })
    }
  }

  for (const survivingSource of ['catalogue', 'installation record']) {
    it(`retains a search-hidden selection still present in the ${survivingSource}`, async () => {
      const selected = makePluginFixture()
      const other = makePluginFixture({ id: 'acme.other' })
      setFixtures(selected, other)
      if (survivingSource === 'catalogue') mock.states.delete(selected.manifest.id)
      const entry = await mount('PluginMarketplaceView')
      await waitFor(() => !entry.state.loading.value)
      await change(entry.container.querySelector('input[type=search]'), other.manifest.id)
      assert.equal(entry.container.querySelectorAll('.plugin-market-item').length, 1)
      if (survivingSource === 'installation record') mock.catalog = [other.catalog]
      await entry.state.load(); await settle()
      assert.equal(entry.state.selectedId.value, selected.manifest.id)
      assert.equal(entry.container.querySelector('.plugin-market-detail-head code').textContent, selected.manifest.id)
      assert.equal(entry.container.querySelectorAll('.plugin-market-item').length, 1)
    })
  }

  for (const failedSource of ['catalogue', 'installation state']) {
    it(`does not discard a missing selection on ${failedSource} failure before a complete refresh`, async () => {
      const selected = makePluginFixture()
      const replacement = makePluginFixture({ id: 'acme.remaining' })
      setFixtures(selected)
      mock.states.clear()
      const entry = await mount('PluginMarketplaceView')
      await waitFor(() => !entry.state.loading.value)
      mock.catalog = [replacement.catalog]
      mock.states.set(replacement.manifest.id, replacement.state)
      if (failedSource === 'catalogue') mock.listCatalog = () => Promise.reject(new Error('catalogue offline'))
      else mock.listPlugins = () => Promise.reject(new Error('installation state offline'))
      await entry.state.load(); await settle()
      assert.equal(entry.state.selectedId.value, selected.manifest.id)
      mock.listCatalog = undefined
      mock.listPlugins = undefined
      await entry.state.load(); await settle()
      assert.equal(entry.state.selectedId.value, replacement.manifest.id)
    })
  }

  it('ignores a superseded refresh that would remove the current marketplace selection', async () => {
    const selected = makePluginFixture()
    setFixtures(selected)
    mock.states.clear()
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    const oldCatalog = deferred()
    const oldStates = deferred()
    mock.listCatalog = () => oldCatalog.promise
    mock.listPlugins = () => oldStates.promise
    const stale = entry.state.load()
    const staleOptions = calls.catalog.at(-1)
    mock.listCatalog = undefined
    mock.listPlugins = undefined
    await entry.state.load(); await settle()
    assert.equal(staleOptions.signal.aborted, true)
    oldCatalog.resolve([])
    oldStates.resolve([])
    assert.equal(await stale, false)
    await settle()
    assert.equal(entry.state.selectedId.value, selected.manifest.id)
    assert.equal(pluginItem(entry.container, selected.manifest.id).getAttribute('aria-pressed'), 'true')
  })

  it('loads installed state on the first render even when catalogue loading fails', async () => {
    mock.listCatalog = () => Promise.reject(new Error('initial catalogue failure'))
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    assert.ok(entry.state.selectedState.value?.installed)
    assert.match(text(entry.container), /initial catalogue failure/)
    assert.equal(button(entry.container, '禁用').disabled, false)
    assert.equal(button(entry.container, '卸载').disabled, false)
    assert.equal(entry.state.canInstall.value, false)
  })

  it('sends the displayed approval digest and refuses a same-version catalogue replacement', async () => {
    const fixture = makePluginFixture()
    setFixtures(fixture); mock.states.delete(fixture.manifest.id)
    const originalInstall = api.installPlugin
    api.installPlugin = async (id, payload) => {
      const current = mock.catalog.find(item => item.manifest.id === id && item.manifest.version === payload.version)
      if (current.sha256 !== payload.expectedSha256) {
        calls.install.push({ id, payload: clonePluginData(payload) })
        throw new ApiError('reviewed catalogue package changed', 409, 'PLUGIN_CATALOG_CONFLICT')
      }
      return originalInstall(id, payload)
    }
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    for (const grant of entry.container.querySelectorAll('.plugin-market-permissions input')) await change(grant, true)
    mock.catalog[0].sha256 = 'b'.repeat(64)
    button(entry.container, '安装此版本').click()
    await waitFor(() => calls.install.length === 1 && !entry.state.busy.value)
    assert.equal(calls.install[0].payload.expectedSha256, fixture.catalog.sha256)
    assert.equal(mock.states.has(fixture.manifest.id), false)
    assert.match(toasts.at(-1).message, /catalogue package changed/)
  })

  it('preserves a dirty same-version baseline on reload and blocks saving after a revision changes', async () => {
    const fixture = makePluginFixture({ state: { revision: 5 } })
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'old local draft')
    setState(fixture.manifest.id, { revision: 6, configuration: { defaultPrompt: 'remote winner', threshold: 0.5 } })
    await entry.state.load(); await settle()
    assert.equal(entry.state.draftRevision.value, 5)
    assert.equal(entry.state.configuration.defaultPrompt, 'old local draft')
    assert.equal(entry.state.draftConflict.value, true)
    assert.equal(button(entry.container, '保存配置').disabled, true)
    entry.state.saveConfiguration()
    assert.equal(calls.configuration.length, 0)
    button(entry.container, '放弃旧草稿并载入当前配置').click(); await settle()
    assert.equal(entry.state.draftRevision.value, 6)
    assert.equal(entry.state.configuration.defaultPrompt, 'remote winner')
    assert.equal(entry.state.draftConflict.value, false)
  })

  it('rejects a sequential stale draft at save and prevents retry until current configuration is loaded', async () => {
    const fixture = makePluginFixture({ state: { revision: 3 } })
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'stale local draft')
    setState(fixture.manifest.id, { revision: 4, configuration: { defaultPrompt: 'saved elsewhere', threshold: 0.5 } })
    mock.configure = async (id, payload) => {
      const state = stateFor(id)
      if (payload.expectedRevision !== state.revision || payload.installedVersion !== state.installedVersion) {
        throw new ApiError('configuration revision conflict', 409, 'PLUGIN_REVISION_CONFLICT')
      }
      return setState(id, { revision: state.revision + 1, configuration: payload.values })
    }
    button(entry.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 1 && !entry.state.busy.value)
    assert.equal(calls.configuration[0].payload.expectedRevision, 3)
    assert.equal(calls.configuration[0].payload.installedVersion, '1.0.0')
    assert.equal(entry.state.draftConflict.value, true)
    assert.equal(button(entry.container, '保存配置').disabled, true)
    entry.state.saveConfiguration()
    assert.equal(calls.configuration.length, 1)
    const reload = deferred()
    mock.listPlugins = () => reload.promise
    button(entry.container, '放弃旧草稿并载入当前配置').click()
    await waitFor(() => entry.state.loading.value)
    reload.reject(new Error('installation state unavailable'))
    await waitFor(() => !entry.state.loading.value)
    assert.match(text(entry.container), /installation state unavailable/)
    assert.equal(entry.state.draftRejected.value, true)
    assert.equal(entry.state.draftConflict.value, true)
    assert.equal(entry.state.configuration.defaultPrompt, 'stale local draft')
    assert.equal(entry.state.draftRevision.value, 3)
    assert.equal(field(entry.container, '默认提示词').disabled, true)
    assert.equal(button(entry.container, '保存配置').disabled, true)
    entry.state.saveConfiguration()
    assert.equal(calls.configuration.length, 1)

    mock.listPlugins = undefined
    mock.listCatalog = () => Promise.reject(new Error('catalogue unavailable'))
    button(entry.container, '放弃旧草稿并载入当前配置').click()
    await waitFor(() => !entry.state.loading.value && !entry.state.draftConflict.value)
    assert.equal(entry.state.configuration.defaultPrompt, 'saved elsewhere')
    assert.equal(entry.state.draftRevision.value, 4)
    assert.equal(calls.configuration.length, 1, 'recovery does not automatically save the stale draft')
    await change(field(entry.container, '默认提示词'), 'revision four edit')
    button(entry.container, '保存配置').click()
    await waitFor(() => calls.configuration.length === 2 && !entry.state.busy.value)
    assert.equal(calls.configuration[1].payload.expectedRevision, 4)
  })

  it('validates configuration scalars and sends an immutable save snapshot with numeric zero and false', async () => {
    const fixture = makePluginFixture({ configurationFields: [
      makeField('defaultPrompt', '默认提示词', 'textarea', { required: true, defaultValue: 'default prompt', maxLength: 4000 }),
      makeField('count', '配置数量', 'number', { required: true, defaultValue: 2, min: 0, max: 5 }),
      makeField('flag', '配置开关', 'boolean', { defaultValue: false }),
    ] })
    setFixtures(fixture)
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '配置数量'), 6)
    assert.equal(button(entry.container, '保存配置').disabled, true)
    assert.match(entry.state.configurationError.value, /配置数量/)
    entry.state.saveConfiguration()
    assert.equal(calls.configuration.length, 0)
    await change(field(entry.container, '配置数量'), 0)
    await change(field(entry.container, '配置开关'), true)
    await change(field(entry.container, '配置开关'), false)
    await change(field(entry.container, '默认提示词'), 'snapshot configuration')
    let submitted
    const pending = deferred()
    mock.configure = (_id, payload) => { submitted = payload; return pending.promise }
    button(entry.container, '保存配置').click(); await settle()
    assert.notEqual(submitted.values, entry.state.configuration)
    assert.deepEqual(submitted.values, { defaultPrompt: 'snapshot configuration', count: 0, flag: false })
    entry.state.configuration.defaultPrompt = 'late mutation of reactive draft'
    assert.equal(submitted.values.defaultPrompt, 'snapshot configuration')
    assert.equal(entry.state.canLeave(), false)
    pending.resolve(setState(fixture.manifest.id, { configuration: submitted.values, activeConfiguration: submitted.values }))
    await waitFor(() => !entry.state.busy.value)
    assert.equal(entry.state.configuration.defaultPrompt, 'snapshot configuration')
    assert.equal(entry.state.dirty.value, false)
    assert.equal(confirmCalls.length, 0)
  })

  for (const completion of ['response', 'error']) {
    it(`suppresses late marketplace mutation ${completion} notifications and state-change events after unmount`, async () => {
      const entry = await mount('PluginMarketplaceView')
      await waitFor(() => !entry.state.loading.value)
      await change(field(entry.container, '默认提示词'), 'unmounted configuration')
      const pending = deferred()
      let configurationSettled = false
      mock.configure = async () => {
        try { return await pending.promise }
        finally { configurationSettled = true }
      }
      button(entry.container, '保存配置').click()
      await waitFor(() => calls.configuration.length === 1 && entry.state.busy.value, 'pending configuration request before unmount')
      assert.deepEqual(calls.configuration[0], { id: 'acme.synthetic', payload: {
        values: { ...entry.state.configuration }, expectedRevision: 0, installedVersion: '1.0.0',
      } })
      assert.equal(configurationSettled, false)
      assert.equal(button(entry.container, '保存配置').disabled, true)
      const changes = []
      const listener = () => changes.push(true)
      window.addEventListener(PLUGIN_STATE_CHANGED, listener)
      try {
        entry.unmount(); mounted.splice(mounted.indexOf(entry), 1)
        if (completion === 'response') pending.resolve(stateFor('acme.synthetic'))
        else pending.reject(new Error('unmounted save failed'))
        await waitFor(() => configurationSettled, 'late configuration completion')
        assert.equal(toasts.length, 0)
        assert.equal(changes.length, 0)
      } finally { window.removeEventListener(PLUGIN_STATE_CHANGED, listener) }
    })
  }

  it('blocks leaving while configuration save is pending and preserves drafts on a failed save', async () => {
    const entry = await mount('PluginMarketplaceView')
    await waitFor(() => !entry.state.loading.value)
    await change(field(entry.container, '默认提示词'), 'unsaved input')
    const pending = deferred()
    mock.configure = () => pending.promise
    button(entry.container, '保存配置').click(); await settle()
    assert.equal(entry.state.busy.value, true)
    assert.equal(entry.state.canLeave(), false)
    assert.equal(confirmCalls.length, 0)
    assert.equal(field(entry.container, '默认提示词').disabled, true)
    pending.reject(new ApiError('synthetic configuration conflict', 409))
    await waitFor(() => !entry.state.busy.value)
    assert.equal(entry.state.dirty.value, true)
    assert.equal(entry.state.configuration.defaultPrompt, 'unsaved input')
    assert.equal(toasts.at(-1).type, 'error')
    assert.match(toasts.at(-1).message, /configuration conflict/)
  })
})

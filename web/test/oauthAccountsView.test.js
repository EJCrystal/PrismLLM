import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'
import { createOAuthAccountsFixture, FIXTURE_ACCOUNT_IDS as IDS, FIXTURE_NOW } from './fixtures/oauth-accounts/accounts.js'
import { createOAuthFixtureFetch } from './fixtures/oauth-accounts/fake-fetch.js'

// No listening server or native fetch: Vite only loads local Vue modules in middleware mode.
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
const originalDateNow = Date.now
const originalConfirm = window.confirm
Date.now = () => FIXTURE_NOW
window.Date.now = () => FIXTURE_NOW
const noUnmockedFetch = async () => { throw new Error('OAuthAccountsView test forbids native network access') }
globalThis.fetch = window.fetch = noUnmockedFetch

const vueRuntime = await import('vue')
const { createApp, h, nextTick, ref, ssrContextKey } = vueRuntime
const headless = await import('@headlessui/vue')
const { parse } = await import('@vue/compiler-sfc')
const { compile: compileTemplate } = await import('@vue/compiler-dom')
const { transform } = await import('esbuild')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default

// Follow actionMenu/searchSelectDialog's client-template loading convention, with real children.
async function loadClientComponent(server, name, components = {}) {
  const component = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
  const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
  const resolvedComponents = { ...components }
  for (const match of descriptor.scriptSetup.content.matchAll(/^import\s+([A-Za-z_]\w*)(?:\s*,\s*\{[^}]*\})?\s+from\s+['"]\.\/([^'"]+\.vue)['"]/gm)) {
    resolvedComponents[match[1]] ??= (await server.ssrLoadModule('/src/components/' + match[2])).default
  }
  // Current template has TypeScript non-null expressions; strip types from compiler output,
  // not source, so this remains the actual template rather than a handwritten test replica.
  const compiled = compileTemplate(descriptor.template.content, { mode: 'function', prefixIdentifiers: true, expressionPlugins: ['typescript'] })
  const { code } = await transform(compiled.code, { loader: 'ts' })
  return {
    ...component,
    ssrRender: undefined,
    render: new Function('Vue', code)(vueRuntime),
    components: { ...headless, ...resolvedComponents },
    setup(props, context) { return { ...component.setup(props, context), ...resolvedComponents } },
  }
}

async function settle() {
  await nextTick()
  await delay(40)
  await nextTick()
}

const text = node => node?.textContent.replace(/\s+/g, ' ').trim()
const account = (fixture, id) => fixture.accounts.find(item => item.id === id)

describe('OAuthAccountsView mounted subscription-account dashboard', () => {
  let server
  let view
  let providerDrawer
  let channelsView
  let buildProviderListItems
  let toastStack
  let toasts
  let api
  let originalStartOAuthLogin
  let originalCompleteOAuthLogin
  let app
  let host
  let mock
  const pendingLoadReleases = new Set()
  const dialog = () => document.querySelector('[role="dialog"]')
  const mappingResource = provider => mock.fixture.providerMappings[provider]
  const mapping = (provider, model) => mappingResource(provider).mappings.find(item => item.upstreamModel === model)
  const writes = () => mock.requests.filter(request => request.method === 'PUT')
  const modelRow = model => dialog()?.querySelector(`[data-upstream-model="${model}"]`)
  const field = label => [...dialog().querySelectorAll('label.field')].find(node => text(node.querySelector('span')) === label)?.querySelector('input')
  async function input(node, value) {
    assert.ok(node, 'Expected editable input')
    node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  }
  async function submitMockedLogin() {
    api.startOAuthLogin = async () => ({ loginId: 'fixture-login', authorizeUrl: 'https://example.invalid/authorize', redirectUri: 'http://localhost/callback', expiresAt: FIXTURE_NOW + 600_000 })
    buttonNamed(dialog(), '生成登录链接').click()
    await settle()
    await input(dialog().querySelector('textarea'), 'http://localhost/callback?code=fixture&state=fixture')
    dialog().querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }
  async function chooseMode(model, value) {
    const select = modelRow(model).querySelector('select')
    select.value = value; select.dispatchEvent(new Event('change', { bubbles: true })); await settle()
  }
  async function manual(model, value) {
    await chooseMode(model, 'manual')
    await input(modelRow(model).querySelector('.oauth-mapping-input input'), value)
  }
  async function openSettings(id) {
    row(id).querySelector(`button[aria-label="设置 ${account(mock.fixture, id).name}"]`).click()
    await settle()
    assert.ok(dialog())
  }
  async function closeSettings() { dialog().querySelector('button[aria-label="关闭"]').click(); await settle() }
  async function save() { buttonNamed(dialog(), '保存').click(); await settle() }
  function gateFetch(predicate) {
    let release
    const gate = new Promise(resolve => { release = () => { pendingLoadReleases.delete(release); resolve() } })
    pendingLoadReleases.add(release)
    globalThis.fetch = window.fetch = async (...args) => {
      const response = await mock.fetch(...args)
      if (predicate(args[0], args[1] ?? {})) await gate
      return response
    }
    return release
  }

  function rows() { return [...host.querySelectorAll('tbody tr[data-account-id]')] }
  function row(id) {
    const result = host.querySelector(`tr[data-account-id="${id}"]`)
    assert.ok(result, 'Expected visible synthetic account ' + id)
    return result
  }
  function rowIds() { return rows().map(item => item.dataset.accountId).sort() }
  function assertRows(ids) { assert.deepEqual(rowIds(), [...ids].sort()) }
  function stats() { return [...host.querySelectorAll('.oauth-account-stats .stat-card strong')].map(item => Number(text(item))) }
  function stat(label) {
    const button = [...host.querySelectorAll('.oauth-account-stats button')].find(item => text(item.querySelector('span')) === label)
    assert.ok(button, 'Expected stat button ' + label)
    return button
  }
  function buttonNamed(root, label) {
    const button = [...root.querySelectorAll('button')].find(item => text(item) === label)
    assert.ok(button, 'Expected button ' + label)
    return button
  }
  async function search(value) {
    const input = host.querySelector('input[aria-label="搜索订阅账号"]')
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
  }
  async function provider(value) {
    const select = host.querySelector('select[aria-label="筛选提供方"]')
    select.value = value
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
  }
  function groupLabels(root) { return [...root.querySelectorAll('.oauth-quota-group-label')].map(text) }
  async function openMenu(id) {
    const trigger = row(id).querySelector('.more-button')
    assert.ok(trigger)
    trigger.getBoundingClientRect = () => ({ top: 200, bottom: 244, left: 100, right: 144, width: 44, height: 44 })
    trigger.click()
    await settle()
    const panel = document.querySelector('.floating-menu')
    assert.ok(panel, 'Expected actual teleported ActionMenu')
    return panel
  }
  async function mount(fixture = createOAuthAccountsFixture(), { deferLoad = false, failLoad = false, drawer = false, autoOpenCreate = false, createOnly = false, providerList = false, providerId = 'antigravity', onClose, onChanged, onToggleEnabled } = {}) {
    assert.equal(fixture.now, FIXTURE_NOW)
    mock = createOAuthFixtureFetch({ fixture, origin: window.location.origin })
    let releaseLoad
    if (deferLoad) {
      const gate = new Promise(resolve => {
        releaseLoad = () => { pendingLoadReleases.delete(releaseLoad); resolve() }
      })
      pendingLoadReleases.add(releaseLoad)
      globalThis.fetch = window.fetch = async (...args) => {
        const response = await mock.fetch(...args)
        await gate
        return response
      }
    } else if (failLoad) {
      globalThis.fetch = window.fetch = async (...args) => {
        const response = await mock.fetch(...args)
        if (args[0] === '/admin/api/oauth-accounts') {
          return new Response(JSON.stringify({ error: { code: 'fixture_load_failed', message: '合成账号列表读取失败' } }), {
            status: 503, headers: { 'Content-Type': 'application/json' },
          })
        }
        return response
      }
    } else {
      globalThis.fetch = window.fetch = mock.fetch
    }
    host = document.createElement('div')
    document.body.append(host)
    const item = drawer ? buildProviderListItems(fixture.channels, fixture.providers, fixture.accounts, fixture.now).find(item => item.providerId === providerId) : null
    const createOpen = ref(true)
    app = createApp({ render: () => h('div', [createOnly ? h('section', { 'aria-label': '提供商列表' }, '提供商列表') : null, providerList ? h(channelsView) : drawer
      ? h(providerDrawer, { item, channels: fixture.channels, autoOpenCreate, onToggleEnabled })
      : (!createOnly || createOpen.value) ? h(view, {
        createOnly, fixedProvider: createOnly ? providerId : null, onChanged,
        onClose() { createOpen.value = false; onClose?.() },
      }) : null, h(toastStack)]) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    if (!deferLoad) assert.equal(host.querySelector('.loading-state'), null)
    assert.deepEqual(mock.requests.filter(request => !providerList || ['/admin/api/oauth-accounts', '/admin/api/oauth-providers', '/admin/api/channels'].includes(request.path)).map(request => [request.method, request.path]).sort(), [
      ['GET', '/admin/api/oauth-accounts'],
      ['GET', '/admin/api/oauth-providers'],
      ['GET', '/admin/api/channels'],
    ].sort())
    return { releaseLoad }
  }

  before(async () => {
    server = await createServer({
      root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, envFile: false,
      cacheDir: join(tmpdir(), 'prism-oauth-accounts-view-client-tests'),
      plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] },
      server: { middlewareMode: true, host: '127.0.0.1', proxy: {}, hmr: false }, appType: 'custom',
    })
    const icon = await loadClientComponent(server, 'Icon')
    const modal = await loadClientComponent(server, 'Modal', { Icon: icon })
    const children = {
      Icon: icon,
      Modal: modal,
      ActionMenu: await loadClientComponent(server, 'ActionMenu', { Icon: icon }),
      CopyButton: await loadClientComponent(server, 'CopyButton', { Icon: icon }),
      ErrorState: await loadClientComponent(server, 'ErrorState', { Icon: icon }),
      TablePlaceholder: await loadClientComponent(server, 'TablePlaceholder', { Icon: icon }),
      OAuthModelTestModal: await loadClientComponent(server, 'OAuthModelTestModal', { Modal: modal }),
      OAuthMappingInput: await loadClientComponent(server, 'OAuthMappingInput', {
        SearchSelect: await loadClientComponent(server, 'SearchSelect', { Icon: icon }),
      }),
    }
    view = await loadClientComponent(server, 'OAuthAccountsView', children)
    providerDrawer = await loadClientComponent(server, 'OAuthProviderDrawer', {
      ...children,
      OAuthAccountsView: view,
      ChannelProtocols: await loadClientComponent(server, 'ChannelProtocols', { ProtocolIcon: await loadClientComponent(server, 'ProtocolIcon') }),
      OAuthProviderMappingsPanel: await loadClientComponent(server, 'OAuthProviderMappingsPanel', children),
    })
    channelsView = await loadClientComponent(server, 'ChannelsView', {
      ...children,
      OAuthAccountsView: view,
      OAuthProviderDrawer: providerDrawer,
      ChannelProtocols: await loadClientComponent(server, 'ChannelProtocols', { ProtocolIcon: await loadClientComponent(server, 'ProtocolIcon') }),
      ChannelHealthSummary: await loadClientComponent(server, 'ChannelHealthSummary', children),
      ChannelModelPreview: await loadClientComponent(server, 'ChannelModelPreview'),
      FilterSelect: await loadClientComponent(server, 'FilterSelect', children),
    })
    channelsView.directives = { scrollHint: (await server.ssrLoadModule('/src/directives/scrollHint.ts')).vScrollHint }
    buildProviderListItems = (await server.ssrLoadModule('/src/utils/providerDisplay.ts')).buildProviderListItems
    toastStack = await loadClientComponent(server, 'ToastStack')
    toasts = (await server.ssrLoadModule('/src/toast.ts')).toasts
    api = (await server.ssrLoadModule('/src/api.ts')).api
    originalStartOAuthLogin = api.startOAuthLogin
    originalCompleteOAuthLogin = api.completeOAuthLogin
  })

  beforeEach(() => {
    window.confirm = () => { throw new Error('Unexpected confirmation in this test') }
  })

  afterEach(async () => {
    try {
      for (const request of mock?.requests ?? []) {
        assert.equal(request.allowed, true, `Unexpected API request: ${request.method} ${request.url}`)
        assert.match(request.url, /^\/admin\/api\//)
        assert.equal(/oauth-login|model-test|\/me(?:$|\/)|\/login/.test(request.path), false, 'No authentication or upstream-test requests')
      }
    } finally {
      app?.unmount()
      app = null
      // Release every deferred fetch even if a loading assertion fails; never leave pending loads.
      for (const release of pendingLoadReleases) release()
      await settle()
      host?.remove()
      host = null
      mock = null
      toasts?.splice(0)
      api.startOAuthLogin = originalStartOAuthLogin
      api.completeOAuthLogin = originalCompleteOAuthLogin
      window.confirm = originalConfirm
      globalThis.fetch = window.fetch = noUnmockedFetch
      await settle()
      assert.equal(document.querySelector('.floating-menu'), null)
    }
  })

  after(async () => {
    await server?.close()
    dom.window.close()
    globalThis.fetch = originalFetch
    Date.now = originalDateNow
  })

  for (const providerId of ['chatgpt', 'antigravity']) {
    for (const scenario of [
      { label: 'provider connection', entry: 'connect', provisioned: true, close: '取消' },
      { label: 'desktop unprovisioned add-account', entry: 'desktop', provisioned: false, close: '取消' },
      { label: 'mobile unprovisioned add-account', entry: 'mobile', provisioned: false, close: '关闭' },
      { label: 'desktop provisioned add-account', entry: 'desktop', provisioned: true, close: 'Escape' },
      { label: 'mobile provisioned add-account', entry: 'mobile', provisioned: true, close: 'backdrop' },
    ]) {
      it(providerId + ' returns to the actual provider list after cancelling ' + scenario.label, async () => {
        const fixture = createOAuthAccountsFixture()
        if (scenario.entry !== 'connect') fixture.accounts = fixture.accounts.filter(account => account.provider !== providerId)
        if (!scenario.provisioned) fixture.channels = fixture.channels.filter(channel => channel.provider !== providerId)
        await mount(fixture, { providerList: true })
        if (scenario.entry === 'connect') {
          buttonNamed(host, '接入提供商').click()
          await settle()
          dialog().querySelector('input[value="subscription"]').click()
          await settle()
          const select = dialog().querySelector('select')
          select.value = providerId
          select.dispatchEvent(new Event('change', { bubbles: true }))
          await settle()
          buttonNamed(dialog(), '继续').click()
        } else {
          const item = buildProviderListItems(fixture.channels, fixture.providers, fixture.accounts, fixture.now).find(item => item.providerId === providerId)
          const providerEntry = scenario.entry === 'desktop'
            ? host.querySelector('tr[data-provider-id="' + providerId + '"]')
            : [...host.querySelectorAll('.channel-mobile-card')].find(card => card.querySelector('.channel-mobile-name')?.getAttribute('title') === item.displayName)
          assert.ok(providerEntry)
          buttonNamed(providerEntry, '添加账号').click()
        }
        await settle()
        assert.equal(document.querySelectorAll('[role="dialog"]').length, 1)
        assert.equal(text(dialog().querySelector('h2')), '新建订阅账号')
        assert.equal(dialog().querySelector('select').value, providerId)
        assert.equal(document.querySelector('.oauth-accounts-page'), null)
        const readsBeforeCancel = mock.requests.length
        if (scenario.close === '取消') buttonNamed(dialog(), '取消').click()
        else if (scenario.close === '关闭') dialog().querySelector('button[aria-label="关闭"]').click()
        else if (scenario.close === 'Escape') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
        else {
          dialog().dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
          dialog().click()
        }
        await settle()
        assert.equal(dialog(), null)
        assert.equal(document.querySelector('.oauth-accounts-page'), null)
        assert.ok(host.querySelector('.channel-summary'))
        assert.ok(host.querySelector('.provider-subscription-row'))
        assert.equal(mock.requests.length, readsBeforeCancel)
        assert.equal(mock.requests.some(request => request.method !== 'GET'), false)
      })
    }

    for (const action of ['取消', '关闭', 'Escape', 'backdrop']) {
      it(providerId + ' closes standalone provider creation without exposing the account pool via ' + action, async () => {
        let closed = 0
        let changed = 0
        await mount(createOAuthAccountsFixture(), { createOnly: true, providerId, onClose: () => closed++, onChanged: () => changed++ })
        assert.equal(document.querySelectorAll('[role="dialog"]').length, 1)
        assert.equal(text(dialog().querySelector('h2')), '新建订阅账号')
        assert.equal(dialog().querySelector('select').value, providerId)
        assert.equal(document.querySelector('.oauth-accounts-table'), null)
        assert.equal(document.querySelector('.oauth-account-stats'), null)
        if (action === '取消') buttonNamed(dialog(), '取消').click()
        else if (action === '关闭') dialog().querySelector('button[aria-label="关闭"]').click()
        else if (action === 'Escape') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
        else {
          dialog().dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
          dialog().click()
        }
        await settle()
        assert.equal(closed, 1)
        assert.equal(changed, 0)
        assert.equal(dialog(), null)
        assert.ok(host.querySelector('[aria-label="提供商列表"]'))
        assert.equal(document.querySelector('.oauth-accounts-page'), null)
        assert.equal(mock.requests.some(request => request.method !== 'GET'), false)
      })
    }
  }

  it('does not reopen standalone creation when cancelled before the initial load finishes', async () => {
    let closed = 0
    const { releaseLoad } = await mount(createOAuthAccountsFixture(), { createOnly: true, deferLoad: true, onClose: () => closed++ })
    assert.match(text(dialog()), /正在读取订阅提供方/)
    assert.equal(buttonNamed(dialog(), '生成登录链接').disabled, true)
    buttonNamed(dialog(), '取消').click()
    await settle()
    releaseLoad()
    await settle()
    assert.equal(closed, 1)
    assert.equal(dialog(), null)
    assert.equal(document.querySelector('.oauth-accounts-page'), null)
  })

  it('allows standalone creation to be cancelled after a load error without exposing the account pool', async () => {
    await mount(createOAuthAccountsFixture(), { createOnly: true, failLoad: true })
    assert.match(text(dialog().querySelector('[role="alert"]')), /合成账号列表读取失败/)
    assert.equal(buttonNamed(dialog(), '生成登录链接').disabled, true)
    buttonNamed(dialog(), '取消').click()
    await settle()
    assert.equal(dialog(), null)
    assert.equal(document.querySelector('.oauth-accounts-page'), null)
  })

  it('refreshes the provider list and closes standalone creation after a successful mocked login', async () => {
    let started
    let completed
    const events = []
    api.startOAuthLogin = async payload => {
      started = payload
      return { loginId: 'fixture-login', authorizeUrl: 'https://example.invalid/authorize', redirectUri: 'http://localhost/callback', expiresAt: FIXTURE_NOW + 600_000 }
    }
    api.completeOAuthLogin = async (loginId, payload) => { completed = { loginId, ...payload } }
    await mount(createOAuthAccountsFixture(), { createOnly: true, onChanged: () => events.push('changed'), onClose: () => events.push('close') })
    buttonNamed(dialog(), '生成登录链接').click()
    await settle()
    assert.deepEqual(started, { provider: 'antigravity', accountId: null, name: null, channelIds: [] })
    await input(dialog().querySelector('textarea'), 'http://localhost/callback?code=fixture&state=fixture')
    dialog().querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    assert.deepEqual(completed, { loginId: 'fixture-login', callbackUrl: 'http://localhost/callback?code=fixture&state=fixture' })
    assert.deepEqual(events, ['changed', 'close'])
    assert.equal(dialog(), null)
    assert.equal(document.querySelector('.oauth-accounts-page'), null)
  })

  it('ignores a late login-start failure after standalone creation is cancelled', async () => {
    let rejectLogin
    api.startOAuthLogin = () => new Promise((resolve, reject) => { rejectLogin = reject })
    await mount(createOAuthAccountsFixture(), { createOnly: true })
    buttonNamed(dialog(), '生成登录链接').click()
    await settle()
    buttonNamed(dialog(), '取消').click()
    await settle()
    rejectLogin(new Error('Delayed synthetic login failure'))
    await settle()
    assert.equal(dialog(), null)
    assert.equal(toasts.length, 0)
  })

  it('still applies the submitted settings and refreshes the pool after successful reauthorization', async () => {
    let changed = 0
    api.completeOAuthLogin = async () => {}
    await mount(createOAuthAccountsFixture(), { onChanged: () => changed++ })
    buttonNamed(await openMenu(IDS.groupedHealthy), '重新授权').click()
    await settle()
    await input(field('账号名称'), '重新授权后名称')
    await submitMockedLogin()
    assert.equal(dialog(), null)
    assert.equal(changed, 1)
    assert.equal(account(mock.fixture, IDS.groupedHealthy).name, '重新授权后名称')
    assert.equal(writes().length, 1)
    assert.equal(writes()[0].path, '/admin/api/oauth-accounts/' + IDS.groupedHealthy + '/settings')
    assert.match(text(host.querySelector('.toast')), /订阅账号已重新授权/)
  })

  for (const outcome of ['success', 'failure']) {
    for (const providerId of ['chatgpt', 'antigravity']) {
      it(providerId + ' ignores late login-completion ' + outcome + ' after standalone creation is cancelled', async () => {
        let finishLogin
        const events = []
        api.completeOAuthLogin = () => new Promise((resolve, reject) => { finishLogin = () => outcome === 'success' ? resolve() : reject(new Error('Delayed synthetic completion failure')) })
        await mount(createOAuthAccountsFixture(), { createOnly: true, providerId, onChanged: () => events.push('changed'), onClose: () => events.push('close') })
        await submitMockedLogin()
        assert.equal(buttonNamed(dialog(), '提交中…').disabled, true)
        buttonNamed(dialog(), '取消').click()
        await settle()
        finishLogin()
        await settle()
        assert.equal(dialog(), null)
        assert.equal(document.querySelector('.oauth-accounts-page'), null)
        assert.deepEqual(events, ['close'])
        assert.equal(toasts.length, 0)
        assert.equal(writes().length, 0)
      })
    }

    it('ignores late login-completion ' + outcome + ' without updating or closing a newer reauthorization dialog', async () => {
      let finishLogin
      let changed = 0
      api.completeOAuthLogin = () => new Promise((resolve, reject) => { finishLogin = () => outcome === 'success' ? resolve() : reject(new Error('Delayed synthetic completion failure')) })
      await mount(createOAuthAccountsFixture(), { onChanged: () => changed++ })
      host.querySelector('.oauth-create-account').click()
      await settle()
      await submitMockedLogin()
      buttonNamed(dialog(), '取消').click()
      await settle()
      buttonNamed(await openMenu(IDS.groupedHealthy), '重新授权').click()
      await settle()
      await input(field('账号名称'), '保留新的重新授权草稿')
      finishLogin()
      await settle()
      assert.ok(dialog())
      assert.equal(text(dialog().querySelector('h2')), '重新授权订阅账号')
      assert.equal(field('账号名称').value, '保留新的重新授权草稿')
      assert.equal(buttonNamed(dialog(), '重新授权').disabled, false)
      assert.equal(writes().length, 0, 'An old completion must never write settings from a newer account dialog')
      assert.equal(toasts.length, 0)
      assert.equal(changed, outcome === 'success' ? 1 : 0, 'A committed authorization still refreshes the existing pool without touching the newer dialog')
    })
  }

  it('does not let a pending reauthorization settings update close a newer creation dialog', async () => {
    api.completeOAuthLogin = async () => {}
    await mount()
    buttonNamed(await openMenu(IDS.groupedHealthy), '重新授权').click()
    await settle()
    const release = gateFetch((path, init) => init.method === 'PUT')
    await submitMockedLogin()
    assert.equal(writes().length, 1)
    buttonNamed(dialog(), '取消').click()
    await settle()
    host.querySelector('.oauth-create-account').click()
    await settle()
    await input(field('账号名称'), '保留新的创建草稿')
    release()
    await settle()
    assert.ok(dialog())
    assert.equal(text(dialog().querySelector('h2')), '新建订阅账号')
    assert.equal(field('账号名称').value, '保留新的创建草稿')
    assert.equal(toasts.length, 0)
  })

  for (const providerId of ['chatgpt', 'antigravity']) {
    it(providerId + ' keeps the existing account-pool workspace after cancelling creation opened from that pool', async () => {
      await mount(createOAuthAccountsFixture(), { drawer: true, autoOpenCreate: true, providerId })
      await settle()
      const createDialog = [...document.querySelectorAll('[role="dialog"]')].find(node => text(node.querySelector('h2')) === '新建订阅账号')
      assert.ok(createDialog)
      assert.equal(createDialog.querySelector('select').value, providerId)
      buttonNamed(createDialog, '取消').click()
      await settle()
      const workspace = dialog()
      assert.equal(document.querySelectorAll('[role="dialog"]').length, 1)
      const provider = mock.fixture.providers.find(provider => provider.id === providerId)
      assert.ok(text(workspace.querySelector('h2')).includes(provider.label))
      assert.ok(workspace.querySelector('.modal.full'), 'The six-column account table needs the full-width workspace')
      assert.equal(workspace.querySelectorAll('[data-account-id]').length, mock.fixture.accounts.filter(account => account.provider === providerId).length)
      assert.equal(workspace.querySelector('.oauth-provider-filter'), null)
      assert.deepEqual([...workspace.querySelector('.oauth-schedule-cell').querySelectorAll('.oauth-schedule-line')].slice(0, 2).map(text), ['40 次 / 3 小时', '并发上限 2'])
      assert.equal(mock.requests.some(request => request.method !== 'GET'), false)
    })
  }

  it('keeps the provider header switch operable with shared styles outside ChannelsView', async () => {
    let toggled
    await mount(createOAuthAccountsFixture(), { drawer: true, onToggleEnabled: item => { toggled = item } })
    const toggle = dialog().querySelector('.channel-status-toggle')
    assert.equal(toggle.getAttribute('role'), 'switch')
    assert.equal(toggle.getAttribute('aria-checked'), 'true')
    assert.ok(toggle.querySelector('.channel-status-track > span'))
    toggle.click()
    await settle()
    assert.equal(toggled.providerId, 'antigravity')
    const styles = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
    const parentStyles = parse(readFileSync(new URL('../src/components/ChannelsView.vue', import.meta.url), 'utf8')).descriptor.styles.map(style => style.content).join('\n')
    assert.match(styles, /(?:^|\n)\.channel-status-toggle\s*\{[^}]*display:\s*inline-flex/)
    assert.doesNotMatch(parentStyles, /(?:^|\n)\.channel-status-toggle\s*\{/)
  })

  it('sizes quota groups and scroll hints by the account workspace rather than the browser viewport', () => {
    const styles = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
    const drawerStyles = parse(readFileSync(new URL('../src/components/OAuthProviderDrawer.vue', import.meta.url), 'utf8')).descriptor.styles.map(style => style.content).join('\n')
    assert.match(styles, /\.oauth-accounts-page\s*\{[^}]*container-type:\s*inline-size;[^}]*container-name:\s*oauth-accounts;/)
    assert.match(styles, /\.oauth-account-col\s*\{\s*width:\s*auto;/)
    assert.match(styles, /@container oauth-accounts\s*\(max-width:\s*1100px\)\s*\{\s*\.oauth-quota-groups\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\)/)
    assert.match(styles, /@container oauth-accounts\s*\(max-width:\s*959px\)\s*\{\s*\.oauth-accounts-scroll-hint\s*\{\s*display:\s*block;/)
    assert.doesNotMatch(styles, /\.oauth-accounts-table\s+\.actions-col\s*\{[^}]*position:\s*sticky/)
    assert.match(styles, /\.oauth-schedule-line\s*\{[^}]*white-space:\s*normal;/)
    assert.match(drawerStyles, /\.provider-tab\.is-active\s*\{[^}]*background:\s*var\(--glass-field-strong\)/)
  })

  it('shows the real loading placeholder until deferred mock reads resolve, not an empty or no-match state', async () => {
    const { releaseLoad } = await mount(createOAuthAccountsFixture(), { deferLoad: true })
    assert.ok(host.querySelector('.loading-state'))
    assert.match(text(host.querySelector('.loading-state-head')), /正在读取订阅账号/)
    assert.equal(host.querySelector('.empty-state'), null)
    assert.equal(host.querySelector('.error-state'), null)
    assert.equal(rows().length, 0)
    assert.equal(pendingLoadReleases.size, 1)
    releaseLoad()
    await settle()
    assert.equal(pendingLoadReleases.size, 0)
    assert.equal(host.querySelector('.loading-state'), null)
    assert.equal(rows().length, 9)
    assert.deepEqual(stats(), [9, 7, 2, 1])
  })

  it('shows the no-account placeholder on a successful empty list, not the filtered no-match state', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.accounts = []
    await mount(fixture)
    assert.equal(rows().length, 0)
    assert.deepEqual(stats(), [0, 0, 0, 0])
    assert.equal(host.querySelector('.loading-state'), null)
    assert.equal(host.querySelector('.error-state'), null)
    const empty = host.querySelector('.empty-state')
    assert.ok(empty)
    assert.equal(text(empty.querySelector('h3')), '还没有订阅账号')
    assert.doesNotMatch(text(empty), /没有匹配|重置筛选/)
    assert.ok(buttonNamed(empty, '新建账号'))
    assert.match(text(host.querySelector('.oauth-filter-count')), /展示 0 \/ 共 0/)
  })

  it('shows a real load-failure alert and retries through mock reads rather than reporting no matches', async () => {
    await mount(createOAuthAccountsFixture(), { failLoad: true })
    assert.equal(rows().length, 0)
    assert.equal(host.querySelector('.loading-state'), null)
    const failure = host.querySelector('.error-state[role="alert"]')
    assert.ok(failure)
    assert.equal(text(failure.querySelector('strong')), '读取失败')
    assert.equal(text(failure.querySelector('p')), '合成账号列表读取失败')
    assert.equal(host.querySelector('.empty-state'), null, 'An initial read failure must not masquerade as a successful empty account list')
    assert.doesNotMatch(text(host), /没有匹配的订阅账号/)
    const retry = buttonNamed(failure, '重试')
    assert.equal(retry.disabled, false)
    globalThis.fetch = window.fetch = mock.fetch
    retry.click()
    await settle()
    assert.equal(host.querySelector('.error-state'), null)
    assert.equal(host.querySelector('.empty-state'), null)
    assert.equal(rows().length, 9)
    assert.deepEqual(stats(), [9, 7, 2, 1])
    assert.equal(mock.requests.length, 6)
    assert.ok(mock.requests.every(request => request.method === 'GET'))
  })

  it('loads nine synthetic accounts and keeps global 9/7/2/1 statistics across status filters', async () => {
    await mount()
    assert.equal(rows().length, 9)
    assert.deepEqual(stats(), [9, 7, 2, 1])
    assert.match(stat('凭据就绪').title, /不等于实时可调度/)
    for (const [label, expected] of [
      ['凭据就绪', [IDS.groupedHealthy, IDS.chatgptWarning, IDS.groupedWarning, IDS.exhausted, IDS.quotaUnknown, IDS.quotaError, IDS.blockedWithoutQuota]],
      ['需重新授权', [IDS.disabledReauth, IDS.reauth]],
      ['冷却中', [IDS.groupedWarning]],
    ]) {
      stat(label).click()
      await settle()
      assertRows(expected)
      assert.equal(stat(label).getAttribute('aria-pressed'), 'true')
      assert.deepEqual(stats(), [9, 7, 2, 1])
    }
    stat('订阅账号').click()
    await settle()
    assert.equal(rows().length, 9)
    assert.match(text(host.querySelector('.oauth-filter-count')), /展示 9 \/ 共 9/)
  })

  it('searches full hidden model names without expanding rows or changing account/model configuration', async () => {
    await mount()
    const target = 'gemini-4-argon-extended-context-synthetic-long-model-name'
    const before = JSON.stringify(mock.fixture)
    assert.ok(![...row(IDS.groupedHealthy).querySelectorAll('.oauth-model-badge')].some(item => text(item) === target))
    assert.equal(host.querySelector('.oauth-full-models'), null)
    await search(target)
    assertRows([IDS.groupedHealthy, IDS.groupedWarning, IDS.quotaUnknown])
    assert.equal(host.querySelector('.oauth-full-models'), null)
    assert.equal(JSON.stringify(mock.fixture), before)
    assert.equal(mock.requests.length, 3)
    assert.deepEqual(stats(), [9, 7, 2, 1])
  })

  it('intersects query, provider and stat status; repeated stat clicks cancel and reset clears all filters', async () => {
    await mount()
    await search('gpt-5-nano')
    await provider('chatgpt')
    stat('需重新授权').click()
    await settle()
    assertRows([IDS.disabledReauth, IDS.reauth])
    assert.deepEqual(stats(), [9, 7, 2, 1])
    stat('需重新授权').click()
    await settle()
    assert.equal(stat('需重新授权').getAttribute('aria-pressed'), 'false')
    assertRows([IDS.chatgptWarning, IDS.exhausted, IDS.disabledReauth, IDS.reauth])
    stat('凭据就绪').click()
    await settle()
    assertRows([IDS.chatgptWarning, IDS.exhausted])
    buttonNamed(host.querySelector('.oauth-accounts-toolbar'), '重置筛选').click()
    await settle()
    assert.equal(host.querySelector('input[type="search"]').value, '')
    assert.equal(host.querySelector('select[aria-label="筛选提供方"]').value, '')
    assert.equal(stat('订阅账号').getAttribute('aria-pressed'), 'true')
    assert.equal(rows().length, 9)
    await provider('antigravity')
    stat('冷却中').click()
    await settle()
    assertRows([IDS.groupedWarning])
    stat('冷却中').click()
    await settle()
    assertRows([IDS.groupedHealthy, IDS.groupedWarning, IDS.quotaUnknown, IDS.quotaError, IDS.blockedWithoutQuota])
    assert.deepEqual(stats(), [9, 7, 2, 1])
  })

  it('shows a distinct no-match placeholder whose reset restores all accounts', async () => {
    await mount()
    await provider('antigravity')
    stat('冷却中').click()
    await settle()
    await search('no-such-synthetic-account-or-model')
    assert.equal(rows().length, 0)
    assert.match(text(host.querySelector('.empty-state h3')), /没有匹配的订阅账号/)
    assert.match(text(host.querySelector('.oauth-filter-count')), /展示 0 \/ 共 9/)
    assert.deepEqual(stats(), [9, 7, 2, 1])
    buttonNamed(host.querySelector('.empty-state'), '重置筛选').click()
    await settle()
    assert.equal(host.querySelector('.empty-state'), null)
    assert.equal(rows().length, 9)
    assert.equal(mock.requests.length, 3)
  })

  it('does not hide disabled REAUTH credentials or their refresh error', async () => {
    await mount()
    const disabled = row(IDS.disabledReauth)
    assert.equal(disabled.querySelector('[role="switch"]').getAttribute('aria-checked'), 'false')
    assert.equal(text(disabled.querySelector('.oauth-auth-state .badge')), '需重新授权')
    assert.ok(disabled.querySelector('.oauth-auth-state .badge').classList.contains('danger'))
    assert.equal(text(disabled.querySelector('.oauth-refresh-error')), account(mock.fixture, IDS.disabledReauth).lastRefreshError)
    assert.equal(text(row(IDS.reauth).querySelector('.oauth-refresh-error')), account(mock.fixture, IDS.reauth).lastRefreshError)
    stat('需重新授权').click()
    await settle()
    assertRows([IDS.disabledReauth, IDS.reauth])
  })

  it('previews two healthy groups and expands/collapses all five with precise labels and multiple windows', async () => {
    await mount()
    const root = row(IDS.groupedHealthy).querySelector('.oauth-quota-cell')
    assert.deepEqual(groupLabels(root), ['Gemini 3 Flash', 'Claude & GPT'])
    const toggle = root.querySelector('.oauth-quota-toggle')
    assert.equal(text(toggle), '+3 更多额度')
    assert.equal(toggle.getAttribute('aria-expanded'), 'false')
    const quotaId = toggle.getAttribute('aria-controls')
    assert.equal(quotaId, `oauth-quotas-${IDS.groupedHealthy}`)
    const controlledGroups = document.getElementById(quotaId)
    assert.ok(controlledGroups)
    assert.equal(controlledGroups, root.querySelector('.oauth-quota-groups'))
    assert.equal(controlledGroups.querySelectorAll('.oauth-quota-group').length, 2)
    assert.match(toggle.getAttribute('aria-label'), /展开.*更多额度/)
    toggle.click()
    await settle()
    assert.equal(toggle.getAttribute('aria-expanded'), 'true')
    assert.equal(document.getElementById(quotaId), controlledGroups)
    assert.equal(controlledGroups.querySelectorAll('.oauth-quota-group').length, 5)
    assert.deepEqual(groupLabels(root), ['Gemini 3 Flash', 'Claude & GPT', 'Claude', 'Gemini 3 Pro', 'Gemini 4 Argon'])
    assert.equal(root.querySelectorAll('.oauth-quota-window').length, 6)
    const claude = [...root.querySelectorAll('.oauth-quota-group')].find(item => text(item.querySelector('.oauth-quota-group-label')) === 'Claude')
    assert.match(text(claude), /5 小时.*已用 27%.*7 天.*已用 34%/)
    assert.match(text(claude), /3 天后重置/)
    assert.match(toggle.getAttribute('aria-label'), /收起.*更多额度/)
    toggle.click()
    await settle()
    assert.equal(toggle.getAttribute('aria-expanded'), 'false')
    assert.equal(root.querySelectorAll('.oauth-quota-group').length, 2)
  })

  it('never folds warning 80/91% groups even when additional healthy groups need expansion', async () => {
    const fixture = createOAuthAccountsFixture()
    const warning = account(fixture, IDS.groupedWarning)
    warning.quota.groups.push({ name: 'Gemini 3 Flash', windows: [{ ...warning.quota.groups[2].windows[0], usedPercent: 30, remainingPercent: 70 }] })
    await mount(fixture)
    const root = row(IDS.groupedWarning).querySelector('.oauth-quota-cell')
    assert.deepEqual(groupLabels(root), ['Claude', 'Claude & GPT', 'Gemini 3 Pro', 'Gemini 3 Flash'])
    assert.match(text(root), /已用 91%/)
    assert.match(text(root), /已用 80%/)
    assert.equal(root.querySelectorAll('.quota-fill.is-warning').length, 2)
    assert.equal(text(root.querySelector('.oauth-quota-toggle')), '+1 更多额度')
    root.querySelector('.oauth-quota-toggle').click()
    await settle()
    assert.equal(root.querySelectorAll('.oauth-quota-group').length, 5)
    const chatgpt = row(IDS.chatgptWarning).querySelector('.oauth-quota-cell')
    assert.equal(chatgpt.querySelectorAll('.oauth-quota-window').length, 2)
    assert.match(text(chatgpt), /已用 80%.*已用 54%/)
    assert.equal(chatgpt.querySelector('.oauth-quota-toggle'), null)
  })

  for (const [scenario, patch, notice] of [
    ['upstream allowed=false', { allowed: false }, '上游额度不可用'],
    ['quota read error', { error: 'quota_read_timeout' }, '额度读取超时'],
  ]) {
    it('shows all five healthy groups without a more button on ' + scenario, async () => {
      const fixture = createOAuthAccountsFixture()
      Object.assign(account(fixture, IDS.groupedHealthy).quota, patch)
      await mount(fixture)
      const root = row(IDS.groupedHealthy).querySelector('.oauth-quota-cell')
      assert.equal(root.querySelectorAll('.oauth-quota-group').length, 5)
      assert.equal(root.querySelectorAll('.oauth-quota-window').length, 6)
      assert.equal(root.querySelector('.oauth-quota-toggle'), null)
      assert.ok(text(root).includes(notice))
      assert.ok(groupLabels(root).includes('Claude'))
      assert.ok(groupLabels(root).includes('Claude & GPT'))
      assert.ok(groupLabels(root).includes('Gemini 4 Argon'))
    })
  }

  it('shows exhaustion, explicit quota errors and account-level recovery even with null quota', async () => {
    await mount()
    const exhausted = row(IDS.exhausted).querySelector('.oauth-quota-cell')
    assert.match(text(exhausted), /已用 100%.*已用 91%/)
    assert.ok(exhausted.querySelector('.quota-fill.is-danger'))
    assert.match(text(exhausted), /额度阻断 · 45 分钟后恢复/)
    const missing = row(IDS.quotaUnknown).querySelector('.oauth-quota-cell')
    assert.equal(text(missing), '暂无读数')
    const blocked = row(IDS.blockedWithoutQuota).querySelector('.oauth-quota-cell')
    assert.equal(account(mock.fixture, IDS.blockedWithoutQuota).quota, null)
    assert.match(text(blocked), /额度阻断 · 6 小时后恢复/)
    assert.match(text(blocked), /暂无读数/)
    const error = row(IDS.quotaError).querySelector('.oauth-quota-cell')
    assert.match(text(error), /未获取到 Antigravity 项目/)
    assert.match(text(error), /若账号尚未开通.*再刷新额度/)
    assert.doesNotMatch(text(error), /暂无读数/)
    assert.match(text(row(IDS.reauth).querySelector('.oauth-quota-cell')), /凭据不可用，请刷新 token/)
  })

  it('expands the full model list with aria-expanded, aria-controls and every hidden model', async () => {
    await mount()
    const root = row(IDS.chatgptWarning)
    const toggle = root.querySelector('.oauth-models-toggle')
    const models = [...account(mock.fixture, IDS.chatgptWarning).models].sort()
    const listId = toggle.getAttribute('aria-controls')
    assert.equal(root.querySelectorAll('.oauth-model-badge').length, 3)
    assert.equal(text(toggle), '+3')
    assert.equal(toggle.getAttribute('aria-expanded'), 'false')
    assert.equal(document.getElementById(listId), null)
    assert.match(toggle.getAttribute('aria-label'), /查看.*完整模型列表/)
    toggle.click()
    await settle()
    assert.equal(toggle.getAttribute('aria-expanded'), 'true')
    const list = document.getElementById(listId)
    assert.ok(list)
    assert.equal(list.tagName, 'UL')
    assert.match(list.getAttribute('aria-label'), /完整模型列表/)
    assert.deepEqual([...list.querySelectorAll('li')].map(text), models)
    assert.match(toggle.getAttribute('aria-label'), /收起.*完整模型列表/)
    toggle.click()
    await settle()
    assert.equal(toggle.getAttribute('aria-expanded'), 'false')
    assert.equal(document.getElementById(listId), null)
    assert.equal(mock.requests.length, 3)
  })

  it('offers an accessible full-model disclosure even when a single model has no hidden badge', async () => {
    await mount()
    const root = row(IDS.blockedWithoutQuota)
    const toggle = root.querySelector('.oauth-models-toggle')
    assert.equal(text(toggle), '查看模型')
    toggle.click()
    await settle()
    assert.equal(toggle.getAttribute('aria-expanded'), 'true')
    assert.deepEqual([...document.getElementById(toggle.getAttribute('aria-controls')).querySelectorAll('li')].map(text), ['gemini-4-argon'])
  })

  for (const id of [IDS.groupedHealthy, IDS.disabledReauth]) {
    it('keeps account settings in a single inline entry for ' + id, async () => {
      await mount()
      const name = account(mock.fixture, id).name
      const settingsButtons = [...row(id).querySelectorAll('.row-actions button')].filter(button => button.getAttribute('aria-label') === '设置 ' + name)
      assert.equal(settingsButtons.length, 1)
      const settingsButton = settingsButtons[0]
      assert.equal(settingsButton.title, '设置 ' + name)

      const panel = await openMenu(id)
      const actions = [...panel.querySelectorAll('[role="menuitem"]')].map(text)
      assert.equal(actions.includes('账号设置'), false)
      assert.ok(actions.includes('调度策略'))
      assert.ok(actions.includes('重新授权'))
      panel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      await settle()
      assert.equal(document.querySelector('.floating-menu'), null)

      settingsButton.click()
      await settle()
      const dialog = document.querySelector('[role="dialog"]')
      assert.ok(dialog)
      assert.equal(text(dialog.querySelector('h2')), '账号设置')
      assert.equal(dialog.querySelector('input[autocomplete="off"]').value, name)
      assert.match(text(dialog), /账号代理/)
      assert.match(text(dialog), /模型授权/)
      assert.ok(dialog.querySelector('.oauth-model-picker'))
      assert.deepEqual(mock.requests.slice(3).map(request => [request.method, request.path]), [
        ['GET', `/admin/api/oauth-providers/${account(mock.fixture, id).provider}/model-mappings`],
        ['POST', '/admin/api/model-suggestions'],
      ], 'Opening settings reads only shared mappings and batch candidates, never upstream or login routes')
      dialog.querySelector('button[aria-label="关闭"]').click()
      await settle()
      assert.equal(document.querySelector('[role="dialog"]'), null)
    })
  }

  it('mounts manual editing in a width-constrained public-ID cell separate from mode/source, with local responsive grid tracks', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    await input(dialog().querySelector('[aria-label="搜索模型授权与映射"]'), 'claude-opus')
    const row = modelRow('claude-opus-4-5-thinking')
    assert.deepEqual([...row.children].map(child => child.className), ['oauth-mapping-upstream', 'oauth-mapping-public', 'oauth-mapping-mode', 'oauth-mapping-source muted small'])
    const wrapper = row.querySelector('.oauth-mapping-input')
    assert.equal(wrapper.parentElement.className, 'oauth-mapping-public')
    assert.equal(wrapper.querySelector('select'), null, 'Mode belongs to its sibling column, never the editable wrapper')
    assert.ok(wrapper.querySelector('input'))
    assert.ok(wrapper.querySelector('.search-select-trigger'))
    assert.equal(row.querySelector('.oauth-mapping-mode').contains(row.querySelector('select')), true)
    // jsdom has no geometry/layout engine. Assert actual SFC grid containment rules rather than inventing bounding boxes.
    const wrapperStyles = parse(readFileSync(new URL('../src/components/OAuthMappingInput.vue', import.meta.url), 'utf8')).descriptor.styles.map(style => style.content).join('\n')
    const viewStyles = parse(readFileSync(new URL('../src/components/OAuthAccountsView.vue', import.meta.url), 'utf8')).descriptor.styles.map(style => style.content).join('\n')
    assert.match(wrapperStyles, /\.oauth-mapping-input\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\);[^}]*max-width:\s*100%/)
    assert.match(wrapperStyles, /:deep\(\.search-select-trigger\)\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*100%/)
    assert.match(viewStyles, /\.oauth-mapping-list\s*\{[^}]*--oauth-mapping-min-width:\s*660px;[^}]*overflow:\s*auto;/)
    assert.match(viewStyles, /\.oauth-mapping-head,\s*\.oauth-mapping-row\s*\{[^}]*grid-template-columns:\s*var\(--oauth-mapping-columns\);[^}]*min-width:\s*var\(--oauth-mapping-min-width\)/)
    assert.match(viewStyles, /\.oauth-mapping-upstream,\s*\.oauth-mapping-public,\s*\.oauth-mapping-mode\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\);[^}]*max-width:\s*100%/)
    assert.match(viewStyles, /@media\s*\(max-width:\s*680px\)\s*\{\s*\.oauth-mapping-list\s*\{[^}]*--oauth-mapping-columns:\s*minmax\(0, 1fr\);[^}]*--oauth-mapping-min-width:\s*0/)
  })

  it('preserves legacy models_dev manual override through unrelated save and sync defaults until restore-auto is explicit', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.automaticMappings['gemini-3-flash'] = 'google/changed-auto-flash'
    await mount(fixture)
    const model = 'gemini-3-flash'
    const initial = { ...mapping('antigravity', model) }
    assert.equal(initial.mode, 'manual')
    assert.equal(initial.mappingSource, 'models_dev')
    await openSettings(IDS.groupedHealthy)
    assert.equal(modelRow(model).querySelector('select').value, 'manual')
    assert.equal(modelRow(model).querySelector('.oauth-mapping-input input').value, initial.publicModelId)
    assert.equal(text(modelRow(model).querySelector('.oauth-mapping-source')), 'models_dev')
    await input(field('账号名称'), '保留旧目录来源覆盖')
    await save()
    assert.deepEqual(writes()[0].body, { name: '保留旧目录来源覆盖' })
    assert.deepEqual(mapping('antigravity', model), initial)
    await openSettings(IDS.groupedHealthy)
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.equal(mapping('antigravity', model).automaticCanonicalModelId, 'google/changed-auto-flash')
    assert.equal(mapping('antigravity', model).publicModelId, initial.publicModelId)
    assert.equal(mapping('antigravity', model).mappingSource, 'models_dev')
    assert.equal(modelRow(model).querySelector('select').value, 'manual')
    buttonNamed(modelRow(model), '恢复自动').click(); await settle()
    assert.equal(text(modelRow(model).querySelector('.oauth-mapping-public > .mono')), 'google/changed-auto-flash')
    assert.equal(text(modelRow(model).querySelector('.oauth-mapping-source')), '待保存；当前来源：models_dev')
    await save()
    assert.deepEqual(writes()[1].body.modelMappingChanges, [{ upstreamModel: model, mode: 'auto' }])
    assert.equal(mapping('antigravity', model).mode, 'auto')
    assert.equal(mapping('antigravity', model).mappingSource, null)
    assert.equal(mapping('antigravity', model).publicModelId, 'google/changed-auto-flash')
  })

  it('fixture GET and atomic settings/sync follow the backend mode/source contract without auto + models_dev states', async () => {
    await mount()
    const read = async provider => (await mock.fetch(`/admin/api/oauth-providers/${provider}/model-mappings`)).json()
    const put = async body => mock.fetch(`/admin/api/oauth-accounts/${IDS.groupedHealthy}/settings`, { method: 'PUT', body: JSON.stringify(body) })
    const assertContract = resource => {
      for (const row of resource.mappings) {
        assert.equal(row.mode, row.mappingSource == null ? 'auto' : row.canonicalModelId == null ? 'upstream' : 'manual')
        assert.equal(row.publicModelId, row.canonicalModelId ?? row.upstreamModel)
        if (row.mode === 'auto') assert.equal(row.canonicalModelId, row.automaticCanonicalModelId)
        assert.equal(row.mode === 'auto' && row.mappingSource === 'models_dev', false)
      }
    }
    for (const provider of ['antigravity', 'chatgpt']) assertContract(await read(provider))
    let resource = await read('antigravity')
    const noop = await put({ modelMappingChanges: [], expectedModelMappingRevision: 0 })
    assert.equal(noop.status, 200, 'Empty changes are noop, even without current mapping revision')
    assert.equal((await read('antigravity')).revision, resource.revision)
    const model = 'gemini-3-pro'
    for (const mode of ['manual', 'upstream', 'auto']) {
      const change = { upstreamModel: model, mode, ...(mode === 'manual' ? { canonicalModelId: ' custom/contract ' } : {}) }
      assert.equal((await put({ modelMappingChanges: [change], expectedModelMappingRevision: resource.revision })).status, 200)
      resource = await read('antigravity')
      assertContract(resource)
      const row = resource.mappings.find(row => row.upstreamModel === model)
      assert.equal(row.mappingSource, mode === 'auto' ? null : 'manual')
    }
    const staleName = account(mock.fixture, IDS.groupedHealthy).name
    assert.equal((await put({ name: 'must-not-save', models: ['gemini-3-flash'], modelMappingChanges: [{ upstreamModel: model, mode: 'upstream' }], expectedModelMappingRevision: resource.revision - 1 })).status, 409)
    assert.equal(account(mock.fixture, IDS.groupedHealthy).name, staleName)
    assert.equal((await read('antigravity')).revision, resource.revision)
    await mock.fetch(`/admin/api/oauth-accounts/${IDS.groupedHealthy}/models/refresh`, { method: 'POST' })
    assertContract(await read('antigravity'))
    assert.equal(mapping('antigravity', 'gemini-3-flash').mappingSource, 'models_dev')
  })

  it('fixture does not advance the shared revision when a mapping change repeats the current shared values', async () => {
    await mount()
    const read = async provider => (await mock.fetch(`/admin/api/oauth-providers/${provider}/model-mappings`)).json()
    const put = async body => mock.fetch(`/admin/api/oauth-accounts/${IDS.groupedHealthy}/settings`, { method: 'PUT', body: JSON.stringify(body) })
    let resource = await read('antigravity')
    const model = 'gemini-3-pro'
    // 每种模式先提交一次真实变化，再原样重复提交；同值重复不得递增版本，否则另一个弹窗会遭遇 409。
    const cycle = [
      { upstreamModel: model, mode: 'manual', canonicalModelId: 'custom/repeat' },
      { upstreamModel: model, mode: 'upstream' },
      { upstreamModel: model, mode: 'auto' },
    ]
    for (const change of cycle) {
      assert.equal((await put({ modelMappingChanges: [change], expectedModelMappingRevision: resource.revision })).status, 200)
      resource = await read('antigravity')
      const revision = resource.revision
      assert.equal((await put({ modelMappingChanges: [change], expectedModelMappingRevision: resource.revision })).status, 200)
      const latest = await read('antigravity')
      assert.equal(latest.revision, revision, `Repeat ${change.mode} submission must not advance the shared revision`)
      resource = latest
    }
    // 真实变化仍然递增版本，两种行为并存才与生产端 mappingsChanged 契约一致。
    assert.equal((await put({ modelMappingChanges: [{ upstreamModel: model, mode: 'manual', canonicalModelId: 'custom/repeat-2' }], expectedModelMappingRevision: resource.revision })).status, 200)
    assert.equal((await read('antigravity')).revision, resource.revision + 1)
  })

  it('edits mappings with allModels on, keeps model search enabled, and separates checkbox labels from mapping controls', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    assert.match(text(dialog()), /同一提供方 Antigravity.*受影响账号 5 个.*不会增加其他账号的模型授权/)
    const model = 'gemini-3-pro'
    const checkbox = modelRow(model).querySelector('input[type="checkbox"]')
    assert.equal(checkbox.disabled, true)
    const mode = modelRow(model).querySelector('select')
    assert.equal(mode.disabled, false)
    assert.equal(mode.closest('label'), null)
    const query = dialog().querySelector('[aria-label="搜索模型授权与映射"]')
    assert.equal(query.disabled, false)
    await input(query, 'gemini-3-pro')
    assert.equal(dialog().querySelectorAll('.oauth-mapping-row').length, 1)
    await manual(model, ' custom/pro ')
    assert.equal(checkbox.checked, false, 'Mapping edits must not toggle authorization')
    await save()
    assert.equal(dialog(), null)
    assert.deepEqual(writes().map(request => request.body), [{
      modelMappingChanges: [{ upstreamModel: model, mode: 'manual', canonicalModelId: 'custom/pro' }], expectedModelMappingRevision: 1,
    }])
    assert.deepEqual(account(mock.fixture, IDS.groupedHealthy).models, [])
    assert.equal(mapping('antigravity', model).publicModelId, 'custom/pro')
  })

  it('keeps explicit manual when selecting the automatic same value through real SearchSelect, and explicitly restores auto/upstream', async () => {
    await mount()
    const model = 'gemini-3-pro'
    const automatic = mapping('antigravity', model).automaticCanonicalModelId
    await openSettings(IDS.groupedHealthy)
    await chooseMode(model, 'manual')
    modelRow(model).querySelector('.search-select-trigger').click()
    await settle()
    const panel = document.querySelector('.search-select-panel')
    assert.ok(panel, 'Expected real local SearchSelect with teleported candidates')
    const option = [...panel.querySelectorAll('[role="option"]')].find(item => text(item.querySelector('strong')) === automatic)
    assert.ok(option)
    assert.match(text(option), /当前提供方有效对外 ID/)
    option.click(); await settle()
    await save()
    assert.deepEqual(writes()[0].body.modelMappingChanges, [{ upstreamModel: model, mode: 'manual', canonicalModelId: automatic }])
    assert.equal(mapping('antigravity', model).mode, 'manual')
    await openSettings(IDS.groupedHealthy)
    assert.equal(modelRow(model).querySelector('select').value, 'manual')
    buttonNamed(modelRow(model), '恢复自动').click(); await settle()
    await save()
    assert.deepEqual(writes()[1].body.modelMappingChanges, [{ upstreamModel: model, mode: 'auto' }])
    assert.equal(mapping('antigravity', model).mappingSource, null)
    await openSettings(IDS.groupedHealthy)
    await chooseMode(model, 'upstream'); await save()
    assert.deepEqual(writes()[2].body.modelMappingChanges, [{ upstreamModel: model, mode: 'upstream' }])
    assert.equal(mapping('antigravity', model).publicModelId, model)
    assert.equal(mapping('antigravity', model).mappingSource, 'manual')
  })

  it('does not rewrite untouched models_dev mappings when only authorization changes and preserves hidden authorized models', async () => {
    const fixture = createOAuthAccountsFixture()
    const target = account(fixture, IDS.groupedHealthy)
    target.models = ['claude-sonnet-4-5', 'legacy-outside-model']
    await mount(fixture)
    const before = JSON.stringify(mappingResource('antigravity'))
    await openSettings(target.id)
    await input(dialog().querySelector('[aria-label="搜索模型授权与映射"]'), 'gemini-3-pro')
    buttonNamed(dialog(), '全选搜索结果').click(); await settle(); await save()
    assert.deepEqual(writes().map(request => request.body), [{ models: ['claude-sonnet-4-5', 'gemini-3-pro', 'legacy-outside-model'] }])
    assert.equal(JSON.stringify(mappingResource('antigravity')), before)
    assert.equal(mock.requests.some(request => request.method === 'PUT' && /\/(models|channels)$/.test(request.path)), false)
  })

  it('atomically saves account settings, authorizations and mapping changes in one managed-account PUT', async () => {
    await mount()
    const target = account(mock.fixture, IDS.quotaError)
    await openSettings(target.id)
    await input(field('账号名称'), '新的合成账号名称')
    assert.equal(field('优先级'), undefined, 'Legacy priority is not editable')
    await input(dialog().querySelector('.oauth-proxy-row > input'), 'http://127.0.0.1:7890')
    modelRow('gemini-3-flash').querySelector('input[type="checkbox"]').click(); await settle()
    await manual('gemini-3-pro', 'custom/atomic')
    await save()
    assert.equal(writes().length, 1)
    assert.deepEqual(writes()[0].body, {
      name: '新的合成账号名称', proxyUrl: 'http://127.0.0.1:7890', clearProxy: false,
      models: ['gemini-3-flash', 'gemini-3-pro'],
      modelMappingChanges: [{ upstreamModel: 'gemini-3-pro', mode: 'manual', canonicalModelId: 'custom/atomic' }], expectedModelMappingRevision: 1,
    })
    assert.equal(dialog(), null)
  })

  for (const providerId of ['chatgpt', 'antigravity']) {
    for (const outcome of ['success', 'failure']) {
      for (const reopen of [false, true]) {
        it(providerId + ' refreshes only committed data after cancelled creation ' + outcome + (reopen ? ' while preserving a newer dialog' : ' without reopening the pool'), async () => {
          let finishLogin
          const fixture = createOAuthAccountsFixture()
          const createdAccount = structuredClone(account(fixture, providerId === 'chatgpt' ? IDS.chatgptWarning : IDS.groupedHealthy))
          fixture.accounts = fixture.accounts.filter(account => account.provider !== providerId)
          api.completeOAuthLogin = () => new Promise((resolve, reject) => {
            finishLogin = () => {
              if (outcome === 'success') { mock.fixture.accounts.push(createdAccount); resolve() }
              else reject(new Error('Delayed synthetic completion failure'))
            }
          })
          await mount(fixture, { providerList: true })
          const providerEntry = () => host.querySelector('tr[data-provider-id="' + providerId + '"]')
          buttonNamed(providerEntry(), '添加账号').click()
          await settle()
          await submitMockedLogin()
          buttonNamed(dialog(), '取消').click()
          await settle()
          if (reopen) {
            buttonNamed(providerEntry(), '添加账号').click()
            await settle()
            await input(field('账号名称'), '新弹窗草稿不被旧提交影响')
          }
          const readsBeforeCompletion = mock.requests.filter(request => request.path === '/admin/api/oauth-accounts').length
          finishLogin()
          await settle()
          assert.equal(mock.requests.filter(request => request.path === '/admin/api/oauth-accounts').length, readsBeforeCompletion + (outcome === 'success' ? 1 : 0))
          buttonNamed(providerEntry(), outcome === 'success' ? '管理账号' : '添加账号')
          assert.equal(document.querySelector('.oauth-accounts-page'), null)
          assert.equal(toasts.length, 0)
          if (reopen) {
            assert.equal(text(dialog().querySelector('h2')), '新建订阅账号')
            assert.equal(field('账号名称').value, '新弹窗草稿不被旧提交影响')
          } else assert.equal(dialog(), null)
        })
      }
    }

    it(providerId + ' refreshes the actual provider list exactly once after uncancelled creation succeeds', async () => {
      const fixture = createOAuthAccountsFixture()
      const createdAccount = structuredClone(account(fixture, providerId === 'chatgpt' ? IDS.chatgptWarning : IDS.groupedHealthy))
      fixture.accounts = fixture.accounts.filter(account => account.provider !== providerId)
      api.completeOAuthLogin = async () => { mock.fixture.accounts.push(createdAccount) }
      await mount(fixture, { providerList: true })
      const providerEntry = () => host.querySelector('tr[data-provider-id="' + providerId + '"]')
      buttonNamed(providerEntry(), '添加账号').click()
      await settle()
      const readsBeforeCompletion = mock.requests.filter(request => request.path === '/admin/api/oauth-accounts').length
      await submitMockedLogin()
      assert.equal(mock.requests.filter(request => request.path === '/admin/api/oauth-accounts').length, readsBeforeCompletion + 1)
      buttonNamed(providerEntry(), '管理账号')
      assert.equal(dialog(), null)
      assert.equal(document.querySelector('.oauth-accounts-page'), null)
      assert.match(text(host.querySelector('.toast')), /订阅账号已通过浏览器登录/)
    })
  }

  it('does not fetch provider data after the provider page is unmounted during account creation', async () => {
    let finishLogin
    api.completeOAuthLogin = () => new Promise(resolve => { finishLogin = resolve })
    await mount(createOAuthAccountsFixture(), { providerList: true })
    buttonNamed(host, '接入提供商').click()
    await settle()
    dialog().querySelector('input[value="subscription"]').click()
    await settle()
    const select = dialog().querySelector('select')
    select.value = 'antigravity'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
    buttonNamed(dialog(), '继续').click()
    await settle()
    await submitMockedLogin()
    app.unmount()
    app = null
    const readsBeforeCompletion = mock.requests.length
    finishLogin()
    await settle()
    assert.equal(mock.requests.length, readsBeforeCompletion)
    assert.equal(toasts.length, 0)
  })

  it('opens account B with new shared mappings after A saves without expanding B authorization', async () => {
    await mount()
    const b = account(mock.fixture, IDS.quotaError)
    const authorization = [...b.models]
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-flash', 'shared/new-flash'); await save()
    assert.deepEqual(b.models, authorization)
    await openSettings(b.id)
    assert.equal(text(modelRow('gemini-3-flash').querySelector('.oauth-mapping-public > .mono')), 'shared/new-flash')
    assert.equal(modelRow('gemini-3-flash').querySelector('input[type="checkbox"]').checked, false)
    assert.equal(modelRow('gemini-3-pro').querySelector('input[type="checkbox"]').checked, true)
    assert.match(text(dialog()), /版本 2/)
  })

  it('discards delayed A mapping/suggestion responses after closing and opening another provider B', async () => {
    await mount()
    const release = gateFetch(path => path.includes('/oauth-providers/antigravity/model-mappings') || path === '/admin/api/model-suggestions')
    await openSettings(IDS.groupedHealthy)
    assert.match(text(dialog()), /正在读取共享映射/)
    await closeSettings()
    globalThis.fetch = window.fetch = mock.fetch
    await openSettings(IDS.chatgptWarning)
    await manual('gpt-5', 'b/draft')
    await input(field('账号名称'), 'B草稿名称')
    release(); await settle()
    assert.match(text(dialog()), /同一提供方 ChatGPT.*受影响账号 4 个/)
    assert.doesNotMatch(text(dialog()), /同一提供方 Antigravity/)
    assert.equal(field('账号名称').value, 'B草稿名称')
    assert.equal(modelRow('gpt-5').querySelector('.oauth-mapping-input input').value, 'b/draft')
    await save()
    assert.equal(writes()[0].path, `/admin/api/oauth-accounts/${IDS.chatgptWarning}/settings`)
    assert.equal(writes()[0].body.expectedModelMappingRevision, 1)
  })

  it('discards old A sync completion even after reopening the same account in a new generation', async () => {
    await mount()
    const release = gateFetch(path => path === `/admin/api/oauth-accounts/${IDS.groupedHealthy}/models/refresh`)
    await openSettings(IDS.groupedHealthy)
    buttonNamed(dialog(), '同步模型').click(); await settle()
    await closeSettings()
    globalThis.fetch = window.fetch = mock.fetch
    await openSettings(IDS.groupedHealthy)
    await input(field('账号名称'), '重新打开的草稿')
    await manual('gemini-3-pro', 'reopened/draft')
    const reads = mock.requests.filter(request => request.path.includes('/model-mappings')).length
    release(); await settle(); await settle()
    assert.equal(mock.requests.filter(request => request.path.includes('/model-mappings')).length, reads)
    assert.equal(field('账号名称').value, '重新打开的草稿')
    assert.equal(modelRow('gemini-3-pro').querySelector('.oauth-mapping-input input').value, 'reopened/draft')
  })

  it('discards delayed A sync responses after switching to B and keeps B mapping/authorization drafts', async () => {
    await mount()
    const release = gateFetch(path => path === `/admin/api/oauth-accounts/${IDS.groupedHealthy}/models/refresh`)
    await openSettings(IDS.groupedHealthy)
    buttonNamed(dialog(), '同步模型').click(); await settle()
    await closeSettings()
    globalThis.fetch = window.fetch = mock.fetch
    await openSettings(IDS.chatgptWarning)
    await manual('gpt-5', 'b/sync-draft')
    await input(field('账号名称'), 'B同步期间草稿')
    const readCount = mock.requests.filter(request => request.path.includes('/model-mappings')).length
    release(); await settle(); await settle()
    assert.equal(mock.requests.filter(request => request.path.includes('/model-mappings')).length, readCount)
    assert.match(text(dialog()), /同一提供方 ChatGPT/)
    assert.equal(field('账号名称').value, 'B同步期间草稿')
    assert.equal(modelRow('gpt-5').querySelector('.oauth-mapping-input input').value, 'b/sync-draft')
    assert.equal(modelRow('gpt-5-nano').querySelector('input[type="checkbox"]').checked, true)
  })

  it('does not let a pending A save close or overwrite B and keeps the write/sync lock across modal generations', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-pro', 'a/pending-save')
    const release = gateFetch((path, init) => init.method === 'PUT')
    buttonNamed(dialog(), '保存').click(); await settle()
    await closeSettings()
    globalThis.fetch = window.fetch = mock.fetch
    await openSettings(IDS.chatgptWarning)
    await input(field('账号名称'), 'B不能被旧保存关闭')
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.equal(buttonNamed(dialog(), '同步模型').disabled, true)
    release(); await settle(); await settle()
    assert.ok(dialog())
    assert.equal(field('账号名称').value, 'B不能被旧保存关闭')
    assert.match(text(dialog()), /同一提供方 ChatGPT/)
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
  })

  it('preserves every dirty mapping and authorization/name/proxy/query draft on sync, including catalog-removed/custom rows', async () => {
    const fixture = createOAuthAccountsFixture()
    const target = account(fixture, IDS.groupedHealthy)
    target.models = ['gemini-3-pro', 'claude-sonnet-4-5']
    fixture.syncCatalogs[target.id] = ['gemini-3-pro', 'new-synced-model']
    fixture.automaticMappings['gemini-3-pro'] = 'google/new-auto-pro'
    await mount(fixture)
    await openSettings(target.id)
    await input(field('账号名称'), '同步草稿名称')
    await input(dialog().querySelector('.oauth-proxy-row > input'), 'http://127.0.0.1:7890')
    await input(dialog().querySelector('[aria-label="补充列表外的模型"]'), 'custom-upstream')
    await manual('custom-upstream', 'custom/public-id')
    await manual('claude-sonnet-4-5', 'manual/preserved')
    await input(dialog().querySelector('[aria-label="搜索模型授权与映射"]'), 'claude')
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.equal(field('账号名称').value, '同步草稿名称')
    assert.equal(dialog().querySelector('.oauth-proxy-row > input').value, 'http://127.0.0.1:7890')
    assert.equal(dialog().querySelector('[aria-label="搜索模型授权与映射"]').value, 'claude')
    const removed = modelRow('claude-sonnet-4-5')
    assert.ok(removed.querySelector('.oauth-model-outside'))
    assert.equal(removed.querySelector('input[type="checkbox"]').checked, true)
    assert.equal(removed.querySelector('.oauth-mapping-input input').value, 'manual/preserved')
    await input(dialog().querySelector('[aria-label="搜索模型授权与映射"]'), '')
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input input').value, 'custom/public-id')
    assert.ok(modelRow('custom-upstream').querySelector('.oauth-model-outside'))
    assert.equal(text(modelRow('gemini-3-pro').querySelector('.oauth-mapping-public > .mono')), 'google/new-auto-pro')
    assert.equal(mapping('antigravity', 'claude-opus-4-5-thinking').canonicalModelId, 'custom/opus', 'Sync must preserve saved manual mappings')
    await save()
    assert.deepEqual(writes()[0].body.models, ['claude-sonnet-4-5', 'custom-upstream', 'gemini-3-pro'])
    assert.deepEqual(writes()[0].body.modelMappingChanges, [
      { upstreamModel: 'claude-sonnet-4-5', mode: 'manual', canonicalModelId: 'manual/preserved' },
    ])
    assert.equal(writes()[0].body.expectedModelMappingRevision, 2)
    assert.equal(mapping('antigravity', 'custom-upstream'), undefined, 'Unknown upstream is never created by settings')
    assert.match(text(dialog()), /账号设置与授权已保存成功.*无共享行的映射草稿未提交/)
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input input').value, 'custom/public-id')
    buttonNamed(modelRow('custom-upstream'), '丢弃此映射草稿').click(); await settle()
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input'), null)
    await save()
    assert.equal(dialog(), null)
    assert.equal(writes().length, 1)
  })

  it('validates every atomic settings mapping before state changes: unknown, duplicate, mode, length and revision fail without side effects', async () => {
    await mount()
    const target = account(mock.fixture, IDS.groupedHealthy)
    const put = async patch => mock.fetch(`/admin/api/oauth-accounts/${target.id}/settings`, { method: 'PUT', body: JSON.stringify({ name: 'must-not-save', models: ['gemini-3-flash'], expectedModelMappingRevision: 1, ...patch }) })
    const valid = { upstreamModel: 'gemini-3-pro', mode: 'manual', canonicalModelId: 'valid/id' }
    for (const [patch, status] of [
      [{ modelMappingChanges: [valid, { upstreamModel: 'custom-upstream', mode: 'manual', canonicalModelId: 'custom/id' }] }, 400],
      [{ modelMappingChanges: [valid, { ...valid, upstreamModel: ' gemini-3-pro ' }] }, 400],
      [{ modelMappingChanges: [{ ...valid, mode: 'invalid' }] }, 400],
      [{ modelMappingChanges: [{ ...valid, canonicalModelId: '   ' }] }, 400],
      [{ modelMappingChanges: [{ ...valid, canonicalModelId: 'x'.repeat(201) }] }, 400],
      [{ modelMappingChanges: [{ upstreamModel: 'gemini-3-pro', mode: 'auto', canonicalModelId: 'forbidden' }] }, 400],
      [{ modelMappingChanges: [valid], expectedModelMappingRevision: -1 }, 400],
      [{ modelMappingChanges: [valid], expectedModelMappingRevision: '1' }, 400],
      [{ modelMappingChanges: [valid], expectedModelMappingRevision: null }, 400],
      [{ modelMappingChanges: [valid], expectedModelMappingRevision: 0 }, 409],
    ]) {
      const before = JSON.stringify(mock.fixture)
      const result = await put(patch)
      assert.equal(result.status, status)
      assert.equal(JSON.stringify(mock.fixture), before, 'Invalid request must not partially alter settings/models/shared rows/revision')
    }
    const before = JSON.stringify(mock.fixture)
    mock.fixture.controls.conflictNextSave = true
    const controlSnapshot = JSON.stringify(mock.fixture)
    assert.equal((await put({ modelMappingChanges: [valid, { upstreamModel: 'unknown', mode: 'upstream' }] })).status, 400)
    assert.equal(JSON.stringify(mock.fixture), controlSnapshot, 'Unknown validation happens even before synthetic competing writer')
    assert.equal((await put({ modelMappingChanges: [valid], expectedModelMappingRevision: 0 })).status, 409)
    assert.equal(JSON.stringify(mock.fixture), controlSnapshot, 'Stale revision is rejected before any fixture control mutates state')
    mock.fixture.controls.conflictNextSave = false
    assert.equal(JSON.stringify(mock.fixture), before)
    assert.equal((await put({ modelMappingChanges: [{ ...valid, upstreamModel: ' gemini-3-pro ', canonicalModelId: ' trimmed/id ' }] })).status, 200)
    assert.equal(mapping('antigravity', 'gemini-3-pro').publicModelId, 'trimmed/id')
  })

  it('detects B shared writes during A sync, preserves A draft and requires explicit confirmation without auto retry', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-pro', 'a/draft-before-sync')
    await input(field('账号名称'), 'A保留名称草稿')
    const response = await mock.fetch(`/admin/api/oauth-accounts/${IDS.quotaError}/settings`, { method: 'PUT', body: JSON.stringify({ modelMappingChanges: [{ upstreamModel: 'gemini-3-pro', mode: 'manual', canonicalModelId: 'b/shared-write' }], expectedModelMappingRevision: 1 }) })
    assert.equal(response.status, 200)
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.equal(modelRow('gemini-3-pro').querySelector('.oauth-mapping-input input').value, 'a/draft-before-sync')
    assert.equal(field('账号名称').value, 'A保留名称草稿')
    assert.match(text(modelRow('gemini-3-pro')), /最新共享值：b\/shared-write/)
    assert.match(text(dialog()), /共享映射冲突.*管理员确认/)
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.equal(writes().length, 1, 'Sync/GET must never write or automatically retry A draft')
    dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').click(); await settle()
    await save()
    assert.equal(writes().length, 2)
    assert.equal(writes()[1].body.expectedModelMappingRevision, 3)
    assert.equal(mapping('antigravity', 'gemini-3-pro').publicModelId, 'a/draft-before-sync')
    assert.deepEqual(account(mock.fixture, IDS.quotaError).models, ['gemini-3-pro'])
  })

  it('detects same-row mode-only B change through mapping GET even if public ID stayed equal, and keeps conflict across repeated refresh', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    const model = 'gemini-3-pro'
    await manual(model, 'a/draft')
    const automatic = mapping('antigravity', model).automaticCanonicalModelId
    await mock.fetch(`/admin/api/oauth-accounts/${IDS.quotaError}/settings`, { method: 'PUT', body: JSON.stringify({ modelMappingChanges: [{ upstreamModel: model, mode: 'manual', canonicalModelId: automatic }], expectedModelMappingRevision: 1 }) })
    buttonNamed(dialog(), '刷新共享映射').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.match(text(modelRow(model)), /最新共享值：google\/gemini-3-pro（manual/)
    buttonNamed(dialog(), '刷新共享映射').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, true, 'Repeated GET must not grant overwrite permission')
    assert.equal(writes().length, 1)
    dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    buttonNamed(dialog(), '刷新共享映射').click(); await settle()
    assert.equal(dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').checked, true, 'Identical latest refresh retains explicit confirmation')
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    await mock.fetch(`/admin/api/oauth-accounts/${IDS.quotaError}/settings`, { method: 'PUT', body: JSON.stringify({ modelMappingChanges: [{ upstreamModel: model, mode: 'manual', canonicalModelId: 'b/changed-again' }], expectedModelMappingRevision: 2 }) })
    buttonNamed(dialog(), '刷新共享映射').click(); await settle()
    assert.equal(dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').checked, false, 'New same-row shared change revokes old confirmation')
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
  })

  it('merges revision-only changes to other rows while keeping the unchanged dirty row savable', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-pro', 'a/unchanged-dirty-row')
    await mock.fetch(`/admin/api/oauth-accounts/${IDS.quotaError}/settings`, { method: 'PUT', body: JSON.stringify({ modelMappingChanges: [{ upstreamModel: 'gemini-3-flash', mode: 'manual', canonicalModelId: 'b/other-row' }], expectedModelMappingRevision: 1 }) })
    buttonNamed(dialog(), '刷新共享映射').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    assert.equal(dialog().querySelector('.oauth-settings-notice'), null)
    assert.equal(text(modelRow('gemini-3-flash').querySelector('.oauth-mapping-public > .mono')), 'b/other-row')
    await save()
    assert.equal(writes()[1].body.expectedModelMappingRevision, 2)
    assert.deepEqual(writes()[1].body.modelMappingChanges, [{ upstreamModel: 'gemini-3-pro', mode: 'manual', canonicalModelId: 'a/unchanged-dirty-row' }])
    assert.equal(mapping('antigravity', 'gemini-3-flash').publicModelId, 'b/other-row')
  })

  it('requires confirmation for changed automatic default when the draft explicitly restores auto, but not for unchanged manual overrides', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.automaticMappings['gemini-3-flash'] = 'google/new-automatic-default'
    await mount(fixture)
    await openSettings(IDS.groupedHealthy)
    buttonNamed(modelRow('gemini-3-flash'), '恢复自动').click(); await settle()
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.match(text(modelRow('gemini-3-flash')), /自动默认 google\/new-automatic-default/)
    dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').click(); await settle(); await save()
    assert.equal(mapping('antigravity', 'gemini-3-flash').publicModelId, 'google/new-automatic-default')
  })

  it('keeps unknown mapping draft out of settings changes while account authorization saves, then sync recovery requires review', async () => {
    const fixture = createOAuthAccountsFixture()
    const target = account(fixture, IDS.groupedHealthy)
    target.models = ['gemini-3-pro', 'custom-upstream']
    await mount(fixture)
    await openSettings(target.id)
    await manual('custom-upstream', 'custom/preserved-draft')
    await input(field('账号名称'), '仅账号设置保存')
    assert.match(text(modelRow('custom-upstream')), /无共享映射.*不会提交/)
    await save()
    assert.deepEqual(writes()[0].body, { name: '仅账号设置保存' })
    assert.equal(mapping('antigravity', 'custom-upstream'), undefined)
    assert.ok(dialog())
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input input').value, 'custom/preserved-draft')
    assert.equal(modelRow('custom-upstream').querySelector('input[type="checkbox"]').checked, true)
    mock.fixture.syncCatalogs[target.id] = ['gemini-3-pro', 'custom-upstream']
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.ok(mapping('antigravity', 'custom-upstream'))
    assert.equal(buttonNamed(dialog(), '保存').disabled, true, 'Restored shared row must be reviewed before submitting old draft')
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input input').value, 'custom/preserved-draft')
    dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').click(); await settle(); await save()
    assert.equal(mapping('antigravity', 'custom-upstream').publicModelId, 'custom/preserved-draft')
  })

  it('keeps an unsubmitted unknown draft when another account sync restores it during the successful PUT refresh', async () => {
    const fixture = createOAuthAccountsFixture()
    const target = account(fixture, IDS.groupedHealthy)
    target.models = ['gemini-3-pro', 'custom-upstream']
    fixture.syncCatalogs[IDS.quotaError] = ['gemini-3-pro', 'custom-upstream']
    await mount(fixture)
    await openSettings(target.id)
    await manual('custom-upstream', 'custom/unsubmitted-draft')
    await input(field('账号名称'), '成功保存但保留映射草稿')
    const release = gateFetch((path, init) => path === `/admin/api/oauth-accounts/${target.id}/settings` && init.method === 'PUT')
    buttonNamed(dialog(), '保存').click(); await settle()
    assert.deepEqual(writes()[0].body, { name: '成功保存但保留映射草稿' }, 'Unknown mapping is excluded from the atomic save snapshot')
    assert.equal(mapping('antigravity', 'custom-upstream'), undefined)
    await mock.fetch(`/admin/api/oauth-accounts/${IDS.quotaError}/models/refresh`, { method: 'POST' })
    const restored = mapping('antigravity', 'custom-upstream')
    assert.ok(restored)
    globalThis.fetch = window.fetch = mock.fetch
    release(); await settle(); await settle()
    assert.ok(dialog(), 'Successful refresh must not close a modal containing unsubmitted mapping drafts')
    assert.equal(field('账号名称').value, '成功保存但保留映射草稿')
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input input').value, 'custom/unsubmitted-draft')
    assert.equal(modelRow('custom-upstream').querySelector('input[type="checkbox"]').checked, true)
    assert.match(text(modelRow('custom-upstream')), /最新共享值：catalog\/custom-upstream/)
    assert.match(text(dialog()), /账号设置与授权已保存成功.*未提交的映射草稿待核对/)
    assert.doesNotMatch(text(dialog()), /保存失败|刷新失败/)
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.equal(writes().length, 1)
    dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').click(); await settle(); await save()
    assert.equal(dialog(), null)
    assert.equal(writes().length, 2)
    assert.deepEqual(writes()[1].body, {
      modelMappingChanges: [{ upstreamModel: 'custom-upstream', mode: 'manual', canonicalModelId: 'custom/unsubmitted-draft' }],
      expectedModelMappingRevision: 2,
    }, 'Confirmation submits only the previously unsubmitted mapping, not the saved account fields')
    assert.equal(mapping('antigravity', 'custom-upstream').publicModelId, 'custom/unsubmitted-draft')
  })

  it('retains a disappeared auto-row draft and authorization, permits discarding mapping only and never creates it through settings', async () => {
    const fixture = createOAuthAccountsFixture()
    const target = account(fixture, IDS.groupedHealthy)
    for (const item of fixture.accounts.filter(item => item.provider === 'antigravity')) item.modelCatalog = ['gemini-3-pro']
    target.modelCatalog = ['gemini-3-pro', 'claude-sonnet-4-5']
    target.models = ['gemini-3-pro', 'claude-sonnet-4-5']
    fixture.syncCatalogs[target.id] = ['gemini-3-pro']
    await mount(fixture)
    await openSettings(target.id)
    await manual('claude-sonnet-4-5', 'removed/auto-draft')
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.equal(mapping('antigravity', 'claude-sonnet-4-5'), undefined)
    assert.equal(modelRow('claude-sonnet-4-5').querySelector('.oauth-mapping-input input').value, 'removed/auto-draft')
    assert.equal(modelRow('claude-sonnet-4-5').querySelector('input[type="checkbox"]').checked, true)
    assert.match(text(modelRow('claude-sonnet-4-5')), /无共享映射/)
    buttonNamed(modelRow('claude-sonnet-4-5'), '丢弃此映射草稿').click(); await settle()
    assert.equal(modelRow('claude-sonnet-4-5').querySelector('input[type="checkbox"]').checked, true)
    assert.equal(modelRow('claude-sonnet-4-5').querySelector('.oauth-mapping-input'), null)
    await input(field('账号名称'), '保存授权但不新建共享行')
    await save()
    assert.deepEqual(writes()[0].body, { name: '保存授权但不新建共享行' })
    assert.equal(mapping('antigravity', 'claude-sonnet-4-5'), undefined)
  })

  it('sync-conflict fixture control simulates B changing the row on sync without native network', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.syncConflict = { upstreamModel: 'gemini-3-pro', canonicalModelId: 'other-account/sync-public-id' }
    await mount(fixture)
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-pro', 'a/gui-draft')
    buttonNamed(dialog(), '同步模型').click(); await settle(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.match(text(modelRow('gemini-3-pro')), /最新共享值：other-account\/sync-public-id/)
    assert.equal(writes().length, 0)
  })

  it('keeps sync and save mutually exclusive while either request is pending', async () => {
    await mount()
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-pro', 'pending/save')
    let release = gateFetch((path, init) => init.method === 'PUT')
    buttonNamed(dialog(), '保存').click(); await settle()
    assert.equal(buttonNamed(dialog(), '同步模型').disabled, true)
    assert.equal(buttonNamed(dialog(), '保存中…').disabled, true)
    release(); await settle(); await settle()
    globalThis.fetch = window.fetch = mock.fetch
    await openSettings(IDS.groupedHealthy)
    release = gateFetch(path => path.endsWith('/models/refresh'))
    buttonNamed(dialog(), '同步模型').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    assert.equal(buttonNamed(dialog(), '同步中…').disabled, true)
    release(); await settle(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
  })

  it('keeps drafts after 409 atomic rollback, shows latest baseline and requires manual conflict confirmation before a new save', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.conflictNextSave = true
    await mount(fixture)
    const target = account(mock.fixture, IDS.quotaError)
    const originalName = target.name
    const originalModels = [...target.models]
    await openSettings(target.id)
    await input(field('账号名称'), '未保存冲突名称')
    modelRow('gemini-3-flash').querySelector('input[type="checkbox"]').click(); await settle()
    await manual('gemini-3-pro', 'my/conflict-draft')
    await save()
    assert.equal(writes().length, 1, 'Never auto-retry stale revisions')
    assert.equal(target.name, originalName)
    assert.deepEqual(target.models, originalModels)
    assert.equal(field('账号名称').value, '未保存冲突名称')
    assert.equal(modelRow('gemini-3-flash').querySelector('input[type="checkbox"]').checked, true)
    assert.equal(modelRow('gemini-3-pro').querySelector('.oauth-mapping-input input').value, 'my/conflict-draft')
    assert.match(text(dialog()), /版本冲突.*均未保存.*保留全部草稿/)
    assert.match(text(modelRow('gemini-3-pro')), /最新共享值：other-admin\/new-public-id/)
    assert.equal(buttonNamed(dialog(), '保存').disabled, true)
    dialog().querySelector('.oauth-settings-notice input[type="checkbox"]').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    await save()
    assert.equal(writes().length, 2)
    assert.equal(writes()[1].body.expectedModelMappingRevision, 2)
    assert.equal(target.name, '未保存冲突名称')
    assert.equal(mapping('antigravity', 'gemini-3-pro').publicModelId, 'my/conflict-draft')
  })

  it('allows manual mapping save when candidate fetch fails, includes custom draft candidates and validates trim length before PUT', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.failSuggestions = true
    await mount(fixture)
    await openSettings(IDS.groupedHealthy)
    assert.match(text(dialog()), /目录候选读取失败.*仍可手工输入并保存/)
    await manual('gemini-3-pro', '   ')
    await save()
    assert.equal(writes().length, 0)
    assert.match(text(host.querySelector('.toast-stack')), /1\.\.200/)
    await input(modelRow('gemini-3-pro').querySelector('.oauth-mapping-input input'), 'x'.repeat(201))
    await save(); assert.equal(writes().length, 0)
    await input(modelRow('gemini-3-pro').querySelector('.oauth-mapping-input input'), ' hand/custom ')
    modelRow('gemini-3-pro').querySelector('.search-select-trigger').click(); await settle()
    assert.match(text(document.querySelector('.search-select-panel')), /hand\/custom.*当前值 \/ 草稿/)
    const custom = [...document.querySelectorAll('.search-select-panel [role="option"]')].find(item => text(item.querySelector('strong')) === 'hand/custom')
    custom.click(); await settle(); await save()
    assert.equal(writes().length, 1)
    assert.equal(writes()[0].body.modelMappingChanges[0].canonicalModelId, 'hand/custom')
  })

  it('mapping GET failure without revision does not block unrelated account settings/authorization save', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.failMappingReads = 1
    await mount(fixture)
    await openSettings(IDS.quotaError)
    assert.match(text(dialog()), /共享映射读取失败.*账号设置与授权仍可保存/)
    await input(field('账号名称'), '仅账号设置仍可保存')
    modelRow('gemini-3-flash').querySelector('input[type="checkbox"]').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    await save()
    assert.deepEqual(writes()[0].body, { name: '仅账号设置仍可保存', models: ['gemini-3-flash', 'gemini-3-pro'] })
    assert.equal(dialog(), null)
  })

  it('requires review when a dirty draft first receives a trusted baseline after initial mapping GET failure, without auto retry', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.failMappingReads = 1
    const model = 'gemini-3-pro'
    const latest = fixture.providerMappings.antigravity.mappings.find(item => item.upstreamModel === model)
    Object.assign(latest, { mode: 'manual', canonicalModelId: 'other-admin/latest-pro', publicModelId: 'other-admin/latest-pro', mappingSource: 'manual' })
    fixture.providerMappings.antigravity.revision = 7
    await mount(fixture)
    await openSettings(IDS.quotaError)
    assert.match(text(dialog()), /共享映射读取失败/)
    await input(field('账号名称'), '首次可信版本前的账号草稿')
    await manual(model, 'local/draft-before-baseline')
    assert.equal(modelRow(model).querySelector('input[type="checkbox"]').checked, true)
    assert.equal(buttonNamed(dialog(), '保存').disabled, true, 'A draft without a trusted revision cannot be submitted')
    buttonNamed(dialog(), '重新读取共享映射').click(); await settle()
    assert.equal(dialog().querySelector('.oauth-mapping-error'), null)
    assert.match(text(dialog()), /版本 7/)
    assert.equal(field('账号名称').value, '首次可信版本前的账号草稿')
    assert.equal(modelRow(model).querySelector('.oauth-mapping-input input').value, 'local/draft-before-baseline')
    assert.equal(writes().length, 0, 'Successful GET must not automatically retry the old mapping draft')
    assert.equal(buttonNamed(dialog(), '保存').disabled, true, 'First trusted baseline must not grant overwrite permission to an unreviewed dirty draft')
    assert.match(text(modelRow(model)), /最新共享值：other-admin\/latest-pro（manual；来源 manual/)
    assert.match(text(dialog()), /草稿已保留.*核对最新值.*管理员确认后才可保存/)
    const confirmation = dialog().querySelector('.oauth-settings-notice input[type="checkbox"]')
    assert.ok(confirmation)
    assert.equal(confirmation.checked, false)
    buttonNamed(dialog(), '刷新共享映射').click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, true, 'Repeated GET is not administrator confirmation')
    await save()
    assert.equal(writes().length, 0, 'Even clicking save cannot attach the new revision to the unconfirmed old draft')
    assert.equal(mapping('antigravity', model).publicModelId, 'other-admin/latest-pro')
    assert.equal(account(mock.fixture, IDS.quotaError).name, account(createOAuthAccountsFixture(), IDS.quotaError).name)
    confirmation.click(); await settle()
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    await save()
    assert.equal(dialog(), null)
    assert.deepEqual(writes().map(request => [request.path, request.body]), [[
      `/admin/api/oauth-accounts/${IDS.quotaError}/settings`, {
        name: '首次可信版本前的账号草稿',
        modelMappingChanges: [{ upstreamModel: model, mode: 'manual', canonicalModelId: 'local/draft-before-baseline' }],
        expectedModelMappingRevision: 7,
      },
    ]])
    assert.equal(mapping('antigravity', model).publicModelId, 'local/draft-before-baseline')
    assert.deepEqual(account(mock.fixture, IDS.quotaError).models, ['gemini-3-pro'])
  })

  it('keeps unknown dirty mappings invalid rather than conflicted on the first trusted GET and still saves account settings', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.failMappingReads = 1
    const target = account(fixture, IDS.quotaError)
    target.models.push('custom-upstream')
    await mount(fixture)
    await openSettings(target.id)
    await manual('custom-upstream', 'custom/untrusted-draft')
    await input(field('账号名称'), '首次读取后仅保存账号设置')
    buttonNamed(dialog(), '重新读取共享映射').click(); await settle()
    assert.match(text(modelRow('custom-upstream')), /无共享映射.*此草稿不会提交/)
    assert.equal(dialog().querySelector('.oauth-settings-notice input[type="checkbox"]'), null)
    assert.equal(buttonNamed(dialog(), '保存').disabled, false)
    await save()
    assert.deepEqual(writes().map(request => request.body), [{ name: '首次读取后仅保存账号设置' }])
    assert.equal(mapping('antigravity', 'custom-upstream'), undefined)
    assert.equal(modelRow('custom-upstream').querySelector('.oauth-mapping-input input').value, 'custom/untrusted-draft')
    assert.equal(modelRow('custom-upstream').querySelector('input[type="checkbox"]').checked, true)
  })

  it('reports save success separately from failed baseline refresh and never resubmits saved changes', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.controls.failMappingAfterSave = true
    await mount(fixture)
    await openSettings(IDS.groupedHealthy)
    await manual('gemini-3-pro', 'persisted/refresh-failed')
    await save()
    assert.equal(writes().length, 1)
    assert.equal(mapping('antigravity', 'gemini-3-pro').publicModelId, 'persisted/refresh-failed')
    assert.ok(dialog())
    assert.match(text(dialog()), /已保存成功，但刷新失败.*不要重复提交/)
    assert.doesNotMatch(text(dialog()), /本次.*均未保存/)
    assert.match(text(host.querySelector('.toast-stack')), /已保存成功，但刷新失败/)
    buttonNamed(dialog(), '重新读取共享映射').click(); await settle()
    await save()
    assert.equal(writes().length, 1, 'Saved mapping must not become a dirty retry')
    assert.equal(dialog(), null)
  })

  it('only uses compatibility channel PUT when a non-managed channel selection changes, avoiding empty settings writes', async () => {
    const fixture = createOAuthAccountsFixture()
    fixture.providers.find(item => item.id === 'chatgpt').managedChannel = false
    await mount(fixture)
    await openSettings(IDS.chatgptWarning)
    dialog().querySelector('input[value="fixture-secondary-channel"]').click(); await settle()
    await save()
    assert.deepEqual(writes().map(request => [request.path, request.body]), [[
      `/admin/api/oauth-accounts/${IDS.chatgptWarning}/channels`, { channelIds: ['fixture-chatgpt-channel', 'fixture-secondary-channel'] },
    ]])
    await openSettings(IDS.chatgptWarning); await save()
    assert.equal(writes().length, 1, 'Unchanged settings and channels are omitted')
  })

  it('renders grouped real menu actions and clears cooldown through the in-memory API', async () => {
    await mount()
    const panel = await openMenu(IDS.groupedWarning)
    assert.equal(panel.getAttribute('role'), 'menu')
    assert.deepEqual([...panel.querySelectorAll('[role="heading"]')].map(text), ['日常运维', '配置管理', '危险操作'])
    assert.equal(panel.querySelectorAll('[role="separator"]').length, 2)
    assert.deepEqual([...panel.querySelectorAll('[role="menuitem"]')].map(text), ['刷新 token', '刷新额度', '解除冷却', '调度策略', '重新授权', '删除账号'])
    assert.equal(buttonNamed(panel, '删除账号').classList.contains('danger'), true)
    buttonNamed(panel, '解除冷却').click()
    await settle()
    assert.equal(document.querySelector('.floating-menu'), null)
    const requests = mock.requests.filter(request => request.method === 'POST')
    assert.deepEqual(requests.map(request => request.path), [`/admin/api/oauth-accounts/${IDS.groupedWarning}/cooldown/clear`])
    assert.equal(account(mock.fixture, IDS.groupedWarning).runtime.cooldownUntil, null)
    assert.equal(account(mock.fixture, IDS.groupedWarning).runtime.consecutiveRateLimits, 0)
    assert.doesNotMatch(text(row(IDS.groupedWarning)), /冷却剩余/)
    assert.deepEqual(stats(), [9, 7, 2, 0])
    assert.match(text(host.querySelector('.toast-stack')), /账号冷却已解除/)
    const reopened = await openMenu(IDS.groupedWarning)
    assert.equal([...reopened.querySelectorAll('[role="menuitem"]')].some(item => text(item) === '解除冷却'), false)
  })

  it('keeps deletion confirmation: cancel makes no request and only confirm removes the account', async () => {
    await mount()
    const confirmations = []
    let confirmed = false
    window.confirm = message => { confirmations.push(message); return confirmed }
    let panel = await openMenu(IDS.groupedHealthy)
    buttonNamed(panel, '删除账号').click()
    await settle()
    assert.equal(confirmations.length, 1)
    assert.match(confirmations[0], /删除订阅账号「多家族额度演示」/)
    assert.equal(mock.requests.filter(request => request.method === 'DELETE').length, 0)
    assert.ok(account(mock.fixture, IDS.groupedHealthy))
    assert.equal(rows().length, 9)
    assert.deepEqual(stats(), [9, 7, 2, 1])
    confirmed = true
    panel = await openMenu(IDS.groupedHealthy)
    buttonNamed(panel, '删除账号').click()
    await settle()
    assert.equal(confirmations.length, 2)
    assert.deepEqual(mock.requests.filter(request => request.method === 'DELETE').map(request => request.path), [`/admin/api/oauth-accounts/${IDS.groupedHealthy}`])
    assert.equal(account(mock.fixture, IDS.groupedHealthy), undefined)
    assert.equal(host.querySelector(`tr[data-account-id="${IDS.groupedHealthy}"]`), null)
    assert.equal(rows().length, 8)
    assert.deepEqual(stats(), [8, 6, 2, 1])
    assert.match(text(host.querySelector('.toast-stack')), /订阅账号已删除/)
  })

  it('enables and disables a REAUTH account through mock settings without clearing credential failure', async () => {
    await mount()
    const id = IDS.disabledReauth
    const expectedError = account(mock.fixture, id).lastRefreshError
    for (const enabled of [true, false]) {
      row(id).querySelector('[role="switch"]').click()
      await settle()
      const updated = account(mock.fixture, id)
      assert.equal(updated.enabled, enabled)
      assert.equal(updated.authState, 'REAUTH_REQUIRED')
      assert.equal(updated.lastRefreshError, expectedError)
      assert.equal(row(id).querySelector('[role="switch"]').getAttribute('aria-checked'), String(enabled))
      assert.equal(text(row(id).querySelector('.oauth-auth-state .badge')), '需重新授权')
      assert.equal(text(row(id).querySelector('.oauth-refresh-error')), expectedError)
      assert.ok(row(id).querySelector('.oauth-auth-state .badge').classList.contains('danger'))
      assert.deepEqual(stats(), [9, 7, 2, 1])
    }
    const writes = mock.requests.filter(request => request.method === 'PUT')
    assert.deepEqual(writes.map(request => [request.path, request.body]), [
      [`/admin/api/oauth-accounts/${id}/settings`, { enabled: true }],
      [`/admin/api/oauth-accounts/${id}/settings`, { enabled: false }],
    ])
    stat('需重新授权').click()
    await settle()
    assertRows([IDS.disabledReauth, IDS.reauth])
  })
})

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
const { createApp, h, nextTick, ssrContextKey } = vueRuntime
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
  // Current template has TypeScript non-null expressions; strip types from compiler output,
  // not source, so this remains the actual template rather than a handwritten test replica.
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
const account = (fixture, id) => fixture.accounts.find(item => item.id === id)

describe('OAuthAccountsView mounted subscription-account dashboard', () => {
  let server
  let view
  let toastStack
  let toasts
  let app
  let host
  let mock
  const pendingLoadReleases = new Set()

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
  async function mount(fixture = createOAuthAccountsFixture(), { deferLoad = false, failLoad = false } = {}) {
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
    app = createApp({ render: () => h('div', [h(view), h(toastStack)]) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
    if (!deferLoad) assert.equal(host.querySelector('.loading-state'), null)
    assert.deepEqual(mock.requests.map(request => [request.method, request.path]), [
      ['GET', '/admin/api/oauth-accounts'],
      ['GET', '/admin/api/oauth-providers'],
      ['GET', '/admin/api/channels'],
    ])
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
    }
    view = await loadClientComponent(server, 'OAuthAccountsView', children)
    toastStack = await loadClientComponent(server, 'ToastStack')
    toasts = (await server.ssrLoadModule('/src/toast.ts')).toasts
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

  it('renders grouped real menu actions and clears cooldown through the in-memory API', async () => {
    await mount()
    const panel = await openMenu(IDS.groupedWarning)
    assert.equal(panel.getAttribute('role'), 'menu')
    assert.deepEqual([...panel.querySelectorAll('[role="heading"]')].map(text), ['日常运维', '配置管理', '危险操作'])
    assert.equal(panel.querySelectorAll('[role="separator"]').length, 2)
    assert.deepEqual([...panel.querySelectorAll('[role="menuitem"]')].map(text), ['刷新 token', '刷新额度', '解除冷却', '账号设置', '调度策略', '重新授权', '删除账号'])
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

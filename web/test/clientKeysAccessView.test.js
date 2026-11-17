import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLButtonElement', 'HTMLInputElement', 'HTMLSelectElement', 'HTMLIFrameElement', 'SVGElement', 'MutationObserver', 'Event', 'MouseEvent', 'KeyboardEvent', 'CustomEvent']) globalThis[name] = dom.window[name]
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
const originalFetch = globalThis.fetch
const policy = {
  channels: { mode: 'allowlist', ids: ['channel-a', 'channel-b'] },
  channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }, { channelId: 'channel-b', keyId: 'key-b' }] },
  models: { mode: 'allowlist', ids: ['Model-A', 'model-b'] },
}
const record = () => ({
  id: 'client-1', name: '测试调用方', keyPrefix: 'prism_example', enabled: true, expiresAt: null,
  dailyTokenLimit: null, dailyUsdLimit: null, maxConcurrentRequests: null, requestsPerMinute: null,
  quotaUsage: { dailyTokens: 0, dailyUsdCost: '0', concurrentRequests: 0, currentMinuteRequests: 0 },
  createdAt: 1700000000000, rotatedAt: null, revokedAt: null, accessPolicy: structuredClone(policy),
})
const catalog = [
  { id: 'channel-a', name: '渠道 A', enabled: true, keys: [{ id: 'key-a', name: '凭据 A', state: 'ACTIVE', credentialSet: true, models: ['upstream-a'] }], models: ['upstream-a'], modelMappings: [{ upstreamModel: 'upstream-a', canonicalModelId: 'Model-A' }] },
  { id: 'channel-b', name: '渠道 B', enabled: true, keys: [{ id: 'key-b', name: '凭据 B', state: 'ACTIVE', credentialSet: true, models: ['upstream-b'] }], models: ['upstream-b'], modelMappings: [{ upstreamModel: 'upstream-b', canonicalModelId: 'model-b' }] },
]
for (const channel of catalog) {
  Object.assign(channel, { baseUrl: 'https://fixture.invalid', authType: 'bearer', protocolAuthTypes: {}, protocols: ['chat_completions'], credentialSet: true, createdAt: 1, updatedAt: 1, priority: 0, kind: 'custom', provider: null, requestOverrides: { inheritGlobal: true, headerProfile: null, userAgent: null, headers: {}, body: {}, protocols: {} }, quota: { providerId: '', config: {} } })
  channel.modelMappings.forEach(mapping => { mapping.mappingSource = null })
  channel.keys.forEach(key => Object.assign(key, { priority: 0, rateLimit: { enabled: false, requestsPerMinute: null, maxConcurrentRequests: null }, stateVersion: 0, autoDisableUntil: null, probeUntil: null, autoDisableReason: null }))
}

const modelRoutes = {
  models: [
    { modelId: 'Model-A', revision: 1, entries: [{ channelId: 'channel-a', credentialType: 'static', credentialId: 'key-a', selectable: true, reason: null }] },
    { modelId: 'model-b', revision: 1, entries: [{ channelId: 'channel-b', credentialType: 'static', credentialId: 'key-b', selectable: true, reason: null }] },
  ],
}

describe('真实调用方密钥视图访问范围行为', () => {
  let server, view, app, saved, writes, failSave, calls
  async function settle() { await nextTick(); await delay(45); await nextTick() }
  async function component(name, overrides = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return { ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: { ...headless, ...overrides }, setup(props, context) { return { ...loaded.setup(props, context), ...overrides } } }
  }
  before(async () => {
    server = await createServer({ root: new URL('..', import.meta.url).pathname.replace(/^\/(\w:)/, '$1'), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const icon = await component('Icon')
    const overrides = { Icon: icon }
    for (const name of ['Modal', 'CopyButton', 'ActionMenu', 'TablePlaceholder', 'ErrorState']) overrides[name] = await component(name, { Icon: icon })
    for (const name of ['AccessModeToggle', 'ClientKeyAccessTree']) overrides[name] = await component(name, { Icon: icon })
    if (existsSync(new URL('../src/components/ClientKeyAccessFields.vue', import.meta.url))) {
      overrides.ClientKeyAccessFields = await component('ClientKeyAccessFields', {
        Icon: icon, CopyButton: overrides.CopyButton,
        AccessModeToggle: overrides.AccessModeToggle, ClientKeyAccessTree: overrides.ClientKeyAccessTree,
      })
    }
    view = await component('ClientKeysView', overrides)
  })
  afterEach(async () => { app?.unmount(); app = null; await settle(); document.body.innerHTML = ''; globalThis.fetch = originalFetch })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount() {
    saved = record(); writes = []; calls = []; failSave = false
    globalThis.fetch = async (url, options = {}) => {
      calls.push(url)
      if (url === '/admin/api/occupancy') return new Response(JSON.stringify({ sampledAt: 1, channels: [], clientKeys: [] }))
      if (url === '/admin/api/channels') return new Response(JSON.stringify(catalog))
      if (url === '/admin/api/model-routes/options') return new Response(JSON.stringify(modelRoutes))
      if (url === '/admin/api/client-keys' && options.method === 'GET') return new Response(JSON.stringify([saved]))
      if (url === '/admin/api/client-keys/client-1' || (url === '/admin/api/client-keys' && options.method === 'POST')) {
        const payload = JSON.parse(options.body); writes.push(payload)
        if (failSave) return new Response(JSON.stringify({ error: { message: '保存失败，请重试' } }), { status: 400 })
        saved = { ...saved, ...payload, accessPolicy: payload.accessPolicy ?? saved.accessPolicy }
        return new Response(JSON.stringify({ ...saved, key: 'prism_fixture_only' }))
      }
      throw new Error('非预期请求 ' + url)
    }
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(view)
    app.provide(ssrContextKey, { modules: new Set() })
    app.directive('scroll-hint', (await server.ssrLoadModule('/src/directives/scrollHint.ts')).vScrollHint)
    app.mount(host); await settle()
  }
  function button(text) { return [...document.querySelectorAll('button')].find(node => node.textContent.trim() === text) }
  async function edit() { document.querySelector('button[aria-label="编辑调用方密钥"]').click(); await settle() }
  async function pickMode(groupLabel, value) {
    const group = document.querySelector('.modal [role="radiogroup"][aria-label="' + groupLabel + '"]')
    assert.ok(group, '缺少分段控制器：' + groupLabel)
    const input = [...group.querySelectorAll('input[type="radio"]')].find(node => node.value === value)
    assert.ok(input, '缺少模式选项：' + value)
    input.click(); await settle()
  }
  const cardMode = value => pickMode('渠道与密钥模式', value)
  const modelMode = value => pickMode('模型模式', value)
  function channelRow(name) {
    const row = [...document.querySelectorAll('.modal .tree-row.is-channel')].find(node => node.textContent.includes(name))
    assert.ok(row, '缺少渠道行：' + name)
    return row
  }
  async function chooseChannel(name) { channelRow(name).querySelector('input[type="checkbox"]').click(); await settle() }
  async function expandAll() {
    const trigger = [...document.querySelectorAll('.modal .tree-action')].find(node => /展开全部|收起全部/.test(node.textContent))
    assert.ok(trigger, '缺少展开全部按钮')
    trigger.click(); await settle()
  }
  async function chooseModel(id) {
    const row = [...document.querySelectorAll('.modal .access-option')].find(node => node.textContent.includes(id))
    assert.ok(row, '缺少模型行：' + id)
    row.querySelector('input').click(); await settle()
  }
  const checkedCount = () => document.querySelectorAll('.modal fieldset input[type="checkbox"]:checked').length

  it('列表展示范围摘要，打开编辑表单后加载一次目录', async () => {
    await mount()
    assert.match(document.querySelector('tbody').textContent, /渠道.*2.*模型.*2/)
    assert.equal(calls.includes('/admin/api/channels'), false)
    await edit()
    assert.equal(calls.filter(url => url === '/admin/api/channels').length, 1)
  })

  it('编辑回显树形勾选，保存完整范围和原有额度字段', async () => {
    await mount(); await edit(); await expandAll()
    assert.equal(document.querySelectorAll('.modal select').length, 0)
    assert.equal(checkedCount(), 6)
    assert.equal(document.querySelectorAll('.modal [role="radiogroup"]').length, 2)
    assert.match(document.querySelector('.modal').textContent, /最终可调用的模型与密钥取以上两个维度的交集/)
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[0].accessPolicy, policy)
    assert.equal(writes[0].enabled, true)
    assert.equal(writes[0].dailyTokenLimit, null)
  })

  it('显式切换不限制时提交空数组，不通过省略字段清除访问范围', async () => {
    await mount(); await edit()
    await cardMode('unrestricted'); await modelMode('unrestricted')
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[0].accessPolicy, {
      channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'unrestricted', keys: [] }, models: { mode: 'unrestricted', ids: [] },
    })
  })

  it('切换合并卡片与模型为黑名单后保存完整排除项，失败重试及启停均保留', async () => {
    await mount(); await edit()
    await cardMode('denylist'); await modelMode('denylist')
    const expected = structuredClone(policy)
    for (const scope of Object.values(expected)) scope.mode = 'denylist'
    failSave = true
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[0].accessPolicy, expected)
    assert.equal(checkedCount(), 4)
    failSave = false
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[1].accessPolicy, expected)
    assert.match(document.querySelector('tbody').textContent, /黑名单|排除|禁止/)
    button('停用').click(); await settle()
    assert.deepEqual(saved.accessPolicy, expected)
    await edit()
    assert.equal(checkedCount(), 4)
  })

  it('保存失败保留表单和选择，重试仍提交原有授权', async () => {
    await mount(); await edit(); failSave = true
    button('保存修改').click(); await settle()
    assert.equal(checkedCount(), 4)
    assert.deepEqual(writes[0].accessPolicy, policy)
    failSave = false
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[1].accessPolicy, policy)
    assert.deepEqual(saved.accessPolicy, policy)
  })

  it('现有启停操作保留已保存授权，重新编辑仍回显原白名单', async () => {
    await mount(); button('停用').click(); await settle()
    assert.equal(saved.enabled, false)
    assert.deepEqual(saved.accessPolicy, policy)
    await edit()
    assert.equal(checkedCount(), 4)
  })

  it('创建时空模型白名单拦截保存，选择模型后可创建并显示一次性密钥', async () => {
    await mount(); button('新建密钥').click(); await settle()
    const name = document.querySelector('.modal input[autocomplete="off"]')
    name.value = '新调用方'; name.dispatchEvent(new Event('input', { bubbles: true }))
    await modelMode('allowlist')
    assert.match(document.querySelector('.modal').textContent, /模型白名单不能为空/)
    assert.equal(button('创建密钥').disabled, true)
    assert.match(document.querySelector('.modal').textContent, /存在未勾选的空名单，请先完成选择/)
    await chooseModel('Model-A')
    assert.equal(button('创建密钥').disabled, false)
    button('创建密钥').click(); await settle()
    assert.deepEqual(writes[0].accessPolicy, {
      channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'unrestricted', keys: [] }, models: { mode: 'allowlist', ids: ['Model-A'] },
    })
    assert.match(document.querySelector('.secret-value').textContent, /prism_fixture_only/)
  })

  it('父渠道级联取消同时收窄渠道与子密钥，全部清空后禁用保存', async () => {
    await mount(); await edit(); await expandAll()
    assert.equal(button('保存修改').disabled, false)
    await chooseChannel('渠道 B')
    assert.equal(writes.length, 0)
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[0].accessPolicy, {
      channels: { mode: 'allowlist', ids: ['channel-a'] },
      channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-a', keyId: 'key-a' }] },
      models: { mode: 'allowlist', ids: ['Model-A', 'model-b'] },
    })
    await edit(); await expandAll()
    await chooseChannel('渠道 A')
    assert.equal(button('保存修改').disabled, true)
    assert.match(document.querySelector('.modal').textContent, /存在未勾选的空名单，请先完成选择/)
    assert.match(document.querySelector('.modal').textContent, /白名单模式下请至少勾选 1 个渠道或密钥/)
    button('保存修改').click(); await settle()
    assert.equal(writes.length, 1)
    await cardMode('unrestricted')
    assert.equal(button('保存修改').disabled, false)
    button('保存修改').click(); await settle()
    assert.deepEqual(writes[1].accessPolicy.channels.mode, 'unrestricted')
  })
})

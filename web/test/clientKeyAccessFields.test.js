import { after, afterEach, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'HTMLInputElement', 'HTMLSelectElement', 'SVGElement', 'Event']) globalThis[name] = dom.window[name]
const { compile, createApp, h, nextTick, ref, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const originalFetch = globalThis.fetch
const unrestricted = () => ({ channels: { mode: 'unrestricted', ids: [] }, channelKeys: { mode: 'unrestricted', keys: [] }, models: { mode: 'unrestricted', ids: [] } })
const catalog = [
  {
    id: 'channel-a', name: '渠道 A', enabled: true,
    keys: [
      { id: 'key-1', name: '静态凭据 A', state: 'ACTIVE', credentialSet: true, models: ['upstream-a'] },
      { id: 'key-2', name: '静态凭据 A2', state: 'ACTIVE', credentialSet: true, models: ['upstream-a'] },
    ],
    models: ['upstream-a'],
    modelMappings: [{ upstreamModel: 'upstream-a', canonicalModelId: 'Model-A' }],
    oauthAccounts: [{ id: 'oauth-1', name: 'oauth-a@example.com', enabled: true, models: ['upstream-a'] }],
  },
  {
    id: 'channel-b', name: '渠道 B', enabled: false,
    keys: [{ id: 'key-1', name: '静态凭据 B', state: 'MANUAL_DISABLED', credentialSet: true, models: ['upstream-b', 'plain-model'] }],
    models: ['upstream-b', 'plain-model'],
    modelMappings: [{ upstreamModel: 'upstream-b', canonicalModelId: 'model-b' }, { upstreamModel: 'plain-model', canonicalModelId: null }],
    oauthAccounts: [],
  },
]
for (const channel of catalog) {
  Object.assign(channel, { baseUrl: 'https://fixture.invalid', authType: 'bearer', protocolAuthTypes: {}, protocols: ['chat_completions'], credentialSet: true, createdAt: 1, updatedAt: 1, priority: 0, kind: 'custom', provider: null, requestOverrides: { inheritGlobal: true, headerProfile: null, userAgent: null, headers: {}, body: {}, protocols: {} }, quota: { providerId: '', config: {} } })
  channel.modelMappings.forEach(mapping => { mapping.mappingSource = null })
  channel.keys.forEach(key => Object.assign(key, { priority: 0, rateLimit: { enabled: false, requestsPerMinute: null, maxConcurrentRequests: null }, stateVersion: 0, autoDisableUntil: null, probeUntil: null, autoDisableReason: null }))
}

const modelRoutes = {
  models: [
    {
      modelId: 'Model-A', revision: 1,
      entries: [
        { channelId: 'channel-a', credentialType: 'static', credentialId: 'key-1', selectable: true, reason: null },
        { channelId: 'channel-a', credentialType: 'static', credentialId: 'key-2', selectable: true, reason: null },
        { channelId: 'channel-a', credentialType: 'oauth', credentialId: 'oauth-1', selectable: true, reason: null },
      ],
    },
    {
      modelId: 'model-b', revision: 1,
      entries: [
        { channelId: 'channel-b', credentialType: 'static', credentialId: 'key-1', selectable: false, reason: 'channel_disabled' },
      ],
    },
  ],
}

const modelRouteDetails = {
  'Model-A': {
    modelId: 'Model-A', revision: 1, configured: true,
    entries: [
      { channelId: 'channel-a', credentialType: 'static', credentialId: 'key-1', channelName: '渠道 A', credentialName: '静态凭据 A', upstreamModels: ['upstream-a'], protocols: ['chat_completions'], selectable: true, reason: null },
      { channelId: 'channel-b', credentialType: 'static', credentialId: 'key-1', channelName: '渠道 B', credentialName: '静态凭据 B', upstreamModels: ['upstream-b'], protocols: ['chat_completions'], selectable: false, reason: 'channel_disabled' },
      { channelId: 'channel-a', credentialType: 'oauth', credentialId: 'oauth-1', channelName: '渠道 A', credentialName: 'oauth-a@example.com', upstreamModels: ['upstream-a'], protocols: ['chat_completions'], selectable: true, reason: null },
    ],
    availableCredentials: [],
  },
}

describe('真实调用方访问范围配置组件', () => {
  let server, parts, app, state, requests
  async function settle() { await nextTick(); await delay(35); await nextTick() }
  async function component(name, overrides = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return { ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: { ...overrides }, setup(props, context) { return { ...loaded.setup(props, context) } } }
  }
  before(async () => {
    server = await createServer({ root: new URL('..', import.meta.url).pathname.replace(/^\/(\w:)/, '$1'), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const icon = await component('Icon')
    parts = { Icon: icon }
    for (const name of ['AccessModeToggle', 'ClientKeyAccessTree', 'CopyButton']) parts[name] = await component(name, { Icon: icon })
  })
  afterEach(() => { app?.unmount(); app = null; document.body.innerHTML = ''; globalThis.fetch = originalFetch })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount(policy = unrestricted(), fetchCatalog = () => catalog, fetchRoutes = () => modelRoutes) {
    requests = []
    globalThis.fetch = async (url) => {
      requests.push(url)
      if (url === '/admin/api/channels') {
        const data = await fetchCatalog()
        return new Response(JSON.stringify(data), { status: 200 })
      }
      if (url === '/admin/api/model-routes/options') {
        const data = await fetchRoutes()
        return new Response(JSON.stringify(data), { status: 200 })
      }
      if (url.startsWith('/admin/api/model-routes/detail?')) {
        const modelId = new URL(url, 'http://localhost').searchParams.get('modelId')
        const detail = modelRouteDetails[modelId]
        if (!detail) return new Response(JSON.stringify({ error: { message: '模型路由不存在' } }), { status: 404 })
        return new Response(JSON.stringify(detail), { status: 200 })
      }
      assert.fail('意外的目录请求：' + url)
    }
    const fields = await component('ClientKeyAccessFields', {
      AccessModeToggle: parts.AccessModeToggle,
      ClientKeyAccessTree: parts.ClientKeyAccessTree,
      CopyButton: parts.CopyButton,
      Icon: parts.Icon,
    })
    state = ref(structuredClone(policy))
    const host = document.createElement('div'); document.body.append(host)
    app = createApp({ render: () => h(fields, { modelValue: state.value, 'onUpdate:modelValue': value => { state.value = value } }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
  }
  const checkedBoxes = () => document.querySelectorAll('input[type="checkbox"]:checked').length
  async function pickMode(groupLabel, value) {
    const group = document.querySelector('[role="radiogroup"][aria-label="' + groupLabel + '"]')
    assert.ok(group, '缺少分段控制器：' + groupLabel)
    const input = [...group.querySelectorAll('input[type="radio"]')].find(node => node.value === value)
    assert.ok(input, '缺少模式选项：' + value)
    input.click(); await settle()
  }
  const cardMode = value => pickMode('渠道与密钥模式', value)
  const modelMode = value => pickMode('模型模式', value)
  function channelRow(name) {
    const row = [...document.querySelectorAll('.tree-row.is-channel')].find(node => node.textContent.includes(name))
    assert.ok(row, '缺少渠道行：' + name)
    return row
  }
  async function chooseChannel(name) { channelRow(name).querySelector('input[type="checkbox"]').click(); await settle() }
  async function expandChannel(name) { channelRow(name).querySelector('.tree-chevron').click(); await settle() }
  async function chooseKey(name, checked) {
    const row = [...document.querySelectorAll('.tree-row.is-key:not(.is-stale)')].find(node => node.textContent.includes(name))
    assert.ok(row, '缺少密钥行：' + name)
    const input = row.querySelector('input[type="checkbox"]')
    if (checked !== undefined) assert.equal(input.checked, checked, '密钥行初始勾选态不符：' + name)
    input.click(); await settle()
  }
  async function chooseModel(id) {
    const row = [...document.querySelectorAll('.access-option')].find(node => node.textContent.includes(id))
    assert.ok(row, '缺少模型行：' + id)
    row.querySelector('input').click(); await settle()
  }
  async function searchTree(text) {
    const input = document.querySelector('input[aria-label="搜索渠道与密钥"]')
    assert.ok(input, '缺少渠道搜索框')
    input.value = text; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  }

  it('默认三维度不限制，打开默认表单时加载目录并渲染统计', async () => {
    await mount()
    assert.equal(requests.length, 2)
    assert.equal(document.querySelectorAll('select').length, 0)
    assert.equal(document.querySelectorAll('[role="radiogroup"]').length, 2)
    assert.match(document.body.textContent, /模型来自已保存的模型路由/)
    assert.match(document.body.textContent, /默认允许所有可用渠道/)
    assert.match(document.body.textContent, /当前生效密钥：2\/2/)
    assert.equal(document.querySelector('.access-tree'), null)
    assert.equal(document.querySelector('[role="radiogroup"][aria-label="渠道与密钥模式"] input[value="unrestricted"]').checked, true)
  })

  it('切换白名单后懒加载目录一次，渠道行渲染短 ID 胶囊与统计', async () => {
    await mount()
    await cardMode('allowlist')
    assert.equal(requests.length, 2)
    const rowA = channelRow('渠道 A')
    const pill = rowA.querySelector('.tree-id-pill')
    assert.match(pill.textContent, /#channe/)
    assert.match(pill.getAttribute('title'), /channel-a/)
    assert.match(channelRow('渠道 B').textContent, /0\/1 密钥/)
    await expandChannel('渠道 A')
    assert.match(document.body.textContent, /OAuth 订阅/)
  })

  it('白名单勾选父渠道即渠道级放行，子密钥随渠道呈勾选态', async () => {
    await mount()
    await cardMode('allowlist')
    await chooseChannel('渠道 A')
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.equal(state.value.channelKeys.mode, 'unrestricted')
    assert.deepEqual(state.value.channelKeys.keys, [])
    await expandChannel('渠道 A')
    const selectedChildren = [...document.querySelectorAll('.tree-row.is-key:not(.is-stale):not(.is-oauth) input')].filter(node => node.checked).length
    assert.equal(selectedChildren, 2)
    assert.match(channelRow('渠道 A').textContent, /全部密钥 \(2\)/)
  })

  it('勾选子密钥自动补渠道并呈半选，父勾选展开其余子密钥', async () => {
    await mount()
    await cardMode('allowlist')
    await expandChannel('渠道 A')
    await chooseKey('静态凭据 A2', false)
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.deepEqual(state.value.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-2' }])
    const parentInput = channelRow('渠道 A').querySelector('input[type="checkbox"]')
    assert.equal(parentInput.indeterminate, true)
    assert.match(channelRow('渠道 A').textContent, /已选 1\/2 密钥/)
    await chooseChannel('渠道 A')
    assert.deepEqual(state.value.channelKeys.keys.map(pair => pair.keyId).sort(), ['key-1', 'key-2'])
    assert.match(channelRow('渠道 A').textContent, /全部密钥 \(2\)/)
    await chooseKey('静态凭据 A2', true)
    assert.deepEqual(state.value.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-1' }])
    assert.equal(channelRow('渠道 A').querySelector('input[type="checkbox"]').indeterminate, true)
    await chooseChannel('渠道 A')
    assert.deepEqual(state.value.channelKeys.keys.map(pair => pair.keyId).sort(), ['key-1', 'key-2'])
    await chooseChannel('渠道 A')
    assert.deepEqual(state.value.channels.ids, [])
    assert.deepEqual(state.value.channelKeys, { mode: 'unrestricted', keys: [] })
    assert.match(document.body.textContent, /至少勾选 1 个渠道或密钥/)
  })

  it('黑名单勾选父渠道覆盖子密钥名单，被排除渠道的子行禁用', async () => {
    await mount()
    await cardMode('denylist')
    await expandChannel('渠道 A')
    await chooseKey('静态凭据 A', false)
    assert.deepEqual(state.value.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-1' }])
    assert.deepEqual(state.value.channels.ids, [])
    await chooseChannel('渠道 A')
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.deepEqual(state.value.channelKeys.keys, [])
    assert.equal(channelRow('渠道 A').querySelector('input[type="checkbox"]').indeterminate, false)
    const disabledRow = [...document.querySelectorAll('.tree-row.is-key:not(.is-stale)')].find(node => node.textContent.includes('静态凭据 A'))
    assert.equal(disabledRow.querySelector('input').disabled, true)
    assert.match(document.body.textContent, /渠道已被整体排除/)
  })

  it('OAuth 行在渠道级白名单下等同勾选渠道，密钥维度启用后仅提示不可勾选', async () => {
    await mount()
    await cardMode('allowlist')
    await expandChannel('渠道 A')
    const oauthRow = [...document.querySelectorAll('.tree-row.is-key.is-oauth')].find(node => node.textContent.includes('oauth-a@example.com'))
    assert.ok(oauthRow, '缺少 OAuth 订阅行')
    oauthRow.querySelector('input[type="checkbox"]').click(); await settle()
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    await chooseKey('静态凭据 A', true)
    assert.notEqual(state.value.channelKeys.mode, 'unrestricted')
    const engagedRow = [...document.querySelectorAll('.tree-row.is-key.is-oauth')].find(node => node.textContent.includes('oauth-a@example.com'))
    assert.equal(engagedRow.querySelector('input[type="checkbox"]'), null)
    assert.match(engagedRow.textContent, /OAuth 账号不参与候选/)
  })

  it('全选与清空保持受限模式，清空后显示空名单拦截', async () => {
    await mount()
    await cardMode('allowlist')
    const selectAll = [...document.querySelectorAll('.tree-action')].find(node => node.textContent.includes('全选'))
    selectAll.click(); await settle()
    assert.deepEqual(state.value.channels.ids, ['channel-a', 'channel-b'])
    assert.equal(state.value.channelKeys.mode, 'unrestricted')
    const clear = [...document.querySelectorAll('.tree-action')].find(node => node.textContent.includes('清空'))
    clear.click(); await settle()
    assert.deepEqual(state.value.channels.ids, [])
    assert.deepEqual(state.value.channelKeys, { mode: 'unrestricted', keys: [] })
    assert.match(document.body.textContent, /白名单模式下请至少勾选 1 个渠道或密钥；如需全部放行请选择「不限制」/)
    assert.equal(state.value.channels.mode, 'allowlist')
  })

  it('搜索命中密钥名时自动展开并只显示匹配渠道', async () => {
    await mount()
    await cardMode('allowlist')
    await searchTree('静态凭据 B')
    const rows = [...document.querySelectorAll('.tree-row.is-channel')]
    assert.equal(rows.length, 1)
    assert.match(rows[0].textContent, /渠道 B/)
    assert.ok([...document.querySelectorAll('.tree-row.is-key:not(.is-stale)')].some(node => node.textContent.includes('静态凭据 B')))
    await searchTree('')
    assert.equal(document.querySelectorAll('.tree-row.is-channel').length, 2)
    assert.equal(requests.length, 2)
  })

  it('模型联动提示随卡片一勾选实时重算', async () => {
    await mount()
    assert.match(document.body.textContent, /基于上方生效的 2 个渠道密钥，当前范围可调用 1 个已配置路由的模型/)
    assert.match(document.body.textContent, /可调用模型池：1 个/)
    await cardMode('denylist')
    await chooseChannel('渠道 A')
    assert.match(document.body.textContent, /基于上方生效的 0 个渠道密钥，当前范围可调用 0 个已配置路由的模型/)
    assert.match(document.body.textContent, /可调用模型池：0 个/)
  })

  it('模型白名单选择后取消渠道触发置灰预警，原选择保留并可取消', async () => {
    await mount()
    await cardMode('allowlist')
    await chooseChannel('渠道 A')
    await modelMode('allowlist')
    await chooseModel('Model-A')
    assert.deepEqual(state.value.models.ids, ['Model-A'])
    assert.match(document.body.textContent, /渠道 A\s*·\s*路由 3 条\s*·\s*当前范围可用 3 个凭据/)
    await chooseChannel('渠道 A')
    assert.deepEqual(state.value.models.ids, ['Model-A'])
    const conflictRow = [...document.querySelectorAll('.access-option')].find(node => node.textContent.includes('Model-A'))
    assert.match(conflictRow.textContent, /当前范围或模型路由没有可用凭据/)
    assert.match(document.body.textContent, /无法路由到这些模型/)
    assert.match(document.body.textContent, /至少勾选 1 个渠道或密钥/)
    conflictRow.querySelector('input').click(); await settle()
    assert.deepEqual(state.value.models.ids, [])
  })

  it('模型选项只来自已保存的模型路由，渠道里未建路由的模型不可选', async () => {
    const data = structuredClone(catalog)
    data[0].modelMappings.push({ upstreamModel: 'no-route', canonicalModelId: 'No-Route', mappingSource: null })
    await mount(unrestricted(), () => data)
    await modelMode('allowlist')
    assert.match(document.body.textContent, /Model-A/)
    assert.doesNotMatch(document.body.textContent, /No-Route/)
  })

  it('没有已保存路由时提示先配置模型路由，且模型池为空', async () => {
    await mount(unrestricted(), () => catalog, () => ({ models: [] }))
    await modelMode('allowlist')
    assert.match(document.body.textContent, /还没有已保存的模型路由/)
    assert.match(document.body.textContent, /可调用模型池：0 个/)
    assert.equal(document.querySelectorAll('.access-option.is-model').length, 0)
  })

  it('已选但未配置路由的模型保留为失效项', async () => {
    const stale = unrestricted()
    stale.models = { mode: 'allowlist', ids: ['Deleted-Model'] }
    await mount(stale)
    assert.match(document.body.textContent, /Deleted-Model/)
    assert.match(document.body.textContent, /未配置模型路由或不存在/)
  })

  it('已保存但空路由的模型标记无可用凭据', async () => {
    const emptyRoute = { models: [{ modelId: 'Model-A', revision: 1, entries: [] }] }
    await mount({ ...unrestricted(), models: { mode: 'allowlist', ids: ['Model-A'] } }, () => catalog, () => emptyRoute)
    const row = [...document.querySelectorAll('.access-option')].find(node => node.textContent.includes('Model-A'))
    assert.ok(row)
    assert.match(row.textContent, /路由 0 条/)
    assert.match(row.textContent, /没有可用凭据/)
  })

  it('展开模型后按保存顺序显示路由凭据与范围过滤结果', async () => {
    await mount({ ...unrestricted(), models: { mode: 'allowlist', ids: ['Model-A'] } })
    const item = [...document.querySelectorAll('.access-model-item')].find(node => node.textContent.includes('Model-A'))
    assert.ok(item)
    const toggle = [...item.querySelectorAll('button')].find(node => node.textContent.includes('查看路由'))
    assert.ok(toggle)
    toggle.click(); await settle()
    const rows = [...item.querySelectorAll('.route-preview-list li')]
    assert.equal(rows.length, 3)
    assert.match(rows[0].textContent, /静态凭据 A/)
    assert.match(rows[0].textContent, /当前范围可用/)
    assert.match(rows[1].textContent, /静态凭据 B/)
    assert.match(rows[1].textContent, /渠道已停用/)
    assert.match(rows[2].textContent, /oauth-a@example.com/)
    assert.match(rows[2].textContent, /当前范围可用/)
    assert.equal(requests.filter(url => url.startsWith('/admin/api/model-routes/detail?')).length, 1)
    toggle.click(); await settle()
    assert.equal(item.querySelector('.route-preview'), null)
  })

  it('渠道范围排除时，预览逐条标注调用方范围拒绝原因', async () => {
    await mount({
      ...unrestricted(),
      channels: { mode: 'denylist', ids: ['channel-a'] },
      models: { mode: 'allowlist', ids: ['Model-A'] },
    })
    const item = [...document.querySelectorAll('.access-model-item')].find(node => node.textContent.includes('Model-A'))
    assert.ok(item)
    const toggle = [...item.querySelectorAll('button')].find(node => node.textContent.includes('查看路由'))
    assert.ok(toggle)
    toggle.click(); await settle()
    const rows = [...item.querySelectorAll('.route-preview-list li')]
    assert.equal(rows.length, 3)
    assert.match(rows[0].textContent, /调用方范围排除了渠道/)
    assert.match(rows[1].textContent, /渠道已停用/)
    assert.match(rows[2].textContent, /调用方范围排除了渠道/)
  })

  it('模型黑名单失效 ID 保留展示，清空后触发空名单拦截', async () => {
    const policy = unrestricted()
    policy.models = { mode: 'denylist', ids: ['deleted-model'] }
    await mount(policy)
    assert.equal(requests.length, 2)
    assert.match(document.body.textContent, /deleted-model/)
    assert.match(document.body.textContent, /可保留.*模型黑名单.*重新配置.*仍禁止/)
    assert.doesNotMatch(document.body.textContent, /模型黑名单不能为空/)
    await chooseModel('deleted-model')
    assert.deepEqual(state.value.models.ids, [])
    assert.match(document.body.textContent, /模型黑名单不能为空/)
    assert.equal(state.value.models.mode, 'denylist')
  })

  it('渠道与密钥混合模式挂载即统一为渠道模式并提示重新确认', async () => {
    await mount({
      channels: { mode: 'allowlist', ids: ['channel-a'] },
      channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-b', keyId: 'key-1' }] },
      models: { mode: 'unrestricted', ids: [] },
    })
    assert.match(document.body.textContent, /已统一为渠道维度模式/)
    assert.equal(state.value.channelKeys.mode, 'unrestricted')
    assert.deepEqual(state.value.channelKeys.keys, [])
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.match(document.body.textContent, /全部密钥 \(2\)/)
  })

  it('目录失败及重试期间保留已有选择，成功后回显原授权', async () => {
    let fail = true
    await mount({ ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a'] } }, () => {
      if (fail) throw new Error('目录失败')
      return catalog
    })
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.match(document.body.textContent, /目录读取失败，保留原选择/)
    assert.match(document.body.textContent, /channel-a/)
    fail = false
    const retry = [...document.querySelectorAll('button')].find(node => node.textContent.includes('重试'))
    assert.ok(retry); retry.click(); await settle()
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.equal(channelRow('渠道 A').querySelector('input[type="checkbox"]').checked, true)
  })

  it('失效渠道取消勾选直接移除该 ID，正常渠道选择不受影响', async () => {
    await mount({ ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a', 'deleted-channel'] } })
    assert.match(document.body.textContent, /已删除或不存在/)
    assert.match(document.body.textContent, /deleted-channel/)
    const staleRow = document.querySelector('.tree-row.is-stale input[aria-label="已失效渠道 deleted-channel"]')
    assert.ok(staleRow, '缺少失效渠道行')
    staleRow.click(); await settle()
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.equal(document.querySelector('.tree-row.is-stale input[aria-label="已失效渠道 deleted-channel"]'), null)
    assert.equal(requests.length, 2)
  })

  it('受限模式往返不限制时保留草稿选择', async () => {
    await mount()
    await cardMode('allowlist')
    await chooseChannel('渠道 A')
    await cardMode('unrestricted')
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.equal(document.querySelector('.access-tree'), null)
    await cardMode('allowlist')
    assert.equal(channelRow('渠道 A').querySelector('input[type="checkbox"]').checked, true)
    assert.equal(requests.length, 2)
  })

  it('渠道选项不回显任何凭据明文', async () => {
    const data = structuredClone(catalog)
    data[0].keys[0].credential = 'UPSTREAM_SECRET_MUST_NOT_RENDER'
    await mount({ ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a'] } }, () => data)
    assert.equal(document.body.innerHTML.includes('UPSTREAM_SECRET_MUST_NOT_RENDER'), false)
    assert.match(document.body.textContent, /渠道停用|密钥停用/)
  })
})

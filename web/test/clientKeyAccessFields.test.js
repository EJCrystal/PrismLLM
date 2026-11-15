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
  { id: 'channel-a', name: '渠道 A', enabled: true, keys: [{ id: 'key-1', name: '静态凭据 A', state: 'ACTIVE', credentialSet: true }], models: ['upstream-a'], modelMappings: [{ upstreamModel: 'upstream-a', canonicalModelId: 'Model-A' }] },
  { id: 'channel-b', name: '渠道 B', enabled: false, keys: [{ id: 'key-1', name: '静态凭据 B', state: 'MANUAL_DISABLED', credentialSet: true }], models: ['upstream-b', 'plain-model'], modelMappings: [{ upstreamModel: 'upstream-b', canonicalModelId: 'model-b' }, { upstreamModel: 'plain-model', canonicalModelId: null }] },
]
for (const channel of catalog) {
  Object.assign(channel, { baseUrl: 'https://fixture.invalid', authType: 'bearer', protocolAuthTypes: {}, protocols: ['chat_completions'], credentialSet: true, createdAt: 1, updatedAt: 1, priority: 0, kind: 'custom', provider: null, requestOverrides: { inheritGlobal: true, headerProfile: null, userAgent: null, headers: {}, body: {}, protocols: {} }, quota: { providerId: '', config: {} } })
  channel.modelMappings.forEach(mapping => { mapping.mappingSource = null })
  channel.keys.forEach(key => Object.assign(key, { priority: 0, models: [...channel.models], rateLimit: { enabled: false, requestsPerMinute: null, maxConcurrentRequests: null }, stateVersion: 0, autoDisableUntil: null, probeUntil: null, autoDisableReason: null }))
}

describe('真实调用方访问范围配置组件', () => {
  let server, component, app, state, requests
  async function settle() { await nextTick(); await delay(35); await nextTick() }
  before(async () => {
    server = await createServer({ root: new URL('..', import.meta.url).pathname.replace(/^\/(\w:)/, '$1'), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  })
  afterEach(() => { app?.unmount(); app = null; document.body.innerHTML = ''; globalThis.fetch = originalFetch })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount(policy = unrestricted(), fetchCatalog = () => catalog) {
    requests = []
    globalThis.fetch = async (url) => {
      requests.push(url)
      assert.equal(url, '/admin/api/channels', '只能读取静态渠道目录，不能请求 OAuth 账号或数据面目录')
      const data = await fetchCatalog()
      return new Response(JSON.stringify(data), { status: 200 })
    }
    component ??= (await server.ssrLoadModule('/src/components/ClientKeyAccessFields.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/ClientKeyAccessFields.vue', import.meta.url), 'utf8'))
    const client = { ...component, ssrRender: undefined, render: compile(descriptor.template.content), setup(props, context) { return { ...component.setup(props, context) } } }
    state = ref(structuredClone(policy))
    const host = document.createElement('div'); document.body.append(host)
    app = createApp({ render: () => h(client, { modelValue: state.value, 'onUpdate:modelValue': value => { state.value = value } }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(host)
    await settle()
  }
  async function mode(label, value) {
    const select = document.querySelector('select[aria-label="' + label + '模式"]')
    assert.ok(select, label + '需要显式模式选择')
    select.value = value; select.dispatchEvent(new Event('change', { bubbles: true })); await settle()
  }
  async function choose(scope, text) {
    const group = document.querySelector('fieldset[aria-label="' + scope + '访问范围"]')
    const label = [...group.querySelectorAll('label')].find(node => node.textContent.includes(text) && node.querySelector('input[type="checkbox"]'))
    assert.ok(label, '缺少选项：' + text)
    label.querySelector('input').click(); await settle()
  }

  it('三维默认不限制，打开默认表单时不读取目录', async () => {
    await mount()
    assert.equal(document.querySelectorAll('select').length, 3)
    assert.equal(requests.length, 0)
  })

  it('三维黑名单可多选并在三模式往返时保留草稿，空名单不显示拒绝全部', async () => {
    await mount()
    for (const name of ['渠道', '渠道密钥', '模型']) {
      const select = document.querySelector('select[aria-label="' + name + '模式"]')
      assert.ok([...select.options].some(option => option.value === 'denylist'))
      await mode(name, 'denylist')
    }
    assert.doesNotMatch(document.body.textContent, /无授权目标/)
    await choose('渠道', 'channel-a')
    await choose('渠道密钥', 'channel-a / key-1')
    await choose('模型', 'Model-A')
    assert.deepEqual(state.value, {
      channels: { mode: 'denylist', ids: ['channel-a'] },
      channelKeys: { mode: 'denylist', keys: [{ channelId: 'channel-a', keyId: 'key-1' }] },
      models: { mode: 'denylist', ids: ['Model-A'] },
    })
    assert.match(document.body.textContent, /OAuth/)
    for (const name of ['渠道', '渠道密钥', '模型']) {
      await mode(name, 'allowlist'); await mode(name, 'unrestricted'); await mode(name, 'denylist')
    }
    assert.equal(document.querySelectorAll('input:checked').length, 3)
    assert.equal(requests.length, 1)
  })

  it('黑名单回显失效模型时保留排除 ID，不显示无授权目标', async () => {
    const policy = unrestricted()
    policy.models = { mode: 'denylist', ids: ['deleted-model'] }
    await mount(policy)
    assert.equal(requests.length, 1)
    assert.match(document.body.textContent, /deleted-model/)
    assert.doesNotMatch(document.body.textContent, /无授权目标/)
    assert.deepEqual(state.value.models.ids, ['deleted-model'])
    assert.match(document.body.textContent, /可保留.*模型黑名单.*重新配置.*仍禁止/)
    assert.doesNotMatch(document.body.textContent, /请明确取消或重新选择/)
    await choose('模型', 'deleted-model')
    assert.deepEqual(state.value.models.ids, [])
  })

  it('模式切换保留多选草稿，密钥必须使用渠道与密钥 ID 成对选择', async () => {
    await mount()
    await mode('渠道', 'allowlist')
    await choose('渠道', 'channel-a'); await choose('渠道', 'channel-b')
    await mode('渠道密钥', 'allowlist')
    await choose('渠道密钥', 'channel-a / key-1'); await choose('渠道密钥', 'channel-b / key-1')
    await mode('模型', 'allowlist')
    await choose('模型', 'Model-A'); await choose('模型', 'model-b')
    assert.deepEqual(state.value.channelKeys.keys, [{ channelId: 'channel-a', keyId: 'key-1' }, { channelId: 'channel-b', keyId: 'key-1' }])
    assert.deepEqual(state.value.models.ids, ['Model-A', 'model-b'])
    await mode('渠道', 'unrestricted'); await mode('渠道', 'allowlist')
    assert.deepEqual(state.value.channels.ids, ['channel-a', 'channel-b'])
    assert.equal(document.querySelectorAll('fieldset[aria-label="渠道访问范围"] input:checked').length, 2)
    await mode('渠道密钥', 'unrestricted'); await mode('渠道密钥', 'allowlist')
    await mode('模型', 'unrestricted'); await mode('模型', 'allowlist')
    assert.equal(document.querySelectorAll('fieldset[aria-label="渠道密钥访问范围"] input:checked').length, 2)
    assert.equal(document.querySelectorAll('fieldset[aria-label="模型访问范围"] input:checked').length, 2)
    assert.equal(requests.length, 1)
  })

  it('空白名单明确显示无授权目标，并保持受限模式', async () => {
    await mount()
    await mode('模型', 'allowlist')
    assert.match(document.body.textContent, /无授权目标/)
    assert.equal(state.value.models.mode, 'allowlist')
    assert.deepEqual(state.value.models.ids, [])
  })

  it('编辑回显停用项及已删除项，不静默过滤或放宽授权', async () => {
    await mount({
      channels: { mode: 'allowlist', ids: ['channel-b', 'deleted-channel'] },
      channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-b', keyId: 'key-1' }, { channelId: 'deleted-channel', keyId: 'deleted-key' }] },
      models: { mode: 'allowlist', ids: ['Model-A', 'deleted-model'] },
    })
    assert.equal(document.querySelectorAll('input:checked').length, 6)
    assert.match(document.body.textContent, /停用/)
    assert.match(document.body.textContent, /deleted-channel/)
    assert.match(document.body.textContent, /已删除|不存在/)
    assert.deepEqual(state.value.models.ids, ['Model-A', 'deleted-model'])
    await choose('模型', 'deleted-model')
    assert.deepEqual(state.value.models.ids, ['Model-A'])
  })

  it('目录失败及重试期间保留已有选择，成功后回显原授权', async () => {
    let fail = true
    await mount({ ...unrestricted(), channels: { mode: 'allowlist', ids: ['channel-a'] } }, () => {
      if (fail) throw new Error('目录失败')
      return catalog
    })
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.equal(document.querySelector('input:checked')?.closest('label').textContent.includes('channel-a'), true)
    assert.match(document.body.textContent, /读取失败|无法连接/)
    fail = false
    const retry = [...document.querySelectorAll('button')].find(node => node.textContent.includes('重试'))
    assert.ok(retry); retry.click(); await settle()
    assert.deepEqual(state.value.channels.ids, ['channel-a'])
    assert.match(document.querySelector('input:checked').closest('label').textContent, /渠道 A/)
  })

  it('唯一授权目标全部被删除后明确显示无授权目标，仍保留原 ID 与受限模式', async () => {
    await mount({ ...unrestricted(), models: { mode: 'allowlist', ids: ['deleted-model'] } })
    assert.match(document.body.textContent, /无授权目标/)
    assert.equal(state.value.models.mode, 'allowlist')
    assert.deepEqual(state.value.models.ids, ['deleted-model'])
    assert.equal(document.querySelector('input:checked')?.closest('label').textContent.includes('deleted-model'), true)
  })

  for (const { label, scope, selection } of [
    { label: '渠道', scope: 'channels', selection: { ids: ['deleted-channel'] } },
    { label: '渠道密钥', scope: 'channelKeys', selection: { keys: [{ channelId: 'deleted-channel', keyId: 'deleted-key' }] } },
    { label: '模型', scope: 'models', selection: { ids: ['deleted-model'] } },
  ]) {
    it(`${label}失效选择切换为不限制时隐藏警告，切回白名单恢复选择与警告`, async () => {
      await mount({ ...unrestricted(), [scope]: { mode: 'allowlist', ...selection } })
      assert.match(document.body.textContent, /部分已选目标已删除或不存在/)
      assert.match(document.body.textContent, /无授权目标/)
      assert.equal(document.querySelectorAll('input:checked').length, 1)

      await mode(label, 'unrestricted')
      assert.doesNotMatch(document.body.textContent, /部分已选目标已删除或不存在/)
      assert.doesNotMatch(document.body.textContent, /无授权目标/)
      assert.equal(document.querySelectorAll('input[type="checkbox"]').length, 0)
      assert.deepEqual(state.value[scope], { mode: 'unrestricted', ...selection })

      await mode(label, 'allowlist')
      assert.match(document.body.textContent, /部分已选目标已删除或不存在/)
      assert.match(document.body.textContent, /无授权目标/)
      assert.equal(document.querySelectorAll('input:checked').length, 1)
      assert.match(document.querySelector('input:checked').closest('label').textContent, /deleted-/)
      assert.deepEqual(state.value[scope], { mode: 'allowlist', ...selection })
      assert.equal(requests.length, 1)
    })
  }

  it('渠道名单缩小时保留冲突密钥并提示调整，选项不回显任何凭据明文', async () => {
    const data = structuredClone(catalog)
    data[0].keys[0].credential = 'UPSTREAM_SECRET_MUST_NOT_RENDER'
    await mount({
      channels: { mode: 'allowlist', ids: ['channel-a', 'channel-b'] },
      channelKeys: { mode: 'allowlist', keys: [{ channelId: 'channel-b', keyId: 'key-1' }] },
      models: { mode: 'unrestricted', ids: [] },
    }, () => data)
    await choose('渠道', 'channel-b')
    assert.deepEqual(state.value.channelKeys.keys, [{ channelId: 'channel-b', keyId: 'key-1' }])
    assert.match(document.body.textContent, /不在渠道白名单/)
    assert.equal(document.body.innerHTML.includes('UPSTREAM_SECRET_MUST_NOT_RENDER'), false)
  })
})

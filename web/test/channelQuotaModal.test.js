import { after, afterEach, before, beforeEach, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const key of ['window', 'document', 'navigator', 'Document', 'HTMLElement', 'Element', 'Node', 'SVGElement', 'Event']) {
  Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true })
}
const { compile, createApp, h, nextTick, ref, ssrContextKey } = await import('vue')
const { parse } = await import('@vue/compiler-sfc')
const { createServer } = await import('vite')
const vue = (await import('@vitejs/plugin-vue')).default
const originalFetch = globalThis.fetch
const response = body => new Response(JSON.stringify(body), { status: 200 })
const button = text => [...document.querySelectorAll('button')].find(element => element.textContent.trim() === text)
const field = text => [...document.querySelectorAll('label.field')].find(element => element.querySelector('span')?.textContent === text)?.querySelector('input,select,textarea')
async function settle() { await nextTick(); await delay(25); await nextTick() }
async function input(element, value, event = 'input') {
  assert.ok(element)
  element.value = value
  element.dispatchEvent(new Event(event, { bubbles: true }))
  await settle()
}

const fixture = () => ({
  id: 'channel-one', name: '测试渠道',
  quota: { providerId: 'new-api', credentialSource: 'DEDICATED', credentialSet: true, config: { authMode: 'system', userId: '999' } },
  keys: [
    { id: 'key-one', name: '密钥一', quota: { providerId: 'sub2api', credentialSource: 'CHANNEL_KEY', credentialSet: false, config: {} } },
    { id: 'key-two', name: '密钥二', quota: { providerId: 'new-api', credentialSource: 'DEDICATED', credentialSet: true, config: { authMode: 'system', userId: '202' } } },
  ],
})

describe('按密钥配置和查询额度的弹窗', () => {
  let server, modal, app, state, channel, calls, saved, queryStatus

  async function component(name, children = {}) {
    const loaded = (await server.ssrLoadModule('/src/components/' + name + '.vue')).default
    const { descriptor } = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'))
    return {
      ...loaded, ssrRender: undefined, render: compile(descriptor.template.content), components: children,
      setup(props, context) {
        const bindings = loaded.setup(props, context)
        if (name === 'ChannelQuotaModal') state = bindings
        return { ...bindings, ...children }
      },
    }
  }

  before(async () => {
    server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
    const icon = await component('Icon')
    const secretInput = await component('SecretInput', { Icon: icon })
    const modalBase = { props: ['title'], setup(_, { slots }) { return () => h('section', [slots.default?.(), h('footer', slots.footer?.())]) } }
    modal = await component('ChannelQuotaModal', { Modal: modalBase, SecretInput: secretInput })
  })

  beforeEach(() => {
    channel = fixture(); calls = []; saved = []; queryStatus = 'AVAILABLE'
    globalThis.fetch = async (url, options = {}) => {
      const method = options.method || 'GET'
      calls.push({ url, method, body: options.body ? JSON.parse(options.body) : undefined })
      if (url === '/admin/api/quota-providers' && method === 'GET') {
        return response([{ id: 'sub2api', displayName: 'Sub2API' }, { id: 'new-api', displayName: 'New API' }])
      }
      for (const key of channel.keys) {
        const path = '/admin/api/channels/' + channel.id + '/keys/' + key.id
        if (url === path + '/quota-settings' && method === 'PUT') {
          const settings = JSON.parse(options.body)
          key.quota = { providerId: settings.providerId, config: settings.config, credentialSource: settings.credentialSource, credentialSet: Boolean(settings.credential) || Boolean(key.quota?.credentialSet) }
          return response(channel)
        }
        if (url === path + '/quota' && method === 'POST') {
          return response({
            status: queryStatus, scope: 'KEY', targetKeyId: key.id, providerId: key.quota.providerId,
            credentialSource: key.quota.credentialSource, queryKey: { id: key.id, name: key.name },
            remaining: queryStatus === 'AVAILABLE' ? '12.5' : undefined, unit: 'USD', checkedAt: 1_800_000_000_000,
            message: queryStatus === 'AUTHENTICATION_FAILED' ? '当前密钥认证失败' : undefined,
          })
        }
      }
      assert.fail('不应请求渠道级额度或其他接口：' + method + ' ' + url)
    }
  })

  afterEach(() => { app?.unmount(); app = null; document.body.innerHTML = ''; globalThis.fetch = originalFetch })
  after(async () => { await server?.close(); dom.window.close() })

  async function mount() {
    const current = ref(channel)
    const root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp({ render: () => h(modal, { channel: current.value, onSaved: updated => { saved.push(updated); current.value = updated } }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount(root)
    await settle()
  }

  it('即使存在旧渠道配置，也默认选择首个密钥且不展示渠道级选项', async () => {
    await mount()
    assert.deepEqual([...field('查询密钥').options].map(option => option.value), ['key-one', 'key-two'])
    assert.equal(field('查询密钥').value, 'key-one')
    assert.equal(field('额度查询插件').value, 'sub2api')
    assert.equal(field('查询凭据来源').value, 'CHANNEL_KEY')
    assert.equal(field('New API 用户 ID'), undefined)
    assert.doesNotMatch(document.body.textContent, /渠道配置（原有）/)
    assert.equal(calls.length, 1)
    assert.equal(calls[0].url, '/admin/api/quota-providers')
  })

  it('仅配置过渠道额度时，密钥仍未启用且不继承旧账户或凭据', async () => {
    channel.keys.forEach(key => { key.quota = null })
    await mount()
    assert.equal(field('额度查询插件').value, '')
    assert.equal(button('查询额度').disabled, true)
    await input(field('额度查询插件'), 'sub2api', 'change')
    assert.equal(field('查询凭据来源').value, 'CHANNEL_KEY')
    button('保存设置').click(); await settle()
    assert.deepEqual(calls[1], {
      url: '/admin/api/channels/channel-one/keys/key-one/quota-settings', method: 'PUT',
      body: { providerId: 'sub2api', config: {}, credentialSource: 'CHANNEL_KEY' },
    })
    assert.equal(saved[0].keys[0].quota.credentialSet, false)
    assert.equal(saved[0].quota.config.userId, '999')
  })

  it('保存与查询仅调用所选密钥接口，未保存时阻止查询和切换', async () => {
    await mount()
    await input(field('JSON 配置'), '{"proxyUrl":"http://proxy.internal:7897"}')
    assert.equal(button('查询额度').disabled, true)
    assert.equal(field('查询密钥').disabled, true)
    button('保存设置').click(); await settle()
    assert.equal(saved.length, 1)
    assert.equal(calls.length, 2)
    assert.equal(calls[1].url, '/admin/api/channels/channel-one/keys/key-one/quota-settings')
    assert.deepEqual(calls[1].body.config, { proxyUrl: 'http://proxy.internal:7897' })
    assert.equal(button('查询额度').disabled, false)
    button('查询额度').click(); await settle()
    assert.equal(calls[2].url, '/admin/api/channels/channel-one/keys/key-one/quota')
    assert.match(document.querySelector('.quota-result').textContent, /密钥一/)
    assert.match(document.querySelector('.quota-balance').textContent, /12.5/)
  })

  it('切换密钥只读取该密钥的账户配置，并清除上次查询结果', async () => {
    await mount()
    button('查询额度').click(); await settle()
    await input(field('查询密钥'), 'key-two', 'change')
    assert.equal(field('额度查询插件').value, 'new-api')
    assert.equal(field('New API 用户 ID').value, '202')
    assert.equal(document.querySelector('#quota-query-credential').value, '')
    assert.match(document.body.textContent, /已保存/)
    assert.equal(document.querySelector('.quota-result'), null)
    assert.equal(button('保存设置').disabled, true)
    button('查询额度').click(); await settle()
    assert.equal(calls.at(-1).url, '/admin/api/channels/channel-one/keys/key-two/quota')
    assert.match(document.querySelector('.quota-result').textContent, /密钥二/)
    assert.match(document.querySelector('.quota-result').textContent, /202/)
  })

  it('所选密钥认证失败时，不回退到其他密钥或旧渠道配置', async () => {
    queryStatus = 'AUTHENTICATION_FAILED'
    await mount()
    button('查询额度').click(); await settle()
    assert.deepEqual(calls.filter(call => call.method === 'POST').map(call => call.url), ['/admin/api/channels/channel-one/keys/key-one/quota'])
    assert.match(document.querySelector('.quota-result').textContent, /当前密钥认证失败/)
    assert.doesNotMatch(document.querySelector('.quota-result').textContent, /查询顺序|认证失败后回退/)
  })

  it('没有已保存密钥时显示提示，并禁止保存、查询及渠道级回退', async () => {
    channel.keys = []
    await mount()
    assert.equal(field('查询密钥').disabled, true)
    assert.equal(field('额度查询插件').disabled, true)
    assert.equal(button('保存设置').disabled, true)
    assert.equal(button('查询额度').disabled, true)
    assert.match(document.body.textContent, /请先为此渠道添加并保存密钥/)
    state.providerId.value = 'sub2api'
    await state.save()
    await state.query()
    assert.equal(calls.length, 1)
  })
})

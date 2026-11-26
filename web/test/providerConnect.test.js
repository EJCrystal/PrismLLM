import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'

const chatgpt = { id: 'chatgpt', label: 'ChatGPT', configured: true, managedChannel: true }
const antigravity = { id: 'antigravity', label: 'Antigravity', configured: true, managedChannel: true }

describe('提供商统一接入流程', () => {
  let server
  let component
  const originalDocument = globalThis.document

  before(async () => {
    server = await createServer({ configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true }, appType: 'custom' })
    component = (await server.ssrLoadModule('/src/components/ChannelsView.vue')).default
    globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  })

  after(async () => {
    globalThis.document = originalDocument
    await server?.close()
  })

  async function setup() {
    let bindings
    await renderToString(createSSRApp({
      ...component,
      setup(props, context) {
        bindings = component.setup(props, context)
        return bindings
      },
    }))
    bindings.openProviderConnect()
    return bindings
  }

  it('先选择接入方式，再进入原有新建表单，自定义 API 不依赖订阅平台', async () => {
    const state = await setup()
    assert.equal(state.canConnectProvider.value, false)
    state.continueProviderConnect()
    assert.equal(state.showForm.value, false)
    assert.equal(state.activeOAuthDrawer.value, null)

    state.providerConnection.value.kind = 'custom'
    assert.equal(state.canConnectProvider.value, true)
    state.continueProviderConnect()
    assert.equal(state.showForm.value, true)
    assert.equal(state.providerConnection.value, null)
    assert.equal(state.activeOAuthDrawer.value, null)
  })

  it('要求明确选择订阅平台，并对选中的平台打开新增账号流程', async () => {
    const state = await setup()
    state.oauthProviders.value = [chatgpt, antigravity]
    state.providerConnection.value.kind = 'subscription'
    assert.equal(state.canConnectProvider.value, false)
    state.continueProviderConnect()
    assert.equal(state.activeOAuthDrawer.value, null)

    state.providerConnection.value.providerId = antigravity.id
    assert.equal(state.canConnectProvider.value, true)
    state.continueProviderConnect()
    assert.equal(state.activeOAuthDrawer.value?.providerId, antigravity.id)
    assert.equal(state.activeOAuthDrawer.value?.tab, 'accounts')
    assert.equal(state.activeOAuthDrawer.value?.autoOpenCreate, true)
    assert.equal(state.showForm.value, false)
    assert.equal(state.providerConnection.value, null)

    state.openProviderConnect()
    assert.deepEqual(state.providerConnection.value, { kind: '', providerId: '' })
  })

  it('阻止继续接入未配置或已不在列表中的订阅平台', async () => {
    const state = await setup()
    Object.assign(state.providerConnection.value, { kind: 'subscription', providerId: antigravity.id })
    for (const providers of [[chatgpt], [chatgpt, { ...antigravity, configured: false }]]) {
      state.oauthProviders.value = providers
      assert.equal(state.canConnectProvider.value, false)
      state.continueProviderConnect()
      assert.equal(state.activeOAuthDrawer.value, null)
      assert.equal(state.showForm.value, false)
      assert.equal(state.providerConnection.value?.providerId, antigravity.id)
    }
  })
})

import { after, before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createOAuthAccountsFixture } from './fixtures/oauth-accounts/accounts.js'

const chatgpt = { id: 'chatgpt', label: 'ChatGPT', configured: true, managedChannel: true }
const antigravity = { id: 'antigravity', label: 'Antigravity', configured: true, managedChannel: true }

describe('提供商统一接入流程', () => {
  let server
  let component
  const originalDocument = globalThis.document

  before(async () => {
    server = await createServer({ configFile: false, envFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
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
    assert.equal(state.newOAuthAccountProvider.value, null)

    state.providerConnection.value.kind = 'custom'
    assert.equal(state.canConnectProvider.value, true)
    state.continueProviderConnect()
    assert.equal(state.showForm.value, true)
    assert.equal(state.providerConnection.value, null)
    assert.equal(state.activeOAuthDrawer.value, null)
    assert.equal(state.newOAuthAccountProvider.value, null)
  })

  for (const selectedProvider of [chatgpt, antigravity]) {
    it(selectedProvider.id + ' 要求明确选择订阅平台，并独立打开新增账号流程', async () => {
      const state = await setup()
      state.oauthProviders.value = [chatgpt, antigravity]
      state.providerConnection.value.kind = 'subscription'
      assert.equal(state.canConnectProvider.value, false)
      state.continueProviderConnect()
      assert.equal(state.activeOAuthDrawer.value, null)

      state.providerConnection.value.providerId = selectedProvider.id
      assert.equal(state.canConnectProvider.value, true)
      state.continueProviderConnect()
      assert.equal(state.newOAuthAccountProvider.value, selectedProvider.id)
      assert.equal(state.activeOAuthDrawer.value, null)
      assert.equal(state.showForm.value, false)
      assert.equal(state.providerConnection.value, null)

      state.openProviderConnect()
      assert.deepEqual(state.providerConnection.value, { kind: '', providerId: '' })
    })

    for (const provisioned of [false, true]) {
      it(selectedProvider.id + ' 零账号时从' + (provisioned ? '已置备' : '未置备') + '提供商直接新增，不先打开账号池', async () => {
        const state = await setup()
        const fixture = createOAuthAccountsFixture()
        state.oauthProviders.value = fixture.providers
        state.oauthAccounts.value = fixture.accounts.filter(account => account.provider !== selectedProvider.id)
        state.channels.value = fixture.channels.filter(channel => provisioned || channel.provider !== selectedProvider.id)
        const item = state.providerItems.value.find(item => item.providerId === selectedProvider.id)
        assert.ok(item)
        assert.equal(item.accountSummary.total, 0)
        assert.equal(Boolean(item.channel), provisioned)
        state.openSubscriptionAccounts(item)
        assert.equal(state.newOAuthAccountProvider.value, selectedProvider.id)
        assert.equal(state.activeOAuthDrawer.value, null)
        assert.equal(state.showForm.value, false)
      })
    }

    it(selectedProvider.id + ' 已有账号时仍打开账号池管理', async () => {
      const state = await setup()
      const fixture = createOAuthAccountsFixture()
      state.oauthProviders.value = fixture.providers
      state.oauthAccounts.value = fixture.accounts
      state.channels.value = fixture.channels
      const item = state.providerItems.value.find(item => item.providerId === selectedProvider.id)
      assert.ok(item.accountSummary.total > 0)
      state.openSubscriptionAccounts(item)
      assert.equal(state.newOAuthAccountProvider.value, null)
      assert.equal(state.activeOAuthDrawer.value?.providerId, selectedProvider.id)
      assert.equal(state.activeOAuthDrawer.value?.tab, 'accounts')
    })

    it(selectedProvider.id + ' 未配置且无账号时不能绕过禁用按钮打开新建', async () => {
      const state = await setup()
      state.oauthProviders.value = [{ ...selectedProvider, configured: false }]
      const item = state.providerItems.value.find(item => item.providerId === selectedProvider.id)
      state.openSubscriptionAccounts(item)
      assert.equal(state.newOAuthAccountProvider.value, null)
      assert.equal(state.activeOAuthDrawer.value, null)
    })
  }

  it('阻止继续接入未配置或已不在列表中的订阅平台', async () => {
    const state = await setup()
    Object.assign(state.providerConnection.value, { kind: 'subscription', providerId: antigravity.id })
    for (const providers of [[chatgpt], [chatgpt, { ...antigravity, configured: false }]]) {
      state.oauthProviders.value = providers
      assert.equal(state.canConnectProvider.value, false)
      state.continueProviderConnect()
      assert.equal(state.activeOAuthDrawer.value, null)
      assert.equal(state.newOAuthAccountProvider.value, null)
      assert.equal(state.showForm.value, false)
      assert.equal(state.providerConnection.value?.providerId, antigravity.id)
    }
  })
})

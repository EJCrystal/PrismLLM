import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildQuotaSettings, buildProviderQuotaSettings, buildNewApiQuotaSettings, quotaBalance, quotaStatus } from '../src/utils/channelQuota'
import { api } from '../src/api'

describe('渠道额度插件', () => {
  it('非 New API 插件可显式使用、替换、保留或清除专用凭据', () => {
    const dedicated = { credentialSource: 'DEDICATED' }
    const settings = buildProviderQuotaSettings('sub2api', '{"baseUrl":"https://upstream.example/site"}', {
      ...dedicated, credential: ' account-query-secret ',
    })
    assert.deepEqual(settings, {
      providerId: 'sub2api', config: { baseUrl: 'https://upstream.example/site' },
      credentialSource: 'DEDICATED', credential: 'account-query-secret',
    })
    assert.equal(JSON.stringify(settings.config).includes('account-query-secret'), false)
    assert.deepEqual(buildProviderQuotaSettings('sub2api', '{}', { ...dedicated, credential: ' ' }), {
      providerId: 'sub2api', config: {}, credentialSource: 'DEDICATED',
    })
    assert.equal(buildProviderQuotaSettings('sub2api', '{}', { ...dedicated, credential: 'replacement' }).credential, 'replacement')
    assert.deepEqual(buildProviderQuotaSettings('custom', '{}', { ...dedicated, clearCredential: true }), {
      providerId: 'custom', config: {}, credentialSource: 'DEDICATED', clearCredential: true,
    })
    assert.throws(() => buildProviderQuotaSettings('sub2api', '{}', {
      ...dedicated, credential: 'secret', clearCredential: true,
    }), /同时/)
  })

  it('仅按明确选择的来源保存，不因已有凭据启用专用来源或提交隐藏输入', () => {
    assert.deepEqual(buildProviderQuotaSettings('sub2api', '{}', {
      credentialSource: 'CHANNEL_KEY', credentialSet: true, credential: 'hidden-secret', clearCredential: true,
    }), { providerId: 'sub2api', config: {}, credentialSource: 'CHANNEL_KEY' })
    assert.deepEqual(buildProviderQuotaSettings('', '{invalid', {
      credentialSource: 'DEDICATED', credential: 'not-forwarded', clearCredential: true,
    }), { providerId: '', config: {} })
  })

  it('系统令牌与用户 ID 使用独立字段，令牌不会进入插件 JSON', () => {
    const settings = buildNewApiQuotaSettings('{"baseUrl":"https://upstream.example/site"}', {
      mode: 'system', userId: ' 00123 ', credential: ' system-secret ',
    })
    assert.deepEqual(settings, {
      providerId: 'new-api', credentialSource: 'DEDICATED', credential: 'system-secret',
      config: { baseUrl: 'https://upstream.example/site', authMode: 'system', userId: '123' },
    })
    assert.equal(JSON.stringify(settings.config).includes('system-secret'), false)
    const preserve = buildNewApiQuotaSettings('{}', { mode: 'system', userId: '123', credential: ' ' })
    assert.equal('credential' in preserve, false)
    assert.equal('clearCredential' in preserve, false)
    const clear = buildNewApiQuotaSettings('{}', { mode: 'system', userId: '123', clearCredential: true })
    assert.equal(clear.clearCredential, true)
  })

  it('切回模型密钥模式会移除用户 ID，系统模式拒绝无效 ID 和冲突操作', () => {
    assert.deepEqual(buildNewApiQuotaSettings('{"authMode":"system","userId":"123"}', {
      mode: 'token', userId: '', credential: 'not-forwarded', clearCredential: true,
    }), { providerId: 'new-api', config: { authMode: 'token' }, credentialSource: 'CHANNEL_KEY' })
    for (const userId of ['', '0', '-1', '1.5', '1e3', '123abc', '9223372036854775808']) {
      assert.throws(() => buildNewApiQuotaSettings('{}', { mode: 'system', userId }), /用户 ID/)
    }
    assert.equal(buildNewApiQuotaSettings('{}', { mode: 'system', userId: '9223372036854775807' }).config.userId, '9223372036854775807')
    assert.throws(() => buildNewApiQuotaSettings('{}', { mode: 'system', userId: '1', credential: 'secret', clearCredential: true }), /同时/)
  })

  it('插件参数只接受 JSON 对象并按 UTF-8 限制大小', () => {
    assert.deepEqual(buildQuotaSettings('sub2api', ''), { providerId: 'sub2api', config: {} })
    assert.deepEqual(buildQuotaSettings('', '{bad'), { providerId: '', config: {} })
    assert.deepEqual(buildQuotaSettings('custom', '{"site":"example","nested":{"n":1}}'), {
      providerId: 'custom', config: { site: 'example', nested: { n: 1 } },
    })
    for (const text of ['[]', 'null', '1', '"text"', '{bad']) {
      assert.throws(() => buildQuotaSettings('custom', text), /JSON/)
    }
    assert.throws(() => buildQuotaSettings('custom', JSON.stringify({ large: '界'.repeat(3000) })), /8 KB/)
  })

  it('保留零余额、负余额和十进制精度，失败不显示为零', () => {
    assert.equal(quotaBalance({ status: 'AVAILABLE', remaining: '0', unit: 'USD' }), '0 USD')
    assert.equal(quotaBalance({ status: 'AVAILABLE', remaining: '-1.250000000000001', unit: 'CNY' }), '-1.250000000000001 CNY')
    assert.equal(quotaBalance({ status: 'AVAILABLE', remaining: '500000', unit: 'quota' }), '500000 quota')
    assert.equal(quotaBalance({ status: 'UNLIMITED' }), '无限制')
    for (const status of ['ERROR', 'UNSUPPORTED', 'AUTHENTICATION_FAILED', 'RATE_LIMITED', 'DISABLED']) {
      assert.equal(quotaBalance({ status, remaining: '0' }), '—')
    }
    assert.equal(quotaBalance({ status: 'AVAILABLE' }), '—')
    assert.equal(quotaStatus({ status: 'AUTHENTICATION_FAILED' }).label, '上游认证失败')
    assert.equal(quotaStatus({ status: 'FUTURE_STATUS' }).label, '查询失败')
  })

  it('读取插件不触发额度查询，设置与查询走独立管理端点', async (t) => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    t.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: 'gw_csrf=test-csrf' }
    const calls = []
    globalThis.fetch = async (url, options) => {
      calls.push({ url, method: options.method || 'GET', body: options.body })
      return new Response(JSON.stringify(url.endsWith('quota-providers') ? [] : {}), { status: 200 })
    }
    await api.listQuotaProviders()
    await api.updateChannelQuota('a/b', { providerId: 'new-api', config: {} })
    await api.queryChannelQuota('a/b')
    assert.deepEqual(calls, [
      { url: '/admin/api/quota-providers', method: 'GET', body: undefined },
      { url: '/admin/api/channels/a%2Fb/quota-settings', method: 'PUT', body: '{"providerId":"new-api","config":{}}' },
      { url: '/admin/api/channels/a%2Fb/quota', method: 'POST', body: undefined },
    ])
  })

  it('额度快照保留实际查询密钥和回退顺序，缓存不改变来源', async (t) => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    t.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: 'gw_csrf=test-csrf' }
    const expected = {
      status: 'AVAILABLE', remaining: '100', unit: 'USD', cached: true,
      queryKey: { id: 'owned-key', name: '自有密钥' },
      attemptedKeys: [{ id: 'welfare-key', name: '福利密钥' }, { id: 'owned-key', name: '自有密钥' }],
    }
    globalThis.fetch = async () => new Response(JSON.stringify(expected), { status: 200 })
    assert.deepEqual(await api.queryChannelQuota('channel'), expected)
  })

  it('按指定密钥保存与查询，返回独立账户的目标绑定', async (t) => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    t.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: 'gw_csrf=test-csrf' }
    const calls = []
    const snapshot = { status: 'AVAILABLE', scope: 'KEY', targetKeyId: 'key/b', credentialSource: 'DEDICATED', remaining: '22', attemptedKeys: [] }
    globalThis.fetch = async (url, options) => {
      calls.push({ url, method: options.method, body: options.body })
      return new Response(JSON.stringify(url.endsWith('/quota') ? snapshot : {}), { status: 200 })
    }
    const settings = buildNewApiQuotaSettings('{"proxyUrl":"http://proxy.internal:7897"}', { mode: 'system', userId: '202', credential: 'second-account-system-token' })
    await api.updateChannelKeyQuota('channel/a', 'key/b', settings)
    assert.deepEqual(await api.queryChannelKeyQuota('channel/a', 'key/b'), snapshot)
    assert.deepEqual(calls, [
      { url: '/admin/api/channels/channel%2Fa/keys/key%2Fb/quota-settings', method: 'PUT', body: JSON.stringify(settings) },
      { url: '/admin/api/channels/channel%2Fa/keys/key%2Fb/quota', method: 'POST', body: undefined },
    ])
    assert.equal(settings.config.userId, '202')
    assert.equal(JSON.stringify(settings.config).includes('second-account-system-token'), false)
  })
})

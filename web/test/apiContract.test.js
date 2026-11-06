import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api'
import { ApiError } from '../src/types/api'
import { normalizeChannelKeyRateLimit } from '../src/utils/channelKeyRateLimit'
import { priceFormState, buildPricePayload } from '../src/utils/priceForm'
import { normalizeKeyAutoDisableSettings } from '../src/utils/keyAutoDisable'
import { fillTrendBuckets } from '../src/utils/usageReport'

describe('API 契约的运行时形状', () => {
  it('API 错误保留 HTTP 状态码和错误码', () => {
    const error = new ApiError('请求失败', 409, 'conflict')
    assert.equal(error.name, 'ApiError')
    assert.equal(error.status, 409)
    assert.equal(error.code, 'conflict')
    assert.equal(error instanceof Error, true)
  })

  it('列表端点的成功响应必须提供 JSON 数组；空响应归一为空数组', async (t) => {
    const originalFetch = globalThis.fetch
    const responses = [
      new Response(JSON.stringify([{ id: 'channel-1' }]), { status: 200 }),
      new Response('', { status: 200 }),
    ]
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async () => responses.shift()
    assert.deepEqual(await api.listChannels(), [{ id: 'channel-1' }])
    assert.deepEqual(await api.listChannels(), [])
  })

  it('列表端点拒绝非数组 JSON，不把对象误交给页面', async (t) => {
    const originalFetch = globalThis.fetch
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async () => new Response(JSON.stringify({ data: [] }), { status: 200 })
    await assert.rejects(api.listChannels(), (error) => {
      assert.equal(error instanceof ApiError, true)
      assert.equal(error.code, 'invalid_response')
      return true
    })
  })

  it('审计查询把所有筛选项和游标一并传给服务端', async (t) => {
    const originalFetch = globalThis.fetch
    let requestedUrl = ''
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async (input) => {
      requestedUrl = String(input)
      return new Response('[]', { status: 200 })
    }
    await api.listAuditLogs({
      limit: 25, beforeId: 90, from: 1_700_000_000_000, to: 1_700_000_060_000,
      actor: 'admin-user', action: 'channel.update', result: 'success',
    })
    const params = new URL(requestedUrl, 'http://localhost').searchParams
    assert.equal(params.get('limit'), '25')
    assert.equal(params.get('beforeId'), '90')
    assert.equal(params.get('from'), '1700000000000')
    assert.equal(params.get('to'), '1700000060000')
    assert.equal(params.get('actor'), 'admin-user')
    assert.equal(params.get('action'), 'channel.update')
    assert.equal(params.get('result'), 'success')
  })

  it('订阅账号接口使用约定路径、方法并编码 id', async (t) => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    const calls = []
    t.after(() => {
      globalThis.fetch = originalFetch
      globalThis.document = originalDocument
    })
    globalThis.document = { cookie: '' }
    globalThis.fetch = async (input, init = {}) => {
      calls.push({ url: String(input), method: init.method || 'GET', body: init.body })
      return new Response(null, { status: 204 })
    }
    await api.importOAuthAccount({ provider: 'chatgpt', refreshToken: 'rt', channelIds: ['channel/1'] })
    await api.updateOAuthAccountPolicy('key 2', { windowLimit: 5 })
    await api.refreshOAuthAccount('key 2')
    await api.clearOAuthAccountCooldown('key 2')

    assert.deepEqual(calls.map((call) => [call.method, call.url]), [
      ['POST', '/admin/api/oauth-accounts'],
      ['PUT', '/admin/api/oauth-accounts/key%202/policy'],
      ['POST', '/admin/api/oauth-accounts/key%202/refresh'],
      ['POST', '/admin/api/oauth-accounts/key%202/cooldown/clear'],
    ])
    assert.deepEqual(JSON.parse(calls[0].body), {
      provider: 'chatgpt',
      refreshToken: 'rt',
      channelIds: ['channel/1'],
    })
    assert.deepEqual(JSON.parse(calls[1].body), { windowLimit: 5 })
  })

  it('系统配置接口使用约定路径与 GET 方法', async (t) => {
    const originalFetch = globalThis.fetch
    let requestedUrl = ''
    let requestedMethod = ''
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async (input, init = {}) => {
      requestedUrl = String(input)
      requestedMethod = init.method || 'GET'
      return new Response(JSON.stringify({ sections: [] }), { status: 200 })
    }

    assert.deepEqual(await api.getSystemConfig(), { sections: [] })
    assert.equal(requestedUrl, '/admin/api/system-config')
    assert.equal(requestedMethod, 'GET')
  })

  it('渠道健康度请求透传取消信号', async (t) => {
    const originalFetch = globalThis.fetch
    let requestedSignal = null
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async (_input, init = {}) => {
      requestedSignal = init.signal
      return new Response(JSON.stringify({ window: '24h', channels: [] }), { status: 200 })
    }
    const controller = new AbortController()
    await api.getChannelHealth('24h', { signal: controller.signal })
    assert.equal(requestedSignal, controller.signal)
  })

  it('非空成功响应无法解析时抛出明确错误', async (t) => {
    const originalFetch = globalThis.fetch
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async () => new Response('<html>upstream error</html>', { status: 200 })
    await assert.rejects(api.listChannels(), (error) => {
      assert.equal(error instanceof ApiError, true)
      assert.equal(error.status, 200)
      assert.equal(error.code, 'invalid_response')
      return true
    })
  })

  it('HTTP 错误响应保留结构化错误信息', async (t) => {
    const originalFetch = globalThis.fetch
    t.after(() => { globalThis.fetch = originalFetch })
    globalThis.fetch = async () => new Response(JSON.stringify({
      error: { code: 'conflict', message: '渠道正在使用' },
    }), { status: 409 })
    await assert.rejects(api.listChannels(), (error) => {
      assert.equal(error instanceof ApiError, true)
      assert.equal(error.status, 409)
      assert.equal(error.code, 'conflict')
      assert.equal(error.message, '渠道正在使用')
      return true
    })
  })

  it('渠道密钥限流结果保留后端扩展字段', () => {
    assert.deepEqual(
      normalizeChannelKeyRateLimit({ enabled: true, requestsPerMinute: '60', futureWindowSeconds: 30 }),
      {
        enabled: true,
        requestsPerMinute: 60,
        maxConcurrentRequests: null,
        futureWindowSeconds: 30,
      },
    )
  })

  it('价格表单与请求体使用后端约定字段', () => {
    assert.deepEqual(priceFormState({
      currency: 'CNY',
      inputPricePerMillion: '1.5',
      outputPricePerMillion: '2',
      pricingMode: 'token',
      priceTiers: [],
    }), {
      currency: 'CNY',
      inputPricePerMillion: '1.5',
      outputPricePerMillion: '2',
      cachedInputPricePerMillion: '',
      cachedInputWritePricePerMillion: '',
      pricingMode: 'token',
      perRequestPrice: '',
      priceTiers: [],
    })
    assert.deepEqual(buildPricePayload({
      currency: 'USD',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      cachedInputPricePerMillion: '',
      cachedInputWritePricePerMillion: '',
      pricingMode: 'token',
      perRequestPrice: '',
      priceTiers: [],
    }, 'channel-1', 'gpt-4.1'), {
      channelId: 'channel-1',
      upstreamModel: 'gpt-4.1',
      currency: 'USD',
      pricingMode: 'token',
      inputPricePerMillion: '1',
      outputPricePerMillion: '2',
      cachedInputPricePerMillion: null,
      cachedInputWritePricePerMillion: null,
      perRequestPrice: null,
      priceTiers: [],
    })
  })

  it('自动禁用规则接受后端返回的完整结构', () => {
    const settings = normalizeKeyAutoDisableSettings({
      enabled: true,
      rules: [{
        id: 'rule_one',
        name: '规则一',
        statusCodes: [401],
        statusCodeAtLeast: null,
        bodyPatterns: [],
        threshold: 2,
        windowMinutes: 5,
        streak: true,
        action: 'PERMANENT',
        disableMinutes: null,
        includeTransportFailures: false,
      }],
    })
    assert.equal(settings.enabled, true)
    assert.equal(settings.rules[0].action, 'PERMANENT')
    assert.deepEqual(settings.rules[0].statusCodes, [401])
  })

  it('报表补齐的桶保留后端成本键和零值字段', () => {
    const rows = fillTrendBuckets([], 0, 3_600_000, 'hour', 0)
    assert.equal(rows.length, 2)
    assert.equal(rows[0].requests, 0)
    assert.deepEqual(rows[0].costs, {})
    assert.equal(rows[0].cachedWriteTokens, 0)
  })
})

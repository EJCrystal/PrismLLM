import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildChannelPayload, buildPriorityUpdatePayload, isKeyRoutable, mergeProbedProtocols } from '../src/utils/channelForm'

describe('channelForm: 渠道保存请求体', () => {
  const baseForm = {
    name: ' deepseek ',
    baseUrl: ' https://example.com/v1 ',
    credential: '',
    authType: 'anthropic',
    protocols: ['chat_completions', 'responses'],
    enabled: true,
  }

  it('基础字段正确序列化', () => {
    const payload = buildChannelPayload(baseForm, ['deepseek-v4.1-flash'], {})

    assert.equal(payload.name, 'deepseek')
    assert.equal(payload.baseUrl, 'https://example.com/v1')
    assert.equal(payload.credential, null)
    assert.deepEqual(payload.protocols, ['chat_completions', 'responses'])
    assert.equal(payload.authType, 'anthropic')
  })

  it('正确生成规范 ID 映射和映射来源', () => {
    const payload = buildChannelPayload(
      baseForm,
      ['deepseek-v4.1-flash', 'qwen3.8-flash'],
      { 'deepseek-v4.1-flash': ' deepseek-flash ' },
    )

    assert.deepEqual(payload.modelMappings, [
      {
        upstreamModel: 'deepseek-v4.1-flash',
        canonicalModelId: 'deepseek-flash',
        mappingSource: 'manual',
      },
      {
        upstreamModel: 'qwen3.8-flash',
        canonicalModelId: null,
        mappingSource: null,
      },
    ])
  })

  it('允许多个上游模型映射为同一个对外 ID', () => {
    const payload = buildChannelPayload(
      baseForm,
      ['deepseek-flash', 'deepseek-v4.1-flash'],
      {
        'deepseek-flash': 'deepseek-flash',
        'deepseek-v4.1-flash': 'deepseek-flash',
      },
    )

    assert.deepEqual(payload.modelMappings, [
      {
        upstreamModel: 'deepseek-flash',
        canonicalModelId: 'deepseek-flash',
        mappingSource: 'manual',
      },
      {
        upstreamModel: 'deepseek-v4.1-flash',
        canonicalModelId: 'deepseek-flash',
        mappingSource: 'manual',
      },
    ])
  })

  it('渠道优先级随表单透传，空值提交 null 以保留原值', () => {
    assert.equal(buildChannelPayload({ ...baseForm, priority: 7 }, [], {}).priority, 7)
    assert.equal(buildChannelPayload({ ...baseForm, priority: 0 }, [], {}).priority, 1)
    assert.equal(buildChannelPayload({ ...baseForm, priority: '' }, [], {}).priority, null)
    assert.equal(buildChannelPayload(baseForm, [], {}).priority, null)
  })

  it('行内修改优先级时保留渠道 Anthropic 鉴权类型', () => {
    const channel = {
      name: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      authType: 'anthropic',
      protocols: ['messages', 'chat_completions', 'responses'],
      enabled: true,
      priority: 1,
    }

    assert.deepEqual(buildPriorityUpdatePayload(channel, 4), {
      name: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      authType: 'anthropic',
      protocols: ['messages', 'chat_completions', 'responses'],
      enabled: true,
      priority: 4,
    })
  })

  it('探测协议时保留已有优先级并追加新协议', () => {
    assert.deepEqual(
      mergeProbedProtocols(['messages', 'chat_completions'], ['chat_completions', 'responses', 'messages']),
      ['messages', 'chat_completions', 'responses'],
    )
    assert.deepEqual(mergeProbedProtocols([], ['chat_completions', 'responses']), ['chat_completions', 'responses'])
  })

  it('未修改的映射保留原 mappingSource', () => {
    const payload = buildChannelPayload(
      baseForm,
      ['deepseek-v4.1-flash'],
      { 'deepseek-v4.1-flash': 'deepseek-flash' },
      {
        'deepseek-v4.1-flash': {
          canonicalModelId: 'deepseek-flash',
          mappingSource: 'models_dev',
        },
      },
    )

    assert.equal(payload.modelMappings[0].mappingSource, 'models_dev')
  })

  it('修改映射后标记为 manual', () => {
    const payload = buildChannelPayload(
      baseForm,
      ['deepseek-v4.1-flash'],
      { 'deepseek-v4.1-flash': 'deepseek-flash-v2' },
      {
        'deepseek-v4.1-flash': {
          canonicalModelId: 'deepseek-flash',
          mappingSource: 'models_dev',
        },
      },
    )

    assert.equal(payload.modelMappings[0].mappingSource, 'manual')
  })

  it('提交密钥池时不发送旧版单凭据', () => {
    const payload = buildChannelPayload(
      { ...baseForm, credential: 'legacy-secret' },
      ['m1', 'm2'],
      {},
      {},
      [
        { id: 'key-1', name: ' primary ', credential: '', state: 'ACTIVE', priority: 1, models: ['m1', 'm1'] },
        { name: 'backup', credential: ' secret ', state: 'MANUAL_DISABLED', priority: 2, models: ['m2'] },
      ],
    )

    assert.deepEqual(payload.keys, [
      {
        id: 'key-1', name: 'primary', credential: null, state: 'ACTIVE', priority: 1, models: ['m1'],
        rateLimit: { enabled: false, requestsPerMinute: null, maxConcurrentRequests: null },
      },
      {
        id: null, name: 'backup', credential: 'secret', state: 'MANUAL_DISABLED', priority: 2, models: ['m2'],
        rateLimit: { enabled: false, requestsPerMinute: null, maxConcurrentRequests: null },
      },
    ])
  })

  it('多密钥模式下不提交顶层凭据，避免与 keys 互斥校验冲突', () => {
    const payload = buildChannelPayload(
      { ...baseForm, credential: 'legacy-secret' },
      ['m1'],
      {},
      {},
      [{ name: 'primary', credential: 'secret', state: 'ACTIVE', priority: 1, models: ['m1'] }],
    )
    assert.equal(payload.credential, null)
    assert.deepEqual(payload.keys.map((key) => key.name), ['primary'])
  })

  it('编辑已保存的密钥携带状态版本，避免覆盖同时发生的自动禁用', () => {
    const payload = buildChannelPayload(baseForm, ['m1'], {}, {}, [
      { id: 'key-1', stateVersion: 0, name: 'primary', state: 'MANUAL_DISABLED', priority: 1, models: ['m1'] },
    ])
    assert.equal(payload.keys[0].stateVersion, 0)
    assert.equal(payload.keys[0].state, 'MANUAL_DISABLED')
    assert.equal(payload.keys[0].credential, null)
    assert.deepEqual(payload.keys[0].rateLimit, {
      enabled: false,
      requestsPerMinute: null,
      maxConcurrentRequests: null,
    })
  })

  it('提交渠道密钥的 RPM 和最大并发规则', () => {
    const payload = buildChannelPayload(baseForm, ['m1'], {}, {}, [
      {
        id: 'key-1',
        name: 'primary',
        credential: '',
        state: 'ACTIVE',
        priority: 1,
        models: ['m1'],
        rateLimit: { enabled: true, requestsPerMinute: 60, maxConcurrentRequests: 3 },
      },
    ])

    assert.deepEqual(payload.keys[0].rateLimit, {
      enabled: true,
      requestsPerMinute: 60,
      maxConcurrentRequests: 3,
    })
  })

  it('提交 AUTO_* 状态被拒', () => {
    assert.throws(
      () => buildChannelPayload(baseForm, ['m1'], {}, {}, [
        { id: 'key-1', name: 'primary', state: 'AUTO_TEMPORARY', models: ['m1'] },
      ]),
      /ACTIVE 或 MANUAL_DISABLED/,
    )
    assert.throws(
      () => buildChannelPayload(baseForm, ['m1'], {}, {}, [
        { id: 'key-1', name: 'primary', state: 'AUTO_PERMANENT', models: ['m1'] },
      ]),
      /ACTIVE 或 MANUAL_DISABLED/,
    )
  })

  it('isKeyRoutable 判断密钥可路由性与临时到期边界', () => {
    const now = 1000
    assert.equal(isKeyRoutable({ state: 'ACTIVE' }, now), true)
    assert.equal(isKeyRoutable({ state: 'MANUAL_DISABLED' }, now), false)
    assert.equal(isKeyRoutable({ state: 'AUTO_PERMANENT' }, now), false)
    assert.equal(isKeyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 1001 }, now), false)
    assert.equal(isKeyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 1000 }, now), true)
    assert.equal(isKeyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: 999 }, now), true)
    assert.equal(isKeyRoutable({ state: 'AUTO_TEMPORARY', autoDisableUntil: null }, now), false)
    assert.equal(isKeyRoutable(null, now), false)
  })
})

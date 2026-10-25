import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildChannelPayload, buildPriorityUpdatePayload } from '../src/utils/channelForm.js'

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
      protocols: ['messages'],
      enabled: true,
      priority: 1,
    }

    assert.deepEqual(buildPriorityUpdatePayload(channel, 4), {
      name: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      authType: 'anthropic',
      protocols: ['messages'],
      enabled: true,
      priority: 4,
    })
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
})

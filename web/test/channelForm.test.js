import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildChannelPayload } from '../src/utils/channelForm.js'

describe('channelForm: 渠道保存请求体', () => {
  const baseForm = {
    name: ' deepseek ',
    baseUrl: ' https://example.com/v1 ',
    credential: '',
    protocols: ['chat_completions', 'responses'],
    enabled: true,
    exposeUpstreamModels: false,
  }

  it('编辑仅别名渠道时保留 exposeUpstreamModels=false', () => {
    const payload = buildChannelPayload(baseForm, ['deepseek-v4.1-flash'], {})

    assert.equal(payload.exposeUpstreamModels, false)
    assert.equal(payload.name, 'deepseek')
    assert.equal(payload.baseUrl, 'https://example.com/v1')
    assert.equal(payload.credential, null)
    assert.deepEqual(payload.protocols, ['chat_completions', 'responses'])
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
})

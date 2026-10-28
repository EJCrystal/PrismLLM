import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  MODEL_TEST_DEFAULT_PROMPT,
  buildModelTestRequest,
  formatModelTestUsage,
  modelTestProtocolLabel,
  normalizeModelTestSettings,
  supportsReasoningEffort,
} from '../src/utils/modelTest.js'

describe('modelTest: 渠道模型连通性测试', () => {
  it('设置缺失时回退到默认提示词', () => {
    assert.deepEqual(normalizeModelTestSettings(null), {
      defaultPrompt: MODEL_TEST_DEFAULT_PROMPT,
      defaultReasoningEffort: '',
    })
  })

  it('保留全局默认提示词和推理强度', () => {
    assert.deepEqual(normalizeModelTestSettings({
      defaultPrompt: '  reply ok  ',
      defaultReasoningEffort: ' high ',
    }), {
      defaultPrompt: 'reply ok',
      defaultReasoningEffort: 'high',
    })
  })

  it('Chat Completions 和 Responses 支持推理强度，Messages 不支持', () => {
    assert.equal(supportsReasoningEffort('chat_completions'), true)
    assert.equal(supportsReasoningEffort('responses'), true)
    assert.equal(supportsReasoningEffort('messages'), false)
  })

  it('构建测试请求时去掉首尾空白，Messages 不发送推理强度', () => {
    assert.deepEqual(buildModelTestRequest({
      model: ' model-a ',
      protocol: 'chat_completions',
      prompt: ' hello ',
      reasoningEffort: ' high ',
    }), {
      model: 'model-a',
      protocol: 'chat_completions',
      keyId: null,
      prompt: 'hello',
      reasoningEffort: 'high',
    })

    assert.equal(buildModelTestRequest({
      model: 'claude',
      protocol: 'messages',
      prompt: 'hello',
      reasoningEffort: 'high',
    }).reasoningEffort, null)
  })

  it('测试请求携带选中的密钥', () => {
    assert.equal(buildModelTestRequest({
      model: 'model-a',
      protocol: 'chat_completions',
      prompt: 'hello',
      keyId: ' key-1 ',
    }).keyId, 'key-1')
  })

  it('协议显示名称和用量摘要可读', () => {
    assert.equal(modelTestProtocolLabel('responses'), 'Responses')
    assert.equal(modelTestProtocolLabel('unknown'), 'unknown')
    assert.equal(formatModelTestUsage({
      inputTokens: 10,
      outputTokens: 3,
      reasoningTokens: 1,
      totalTokens: 13,
    }), '输入 10 · 输出 3 · 推理 1 · 总计 13')
  })
})

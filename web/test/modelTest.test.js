import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  MODEL_TEST_DEFAULT_PROMPT,
  buildModelTestRequest,
  formatModelTestUsage,
  modelTestProtocolLabel,
  modelTestTargetKey,
  normalizeModelTestSettings,
} from '../src/utils/modelTest'

describe('modelTest: 渠道模型连通性测试', () => {
  it('设置缺失时回退到默认提示词', () => {
    assert.deepEqual(normalizeModelTestSettings(null), {
      defaultPrompt: MODEL_TEST_DEFAULT_PROMPT,
    })
  })

  it('全局测试设置只保留默认提示词', () => {
    assert.deepEqual(normalizeModelTestSettings({
      defaultPrompt: '  reply ok  ',
      defaultReasoningEffort: ' high ',
    }), {
      defaultPrompt: 'reply ok',
    })
  })

  it('构建测试请求时去掉首尾空白且不发送推理强度', () => {
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
    })
  })

  it('测试请求携带选中的密钥', () => {
    assert.equal(buildModelTestRequest({
      model: 'model-a',
      protocol: 'chat_completions',
      prompt: 'hello',
      keyId: ' key-1 ',
    }).keyId, 'key-1')
  })

  it('用渠道与模型组合出唯一的目标 key', () => {
    assert.notEqual(modelTestTargetKey('c1', 'm1'), modelTestTargetKey('c2', 'm1'))
    assert.notEqual(modelTestTargetKey('c1', 'm1'), modelTestTargetKey('c1', 'm2'))
    assert.equal(modelTestTargetKey('c1', 'm1'), modelTestTargetKey('c1', 'm1'))
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

import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  MODEL_TEST_DEFAULT_PROMPT,
  buildModelTestRequest,
  formatModelTestUsage,
  modelTestErrorView,
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

  it('404 翻译成模型权限不足，并保留上游原文', () => {
    const view = modelTestErrorView({
      statusCode: 404,
      errorMessage: 'Requested entity was not found.',
    })
    assert.equal(view.title, '该账号无法调用这个模型')
    assert.equal(view.detail, 'Requested entity was not found.')
    assert.match(view.hint, /套餐|模型目录/)
  })

  it('鉴权与限流错误给出可执行的下一步', () => {
    assert.equal(modelTestErrorView({ statusCode: 401, errorMessage: 'Unauthorized' }).title, '账号凭据已失效')
    assert.equal(modelTestErrorView({ statusCode: 403, errorMessage: 'Permission denied' }).title, '账号无权调用这个模型')
    assert.equal(modelTestErrorView({ statusCode: 429, errorMessage: 'Rate limited' }).title, '上游限流')
    assert.equal(modelTestErrorView({ statusCode: 503, errorMessage: 'Unavailable' }).title, '上游暂时不可用')
  })

  it('没有状态码时识别超时、项目缺失与网络错误', () => {
    assert.equal(modelTestErrorView({ statusCode: null, errorMessage: '测试超时（45 秒）' }).title, '测试超时')
    assert.equal(
      modelTestErrorView({ statusCode: null, errorMessage: '未获取到 Antigravity 项目，无法发起请求；请确认账号已完成开通' }).title,
      '账号还没有 Antigravity 项目',
    )
    assert.equal(modelTestErrorView({ statusCode: null, errorMessage: '无法连接上游：connect timeout' }).title, '无法连接上游')
    assert.equal(modelTestErrorView(null), null)
  })

  it('未识别的错误保留原文，不吞掉排查信息', () => {
    const view = modelTestErrorView({ statusCode: 418, errorMessage: 'unexpected teapot' })
    assert.equal(view.title, 'unexpected teapot')
    assert.equal(view.detail, null)
    assert.equal(view.hint, null)
  })
})

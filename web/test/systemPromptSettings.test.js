import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { changeSystemPromptScope, createSystemPromptRule, systemPromptRuleError } from '../src/utils/systemPromptSettings'

describe('系统提示词规则编辑', () => {
  const valid = () => ({ ...createSystemPromptRule('rule-one'), name: '编码助手', prompt: '  exact prompt\n  ' })

  it('保留提示词原文并要求受限作用域有明确目标', () => {
    const rule = valid()
    assert.equal(systemPromptRuleError(rule, new Set()), null)
    rule.scope = 'channel_protocols'
    assert.match(systemPromptRuleError(rule, new Set()), /出口协议/)
    rule.protocols = ['gemini', 'messages']
    assert.match(systemPromptRuleError(rule, new Set()), /渠道/)
    rule.channelIds = ['channel-one', 'channel-two']
    assert.equal(systemPromptRuleError(rule, new Set(rule.channelIds)), null)
    assert.equal(rule.prompt, '  exact prompt\n  ')
  })

  it('切换作用域仅清理不再适用的目标', () => {
    const rule = { ...valid(), scope: 'channel_protocols', protocols: ['gemini'], channelIds: ['channel-one'] }
    rule.scope = 'channels'
    changeSystemPromptScope(rule)
    assert.deepEqual(rule.protocols, [])
    assert.deepEqual(rule.channelIds, ['channel-one'])
    rule.scope = 'global'
    changeSystemPromptScope(rule)
    assert.deepEqual(rule.channelIds, [])
  })

  it('拒绝已删除渠道、空正文和非法优先级，豁免规则无需正文', () => {
    const rule = { ...valid(), scope: 'channels', channelIds: ['deleted'] }
    assert.match(systemPromptRuleError(rule, new Set()), /已删除/)
    rule.scope = 'global'
    changeSystemPromptScope(rule)
    rule.prompt = '  '
    assert.match(systemPromptRuleError(rule, new Set()), /不能为空/)
    rule.action = 'preserve'
    assert.equal(systemPromptRuleError(rule, new Set()), null)
    rule.priority = 0.5
    assert.match(systemPromptRuleError(rule, new Set()), /整数/)
  })
})

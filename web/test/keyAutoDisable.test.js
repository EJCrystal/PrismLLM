import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_KEY_AUTO_DISABLE_SETTINGS,
  buildKeyAutoDisableSettingsPayload,
  fromKeyDisableRuleDraft,
  keyAutoDisableRuleLabel,
  normalizeKeyAutoDisableSettings,
  toKeyDisableRuleDraft,
} from '../src/utils/keyAutoDisable.js'

function customRule(overrides = {}) {
  return {
    id: 'custom_1',
    name: '自定义规则',
    statusCodes: [401, 403],
    statusCodeAtLeast: null,
    bodyPatterns: ['invalid_api_key'],
    threshold: 2,
    windowMinutes: 5,
    streak: true,
    action: 'TEMPORARY',
    disableMinutes: 30,
    includeTransportFailures: false,
    ...overrides,
  }
}

describe('密钥自动禁用规则', () => {
  it('规范化默认规则列表', () => {
    const normalized = normalizeKeyAutoDisableSettings(DEFAULT_KEY_AUTO_DISABLE_SETTINGS)
    assert.equal(normalized.enabled, true)
    assert.equal(normalized.rules.length, 4)
    assert.equal(normalized.rules[0].id, 'credential_invalid')
    assert.equal(normalized.rules[3].action, 'TEMPORARY')
  })

  it('保存规则列表并保留关闭状态', () => {
    const payload = buildKeyAutoDisableSettingsPayload({
      enabled: false,
      rules: [customRule()],
    })
    assert.equal(payload.enabled, false)
    assert.deepEqual(payload.rules, [customRule()])
  })

  it('编辑草稿与提交规则可以往返', () => {
    const rule = customRule()
    const draft = fromKeyDisableRuleDraft(toKeyDisableRuleDraft(rule))
    assert.deepEqual(
      buildKeyAutoDisableSettingsPayload({ enabled: true, rules: [draft] }).rules,
      [rule],
    )
  })

  it('兼容旧的标量设置响应', () => {
    const normalized = normalizeKeyAutoDisableSettings({
      enabled: true,
      authFailureThreshold: 3,
      failureThreshold: 5,
      failureWindowMinutes: 5,
    })
    assert.equal(normalized.rules.length, 2)
    assert.equal(normalized.rules[0].id, 'legacy_auth_streak')
    assert.equal(normalized.rules[1].id, 'legacy_failure_window')
  })

  it('拒绝非法规则', () => {
    for (const rule of [
      customRule({ id: 'Bad-Id' }),
      customRule({ name: '' }),
      customRule({ threshold: 0 }),
      customRule({ windowMinutes: 0 }),
      customRule({ statusCodes: [99] }),
      customRule({ statusCodes: [], statusCodeAtLeast: null, includeTransportFailures: false }),
      customRule({ action: 'PERMANENT' }),
      customRule({ action: 'TEMPORARY', disableMinutes: null }),
      customRule({ bodyPatterns: ['x'.repeat(201)] }),
    ]) {
      assert.throws(() => buildKeyAutoDisableSettingsPayload({ enabled: true, rules: [rule] }))
    }
    assert.throws(() => buildKeyAutoDisableSettingsPayload({
      enabled: true,
      rules: [customRule(), customRule()],
    }))
  })

  it('拒绝不完整的读取结果，不能用默认值覆盖加载失败的规则', () => {
    assert.throws(() => normalizeKeyAutoDisableSettings(null))
    assert.throws(() => normalizeKeyAutoDisableSettings({ enabled: true }))
    assert.throws(() => normalizeKeyAutoDisableSettings({ ...DEFAULT_KEY_AUTO_DISABLE_SETTINGS, enabled: 'false' }))
  })

  it('生成人类可读的规则摘要', () => {
    assert.equal(
      keyAutoDisableRuleLabel(DEFAULT_KEY_AUTO_DISABLE_SETTINGS),
      '凭据失效、上游账号被停、鉴权被拒、上游不稳定',
    )
    assert.equal(
      keyAutoDisableRuleLabel({ enabled: false, rules: [customRule({ name: '仅冷却' })] }),
      '已关闭自动禁用（仅冷却）',
    )
  })
})

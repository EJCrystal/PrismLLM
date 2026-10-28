import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_KEY_AUTO_DISABLE_SETTINGS,
  buildKeyAutoDisableSettingsPayload,
  keyAutoDisableRuleLabel,
  normalizeKeyAutoDisableSettings,
} from '../src/utils/keyAutoDisable.js'

describe('密钥自动禁用规则', () => {
  it('默认开启连续三次鉴权失败或五分钟内五次失败', () => {
    assert.deepEqual(normalizeKeyAutoDisableSettings(DEFAULT_KEY_AUTO_DISABLE_SETTINGS), {
      enabled: true, authFailureThreshold: 3, failureThreshold: 5, failureWindowMinutes: 5,
    })
    assert.equal(keyAutoDisableRuleLabel(DEFAULT_KEY_AUTO_DISABLE_SETTINGS), '连续 3 次 401/403，或 5 分钟内失败 5 次')
  })

  it('保存整数输入并保留关闭状态', () => {
    assert.deepEqual(buildKeyAutoDisableSettingsPayload({
      enabled: false, authFailureThreshold: '4', failureThreshold: 10, failureWindowMinutes: '15',
    }), { enabled: false, authFailureThreshold: 4, failureThreshold: 10, failureWindowMinutes: 15 })
    assert.equal(keyAutoDisableRuleLabel({ enabled: false, authFailureThreshold: 4, failureThreshold: 10, failureWindowMinutes: 15 }), '已关闭自动禁用（连续 4 次 401/403，或 15 分钟内失败 10 次）')
  })

  it('拒绝空值、小数、非数值和超出范围的阈值', () => {
    for (const value of ['', 0, -1, 1.5, 'invalid', Infinity, 101]) {
      assert.throws(() => buildKeyAutoDisableSettingsPayload({ ...DEFAULT_KEY_AUTO_DISABLE_SETTINGS, authFailureThreshold: value }))
    }
    assert.throws(() => buildKeyAutoDisableSettingsPayload({ ...DEFAULT_KEY_AUTO_DISABLE_SETTINGS, failureThreshold: 1001 }))
    assert.throws(() => buildKeyAutoDisableSettingsPayload({ ...DEFAULT_KEY_AUTO_DISABLE_SETTINGS, failureWindowMinutes: 1441 }))
  })

  it('拒绝不完整的读取结果，不能用默认值覆盖加载失败的规则', () => {
    assert.throws(() => normalizeKeyAutoDisableSettings(null))
    assert.throws(() => normalizeKeyAutoDisableSettings({ enabled: true }))
    assert.throws(() => normalizeKeyAutoDisableSettings({ ...DEFAULT_KEY_AUTO_DISABLE_SETTINGS, enabled: 'false' }))
  })
})

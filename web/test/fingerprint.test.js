import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  fingerprintBarWidths,
  fingerprintDecisionLabel,
  fingerprintDecisionTone,
  fingerprintSampleStatus,
  formatFingerprintProbability,
  formatFingerprintScore,
  parseFingerprintBankTime,
} from '../src/utils/fingerprint'

describe('fingerprint: 指纹检测展示', () => {
  it('判定结果有对应的中文标签与色调', () => {
    assert.equal(fingerprintDecisionLabel('scored'), '已完成排名')
    assert.equal(fingerprintDecisionLabel('partial'), '部分样本排名')
    assert.equal(fingerprintDecisionLabel('not_model'), '不像语言模型回答')
    assert.equal(fingerprintDecisionLabel('unscorable'), '需要三条完整回答')
    assert.equal(fingerprintDecisionTone('scored'), 'success')
    assert.equal(fingerprintDecisionTone('not_model'), 'danger')
    assert.equal(fingerprintDecisionTone('unscorable'), 'warning')
  })

  it('概率按百分比显示，极小值不伪装成 0%', () => {
    assert.equal(formatFingerprintProbability(0.9999994997), '99.9999%')
    assert.equal(formatFingerprintProbability(0.5), '50%')
    assert.equal(formatFingerprintProbability(0.0000001), '<0.01%')
    assert.equal(formatFingerprintProbability(0), '0%')
    assert.equal(formatFingerprintProbability(null), '—')
  })

  it('候选条宽度按分数区间归一化', () => {
    const widths = fingerprintBarWidths([
      { model: 'a', displayName: 'a', family: 'a', familyName: 'a', score: 4, probability: null },
      { model: 'b', displayName: 'b', family: 'b', familyName: 'b', score: 2, probability: null },
      { model: 'c', displayName: 'c', family: 'c', familyName: 'c', score: -2, probability: null },
    ])
    assert.equal(widths[0], 100)
    assert.equal(widths[2], 6)
    assert.ok(widths[1] > widths[2] && widths[1] < widths[0])
    assert.deepEqual(fingerprintBarWidths([]), [])
  })

  it('参考库时间兼容 6 位小数秒', () => {
    assert.equal(parseFingerprintBankTime('2026-11-09T19:31:55.474184+00:00'), Date.UTC(2026, 10, 9, 19, 31, 55, 474))
    assert.equal(parseFingerprintBankTime('not-a-date'), 0)
    assert.equal(parseFingerprintBankTime(null), 0)
  })

  it('分数与样本状态可读', () => {
    assert.equal(formatFingerprintScore(4.882661840816606), '4.883')
    assert.equal(formatFingerprintScore(null), '—')
    assert.equal(fingerprintSampleStatus(true, 218, 120), '有效 218 个整数')
    assert.equal(fingerprintSampleStatus(false, 42, 120), '仅 42 个整数，至少需要 120 个')
  })
})
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  auditActionLabel,
  auditResultClass,
  auditResultLabel,
  auditDetailsRows,
  auditDetailsSummary,
} from '../src/utils/adminAudit.js'

describe('adminAudit: 管理操作审计展示', () => {
  it('把已知动作翻译为可读文案，未知动作保留原值', () => {
    assert.equal(auditActionLabel('client_key.rotate'), '轮换调用方密钥')
    assert.equal(auditActionLabel('client_key.revoke'), '撤销调用方密钥')
    assert.equal(auditActionLabel('model_price.batch'), '批量套用模型单价')
    assert.equal(auditActionLabel('model_default_price.create'), '创建模型默认价')
    assert.equal(auditActionLabel('future.action'), 'future.action')
  })

  it('成功与失败使用明确文案和状态颜色', () => {
    assert.equal(auditResultLabel('success'), '成功')
    assert.equal(auditResultLabel('failure'), '失败')
    assert.equal(auditResultClass('success'), 'success')
    assert.equal(auditResultClass('failure'), 'danger')
  })

  it('展示自动禁用原因、计数和规则变更', () => {
    const entry = {
      action: 'channel_key.auto_disable',
      details: { channelName: 'openai', keyName: 'primary', message: '连续 3 次 401/403',
        authFailureStreak: 3, authFailureThreshold: 3, failureCount: 3, failureThreshold: 5, failureWindowMinutes: 5, httpStatus: 403 },
    }
    assert.equal(auditActionLabel(entry.action), '自动禁用上游密钥')
    assert.equal(auditDetailsSummary(entry), 'openai / primary：连续 3 次 401/403')
    assert.deepEqual(auditDetailsRows(entry).find(([label]) => label === '上游 HTTP'), ['上游 HTTP', 403])
    const update = { action: 'key_auto_disable_settings.update', details: { before: { enabled: false }, after: {
      enabled: true, authFailureThreshold: 3, failureThreshold: 5, failureWindowMinutes: 5,
    } } }
    assert.equal(auditDetailsRows(update)[0][1], '已关闭自动禁用')
    assert.equal(auditDetailsSummary(update), '连续 3 次 401/403，或 5 分钟内失败 5 次')
    assert.deepEqual(auditDetailsRows({ action: 'admin.login' }), [])
  })
})

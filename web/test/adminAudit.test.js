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
      details: {
        channelName: 'openai', keyName: 'primary', message: '鉴权被拒 3 次',
        ruleId: 'auth_rejected', ruleName: '鉴权被拒', count: 3, threshold: 3,
        action: 'TEMPORARY', state: 'AUTO_TEMPORARY', disableUntil: 1_700_000_000_000,
        httpStatus: 403, upstreamErrorCode: 'invalid_api_key',
      },
    }
    assert.equal(auditActionLabel(entry.action), '自动禁用上游密钥')
    assert.equal(auditDetailsSummary(entry), 'openai / primary：鉴权被拒 3 次')
    assert.deepEqual(auditDetailsRows(entry).find(([label]) => label === '上游 HTTP'), ['上游 HTTP', 403])
    assert.deepEqual(auditDetailsRows(entry).find(([label]) => label === '命中规则'), ['命中规则', '鉴权被拒（auth_rejected）'])
    const update = {
      action: 'key_auto_disable_settings.update',
      details: {
        before: { enabled: false, rules: [] },
        after: { enabled: true, rules: [{ name: '凭据失效' }, { name: '上游不稳定' }] },
      },
    }
    assert.equal(auditDetailsRows(update)[0][1], '已关闭自动禁用')
    assert.equal(auditDetailsSummary(update), '凭据失效、上游不稳定')
    assert.deepEqual(auditDetailsRows({ action: 'admin.login' }), [])
  })

  it('区分探测成功恢复与后台到期恢复', () => {
    const base = { channelName: 'openai', keyName: 'primary', channelId: 'c1', keyId: 'k1', previousState: 'AUTO_TEMPORARY', state: 'ACTIVE' }
    const probe = { action: 'channel_key.auto_recover', details: { ...base, reason: 'probe_success' } }
    const expired = { action: 'channel_key.auto_recover', details: { ...base, reason: 'expired' } }
    assert.equal(auditDetailsSummary(probe), 'openai / primary：探测成功恢复')
    assert.equal(auditDetailsSummary(expired), 'openai / primary：到期自动恢复')
    assert.deepEqual(auditDetailsRows(expired).find(([label]) => label === '恢复原因'), ['恢复原因', '到期自动恢复'])
  })
})

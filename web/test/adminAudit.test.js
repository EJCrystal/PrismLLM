import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  auditActionLabel,
  auditResultClass,
  auditResultLabel,
} from '../src/utils/adminAudit.js'

describe('adminAudit: 管理操作审计展示', () => {
  it('把已知动作翻译为可读文案，未知动作保留原值', () => {
    assert.equal(auditActionLabel('client_key.rotate'), '轮换调用方密钥')
    assert.equal(auditActionLabel('client_key.revoke'), '撤销调用方密钥')
    assert.equal(auditActionLabel('future.action'), 'future.action')
  })

  it('成功与失败使用明确文案和状态颜色', () => {
    assert.equal(auditResultLabel('success'), '成功')
    assert.equal(auditResultLabel('failure'), '失败')
    assert.equal(auditResultClass('success'), 'success')
    assert.equal(auditResultClass('failure'), 'danger')
  })
})

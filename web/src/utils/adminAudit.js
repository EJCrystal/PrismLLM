import { keyAutoDisableRuleLabel } from './keyAutoDisable.js'

const actionLabels = {
  'admin.login': '管理员登录',
  'admin.logout': '管理员退出',
  'admin.model_suggestions': '匹配参考模型',
  'admin.write': '其他管理操作',
  'account.avatar.update': '更新管理员头像',
  'account.password.update': '修改管理员密码',
  'channel.discover_models': '发现渠道模型',
  'channel.probe_protocols': '探测渠道协议',
  'channel.model_test': '测试渠道模型',
  'channel.create': '创建渠道',
  'channel.update': '更新渠道',
  'channel.delete': '删除渠道',
  'channel_key.auto_disable': '自动禁用上游密钥',
  'channel_key.auto_recover': '自动恢复上游密钥',
  'key_auto_disable_settings.update': '更新密钥自动禁用规则',
  'alias.create': '创建模型别名',
  'alias.update': '更新模型别名',
  'alias.delete': '删除模型别名',
  'client_key.create': '创建调用方密钥',
  'client_key.update': '更新调用方密钥',
  'client_key.delete': '删除调用方密钥',
  'client_key.rotate': '轮换调用方密钥',
  'client_key.revoke': '撤销调用方密钥',
  'model_price.create': '创建模型单价',
  'model_price.batch': '批量套用模型单价',
  'model_price.update': '更新模型单价',
  'model_price.delete': '删除模型单价',
  'model_default_price.create': '创建模型默认价',
  'model_default_price.update': '更新模型默认价',
  'model_default_price.delete': '删除模型默认价',
  'model_test_settings.update': '更新模型测试设置',
  'request_log.cleanup': '清理请求日志',
}

export function auditActionLabel(action) {
  return actionLabels[action] || action
}

export function auditResultLabel(result) {
  return result === 'success' ? '成功' : result === 'failure' ? '失败' : result
}

export function auditResultClass(result) {
  return result === 'success' ? 'success' : 'danger'
}

export function auditDetailsSummary(entry) {
  const details = entry?.details
  if (!details) return ''
  if (entry.action === 'channel_key.auto_disable') {
    return `${details.channelName || '—'} / ${details.keyName || '—'}：${details.message || '已禁用'}`
  }
  if (entry.action === 'channel_key.auto_recover') {
    const reason = details.reason === 'expired' ? '到期自动恢复' : '探测成功恢复'
    return `${details.channelName || '—'} / ${details.keyName || '—'}：${reason}`
  }
  if (entry.action === 'key_auto_disable_settings.update') return keyAutoDisableRuleLabel(details.after)
  return ''
}

export function auditDetailsRows(entry) {
  const details = entry?.details
  if (!details) return []
  if (entry.action === 'channel_key.auto_disable') {
    const count = details.count ?? details.authFailureStreak ?? details.failureCount
    const threshold = details.threshold ?? details.authFailureThreshold ?? details.failureThreshold
    const action = details.action === 'TEMPORARY'
      ? '临时停用'
      : details.action === 'PERMANENT' ? '永久停用' : '—'
    return [
      ['渠道 ID', details.channelId],
      ['密钥 ID', details.keyId],
      ['命中规则', details.ruleName ? `${details.ruleName}（${details.ruleId ?? '—'}）` : details.reason ?? '—'],
      ['命中次数', count != null ? `${count} 次 / 阈值 ${threshold ?? '—'} 次` : '—'],
      ['动作', action],
      ['恢复时间', details.disableUntil ? new Date(details.disableUntil).toLocaleString('zh-CN') : '—'],
      ['上游 HTTP', details.httpStatus ?? '—'],
      ['上游错误码', details.upstreamErrorCode ?? details.errorCode ?? '—'],
    ]
  }
  if (entry.action === 'channel_key.auto_recover') {
    return [
      ['渠道 ID', details.channelId],
      ['密钥 ID', details.keyId],
      ['原状态', details.previousState ?? '—'],
      ['当前状态', details.state ?? '—'],
      ['恢复原因', details.reason === 'probe_success' ? '到期探测成功' : details.reason === 'expired' ? '到期自动恢复' : details.reason ?? '—'],
    ]
  }
  if (entry.action === 'key_auto_disable_settings.update') {
    return [
      ['修改前', keyAutoDisableRuleLabel(details.before)],
      ['修改后', keyAutoDisableRuleLabel(details.after)],
    ]
  }
  return []
}

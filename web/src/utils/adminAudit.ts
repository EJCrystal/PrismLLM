import type { KeyAutoDisableSettingsResponse, JsonObject } from '../types/api'
import { keyAutoDisableRuleLabel } from './keyAutoDisable'

const actionLabels: Record<string, string> = {
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
  'oauth_account.import': '导入订阅账号',
  'oauth_account.policy.update': '更新订阅账号调度策略',
  'oauth_account.refresh': '刷新订阅账号 Token',
  'oauth_account.quota.refresh': '读取订阅账号上游额度',
  'oauth_account.cooldown.clear': '解除订阅账号冷却',
  'key_auto_disable_settings.update': '更新密钥自动禁用规则',
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
  'upstream_request_settings.update': '更新上游请求配置',
  'request_log.retention.update': '更新请求日志保留策略',
  'request_log.cleanup': '清理请求日志',
}

export const auditActionOptions = Object.entries(actionLabels)
  .map(([value, label]) => ({ value, label }))
  .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'))

export function auditActionLabel(action: string): string {
  return actionLabels[action] || action
}

export function auditResultLabel(result: string): string {
  return result === 'success' ? '成功' : result === 'failure' ? '失败' : String(result)
}

export function auditResultClass(result: string): string {
  return result === 'success' ? 'success' : 'danger'
}

interface AuditDetails {
  channelId?: string | number | null
  keyId?: string | number | null
  channelName?: string | null
  keyName?: string | null
  message?: string | null
  reason?: string | null
  ruleName?: string | null
  ruleId?: string | null
  count?: number | null
  authFailureStreak?: number | null
  failureCount?: number | null
  threshold?: number | null
  authFailureThreshold?: number | null
  failureThreshold?: number | null
  action?: string | null
  disableUntil?: number | string | null
  httpStatus?: number | null
  upstreamErrorCode?: string | null
  errorCode?: string | null
  previousState?: string | null
  state?: string | null
  provider?: string | null
  proxyUrl?: string | null
  result?: string | null
  windowLimit?: number | null
  windowSeconds?: number | null
  maxConcurrentRequests?: number | null
  before?: KeyAutoDisableSettingsResponse | null
  after?: KeyAutoDisableSettingsResponse | null
}

export interface AuditEntry {
  action: string
  details?: AuditDetails | JsonObject | null
}

export function auditDetailsSummary(entry: AuditEntry | null | undefined): string {
  const details = entry?.details as AuditDetails | undefined
  if (!details) return ''
  if (entry?.action === 'channel_key.auto_disable') {
    return `${details.channelName || '—'} / ${details.keyName || '—'}：${details.message || '已禁用'}`
  }
  if (entry?.action === 'channel_key.auto_recover') {
    const reason = details.reason === 'expired' ? '到期自动恢复' : '探测成功恢复'
    return `${details.channelName || '—'} / ${details.keyName || '—'}：${reason}`
  }
  if (entry?.action === 'key_auto_disable_settings.update') return keyAutoDisableRuleLabel(details.after as KeyAutoDisableSettingsResponse | null | undefined)
  return ''
}

export type AuditDetailValue = string | number

export function auditDetailsRows(entry: AuditEntry | null | undefined): AuditDetailValue[][] {
  const details = entry?.details as AuditDetails | undefined
  if (!details) return []
  if (entry?.action === 'channel_key.auto_disable') {
    const count = details.count ?? details.authFailureStreak ?? details.failureCount
    const threshold = details.threshold ?? details.authFailureThreshold ?? details.failureThreshold
    const action = details.action === 'TEMPORARY'
      ? '临时停用'
      : details.action === 'PERMANENT' ? '永久停用' : '—'
    return [
      ['渠道 ID', displayDetail(details.channelId)],
      ['密钥 ID', displayDetail(details.keyId)],
      ['命中规则', details.ruleName ? `${details.ruleName}（${details.ruleId ?? '—'}）` : displayDetail(details.reason)],
      ['命中次数', count != null ? `${count} 次 / 阈值 ${threshold ?? '—'} 次` : '—'],
      ['动作', action],
      ['恢复时间', details.disableUntil ? new Date(details.disableUntil).toLocaleString('zh-CN') : '—'],
      ['上游 HTTP', displayDetail(details.httpStatus)],
      ['上游错误码', details.upstreamErrorCode ?? details.errorCode ?? '—'],
    ]
  }
  if (entry?.action === 'channel_key.auto_recover') {
    return [
      ['渠道 ID', displayDetail(details.channelId)],
      ['密钥 ID', displayDetail(details.keyId)],
      ['原状态', displayDetail(details.previousState)],
      ['当前状态', displayDetail(details.state)],
      ['恢复原因', details.reason === 'probe_success' ? '到期探测成功' : details.reason === 'expired' ? '到期自动恢复' : displayDetail(details.reason)],
    ]
  }
  if (entry?.action === 'key_auto_disable_settings.update') {
    return [
      ['修改前', keyAutoDisableRuleLabel(details.before)],
      ['修改后', keyAutoDisableRuleLabel(details.after)],
    ]
  }
  if (entry?.action === 'oauth_account.import') {
    return [
      ['渠道 ID', displayDetail(details.channelId)],
      ['密钥 ID', displayDetail(details.keyId)],
      ['提供方', displayDetail(details.provider)],
      ['账号代理', displayDetail(details.proxyUrl)],
    ]
  }
  if (entry?.action === 'oauth_account.policy.update') {
    return [
      ['密钥 ID', displayDetail(details.keyId)],
      ['窗口请求数', details.windowLimit == null ? '不限制' : details.windowLimit],
      ['窗口时长（秒）', displayDetail(details.windowSeconds)],
      ['单号最大并发', displayDetail(details.maxConcurrentRequests)],
    ]
  }
  if (entry?.action === 'oauth_account.refresh') {
    return [
      ['密钥 ID', displayDetail(details.keyId)],
      ['结果', details.result === 'success' ? '成功' : details.result === 'failed' ? '失败' : displayDetail(details.result)],
      ['错误码', displayDetail(details.errorCode)],
    ]
  }
  if (entry?.action === 'oauth_account.quota.refresh') {
    return [
      ['密钥 ID', displayDetail(details.keyId)],
      ['结果', details.result === 'success' ? '成功' : details.result === 'failed' ? '失败' : displayDetail(details.result)],
      ['错误码', displayDetail(details.errorCode)],
    ]
  }
  if (entry?.action === 'oauth_account.cooldown.clear') {
    return [['密钥 ID', displayDetail(details.keyId)]]
  }
  return []
}

function displayDetail(value: string | number | null | undefined): AuditDetailValue {
  return value == null ? '—' : value
}

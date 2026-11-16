import type {
  Channel,
  NotificationDeliveryStatus,
  NotificationEventType,
  NotificationSettings,
  NotificationTargetRequest,
  NotificationTargetResponse,
} from '../types/api'

export const notificationEventOptions: { value: NotificationEventType; label: string; description: string }[] = [
  { value: 'channel.unhealthy', label: '渠道异常', description: '渠道健康状态转为异常。' },
  { value: 'channel.recovered', label: '渠道恢复', description: '异常渠道恢复可用。' },
  { value: 'key.disabled', label: '上游渠道密钥禁用', description: '渠道中的上游密钥被禁用。' },
  { value: 'key.recovered', label: '上游渠道密钥恢复', description: '已禁用的上游密钥恢复。' },
  { value: 'attempt.consecutive_failures', label: '上游连续失败', description: '上游尝试连续失败达到全局阈值。' },
  { value: 'attempt.recovered', label: '连续失败恢复', description: '连续失败后再次出现成功尝试。' },
]

export function notificationThresholdError(value: unknown): string {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 100
    ? ''
    : '连续失败阈值必须是 1 到 100 之间的整数。'
}

export function notificationTargetError(
  payload: NotificationTargetRequest,
  channels?: Pick<Channel, 'id'>[],
  allChannels = payload.channelIds.length === 0,
): string {
  if (!payload.name.trim() || payload.name.trim().length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(payload.name)) {
    return '目标名称需为 1 到 100 个字符，且不含控制字符。'
  }
  if (payload.type !== 'webhook' && payload.type !== 'feishu') return '请选择 Webhook 或飞书机器人。'
  const supported = new Set<string>(notificationEventOptions.map(event => event.value))
  if (!payload.events.length || payload.events.length > notificationEventOptions.length || payload.events.some(event => !supported.has(event))) {
    return '请至少选择一种有效的通知事件。'
  }
  if (!allChannels && payload.channelIds.length === 0) return '请选择至少一个渠道，或启用“全部渠道”。'
  if (!allChannels && payload.channelIds.length > 100) return '单个通知目标最多选择 100 个渠道。'
  if (!allChannels && payload.channelIds.some(id => id.length > 100)) return '渠道 ID 不能超过 100 个字符。'
  if (!allChannels && channels && payload.channelIds.some(id => !channels.some(channel => channel.id === id))) {
    return '所选渠道中有已删除或不存在的渠道，请移除后保存。'
  }
  return ''
}

export function notificationCredentialUrlError(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return ''
  if (trimmed.length > 2048) return '接收端 URL 不能超过 2048 个字符。'
  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    return '请填写完整的 HTTP 或 HTTPS 地址。'
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return '接收端 URL 仅支持 HTTP 或 HTTPS。'
  if (parsed.username || parsed.password) return '接收端 URL 不能包含账号或密码。'
  return ''
}

export function notificationCredentialSecretError(value: string, label: string): string {
  if (!value) return ''
  if (value.length > 4096) return `${label}不能超过 4096 个字符。`
  if (/[\u0000-\u001f\u007f-\u009f]/.test(value)) return `${label}不能包含控制字符。`
  if (/[^\x21-\x7e]/.test(value)) return `${label}只能包含可打印 ASCII 字符。`
  return ''
}

export function notificationTestDisabledReason(settings: NotificationSettings | null, target: NotificationTargetResponse): string {
  if (!settings?.enabled) return '请先启用并保存全局通知设置。'
  if (!target.enabled) return '请先启用并保存此通知目标。'
  const credentials = target.credentials
  if (credentials?.urlUnreadable || credentials?.bearerTokenUnreadable || credentials?.signingSecretUnreadable) {
    return '目标凭据无法解密，请在编辑器中重新填写。'
  }
  if (!credentials?.urlConfigured) return '请先在编辑器中填写接收端 URL，再刷新目标状态。'
  return ''
}

export function notificationEventLabel(event: string): string {
  if (event === 'notification.test') return '测试通知'
  return notificationEventOptions.find(option => option.value === event)?.label ?? event
}

export function notificationDeliveryStatusLabel(status: NotificationDeliveryStatus): string {
  const labels: Record<NotificationDeliveryStatus, string> = {
    pending: '等待投递',
    in_flight: '投递中',
    succeeded: '投递成功',
    failed: '投递失败',
    cancelled: '已取消',
  }
  return labels[status] ?? status
}

export function notificationDeliveryFinished(status: NotificationDeliveryStatus): boolean {
  return status === 'succeeded' || status === 'failed' || status === 'cancelled'
}

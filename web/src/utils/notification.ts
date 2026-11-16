import type {
  Channel,
  NotificationDeliveryStatus,
  NotificationEventType,
  NotificationSettings,
  NotificationTargetRequest,
  NotificationTargetResponse,
} from '../types/api'

export const notificationEnvironmentPrefixPattern = /^PRISM_NOTIFY_[A-Z][A-Z0-9_]{0,47}$/

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
  if (!notificationEnvironmentPrefixPattern.test(payload.environmentPrefix)) {
    return '环境变量前缀须以 PRISM_NOTIFY_ 开头，后接 1 到 48 位大写字母、数字或下划线，且首位为字母。'
  }
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

export function notificationTestDisabledReason(settings: NotificationSettings | null, target: NotificationTargetResponse): string {
  if (!settings?.enabled) return '请先启用并保存全局通知设置。'
  if (!target.enabled) return '请先启用并保存此通知目标。'
  if (!target.credentials?.urlConfigured) return `请先配置 ${target.environmentPrefix}_URL，再刷新目标状态。`
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

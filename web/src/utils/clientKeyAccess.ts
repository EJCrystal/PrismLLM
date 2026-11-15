import type { ClientKeyAccessPolicy, ClientKeyAllowedChannelKey } from '../types/api'

/** 为新表单创建独立的默认访问范围。 */
export function createUnrestrictedAccessPolicy(): ClientKeyAccessPolicy {
  return {
    channels: { mode: 'unrestricted', ids: [] },
    channelKeys: { mode: 'unrestricted', keys: [] },
    models: { mode: 'unrestricted', ids: [] },
  }
}

/** 复制编辑草稿或构造完整请求；保留未知目标，由管理 API 校验存在性与长度。 */
export function normalizeClientKeyAccessPolicy(policy: ClientKeyAccessPolicy): ClientKeyAccessPolicy {
  const ids = (values: string[]) => [...new Set(values.map(value => value.trim()))]
  const keys = new Map<string, { channelId: string; keyId: string }>()
  for (const value of policy.channelKeys.keys) {
    const pair = { channelId: value.channelId.trim(), keyId: value.keyId.trim() }
    keys.set(JSON.stringify([pair.channelId, pair.keyId]), pair)
  }
  return {
    channels: { mode: policy.channels.mode, ids: policy.channels.mode !== 'unrestricted' ? ids(policy.channels.ids) : [] },
    channelKeys: { mode: policy.channelKeys.mode, keys: policy.channelKeys.mode !== 'unrestricted' ? [...keys.values()] : [] },
    models: { mode: policy.models.mode, ids: policy.models.mode !== 'unrestricted' ? ids(policy.models.ids) : [] },
  }
}

/** 密钥白名单中所属渠道不在渠道白名单（或被渠道黑名单排除）的密钥；后端会拒绝保存该组合。 */
export function conflictingClientKeyAccessKeys(policy: ClientKeyAccessPolicy): ClientKeyAllowedChannelKey[] {
  if (policy.channelKeys.mode !== 'allowlist') return []
  return policy.channelKeys.keys.filter(pair => policy.channels.mode === 'allowlist'
    ? !policy.channels.ids.includes(pair.channelId)
    : policy.channels.mode === 'denylist' && policy.channels.ids.includes(pair.channelId))
}

/** 展示保存的模式与数量，不将空白名单误报为不限制。 */
export function clientKeyAccessSummary(policy: ClientKeyAccessPolicy): string {
  const dimensions = [
    { label: '渠道', mode: policy.channels.mode, count: policy.channels.ids.length },
    { label: '渠道密钥', mode: policy.channelKeys.mode, count: policy.channelKeys.keys.length },
    { label: '模型', mode: policy.models.mode, count: policy.models.ids.length },
  ]
  if (dimensions.every(scope => scope.mode === 'unrestricted')) return '不限制访问范围'
  return dimensions.map(scope => scope.mode === 'unrestricted'
    ? `${scope.label}不限制`
    : scope.mode === 'denylist' ? `${scope.label}黑名单 ${scope.count} 项`
    : scope.count === 0 ? `${scope.label}无授权目标` : `${scope.label} ${scope.count} 项`).join(' · ')
}

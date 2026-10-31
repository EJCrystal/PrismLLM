import type { ChannelQuotaSettings, ChannelQuotaSettingsRequest, ChannelQuotaSnapshot } from '../types/api'

export function buildQuotaSettings(providerId: string, configText: string): ChannelQuotaSettings {
  if (!providerId) return { providerId: '', config: {} }
  let config: unknown
  try { config = JSON.parse(configText.trim() || '{}') } catch { throw new Error('插件配置必须是有效的 JSON 对象') }
  if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new Error('插件配置必须是 JSON 对象')
  if (new TextEncoder().encode(JSON.stringify(config)).length > 8192) throw new Error('插件配置不能超过 8 KB')
  return { providerId, config: config as ChannelQuotaSettings['config'] }
}

export function buildNewApiQuotaSettings(configText: string, options: {
  mode: 'token' | 'system'; userId: string; credential?: string; clearCredential?: boolean
}): ChannelQuotaSettingsRequest {
  const settings: ChannelQuotaSettingsRequest = buildQuotaSettings('new-api', configText)
  settings.config.authMode = options.mode
  if (options.mode === 'system') {
    const userId = options.userId.trim()
    if (!/^[0-9]{1,19}$/.test(userId) || BigInt(userId) <= 0n || BigInt(userId) > 9223372036854775807n) {
      throw new Error('New API 用户 ID 必须为正整数')
    }
    settings.config.userId = BigInt(userId).toString()
    settings.credentialSource = 'DEDICATED'
    const credential = options.credential?.trim()
    if (credential) settings.credential = credential
    if (options.clearCredential) settings.clearCredential = true
    if (settings.clearCredential && credential) throw new Error('不能同时更换和清除系统访问令牌')
  } else {
    delete settings.config.userId
    settings.credentialSource = 'CHANNEL_KEY'
  }
  if (new TextEncoder().encode(JSON.stringify(settings.config)).length > 8192) throw new Error('插件配置不能超过 8 KB')
  return settings
}

export function quotaStatus(result: Pick<ChannelQuotaSnapshot, 'status'>): { label: string; className: string } {
  switch (result.status) {
    case 'AVAILABLE': return { label: '已查询', className: 'success' }
    case 'UNLIMITED': return { label: '无限制', className: 'success' }
    case 'UNSUPPORTED': return { label: '无法查询共享额度', className: 'warning' }
    case 'AUTHENTICATION_FAILED': return { label: '上游认证失败', className: 'danger' }
    case 'RATE_LIMITED': return { label: '查询受限', className: 'warning' }
    case 'DISABLED': return { label: '未启用', className: 'neutral' }
    default: return { label: '查询失败', className: 'danger' }
  }
}

export function quotaBalance(result: ChannelQuotaSnapshot): string {
  if (result.status === 'UNLIMITED') return '无限制'
  if (result.status !== 'AVAILABLE' || result.remaining == null) return '—'
  return result.remaining + (result.unit ? ' ' + result.unit : '')
}

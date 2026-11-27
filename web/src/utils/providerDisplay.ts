import type { Channel, OAuthAccount, OAuthProviderInfo } from '../types/api'
import { channelDisplayModels, type ChannelStatusFilter } from './channelDisplay'
import {
  isOAuthAccountCooling,
  isOAuthQuotaBlocked,
  oauthAccountMatchesStatus,
  oauthProviderLabel,
  oauthProviderProtocols,
} from './oauthAccount'

export type ProviderKindFilter = 'all' | 'custom' | 'subscription'

export interface SubscriptionAccountSummary {
  total: number
  /** 已启用、凭据有效、未冷却且未被额度阻断，当前可以真正参与调度。 */
  schedulable: number
  ready: number
  reauth: number
  cooling: number
  quotaBlocked: number
  disabled: number
}

/** 与后端账号准入对齐的实时可调度判断（不含瞬时并发占用）。 */
export function isOAuthAccountSchedulable(account: Pick<OAuthAccount, 'enabled' | 'authState' | 'quota' | 'runtime'>, now: number): boolean {
  return account.enabled
    && account.authState === 'ACTIVE'
    && !isOAuthAccountCooling(account, now)
    && !isOAuthQuotaBlocked(account, now)
    && account.quota?.allowed !== false
}

export interface ProviderListItem {
  /** 列表稳定键：已置备/自定义渠道用 channelId，未置备内置订阅提供商用 `oauth:<providerId>`。 */
  key: string
  kind: 'custom' | 'subscription'
  /** UI 展示名称；内置订阅渠道自动去掉历史的「（系统）」后缀。 */
  displayName: string
  /** 列表展示通道；订阅渠道的 models 限于启用账号授权范围，原始目录不变。 */
  channel: Channel | null
  oauthProvider: OAuthProviderInfo | null
  providerId: string | null
  baseUrl: string
  protocols: string[]
  enabled: boolean
  configured: boolean
  accounts: OAuthAccount[]
  accountSummary: SubscriptionAccountSummary
  updatedAt: number | null
}

export interface SubscriptionAlertSummary {
  reauthCount: number
  coolingCount: number
  quotaBlockedCount: number
  firstReauthProviderId: string | null
  firstCoolingProviderId: string | null
  firstQuotaBlockedProviderId: string | null
}

const SYSTEM_SUFFIX_RE = /[（(]系统[）)]$/

/** 统一展示名：官方订阅渠道去掉历史「（系统）」后缀，自定义渠道保持原名。 */
export function providerDisplayName(name: string, kind: 'custom' | 'subscription'): string {
  const trimmed = name.trim()
  if (kind !== 'subscription') return trimmed
  return trimmed.replace(SYSTEM_SUFFIX_RE, '').trim() || trimmed
}

export function summarizeSubscriptionAccounts(
  accounts: readonly OAuthAccount[],
  now: number,
): SubscriptionAccountSummary {
  let schedulable = 0
  let ready = 0
  let reauth = 0
  let cooling = 0
  let quotaBlocked = 0
  let disabled = 0
  for (const account of accounts) {
    if (isOAuthAccountSchedulable(account, now)) schedulable++
    if (oauthAccountMatchesStatus(account, 'ready', now)) ready++
    if (account.authState === 'REAUTH_REQUIRED') reauth++
    if (isOAuthAccountCooling(account, now)) cooling++
    if (isOAuthQuotaBlocked(account, now)) quotaBlocked++
    if (!account.enabled) disabled++
  }
  return { total: accounts.length, schedulable, ready, reauth, cooling, quotaBlocked, disabled }
}

export function summarizeSubscriptionAlerts(
  accounts: readonly OAuthAccount[],
  now: number,
): SubscriptionAlertSummary {
  let reauthCount = 0
  let coolingCount = 0
  let quotaBlockedCount = 0
  let firstReauthProviderId: string | null = null
  let firstCoolingProviderId: string | null = null
  let firstQuotaBlockedProviderId: string | null = null
  for (const account of accounts) {
    if (account.authState === 'REAUTH_REQUIRED') {
      reauthCount++
      if (!firstReauthProviderId) firstReauthProviderId = account.provider
    }
    if (isOAuthAccountCooling(account, now)) {
      coolingCount++
      if (!firstCoolingProviderId) firstCoolingProviderId = account.provider
    }
    if (isOAuthQuotaBlocked(account, now)) {
      quotaBlockedCount++
      if (!firstQuotaBlockedProviderId) firstQuotaBlockedProviderId = account.provider
    }
  }
  return {
    reauthCount,
    coolingCount,
    quotaBlockedCount,
    firstReauthProviderId,
    firstCoolingProviderId,
    firstQuotaBlockedProviderId,
  }
}

function defaultSubscriptionTitle(provider: OAuthProviderInfo): string {
  if (provider.id === 'chatgpt') return 'OpenAI 订阅'
  if (provider.id === 'antigravity') return 'Google Antigravity'
  return oauthProviderLabel(provider.id) || provider.label
}

/**
 * 将后端返回的渠道（含 custom 与 system provider）、内置 OAuth 提供方定义与订阅账号
 * 归并成统一的「提供商」列表。官方订阅排在前面（按提供方定义顺序），自定义 API 随后。
 */
export function buildProviderListItems(
  channels: readonly Channel[],
  oauthProviders: readonly OAuthProviderInfo[],
  oauthAccounts: readonly OAuthAccount[],
  now: number,
): ProviderListItem[] {
  const accountsByProvider = new Map<string, OAuthAccount[]>()
  for (const account of oauthAccounts) {
    const list = accountsByProvider.get(account.provider) ?? []
    list.push(account)
    accountsByProvider.set(account.provider, list)
  }

  const providerChannelsByProvider = new Map<string, Channel>()
  const providerChannelsById = new Map<string, Channel>()
  const customChannels: Channel[] = []
  for (const channel of channels) {
    if (channel.kind === 'provider') {
      const enabledModels = new Set(channel.enabledModels ?? [])
      const displayChannel = { ...channel, models: channel.models.filter((model) => enabledModels.has(model)) }
      providerChannelsById.set(channel.id, displayChannel)
      if (channel.provider) providerChannelsByProvider.set(channel.provider, displayChannel)
    } else {
      customChannels.push(channel)
    }
  }

  const seenProviderIds = new Set<string>()
  const subscriptionItems: ProviderListItem[] = []

  for (const provider of oauthProviders) {
    seenProviderIds.add(provider.id)
    const channel = (provider.channelId ? providerChannelsById.get(provider.channelId) : undefined)
      ?? providerChannelsByProvider.get(provider.id)
      ?? null
    const accounts = accountsByProvider.get(provider.id) ?? []
    subscriptionItems.push({
      key: channel ? channel.id : `oauth:${provider.id}`,
      kind: 'subscription',
      displayName: channel ? providerDisplayName(channel.name, 'subscription') : defaultSubscriptionTitle(provider),
      channel,
      oauthProvider: provider,
      providerId: provider.id,
      baseUrl: channel?.baseUrl || provider.baseUrl || '',
      protocols: channel?.protocols?.length
        ? channel.protocols
        : provider.protocols?.length
          ? provider.protocols
          : oauthProviderProtocols(provider.id),
      enabled: channel ? channel.enabled : false,
      configured: provider.configured,
      accounts,
      accountSummary: summarizeSubscriptionAccounts(accounts, now),
      updatedAt: channel?.updatedAt ?? null,
    })
  }

  // 兜底：存在后端已置备的 provider 渠道但不在 oauthProviders 列表里时仍然展示，不丢数据。
  for (const channel of providerChannelsById.values()) {
    const pid = channel.provider ?? ''
    if (pid && seenProviderIds.has(pid)) continue
    const accounts = pid ? (accountsByProvider.get(pid) ?? []) : []
    subscriptionItems.push({
      key: channel.id,
      kind: 'subscription',
      displayName: providerDisplayName(channel.name, 'subscription'),
      channel,
      oauthProvider: null,
      providerId: pid || null,
      baseUrl: channel.baseUrl,
      protocols: channel.protocols,
      enabled: channel.enabled,
      configured: true,
      accounts,
      accountSummary: summarizeSubscriptionAccounts(accounts, now),
      updatedAt: channel.updatedAt,
    })
  }

  const customItems: ProviderListItem[] = customChannels.map((channel) => ({
    key: channel.id,
    kind: 'custom',
    displayName: providerDisplayName(channel.name, 'custom'),
    channel,
    oauthProvider: null,
    providerId: null,
    baseUrl: channel.baseUrl,
    protocols: channel.protocols,
    enabled: channel.enabled,
    configured: true,
    accounts: [],
    accountSummary: summarizeSubscriptionAccounts([], now),
    updatedAt: channel.updatedAt,
  }))

  return [...subscriptionItems, ...customItems]
}

export function providerModelFilterOptions(items: readonly ProviderListItem[]): string[] {
  const models = new Set<string>()
  for (const item of items) {
    if (item.channel) {
      for (const model of channelDisplayModels(item.channel)) models.add(model.displayName)
    }
  }
  return [...models].sort()
}

export function providerMatchesFilters(
  item: ProviderListItem,
  kind: ProviderKindFilter,
  status: ChannelStatusFilter,
  model: string,
  keyword: string,
): boolean {
  if (kind !== 'all' && item.kind !== kind) return false
  if (status === 'enabled' && !item.enabled) return false
  if (status === 'disabled' && item.enabled) return false
  const query = keyword.trim().toLocaleLowerCase()
  if (query) {
    const matchName = item.displayName.toLocaleLowerCase().includes(query)
      || (item.channel?.name ?? '').toLocaleLowerCase().includes(query)
      || (item.providerId ?? '').toLocaleLowerCase().includes(query)
      || item.accounts.some((account) =>
        account.name.toLocaleLowerCase().includes(query)
        || (account.accountEmail ?? '').toLocaleLowerCase().includes(query),
      )
    if (!matchName) return false
  }
  if (model) {
    if (!item.channel) return false
    if (!channelDisplayModels(item.channel).some((entry) => entry.displayName === model)) return false
  }
  return true
}

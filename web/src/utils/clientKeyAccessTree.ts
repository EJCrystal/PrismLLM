import type { Channel, ChannelKeyState, ClientKeyAccessMode, ClientKeyAccessPolicy, ClientKeyAllowedChannelKey, ClientKeyCredentialType, ModelRouteAccessOption } from '../types/api'

/**
 * 「渠道与密钥」合并卡片的双向映射。
 *
 * 卡片只有一个模式选择器，同时驱动 `channels` 与 `channelKeys` 两个维度；
 * 渠道行是父节点、静态密钥是子节点，策略本身仍是唯一事实源，树的勾选状态全部由策略推导。
 *
 * 关键不变量：
 * - 渠道白名单维度：`channelKeys.keys` 非空 ⟺ `channelKeys.mode === 'allowlist'`（有密钥级选择才启用密钥维度）；
 *   渠道白名单必须包含全部已选密钥的所属渠道（后端校验要求），勾选子密钥时自动补齐。
 * - 渠道黑名单维度：父渠道排除已覆盖其全部子密钥，勾选父渠道时丢弃该渠道的密钥名单。
 * - 白名单模式下勾选父渠道仅在密钥维度已启用时展开子密钥，否则保持渠道级语义（渠道新增密钥自动放行）。
 */

export type ChannelCheckState = 'checked' | 'indeterminate' | 'unchecked'

export interface ChannelCheckInfo {
  state: ChannelCheckState
  /** 该渠道下已选中的静态密钥数。 */
  selectedKeys: number
  /** 该渠道下的静态密钥总数。 */
  totalKeys: number
  /** 该渠道下已选中的订阅账号数。 */
  selectedOAuth: number
  /** 该渠道下的订阅账号总数。 */
  totalOAuth: number
  /** 静态密钥与订阅账号合计。 */
  selectedCredentials: number
  totalCredentials: number
}

function credentialTypeOf(pair: ClientKeyAllowedChannelKey): ClientKeyCredentialType {
  return pair.credentialType === 'oauth' ? 'oauth' : 'static'
}

/** 统一的凭据身份；静态密钥保持旧的无类型对象，OAuth 显式带类型。 */
export function credentialIdentity(
  channelId: string,
  keyId: string,
  credentialType: ClientKeyCredentialType,
): ClientKeyAllowedChannelKey {
  return credentialType === 'oauth' ? { channelId, keyId, credentialType } : { channelId, keyId }
}

export function sameAccessKey(left: ClientKeyAllowedChannelKey, right: ClientKeyAllowedChannelKey): boolean {
  return left.channelId === right.channelId && left.keyId === right.keyId &&
    credentialTypeOf(left) === credentialTypeOf(right)
}

/** UUID 展示为短胶囊：去掉连字符取前 6 位。 */
export function shortId(id: string): string {
  return '#' + id.replace(/-/g, '').slice(0, 6)
}

/** 合并卡片展示的模式：渠道维度受限时以渠道为准，否则取密钥维度。 */
export function mergedAccessMode(policy: ClientKeyAccessPolicy): ClientKeyAccessMode {
  return policy.channels.mode !== 'unrestricted' ? policy.channels.mode : policy.channelKeys.mode
}

/**
 * 渠道行勾选状态。
 * - 白名单 + 密钥维度未启用：渠道在名单中即全选（子密钥随渠道放行）。
 * - 白名单 + 密钥维度已启用：全部子密钥在名单中为全选，部分为半选。
 * - 黑名单：渠道在名单中即整渠道排除，子密钥随渠道排除。
 *
 * 分支一律按合并卡片的展示模式派发：渠道维度未限制、密钥维度受限的旧策略
 * 也按其实际卡片模式（黑名单）渲染与操作，避免把排除名单当放行名单使用。
 */
export function channelCheckInfo(policy: ClientKeyAccessPolicy, channel: Channel | undefined): ChannelCheckInfo {
  const keys = channel?.keys ?? []
  const accounts = channel?.oauthAccounts ?? []
  const totalKeys = keys.length
  const totalOAuth = accounts.length
  const selectedKeys = policy.channelKeys.mode === 'unrestricted'
    ? 0
    : keys.filter(key => policy.channelKeys.keys.some(pair =>
        sameAccessKey(pair, credentialIdentity(channel!.id, key.id, 'static')))).length
  const selectedOAuth = policy.channelKeys.mode === 'unrestricted'
    ? 0
    : accounts.filter(account => policy.channelKeys.keys.some(pair =>
        sameAccessKey(pair, credentialIdentity(channel!.id, account.id, 'oauth')))).length
  const selectedCredentials = selectedKeys + selectedOAuth
  const totalCredentials = totalKeys + totalOAuth
  const channelSelected = policy.channels.ids.includes(channel?.id ?? '')
  const merged = mergedAccessMode(policy)

  if (merged === 'allowlist') {
    if (!channelSelected) return { state: selectedCredentials > 0 ? 'indeterminate' : 'unchecked', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
    if (policy.channelKeys.mode === 'unrestricted') return { state: 'checked', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
    if (selectedCredentials === 0) return { state: 'unchecked', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
    return { state: selectedCredentials === totalCredentials ? 'checked' : 'indeterminate', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
  }
  // 黑名单：渠道排除覆盖全部凭据；未排除渠道时按已选凭据数呈现半选。
  if (channelSelected) return { state: 'checked', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
  return selectedCredentials === 0
    ? { state: 'unchecked', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
    : { state: 'indeterminate', selectedKeys, totalKeys, selectedOAuth, totalOAuth, selectedCredentials, totalCredentials }
}

/** 静态密钥行是否勾选：在密钥名单中，或渠道级选中（白名单 + 密钥维度未启用）时随渠道勾选。 */
export function keyChecked(policy: ClientKeyAccessPolicy, channel: Channel | undefined, keyId: string): boolean {
  const identity = credentialIdentity(channel?.id ?? '', keyId, 'static')
  if (policy.channelKeys.keys.some(pair => sameAccessKey(pair, identity))) return true
  if (mergedAccessMode(policy) === 'allowlist' && policy.channelKeys.mode === 'unrestricted') {
    return policy.channels.ids.includes(channel?.id ?? '')
  }
  return false
}

/** OAuth 行是否勾选：在凭据名单中，或渠道级选中（白名单 + 密钥维度未启用）时随渠道勾选。 */
export function oauthChecked(policy: ClientKeyAccessPolicy, channel: Channel | undefined, accountId: string): boolean {
  const identity = credentialIdentity(channel?.id ?? '', accountId, 'oauth')
  if (policy.channelKeys.keys.some(pair => sameAccessKey(pair, identity))) return true
  if (mergedAccessMode(policy) === 'allowlist' && policy.channelKeys.mode === 'unrestricted') {
    return policy.channels.ids.includes(channel?.id ?? '')
  }
  return false
}

/** 渠道在当前策略下是否整体放行（渠道维度判断，不含凭据维度）。 */
function channelInScope(policy: ClientKeyAccessPolicy, channelId: string): boolean {
  const selected = policy.channels.ids.includes(channelId)
  return policy.channels.mode === 'allowlist' ? selected : !selected
}

function withKeys(policy: ClientKeyAccessPolicy, keys: ClientKeyAllowedChannelKey[]): ClientKeyAccessPolicy {
  // 白名单密钥名单清空时回到未限制，保持“纯渠道级”语义；黑名单保留模式以呈现空名单拦截。
  const mode: ClientKeyAccessMode = keys.length > 0 || policy.channelKeys.mode === 'denylist'
    ? policy.channelKeys.mode
    : 'unrestricted'
  return { ...policy, channelKeys: { mode, keys } }
}

/** 勾选/取消一个渠道（父节点）。 */
export function toggleChannelSelection(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  channelId: string,
): ClientKeyAccessPolicy {
  const channel = channels.find(item => item.id === channelId)
  if (channel == null) {
    // 目录中已不存在的失效渠道：取消勾选时直接移除；树不会展示未勾选的失效项，无重新勾选路径。
    return { ...policy, channels: { ...policy.channels, ids: policy.channels.ids.filter(id => id !== channelId) } }
  }
  const info = channelCheckInfo(policy, channel)
  const ids = policy.channels.ids.filter(id => id !== channelId)
  if (info.state === 'checked') {
    // 取消整渠道：移除渠道及其全部子密钥名单；密钥维度是否解除由 withKeys 统一裁定。
    const keys = policy.channelKeys.keys.filter(pair => pair.channelId !== channelId)
    const next = withKeys(policy, keys)
    // 与子凭据取消路径一致：凭据白名单维度收窄或解除时，移除零选中凭据的目录内渠道，
    // 避免其余渠道以渠道级语义重新放行已被取消的凭据。纯渠道级操作（凭据维度未启用）
    // 不清理，保留其他渠道的父勾选意图。
    if (policy.channelKeys.mode === 'allowlist') {
      const selectedChannels = new Set(keys.map(item => item.channelId))
      const survivors = ids.filter(id => {
        const other = channels.find(item => item.id === id)
        return other == null || channelCredentials(other).length === 0 || selectedChannels.has(id)
      })
      return { ...next, channels: { ...next.channels, ids: survivors } }
    }
    return { ...next, channels: { ...next.channels, ids } }
  }
  // 勾选（含半选转全选）与全选动作一致。
  return selectChannel(policy, channels, channelId)
}

/** 渠道下的全部可寻址凭据：静态密钥 + 订阅账号。 */
function channelCredentials(channel: Channel | undefined): ClientKeyAllowedChannelKey[] {
  if (!channel) return []
  return [
    ...channel.keys.map(key => credentialIdentity(channel.id, key.id, 'static')),
    ...(channel.oauthAccounts ?? []).map(account => credentialIdentity(channel.id, account.id, 'oauth')),
  ]
}

function selectChannel(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  channelId: string,
): ClientKeyAccessPolicy {
  const merged = mergedAccessMode(policy)
  const withChannel: ClientKeyAccessPolicy = {
    ...policy,
    // 渠道维度尚未限制时提升为卡片模式（黑名单勾父=排除该渠道），否则勾选不会生效。
    channels: {
      mode: policy.channels.mode === 'unrestricted' ? merged : policy.channels.mode,
      ids: [...new Set([...policy.channels.ids, channelId])],
    },
  }
  if (merged === 'allowlist') {
    if (policy.channelKeys.mode === 'unrestricted') return withChannel
    const expanded = channelCredentials(channels.find(item => item.id === channelId))
    const keys = [...withChannel.channelKeys.keys.filter(pair => pair.channelId !== channelId), ...expanded]
    return { ...withChannel, channelKeys: { mode: 'allowlist', keys: dedupeKeys(keys) } }
  }
  // 黑名单：整渠道排除覆盖全部凭据，丢弃该渠道的凭据名单。
  return withKeys(withChannel, withChannel.channelKeys.keys.filter(pair => pair.channelId !== channelId))
}

function dedupeKeys(keys: ClientKeyAllowedChannelKey[]): ClientKeyAllowedChannelKey[] {
  const seen = new Set<string>()
  const result: ClientKeyAllowedChannelKey[] = []
  for (const item of keys) {
    const signature = JSON.stringify([item.channelId, credentialTypeOf(item), item.keyId])
    if (seen.has(signature)) continue
    seen.add(signature)
    result.push(item)
  }
  return result
}

/** 勾选/取消一个静态密钥（子节点）；`checked` 为用户点击后的目标状态。 */
function toggleCredentialSelection(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  pair: ClientKeyAllowedChannelKey,
  checked: boolean,
): ClientKeyAccessPolicy {
  const merged = mergedAccessMode(policy)
  const exists = policy.channelKeys.keys.some(item => sameAccessKey(item, pair))
  if (merged === 'denylist') {
    if (!checked) return withKeys(policy, policy.channelKeys.keys.filter(item => !sameAccessKey(item, pair)))
    // 所属渠道已被排除时子凭据冗余，直接忽略。
    if (policy.channels.ids.includes(pair.channelId)) return policy
    return { ...policy, channelKeys: { mode: 'denylist', keys: [...policy.channelKeys.keys, pair] } }
  }
  if (!checked) {
    // 覆盖态（随渠道勾选但不在名单中）的凭据取消勾选：先按已勾选渠道展开再排除该项。
    const keys = exists
      ? policy.channelKeys.keys.filter(item => !sameAccessKey(item, pair))
      : policy.channels.ids.flatMap(id => channelCredentials(channels.find(item => item.id === id)))
        .filter(item => !sameAccessKey(item, pair))
    const deduped = dedupeKeys(keys)
    // 取消凭据后，同步移除“没有任何选中凭据”的目录内渠道，保证树状态与策略一致。
    const selectedChannels = new Set(deduped.map(item => item.channelId))
    const survivors = policy.channels.ids.filter(id => {
      const channel = channels.find(item => item.id === id)
      return channel == null || channelCredentials(channel).length === 0 || selectedChannels.has(id)
    })
    if (deduped.length === 0) {
      return {
        ...policy,
        channels: { ...policy.channels, ids: survivors },
        channelKeys: { mode: 'unrestricted', keys: [] },
      }
    }
    return { ...policy, channels: { ...policy.channels, ids: survivors }, channelKeys: { mode: 'allowlist', keys: deduped } }
  }
  // 白名单：自动把所属渠道补进渠道名单；凭据维度首次启用时展开此前已勾选渠道的全部凭据。
  const engaged = policy.channelKeys.mode === 'allowlist'
  const expandedPrevious = policy.channels.ids.flatMap(id => channelCredentials(channels.find(item => item.id === id)))
  const ids = [...new Set([...policy.channels.ids, pair.channelId])]
  const base: ClientKeyAccessPolicy = { ...policy, channels: { ...policy.channels, ids } }
  if (!engaged) {
    return { ...base, channelKeys: { mode: 'allowlist', keys: dedupeKeys([...expandedPrevious, pair]) } }
  }
  return { ...base, channelKeys: { mode: 'allowlist', keys: dedupeKeys([...base.channelKeys.keys, pair]) } }
}

/** 勾选/取消一个静态密钥。 */
export function toggleKeySelection(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  pair: ClientKeyAllowedChannelKey,
  checked: boolean,
): ClientKeyAccessPolicy {
  return toggleCredentialSelection(policy, channels, credentialIdentity(pair.channelId, pair.keyId, 'static'), checked)
}

/** 勾选/取消一个 OAuth 订阅账号。 */
export function toggleOAuthSelection(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  pair: { channelId: string; accountId: string },
  checked: boolean,
): ClientKeyAccessPolicy {
  return toggleCredentialSelection(policy, channels, credentialIdentity(pair.channelId, pair.accountId, 'oauth'), checked)
}

/** 全选：白名单选中全部渠道（密钥维度已启用时展开全部子密钥），黑名单排除全部渠道。 */
export function selectAllChannels(policy: ClientKeyAccessPolicy, channels: Channel[]): ClientKeyAccessPolicy {
  const merged = mergedAccessMode(policy)
  const ids = channels.map(channel => channel.id)
  const base: ClientKeyAccessPolicy = {
    ...policy,
    channels: { mode: policy.channels.mode === 'unrestricted' ? merged : policy.channels.mode, ids },
  }
  if (merged === 'allowlist') {
    if (policy.channelKeys.mode === 'unrestricted') return base
    const keys = channels.flatMap(channel => channelCredentials(channel))
    return { ...base, channelKeys: { mode: 'allowlist', keys: dedupeKeys(keys) } }
  }
  return { ...base, channelKeys: { mode: policy.channelKeys.mode, keys: [] } }
}

/** 清空：保留模式以呈现空名单拦截，由用户显式切换「不限制」。 */
export function clearChannelSelection(policy: ClientKeyAccessPolicy): ClientKeyAccessPolicy {
  return {
    ...policy,
    channels: { ...policy.channels, ids: [] },
    channelKeys: { mode: policy.channelKeys.mode, keys: [] },
  }
}

/**
 * 切换合并卡片的模式；沿用“切换保留草稿选择”的行为。
 * 白名单启用密钥名单时补齐渠道名单以满足后端校验。
 */
export function changeMergedMode(policy: ClientKeyAccessPolicy, mode: ClientKeyAccessMode): ClientKeyAccessPolicy {
  if (mode === 'unrestricted') {
    return {
      ...policy,
      channels: { ...policy.channels, mode },
      channelKeys: { ...policy.channelKeys, mode },
    }
  }
  if (mode === 'allowlist' && policy.channelKeys.keys.length > 0) {
    const keyChannels = [...new Set(policy.channelKeys.keys.map(pair => pair.channelId))]
    return {
      ...policy,
      channels: { mode, ids: [...new Set([...policy.channels.ids, ...keyChannels])] },
      channelKeys: { ...policy.channelKeys, mode },
    }
  }
  return {
    ...policy,
    channels: { ...policy.channels, mode },
    channelKeys: { ...policy.channelKeys, mode: policy.channelKeys.keys.length > 0 ? mode : policy.channelKeys.mode },
  }
}

/**
 * 旧数据统一：仅当渠道与密钥两个维度都已限制且模式不同（跨维度混合）时需要统一。
 * 目标模式取渠道维度；密钥名单无法安全换义，清空并要求用户重新确认。
 * 白名单目标下清空即解除密钥维度（回到渠道级语义），避免出现“启用但为空”的名单。
 * 渠道未限制 + 密钥受限的组合在合并卡片中可直接表达，不做统一。
 */
export function unifyMixedModePolicy(policy: ClientKeyAccessPolicy): { policy: ClientKeyAccessPolicy; unified: boolean } {
  const mixed = policy.channels.mode !== 'unrestricted' && policy.channelKeys.mode !== 'unrestricted'
    && policy.channels.mode !== policy.channelKeys.mode
  if (!mixed) return { policy, unified: false }
  const mode = policy.channels.mode
  return {
    policy: {
      ...policy,
      channelKeys: { mode: mode === 'denylist' ? 'denylist' : 'unrestricted', keys: [] },
    },
    unified: true,
  }
}

/** 当前策略下渠道维度是否放行指定渠道（不含密钥维度）。 */
export function channelAllowedByPolicy(policy: ClientKeyAccessPolicy, channelId: string): boolean {
  if (policy.channels.mode === 'unrestricted') return true
  return channelInScope(policy, channelId)
}

/** 渠道维度放行的渠道集合；未限制时为 null。 */
export function effectiveChannelIds(policy: ClientKeyAccessPolicy): Set<string> | null {
  if (policy.channels.mode === 'unrestricted') return null
  return new Set(policy.channels.ids)
}

/** 一个静态密钥凭据是否在当前策略下生效。 */
export function staticKeyEffective(policy: ClientKeyAccessPolicy, pair: ClientKeyAllowedChannelKey): boolean {
  if (!channelAllowedByPolicy(policy, pair.channelId)) return false
  if (policy.channelKeys.mode === 'unrestricted') return true
  const listed = policy.channelKeys.keys.some(item => sameAccessKey(item, pair))
  return policy.channelKeys.mode === 'allowlist' ? listed : !listed
}

/**
 * 与后端 `ChannelKeyState.isRoutableAt` 对齐的静态密钥可路由判定：
 * ACTIVE 直接可用；临时停用到期且探测租约结束才恢复；人工/永久停用永不参与路由。
 */
export function keyRoutable(
  key: { state: ChannelKeyState; autoDisableUntil: number | null; probeUntil: number | null },
  now: number = Date.now(),
): boolean {
  if (key.state === 'ACTIVE') return true
  if (key.state === 'AUTO_TEMPORARY') {
    return key.autoDisableUntil != null && now >= key.autoDisableUntil &&
      (key.probeUntil == null || now >= key.probeUntil)
  }
  return false
}

/** OAuth 凭据是否生效：渠道放行且该账号未被凭据名单排除；未指定账号时保留旧的整体判断。 */
export function oauthEffective(policy: ClientKeyAccessPolicy, channelId: string, accountId?: string): boolean {
  if (!channelAllowedByPolicy(policy, channelId)) return false
  if (policy.channelKeys.mode === 'unrestricted') return true
  if (!accountId) return policy.channelKeys.mode !== 'allowlist'
  const identity = credentialIdentity(channelId, accountId, 'oauth')
  const listed = policy.channelKeys.keys.some(item => sameAccessKey(item, identity))
  return policy.channelKeys.mode === 'allowlist' ? listed : !listed
}

/** 当前策略下生效的静态密钥总数；停用渠道与不可路由密钥（人工停用、冷却中）不计入。 */
export function effectiveStaticKeyCount(policy: ClientKeyAccessPolicy, channels: Channel[]): number {
  const now = Date.now()
  return channels.filter(channel => channel.enabled).reduce((count, channel) => count + channel.keys.filter(key =>
    keyRoutable(key, now) && staticKeyEffective(policy, { channelId: channel.id, keyId: key.id })).length, 0)
}

export interface ModelAvailability {
  /** 在当前生效的渠道/密钥与已保存模型路由下是否可调用。 */
  supported: boolean
  /** 支撑该模型的生效静态密钥数量。 */
  staticKeyCount: number
  /** 该模型路由中的身份总数。 */
  routeEntryCount: number
  /** 当前调用方范围下实际可用的路由身份数量。 */
  availableCredentialCount: number
}

/**
 * 计算对外模型 ID 的可用性：模型必须已有保存的模型路由，且路由中的身份
 * 在当前渠道/密钥范围内仍可选。未配置路由的模型不会出现在结果中。
 */
export function modelAvailabilityMap(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  routeOptions: ModelRouteAccessOption[] = [],
): Map<string, ModelAvailability> {
  const now = Date.now()
  const channelsById = new Map(channels.map(channel => [channel.id, channel]))
  const availability = new Map<string, ModelAvailability>()
  for (const route of routeOptions) {
    let staticKeyCount = 0
    let availableCredentialCount = 0
    for (const entry of route.entries) {
      const channel = channelsById.get(entry.channelId)
      if (!channel?.enabled || !channelAllowedByPolicy(policy, channel.id)) continue
      if (entry.credentialType === 'static') {
        const key = channel.keys.find(item => item.id === entry.credentialId)
        if (!key || !entry.selectable || !keyRoutable(key, now) ||
          !staticKeyEffective(policy, { channelId: channel.id, keyId: key.id })) continue
        staticKeyCount += 1
        availableCredentialCount += 1
        continue
      }
      const account = (channel.oauthAccounts ?? []).find(item => item.id === entry.credentialId)
      if (!account?.enabled || !entry.selectable || !oauthEffective(policy, channel.id, entry.credentialId)) continue
      availableCredentialCount += 1
    }
    availability.set(route.modelId, {
      supported: availableCredentialCount > 0,
      staticKeyCount,
      routeEntryCount: route.entries.length,
      availableCredentialCount,
    })
  }
  return availability
}

/** 白名单/黑名单维度没有任何目标时的维度名，用于保存拦截；以合并卡片的展示模式为准。 */
export function emptyAccessDimensionLabels(policy: ClientKeyAccessPolicy): string[] {
  const labels: string[] = []
  if (mergedAccessMode(policy) !== 'unrestricted'
    && policy.channels.ids.length === 0 && policy.channelKeys.keys.length === 0) {
    // 渠道白名单允许只选密钥（跨渠道），但渠道与密钥都为空即拒绝全部；黑名单同理。
    labels.push('渠道与密钥')
  }
  if (policy.models.mode !== 'unrestricted' && policy.models.ids.length === 0) labels.push('模型')
  return labels
}

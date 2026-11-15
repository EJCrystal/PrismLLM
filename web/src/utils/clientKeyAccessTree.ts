import type { Channel, ClientKeyAccessMode, ClientKeyAccessPolicy, ClientKeyAllowedChannelKey } from '../types/api'

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
}

export function sameAccessKey(left: ClientKeyAllowedChannelKey, right: ClientKeyAllowedChannelKey): boolean {
  return left.channelId === right.channelId && left.keyId === right.keyId
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
 */
export function channelCheckInfo(policy: ClientKeyAccessPolicy, channel: Channel | undefined): ChannelCheckInfo {
  const keys = channel?.keys ?? []
  const totalKeys = keys.length
  const selectedKeys = policy.channelKeys.mode === 'unrestricted'
    ? 0
    : keys.filter(key => policy.channelKeys.keys.some(pair => sameAccessKey(pair, { channelId: channel!.id, keyId: key.id }))).length
  const channelSelected = policy.channels.ids.includes(channel?.id ?? '')

  if (policy.channels.mode === 'allowlist') {
    if (!channelSelected) return { state: selectedKeys > 0 ? 'indeterminate' : 'unchecked', selectedKeys, totalKeys }
    if (policy.channelKeys.mode === 'unrestricted') return { state: 'checked', selectedKeys, totalKeys }
    if (selectedKeys === 0) return { state: 'unchecked', selectedKeys, totalKeys }
    return { state: selectedKeys === totalKeys ? 'checked' : 'indeterminate', selectedKeys, totalKeys }
  }
  // 黑名单：渠道排除覆盖子密钥；未排除渠道时按选中的子密钥数呈现半选，
  // 即便全部子密钥都被排除也只是半选——OAuth 账号仍随渠道放行，整渠道排除才是全选。
  if (channelSelected) return { state: 'checked', selectedKeys, totalKeys }
  return selectedKeys === 0 ? { state: 'unchecked', selectedKeys, totalKeys } : { state: 'indeterminate', selectedKeys, totalKeys }
}

/** 子密钥行是否勾选：在密钥名单中，或渠道级选中（白名单 + 密钥维度未启用）时随渠道勾选。 */
export function keyChecked(policy: ClientKeyAccessPolicy, channel: Channel | undefined, keyId: string): boolean {
  if (policy.channelKeys.keys.some(pair => sameAccessKey(pair, { channelId: channel?.id ?? '', keyId }))) return true
  if (policy.channels.mode === 'allowlist' && policy.channelKeys.mode === 'unrestricted') {
    return policy.channels.ids.includes(channel?.id ?? '')
  }
  return false
}

/** 渠道在当前策略下是否整体放行（渠道维度判断，不含密钥维度）。 */
function channelInScope(policy: ClientKeyAccessPolicy, channelId: string): boolean {
  const selected = policy.channels.ids.includes(channelId)
  return policy.channels.mode === 'allowlist' ? selected : !selected
}

/** 二级 OAuth 行是否可勾选：只有白名单 + 密钥维度未启用时勾选才有意义（等同勾选渠道）。 */
export function oauthRowInteractive(policy: ClientKeyAccessPolicy): boolean {
  return policy.channels.mode === 'allowlist' && policy.channelKeys.mode === 'unrestricted'
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
  const info = channelCheckInfo(policy, channel)
  const ids = policy.channels.ids.filter(id => id !== channelId)
  if (info.state === 'checked') {
    const keys = policy.channelKeys.mode === 'unrestricted'
      ? policy.channelKeys.keys
      : policy.channelKeys.keys.filter(pair => pair.channelId !== channelId)
    const next = withKeys(policy, keys)
    return { ...next, channels: { ...next.channels, ids } }
  }
  // 勾选（含半选转全选）与全选动作一致。
  return selectChannel(policy, channels, channelId)
}

function selectChannel(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  channelId: string,
): ClientKeyAccessPolicy {
  const withChannel: ClientKeyAccessPolicy = {
    ...policy,
    channels: { ...policy.channels, ids: [...new Set([...policy.channels.ids, channelId])] },
  }
  if (policy.channels.mode === 'allowlist') {
    if (policy.channelKeys.mode === 'unrestricted') return withChannel
    const channel = channels.find(item => item.id === channelId)
    const expanded = (channel?.keys ?? []).map(key => ({ channelId, keyId: key.id }))
    const keys = [...withChannel.channelKeys.keys.filter(pair => pair.channelId !== channelId), ...expanded]
    return { ...withChannel, channelKeys: { mode: 'allowlist', keys } }
  }
  // 黑名单：整渠道排除覆盖子密钥，丢弃该渠道的密钥名单。
  return withKeys(withChannel, withChannel.channelKeys.keys.filter(pair => pair.channelId !== channelId))
}

function dedupeKeys(keys: ClientKeyAllowedChannelKey[]): ClientKeyAllowedChannelKey[] {
  const seen = new Set<string>()
  const result: ClientKeyAllowedChannelKey[] = []
  for (const item of keys) {
    const signature = JSON.stringify([item.channelId, item.keyId])
    if (seen.has(signature)) continue
    seen.add(signature)
    result.push(item)
  }
  return result
}

/** 勾选/取消一个静态密钥（子节点）；`checked` 为用户点击后的目标状态。 */
export function toggleKeySelection(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  pair: ClientKeyAllowedChannelKey,
  checked: boolean,
): ClientKeyAccessPolicy {
  const exists = policy.channelKeys.keys.some(item => sameAccessKey(item, pair))
  if (policy.channels.mode === 'denylist') {
    if (!checked) return withKeys(policy, policy.channelKeys.keys.filter(item => !sameAccessKey(item, pair)))
    // 所属渠道已被排除时子密钥冗余，直接忽略。
    if (policy.channels.ids.includes(pair.channelId)) return policy
    return withKeys(policy, [...policy.channelKeys.keys, pair])
  }
  if (!checked) {
    // 覆盖态（随渠道勾选但不在名单中）的子密钥取消勾选：先按已勾选渠道展开再排除该项。
    const keys = exists
      ? policy.channelKeys.keys.filter(item => !sameAccessKey(item, pair))
      : policy.channels.ids.flatMap(id => {
          const channel = channels.find(item => item.id === id)
          return (channel?.keys ?? []).map(key => ({ channelId: id, keyId: key.id }))
        }).filter(item => !sameAccessKey(item, pair))
    const deduped = dedupeKeys(keys)
    if (deduped.length === 0) {
      // 密钥维度解除启用；该渠道没有任何选中子密钥时一并取消渠道勾选（与原型树语义一致）。
      return {
        ...policy,
        channels: { ...policy.channels, ids: policy.channels.ids.filter(id => id !== pair.channelId) },
        channelKeys: { mode: 'unrestricted', keys: [] },
      }
    }
    return { ...policy, channelKeys: { mode: 'allowlist', keys: deduped } }
  }
  // 白名单：自动把所属渠道补进渠道名单；密钥维度首次启用时仅展开此前已勾选的渠道，
  // 点选密钥的渠道只入选当前密钥，保持“已选 1/N”的半选语义。
  const engaged = policy.channelKeys.mode === 'allowlist'
  const expandedPrevious = policy.channels.ids.flatMap(id => {
    const channel = channels.find(item => item.id === id)
    return (channel?.keys ?? []).map(key => ({ channelId: id, keyId: key.id }))
  })
  const ids = [...new Set([...policy.channels.ids, pair.channelId])]
  const base: ClientKeyAccessPolicy = { ...policy, channels: { ...policy.channels, ids } }
  if (!engaged) {
    return { ...base, channelKeys: { mode: 'allowlist', keys: dedupeKeys([...expandedPrevious, pair]) } }
  }
  return { ...base, channelKeys: { mode: 'allowlist', keys: dedupeKeys([...base.channelKeys.keys, pair]) } }
}

/** 勾选一个 OAuth 账号：等同勾选其所属渠道（OAuth 资格完全跟随渠道维度）。 */
export function toggleOAuthSelection(
  policy: ClientKeyAccessPolicy,
  channels: Channel[],
  channelId: string,
): ClientKeyAccessPolicy {
  return toggleChannelSelection(policy, channels, channelId)
}

/** 全选：白名单选中全部渠道（密钥维度已启用时展开全部子密钥），黑名单排除全部渠道。 */
export function selectAllChannels(policy: ClientKeyAccessPolicy, channels: Channel[]): ClientKeyAccessPolicy {
  const ids = channels.map(channel => channel.id)
  const base: ClientKeyAccessPolicy = { ...policy, channels: { ...policy.channels, ids } }
  if (policy.channels.mode === 'allowlist') {
    if (policy.channelKeys.mode === 'unrestricted') return base
    const keys = channels.flatMap(channel => channel.keys.map(key => ({ channelId: channel.id, keyId: key.id })))
    return { ...base, channelKeys: { mode: 'allowlist', keys } }
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

/** OAuth 凭据是否生效：跟随渠道维度；密钥白名单会整体排除 OAuth 候选。 */
export function oauthEffective(policy: ClientKeyAccessPolicy, channelId: string): boolean {
  if (!channelAllowedByPolicy(policy, channelId)) return false
  return policy.channelKeys.mode !== 'allowlist'
}

/** 当前策略下生效的静态密钥总数；停用渠道的密钥不可路由，不计入。 */
export function effectiveStaticKeyCount(policy: ClientKeyAccessPolicy, channels: Channel[]): number {
  return channels.filter(channel => channel.enabled).reduce((count, channel) => count + channel.keys.filter(key =>
    staticKeyEffective(policy, { channelId: channel.id, keyId: key.id })).length, 0)
}

export interface ModelAvailability {
  /** 在当前生效的渠道/密钥下是否可调用。 */
  supported: boolean
  /** 支撑该模型的生效静态密钥数量。 */
  staticKeyCount: number
}

/**
 * 计算对外模型 ID 的可用性：数据面以 canonical ?? upstream 作为对外 ID，
 * 模型生效要求存在生效渠道上的生效凭据支撑其任一上游映射。
 */
export function modelAvailabilityMap(policy: ClientKeyAccessPolicy, channels: Channel[]): Map<string, ModelAvailability> {
  const availability = new Map<string, ModelAvailability>()
  const record = (publicId: string, supported: boolean, staticKeyCount: number): void => {
    const current = availability.get(publicId) ?? { supported: false, staticKeyCount: 0 }
    availability.set(publicId, {
      supported: current.supported || supported,
      staticKeyCount: current.staticKeyCount + staticKeyCount,
    })
  }
  for (const channel of channels) {
    if (!channel.enabled) continue
    if (!channelAllowedByPolicy(policy, channel.id)) continue
    const oauthReady = (channel.oauthAccounts ?? []).some(account =>
      account.enabled && oauthEffective(policy, channel.id))
    for (const mapping of channel.modelMappings) {
      const publicId = mapping.canonicalModelId ?? mapping.upstreamModel
      const keyCount = channel.keys.filter(key =>
        key.models.includes(mapping.upstreamModel) && staticKeyEffective(policy, { channelId: channel.id, keyId: key.id })).length
      record(publicId, keyCount > 0 || oauthReady, keyCount)
    }
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

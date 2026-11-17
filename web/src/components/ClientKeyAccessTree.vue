<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  channelCheckInfo,
  keyChecked,
  oauthChecked,
  shortId,
} from '../utils/clientKeyAccessTree'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import type { Channel, ClientKeyAccessMode, ClientKeyAccessPolicy, ClientKeyAllowedChannelKey } from '../types/api'

/**
 * 「渠道与密钥」合并卡片的二级树形勾选列表。
 * 组件只负责展示与本地 UI 状态（搜索词、展开集合），勾选动作全部回交给持有策略的一方。
 *
 * @author 文杰
 * @since 2026-11-15
 */
const props = defineProps<{
  mode: ClientKeyAccessMode
  channels: Channel[]
  policy: ClientKeyAccessPolicy
  loaded: boolean
  /** 目录加载失败或未就绪时，保留中选择的统一提示文案。 */
  unavailableLabel: string
  /** 策略里勾选但目录中已不存在的渠道 ID。 */
  staleChannels: string[]
  /** 策略里勾选但目录中已不存在的静态密钥。 */
  staleKeys: ClientKeyAllowedChannelKey[]
}>()
const emit = defineEmits<{
  'toggle-channel': [channelId: string]
  'toggle-key': [pair: ClientKeyAllowedChannelKey, checked: boolean]
  'toggle-oauth': [pair: { channelId: string; accountId: string }, checked: boolean]
  'select-all': []
  'clear': []
}>()

const searchText = ref('')
const expandedIds = ref<Set<string>>(new Set())

/** 搜索命中渠道名/ID/短 ID 或任意子项名/ID；命中时自动展开。 */
const filteredChannels = computed(() => {
  const query = searchText.value.trim().toLowerCase()
  if (!query) return props.channels
  return props.channels.filter(channel => {
    if (channel.name.toLowerCase().includes(query)) return true
    if (channel.id.toLowerCase().includes(query)) return true
    if (shortId(channel.id).includes(query)) return true
    return channel.keys.some(key => key.name.toLowerCase().includes(query) || key.id.toLowerCase().includes(query))
      || (channel.oauthAccounts ?? []).some(account => account.name.toLowerCase().includes(query))
  })
})

function toggleExpand(channelId: string): void {
  const next = new Set(expandedIds.value)
  if (next.has(channelId)) next.delete(channelId)
  else next.add(channelId)
  expandedIds.value = next
}

const isExpanded = (channelId: string): boolean =>
  searchText.value.trim() !== '' || expandedIds.value.has(channelId)

const allVisibleExpanded = computed(() =>
  filteredChannels.value.length > 0 && filteredChannels.value.every(channel => isExpanded(channel.id)))

function toggleExpandAll(): void {
  expandedIds.value = allVisibleExpanded.value
    ? new Set()
    : new Set(filteredChannels.value.map(channel => channel.id))
}

const hasSelection = computed(() =>
  props.policy.channels.ids.length > 0 || props.policy.channelKeys.keys.length > 0)

/** 黑名单模式下渠道已被整体排除时，其子密钥行禁用（排除渠道已覆盖）。 */
function keyDisabled(channelId: string): boolean {
  return props.mode === 'denylist' && props.policy.channels.ids.includes(channelId)
}

function channelHint(channel: Channel): string {
  return channel.enabled ? '' : '渠道停用'
}

function keyHint(channel: Channel, keyId: string): string {
  const key = channel.keys.find(item => item.id === keyId)
  if (!key) return ''
  if (keyDisabled(channel.id)) return '渠道已被整体排除'
  if (!channel.enabled) return '渠道停用'
  return key.state !== 'ACTIVE' ? '密钥停用' : !key.credentialSet ? '未配置凭据' : ''
}

function onKeyChange(channel: Channel, keyId: string, event: Event): void {
  const target = event.target as HTMLInputElement
  emit('toggle-key', { channelId: channel.id, keyId }, target.checked)
}

function onOAuthChange(channel: Channel, accountId: string, event: Event): void {
  const target = event.target as HTMLInputElement
  emit('toggle-oauth', { channelId: channel.id, accountId }, target.checked)
}

/** 失效凭据按类型走对应删除路径，避免 OAuth 被误当成同 ID 静态密钥。 */
function removeStaleCredential(pair: ClientKeyAllowedChannelKey): void {
  if (pair.credentialType === 'oauth') {
    emit('toggle-oauth', { channelId: pair.channelId, accountId: pair.keyId }, false)
  } else {
    emit('toggle-key', pair, false)
  }
}

function oauthHint(channel: Channel): string {
  return props.mode === 'denylist' && props.policy.channels.ids.includes(channel.id) ? '渠道已被整体排除' : ''
}

/** 右侧选中统计：全部凭据 / 已选 x/N 凭据；黑名单语境改为“排除”。 */
function channelStatLabel(info: ReturnType<typeof channelCheckInfo>): string {
  if (info.totalCredentials === 0) return ''
  const excluded = props.mode === 'denylist'
  if (info.state === 'checked') return excluded ? `排除整渠道 (${info.totalCredentials})` : `全部凭据 (${info.totalCredentials})`
  if (info.state === 'indeterminate') {
    return excluded
      ? `已排除 ${info.selectedCredentials}/${info.totalCredentials} 凭据`
      : `已选 ${info.selectedCredentials}/${info.totalCredentials} 凭据`
  }
  return `0/${info.totalCredentials} 凭据`
}
</script>

<template>
  <div class="access-tree">
    <div class="tree-toolbar">
      <div class="tree-search">
        <Icon name="search" :size="14" />
        <input
          v-model="searchText"
          type="search"
          aria-label="搜索渠道与密钥"
          placeholder="搜索渠道名称、密钥或短 ID..."
        />
      </div>
      <div class="tree-actions">
        <button type="button" class="tree-action is-primary" @click="emit('select-all')">全选</button>
        <span class="tree-action-divider">|</span>
        <button type="button" class="tree-action" :disabled="!hasSelection" @click="emit('clear')">清空</button>
        <span class="tree-action-divider">|</span>
        <button type="button" class="tree-action" @click="toggleExpandAll">
          {{ allVisibleExpanded ? '收起全部' : '展开全部' }}
        </button>
      </div>
    </div>

    <div class="tree-body" role="group" aria-label="渠道与密钥树">
      <template v-for="channel in filteredChannels" :key="channel.id">
        <div class="tree-row is-channel" :class="{ 'is-selected': channelCheckInfo(policy, channel).state !== 'unchecked' }">
          <button
            type="button"
            class="tree-chevron"
            :aria-label="isExpanded(channel.id) ? `收起 ${channel.name}` : `展开 ${channel.name}`"
            :aria-expanded="isExpanded(channel.id)"
            @click="toggleExpand(channel.id)"
          >
            <Icon name="chevron" :size="14" :class="isExpanded(channel.id) ? 'is-open' : 'is-closed'" />
          </button>
          <label class="tree-main">
            <input
              type="checkbox"
              :checked="channelCheckInfo(policy, channel).state === 'checked'"
              :indeterminate.prop="channelCheckInfo(policy, channel).state === 'indeterminate'"
              :aria-label="`渠道 ${channel.name}`"
              @change="emit('toggle-channel', channel.id)"
            />
            <strong>{{ channel.name }}</strong>
            <span v-if="channel.kind === 'provider'" class="tree-tag">系统</span>
            <CopyButton
              variant="subtle"
              class="tree-id-pill"
              :text="channel.id"
              :title="`完整 ID: ${channel.id} (点击复制)`"
            >
              <span>{{ shortId(channel.id) }}</span>
            </CopyButton>
            <small v-if="channelHint(channel)" class="tree-hint">{{ channelHint(channel) }}</small>
          </label>
          <span v-if="channelStatLabel(channelCheckInfo(policy, channel))" class="tree-stat">
            {{ channelStatLabel(channelCheckInfo(policy, channel)) }}
          </span>
        </div>

        <div v-if="isExpanded(channel.id)" class="tree-children">
          <label
            v-for="key in channel.keys"
            :key="key.id"
            class="tree-row is-key"
            :class="{ 'is-selected': keyChecked(policy, channel, key.id), 'is-disabled': keyDisabled(channel.id) }"
          >
            <input
              type="checkbox"
              :checked="keyChecked(policy, channel, key.id)"
              :disabled="keyDisabled(channel.id)"
              :aria-label="`渠道密钥 ${channel.name} / ${key.name}`"
              @change="onKeyChange(channel, key.id, $event)"
            />
            <Icon name="keys" :size="13" />
            <span class="tree-name">{{ key.name }}</span>
            <span class="tree-tag">静态密钥</span>
            <small v-if="keyHint(channel, key.id)" class="tree-hint">{{ keyHint(channel, key.id) }}</small>
            <span class="tree-stat is-plain">关联 {{ key.models.length }} 个模型</span>
          </label>
          <label
            v-for="account in channel.oauthAccounts ?? []"
            :key="account.id"
            class="tree-row is-key is-oauth"
            :class="{ 'is-selected': oauthChecked(policy, channel, account.id), 'is-disabled': keyDisabled(channel.id) }"
          >
            <input
              type="checkbox"
              :checked="oauthChecked(policy, channel, account.id)"
              :disabled="keyDisabled(channel.id)"
              :aria-label="`OAuth 账号 ${channel.name} / ${account.name}`"
              @change="onOAuthChange(channel, account.id, $event)"
            />
            <Icon name="lock" :size="13" />
            <span class="tree-name">{{ account.name }}</span>
            <span class="tree-tag is-oauth-tag">OAuth 订阅</span>
            <small v-if="oauthHint(channel)" class="tree-hint">{{ oauthHint(channel) }}</small>
            <span class="tree-stat is-plain">关联 {{ account.models.length }} 个模型</span>
          </label>
        </div>
      </template>
      <p v-if="loaded && filteredChannels.length === 0" class="tree-empty">
        {{ searchText ? '没有匹配搜索条件的渠道或密钥。' : '暂无可选择的渠道。' }}
      </p>
    </div>

    <div v-if="staleChannels.length || staleKeys.length" class="tree-stale">
      <p class="tree-stale-title">以下选择已不在目录中（{{ unavailableLabel }}）：</p>
      <label v-for="id in staleChannels" :key="id" class="tree-row is-key is-stale">
        <input type="checkbox" checked :aria-label="`已失效渠道 ${id}`" @change="emit('toggle-channel', id)" />
        <span class="tree-name">{{ unavailableLabel }}</span>
        <code>{{ id }}</code>
      </label>
      <label
        v-for="pair in staleKeys"
        :key="`${pair.channelId}/${pair.credentialType ?? 'static'}/${pair.keyId}`"
        class="tree-row is-key is-stale"
      >
        <input
          type="checkbox"
          checked
          :aria-label="`已失效渠道凭据 ${pair.channelId} / ${pair.keyId}`"
          @change="removeStaleCredential(pair)"
        />
        <span class="tree-name">{{ unavailableLabel }}</span>
        <span class="tree-tag">{{ pair.credentialType === 'oauth' ? 'OAuth' : '静态' }}</span>
        <code>{{ pair.channelId }} / {{ pair.keyId }}</code>
      </label>
    </div>
  </div>
</template>

<style scoped>
.access-tree { display: grid; gap: 10px; min-width: 0; }
.tree-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.tree-search {
  display: flex; align-items: center; gap: 6px; flex: 1 1 auto; min-width: 0;
  padding: 5px 10px; border: 1px solid var(--line); border-radius: 8px;
  background: var(--glass-field); color: var(--muted);
}
.tree-search input {
  flex: 1 1 auto; min-width: 0; border: 0; background: transparent; color: var(--ink);
  font-size: 12px; outline: none;
}
.tree-actions { display: flex; align-items: center; gap: 4px; flex: 0 0 auto; font-size: 12px; }
.tree-action {
  border: 0; background: transparent; padding: 3px 6px; border-radius: 6px;
  color: var(--muted); font-size: 12px; cursor: pointer;
}
.tree-action:hover { background: var(--glass-bg-strong); color: var(--ink); }
.tree-action.is-primary { color: var(--brand); font-weight: 600; }
.tree-action:disabled { cursor: not-allowed; opacity: 0.5; }
.tree-action-divider { color: var(--line); }
.tree-body {
  display: grid; gap: 0; max-height: 260px; overflow-y: auto; overscroll-behavior: contain;
  border: 1px solid var(--line); border-radius: 10px; background: var(--glass-bg-soft);
}
.tree-row {
  display: flex; align-items: center; gap: 8px; min-width: 0;
  padding: 8px 10px; border-bottom: 1px solid var(--line); cursor: pointer;
}
.tree-row:last-child { border-bottom: 0; }
.tree-row:hover { background: var(--glass-bg-strong); }
.tree-row.is-channel.is-selected { background: color-mix(in srgb, var(--brand) 7%, transparent); }
.tree-row.is-key { padding-left: 32px; }
.tree-row.is-key.is-selected { background: color-mix(in srgb, var(--brand) 6%, transparent); }
.tree-row.is-disabled { cursor: not-allowed; opacity: 0.6; }
.tree-row.is-disabled:hover { background: transparent; }
.tree-row input { flex: 0 0 auto; margin: 0; accent-color: var(--brand); }
.tree-row.is-channel input { accent-color: var(--brand); }
.tree-main { display: flex; align-items: center; gap: 6px; min-width: 0; flex: 1 1 auto; }
.tree-main strong { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-name { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-chevron {
  flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px; border: 0; border-radius: 5px; background: transparent;
  color: var(--muted); cursor: pointer;
}
.tree-chevron:hover { background: var(--glass-bg-strong); color: var(--ink); }
.tree-chevron .is-open { transform: rotate(0deg); }
.tree-chevron .is-closed { transform: rotate(-90deg); }
.tree-tag {
  flex: 0 0 auto; padding: 1px 6px; border-radius: 999px; font-size: 10px;
  border: 1px solid var(--line); color: var(--muted); background: var(--glass-bg);
}
.tree-tag.is-oauth-tag { color: var(--brand); border-color: color-mix(in srgb, var(--brand) 35%, transparent); }
.tree-hint { flex: 0 0 auto; color: var(--muted); font-size: 11px; }
.tree-id-pill {
  flex: 0 0 auto; display: inline-flex; align-items: center; gap: 2px;
  padding: 1px 6px !important; border-radius: 999px !important; font-size: 11px !important;
  font-family: ui-monospace, monospace; background: var(--glass-bg) !important;
  border: 1px solid var(--line); color: var(--muted) !important;
}
.tree-stat {
  flex: 0 0 auto; margin-left: auto; padding: 2px 8px; border-radius: 999px;
  font-size: 11px; color: var(--muted); background: var(--glass-bg); border: 1px solid var(--line);
}
.tree-stat.is-plain { background: transparent; border: 0; padding: 0; }
.tree-children { display: grid; background: color-mix(in srgb, var(--glass-bg-soft) 60%, transparent); }
.tree-empty { margin: 0; padding: 14px; text-align: center; color: var(--muted); font-size: 12px; }
.tree-stale { display: grid; gap: 4px; padding: 8px 10px; border: 1px dashed var(--line); border-radius: 10px; }
.tree-stale-title { margin: 0; color: var(--warning); font-size: 11px; }
.tree-row.is-stale { border-bottom: 0; padding: 4px 2px; }
@media (max-width: 640px) {
  /* 手机端单行渲染：行不再换行（名称省略号兜底），复制胶囊去掉图标，子密钥行
     的“关联模型”统计隐藏；停用/失效等 hint 保留，警告信息优先于名称完整度。 */
  .tree-toolbar { flex-wrap: wrap; }
  .tree-row { flex-wrap: nowrap; min-height: 44px; }
  .tree-row.is-key { padding-left: 24px; }
  .tree-row.is-key:not(.is-stale) .tree-stat { display: none; }
  /* 复制图标渲染在子组件 CopyButton 内部，scoped 选择器需 :deep() 才能命中其后代 SVG。 */
  .tree-id-pill :deep(svg) { display: none; }
}
</style>

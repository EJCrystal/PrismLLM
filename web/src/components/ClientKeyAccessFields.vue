<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import {
  changeMergedMode,
  channelAllowedByPolicy,
  clearChannelSelection,
  effectiveStaticKeyCount,
  keyRoutable,
  mergedAccessMode,
  modelAvailabilityMap,
  oauthEffective,
  selectAllChannels,
  sameAccessKey,
  staticKeyEffective,
  toggleChannelSelection,
  toggleKeySelection,
  toggleOAuthSelection,
  unifyMixedModePolicy,
  emptyAccessDimensionLabels,
} from '../utils/clientKeyAccessTree'
import AccessModeToggle from './AccessModeToggle.vue'
import ClientKeyAccessTree from './ClientKeyAccessTree.vue'
import Icon from './Icon.vue'
import type { Channel, ClientKeyAccessMode, ClientKeyAccessPolicy, ModelRouteAccessOption, ModelRouteDetail } from '../types/api'

/**
 * 调用方密钥访问范围编辑字段：两张卡片（渠道与密钥合并树、对外模型 ID）。
 * 策略是唯一事实源，树与模型的展示状态全部由策略推导；旧混合模式数据挂载时统一并提示。
 *
 * @author 文杰
 * @since 2026-11-15
 */
const props = defineProps<{ modelValue: ClientKeyAccessPolicy }>()
const emit = defineEmits<{ 'update:modelValue': [value: ClientKeyAccessPolicy] }>()
const channels = ref<Channel[]>([])
const routeOptions = ref<ModelRouteAccessOption[]>([])
const routeDetails = ref<Record<string, ModelRouteDetail>>({})
const expandedModels = ref<string[]>([])
const routeLoading = ref<string[]>([])
const routeErrors = ref<Record<string, string>>({})
const loading = ref(false)
const loaded = ref(false)
const error = ref('')

/** 按需读取管理目录与已保存模型路由；失败保留已有目录及名单，不自动改变授权。 */
async function loadCatalog(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [channelCatalog, modelRoutes] = await Promise.all([
      api.listChannels(),
      api.listModelRouteAccessOptions(),
    ])
    channels.value = channelCatalog
    routeOptions.value = modelRoutes.models
    routeDetails.value = {}
    routeErrors.value = {}
    expandedModels.value = []
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}
// 卡片头部的生效统计与模型联动提示常显，挂载即读取一次目录；失败保留已有选择并可重试。
watch(() => props.modelValue, () => { if (!loaded.value && !loading.value && !error.value) void loadCatalog() }, { immediate: true })

// 旧数据兼容：渠道与密钥使用了不同受限模式时，统一为渠道模式并要求重新确认密钥名单。
const initial = unifyMixedModePolicy(props.modelValue)
const unifiedNotice = ref(initial.unified)
if (initial.unified) emit('update:modelValue', initial.policy)

const cardMode = computed(() => mergedAccessMode(props.modelValue))
const unavailableLabel = computed(() => error.value ? '目录读取失败，保留原选择' : !loaded.value ? '目录加载中，保留原选择' : '已删除或不存在')

const staleChannels = computed(() => props.modelValue.channels.mode !== 'unrestricted'
  ? props.modelValue.channels.ids.filter(id => !channels.value.some(channel => channel.id === id)) : [])
const staleKeys = computed(() => props.modelValue.channelKeys.mode !== 'unrestricted'
  ? props.modelValue.channelKeys.keys.filter(pair => !channels.value.some(channel =>
      channel.id === pair.channelId && channel.keys.some(key => key.id === pair.keyId))) : [])
const catalogModelIds = computed(() => routeOptions.value.map(route => route.modelId).sort())
const missingModels = computed(() => props.modelValue.models.mode !== 'unrestricted'
  ? props.modelValue.models.ids.filter(id => !catalogModelIds.value.includes(id)) : [])

/** 模型可用性随第一卡片的勾选实时重算；未加载目录时保持空表。 */
const availability = computed(() => loaded.value ? modelAvailabilityMap(props.modelValue, channels.value, routeOptions.value) : new Map())
const effectiveKeys = computed(() => loaded.value ? effectiveStaticKeyCount(props.modelValue, channels.value) : 0)
const totalKeys = computed(() => channels.value.filter(channel => channel.enabled).reduce((count, channel) => count + channel.keys.length, 0))
const availableModelCount = computed(() => [...availability.value.values()].filter(item => item.supported).length)
/** 模型 → 模型路由中的渠道名列表，用于模型行的来源标注。 */
const modelChannelNames = computed(() => {
  const channelsById = new Map(channels.value.map(channel => [channel.id, channel]))
  const map = new Map<string, string[]>()
  for (const route of routeOptions.value) {
    const names = route.entries
      .map(entry => channelsById.get(entry.channelId)?.name)
      .filter((name): name is string => Boolean(name))
      .filter((name, index, all) => all.indexOf(name) === index)
    map.set(route.modelId, names)
  }
  return map
})
const routeEmptyLabels = computed(() => emptyAccessDimensionLabels(props.modelValue))
const routeEmpty = computed(() => cardMode.value !== 'unrestricted' && routeEmptyLabels.value.includes('渠道与密钥'))
const modelEmpty = computed(() => props.modelValue.models.mode !== 'unrestricted' && routeEmptyLabels.value.includes('模型'))
/** 白名单中勾选但当前生效渠道/密钥已不支持的模型：置灰预警，不静默消失。 */
const conflictingModels = computed(() => props.modelValue.models.mode === 'allowlist'
  ? props.modelValue.models.ids.filter(id => !missingModels.value.includes(id)
      && !(availability.value.get(id)?.supported ?? false))
  : [])

const modelSearch = ref('')
const supportedModels = computed(() => catalogModelIds.value.filter(id => availability.value.get(id)?.supported ?? false))
const unsupportedCheckedModels = computed(() => props.modelValue.models.ids
  .filter(id => catalogModelIds.value.includes(id) && !(availability.value.get(id)?.supported ?? false))
  .sort())
const conflictStaleModels = computed(() => missingModels.value)
function matchesModelSearch(id: string): boolean {
  const query = modelSearch.value.trim().toLowerCase()
  return query === '' || id.toLowerCase().includes(query)
}
const visibleSupportedModels = computed(() => supportedModels.value.filter(matchesModelSearch))
const visibleUnsupportedModels = computed(() => unsupportedCheckedModels.value.filter(matchesModelSearch))
const visibleStaleModels = computed(() => conflictStaleModels.value.filter(matchesModelSearch))

const routeReasonLabels: Record<string, string> = {
  channel_missing: '渠道已删除',
  credential_missing: '凭据已删除',
  channel_disabled: '渠道已停用',
  credential_disabled: '凭据已停用',
  model_unsupported: '凭据不再支持该模型',
  protocol_unsupported: '没有共同支持的协议',
}

interface RoutePreviewItem {
  key: string
  channelName: string
  credentialName: string
  credentialType: 'static' | 'oauth'
  upstreamModels: string[]
  available: boolean
  reason: string
}

function isModelPreviewOpen(modelId: string): boolean {
  return expandedModels.value.includes(modelId)
}

/** 按需读取单个模型的服务端路由详情，展开预览不改变授权。 */
async function toggleModelPreview(modelId: string): Promise<void> {
  if (isModelPreviewOpen(modelId)) {
    expandedModels.value = expandedModels.value.filter(id => id !== modelId)
    return
  }
  expandedModels.value = [...expandedModels.value, modelId]
  if (routeDetails.value[modelId] || routeLoading.value.includes(modelId)) return
  routeLoading.value = [...routeLoading.value, modelId]
  const errors = { ...routeErrors.value }
  delete errors[modelId]
  routeErrors.value = errors
  try {
    routeDetails.value = { ...routeDetails.value, [modelId]: await api.getModelRoute(modelId) }
  } catch (cause) {
    routeErrors.value = { ...routeErrors.value, [modelId]: cause instanceof Error ? cause.message : String(cause) }
  } finally {
    routeLoading.value = routeLoading.value.filter(id => id !== modelId)
  }
}

/** 已保存顺序的路由身份，叠加当前调用方范围的过滤结果。 */
function routePreview(modelId: string): RoutePreviewItem[] {
  const detail = routeDetails.value[modelId]
  if (!detail) return []
  const channelsById = new Map(channels.value.map(channel => [channel.id, channel]))
  return detail.entries.map((entry, index) => {
    const channel = channelsById.get(entry.channelId)
    let available = false
    let reason = entry.reason ? (routeReasonLabels[entry.reason] ?? entry.reason) : ''
    if (!channel) {
      reason = reason || '渠道已删除'
    } else if (!channel.enabled) {
      reason = reason || '渠道已停用'
    } else if (!entry.selectable) {
      reason = reason || '路由当前不可用'
    } else if (!channelAllowedByPolicy(props.modelValue, channel.id)) {
      reason = '调用方范围排除了渠道'
    } else if (entry.credentialType === 'static') {
      const key = channel.keys.find(item => item.id === entry.credentialId)
      if (!key) reason = '静态密钥已删除'
      else if (!keyRoutable(key, Date.now())) reason = '静态密钥当前不可路由'
      else if (!staticKeyEffective(props.modelValue, { channelId: channel.id, keyId: key.id })) reason = '调用方范围排除了静态密钥'
      else available = true
    } else {
      const account = (channel.oauthAccounts ?? []).find(item => item.id === entry.credentialId)
      if (!account) reason = '订阅账号已删除'
      else if (!account.enabled) reason = '订阅账号已停用'
      else if (!oauthEffective(props.modelValue, channel.id)) reason = '密钥白名单不包含 OAuth 账号'
      else available = true
    }
    return {
      key: `${entry.credentialType}:${entry.channelId}:${entry.credentialId}:${index}`,
      channelName: channel?.name ?? entry.channelId,
      credentialName: entry.credentialName || entry.credentialId,
      credentialType: entry.credentialType,
      upstreamModels: entry.upstreamModels,
      available,
      reason: available ? '当前范围可用' : (reason || '路由当前不可用'),
    }
  })
}

function onCardModeChange(mode: ClientKeyAccessMode): void {
  emit('update:modelValue', changeMergedMode(props.modelValue, mode))
}
function onModelModeChange(mode: ClientKeyAccessMode): void {
  // 临时切回不限制也保留草稿选择，完整保存时由 helper 清空该维度数组。
  emit('update:modelValue', { ...props.modelValue, models: { ...props.modelValue.models, mode } })
}
function onToggleChannel(channelId: string): void {
  emit('update:modelValue', toggleChannelSelection(props.modelValue, channels.value, channelId))
}
function onToggleKey(pair: { channelId: string; keyId: string }, checked: boolean): void {
  emit('update:modelValue', toggleKeySelection(props.modelValue, channels.value, pair, checked))
}
function onToggleOAuth(channelId: string): void {
  emit('update:modelValue', toggleOAuthSelection(props.modelValue, channels.value, channelId))
}
function onSelectAll(): void {
  emit('update:modelValue', selectAllChannels(props.modelValue, channels.value))
}
function onClear(): void {
  emit('update:modelValue', clearChannelSelection(props.modelValue))
}
function changeModelId(id: string, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  const current = props.modelValue.models
  const ids = checked ? [...new Set([...current.ids, id])] : current.ids.filter(value => value !== id)
  emit('update:modelValue', { ...props.modelValue, models: { ...current, ids } })
}
function selectAllAvailableModels(): void {
  emit('update:modelValue', {
    ...props.modelValue,
    models: { ...props.modelValue.models, ids: [...new Set([...props.modelValue.models.ids, ...supportedModels.value])] },
  })
}
function clearModelSelection(): void {
  emit('update:modelValue', { ...props.modelValue, models: { ...props.modelValue.models, ids: [] } })
}

const missingScopes = computed(() => loaded.value ? [
  props.modelValue.channels.mode === 'allowlist' && props.modelValue.channels.ids.length > 0 && staleChannels.value.length === props.modelValue.channels.ids.length ? '渠道与密钥' : '',
  props.modelValue.models.mode === 'allowlist' && props.modelValue.models.ids.length > 0 && missingModels.value.length === props.modelValue.models.ids.length ? '模型' : '',
].filter(Boolean) : [])
</script>

<template>
  <div class="client-key-access-fields">
    <div class="access-intro">
      <strong>访问范围</strong>
      <p class="muted small">模型来自已保存的模型路由；渠道与密钥用于进一步收窄该路由的候选凭据。</p>
    </div>
    <div v-if="loading || error" class="access-catalog-state" role="status">
      <span v-if="loading">正在读取渠道、静态密钥与模型路由…</span>
      <template v-else>
        <span>目录读取失败：{{ error }}。已有选择保留。</span>
        <button type="button" class="button mini" @click="loadCatalog">重试目录</button>
      </template>
    </div>
    <p v-if="unifiedNotice" class="access-notice" role="status">
      渠道与密钥原先使用了不同的名单模式，已统一为渠道维度模式；原密钥名单已清空，请重新确认后保存。
    </p>

    <fieldset aria-label="渠道与密钥访问范围" class="access-scope">
      <legend>渠道与密钥</legend>
      <div class="scope-head">
        <Icon name="layers" :size="15" class="scope-icon" />
        <span class="scope-pill">支持按整渠道或子密钥级联限制</span>
        <span class="scope-stat">当前生效密钥：<strong>{{ effectiveKeys }}/{{ totalKeys }}</strong></span>
      </div>
      <AccessModeToggle
        :model-value="cardMode"
        label="渠道与密钥模式"
        name="channel-access-mode"
        @update:model-value="onCardModeChange"
      />
      <p v-if="cardMode === 'unrestricted'" class="muted small unrestricted-hint">
        默认允许所有可用渠道，并沿用各渠道下静态密钥与 OAuth 订阅账号的候选规则。
      </p>
      <template v-else>
        <ClientKeyAccessTree
          :mode="cardMode"
          :channels="channels"
          :policy="modelValue"
          :loaded="loaded"
          :unavailable-label="unavailableLabel"
          :stale-channels="staleChannels"
          :stale-keys="staleKeys"
          @toggle-channel="onToggleChannel"
          @toggle-key="onToggleKey"
          @toggle-oauth="onToggleOAuth"
          @select-all="onSelectAll"
          @clear="onClear"
        />
        <p v-if="routeEmpty" class="access-error" role="alert">
          {{ cardMode === 'allowlist' ? '白名单' : '黑名单' }}模式下请至少勾选 1 个渠道或密钥；如需全部放行请选择「不限制」。
        </p>
      </template>
    </fieldset>

    <fieldset aria-label="模型访问范围" class="access-scope">
      <legend>对外模型 ID</legend>
      <div class="scope-head">
        <Icon name="cpu" :size="15" class="scope-icon" />
        <span class="scope-stat">可调用模型池：<strong>{{ availableModelCount }}</strong> 个</span>
      </div>
      <AccessModeToggle
        :model-value="modelValue.models.mode"
        label="模型模式"
        name="model-access-mode"
        @update:model-value="onModelModeChange"
      />
      <p class="access-link-hint" role="status">
        基于上方生效的 <strong>{{ effectiveKeys }}</strong> 个渠道密钥，当前范围可调用
        <strong>{{ availableModelCount }}</strong> 个已配置路由的模型。
      </p>
      <p v-if="loaded && !routeOptions.length" class="access-warning" role="status">
        还没有已保存的模型路由；调用方目前不能授权任何模型，请先在「模型路由」中配置。
      </p>
      <p v-if="modelValue.models.mode === 'unrestricted'" class="muted small">
        按调用请求中的对外模型 ID 精确匹配，区分大小写，不支持通配符。
      </p>
      <template v-else>
        <div class="model-toolbar">
          <div class="model-search">
            <input
              v-model="modelSearch"
              type="search"
              aria-label="搜索对外模型 ID"
              placeholder="搜索对外模型 ID (如 gpt-4o, gemini)..."
            />
          </div>
          <div class="tree-actions model-actions">
            <button type="button" class="tree-action is-primary" @click="selectAllAvailableModels">全选可用</button>
            <span class="tree-action-divider">|</span>
            <button type="button" class="tree-action" @click="clearModelSelection">清空</button>
          </div>
        </div>
        <div class="access-options" :aria-label="modelValue.models.mode === 'denylist' ? '模型黑名单' : '模型白名单'">
          <div v-for="id in visibleSupportedModels" :key="id" class="access-model-item">
            <div class="access-option is-model access-model-row">
              <label class="access-model-label">
                <input type="checkbox" :checked="modelValue.models.ids.includes(id)" @change="changeModelId(id, $event)" />
                <span class="access-model-id"><code>{{ id }}</code></span>
                <span class="access-model-stat">
                  {{ (modelChannelNames.get(id) ?? []).join('、') }}
                  · 路由 {{ availability.get(id)?.routeEntryCount ?? 0 }} 条
                  <template v-if="(availability.get(id)?.availableCredentialCount ?? 0) > 0">
                    · 当前范围可用 {{ availability.get(id)?.availableCredentialCount }} 个凭据
                  </template>
                </span>
              </label>
              <button
                type="button"
                class="route-preview-toggle"
                :aria-expanded="isModelPreviewOpen(id)"
                @click.prevent.stop="toggleModelPreview(id)"
              >
                {{ isModelPreviewOpen(id) ? '收起路由' : '查看路由' }}
              </button>
            </div>
            <div v-if="isModelPreviewOpen(id)" class="route-preview" role="group" :aria-label="`${id} 的生效路由`">
              <p v-if="routeLoading.includes(id)" class="muted small">正在读取模型路由…</p>
              <p v-else-if="routeErrors[id]" class="access-warning">{{ routeErrors[id] }}</p>
              <p v-else-if="!routePreview(id).length" class="muted small">模型路由为空，暂无凭据。</p>
              <ol v-else class="route-preview-list">
                <li v-for="(item, index) in routePreview(id)" :key="item.key">
                  <span class="route-index">{{ index + 1 }}</span>
                  <strong class="route-name">{{ item.credentialName }}</strong>
                  <span class="badge neutral">{{ item.credentialType === 'oauth' ? '订阅账号' : '静态密钥' }}</span>
                  <span class="route-channel">{{ item.channelName }}</span>
                  <span class="route-status" :class="item.available ? 'is-ok' : 'is-off'">{{ item.reason }}</span>
                  <small v-if="item.upstreamModels.length" class="route-upstreams">{{ item.upstreamModels.join(' / ') }}</small>
                </li>
              </ol>
            </div>
          </div>
          <div v-for="id in visibleUnsupportedModels" :key="id" class="access-model-item">
            <div class="access-option is-model is-conflict access-model-row">
              <label class="access-model-label">
                <input type="checkbox" checked @change="changeModelId(id, $event)" />
                <span class="access-model-id"><code>{{ id }}</code></span>
                <span class="access-model-stat">路由 {{ availability.get(id)?.routeEntryCount ?? 0 }} 条</span>
                <span class="access-model-conflict">⚠️ 当前范围或模型路由没有可用凭据</span>
              </label>
              <button
                type="button"
                class="route-preview-toggle"
                :aria-expanded="isModelPreviewOpen(id)"
                @click.prevent.stop="toggleModelPreview(id)"
              >
                {{ isModelPreviewOpen(id) ? '收起路由' : '查看路由' }}
              </button>
            </div>
            <div v-if="isModelPreviewOpen(id)" class="route-preview" role="group" :aria-label="`${id} 的生效路由`">
              <p v-if="routeLoading.includes(id)" class="muted small">正在读取模型路由…</p>
              <p v-else-if="routeErrors[id]" class="access-warning">{{ routeErrors[id] }}</p>
              <p v-else-if="!routePreview(id).length" class="muted small">模型路由为空，暂无凭据。</p>
              <ol v-else class="route-preview-list">
                <li v-for="(item, index) in routePreview(id)" :key="item.key">
                  <span class="route-index">{{ index + 1 }}</span>
                  <strong class="route-name">{{ item.credentialName }}</strong>
                  <span class="badge neutral">{{ item.credentialType === 'oauth' ? '订阅账号' : '静态密钥' }}</span>
                  <span class="route-channel">{{ item.channelName }}</span>
                  <span class="route-status" :class="item.available ? 'is-ok' : 'is-off'">{{ item.reason }}</span>
                  <small v-if="item.upstreamModels.length" class="route-upstreams">{{ item.upstreamModels.join(' / ') }}</small>
                </li>
              </ol>
            </div>
          </div>
          <label v-for="id in visibleStaleModels" :key="id" class="access-option is-model is-unavailable">
            <input type="checkbox" checked @change="changeModelId(id, $event)" />
            <span class="access-model-id"><strong>{{ unavailableLabel }}</strong><code>{{ id }}</code></span>
          </label>
        </div>
        <p v-if="loaded && visibleSupportedModels.length === 0 && visibleUnsupportedModels.length === 0 && visibleStaleModels.length === 0" class="muted small">
          {{ modelSearch ? '没有匹配搜索条件的模型。' : routeOptions.length ? '当前所选渠道/密钥与模型路由下暂无可调用模型。' : '还没有已保存的模型路由，请先在「模型路由」中配置。' }}
        </p>
        <p v-if="conflictingModels.length" class="access-notice" role="status">
          检测到 {{ conflictingModels.length }} 个已勾选模型（{{ conflictingModels.join('、') }}）在当前渠道/密钥范围或模型路由下没有可用凭据，保存后实际调用时无法路由到这些模型。
        </p>
        <p v-if="modelEmpty" class="access-error" role="alert">
          模型{{ modelValue.models.mode === 'allowlist' ? '白' : '黑' }}名单不能为空：请至少勾选 1 个模型，或切换为「不限制」。
        </p>
      </template>
    </fieldset>

    <p v-if="missingScopes.length" class="access-warning" role="status">无授权目标：当前配置或模型路由中找不到所选{{ missingScopes.join('、') }}，原 ID 与受限模式已保留。</p>
    <p v-if="loaded && (staleChannels.length || staleKeys.length || (modelValue.models.mode === 'allowlist' && missingModels.length))" class="access-warning" role="status">部分已选目标已删除、未配置模型路由或不存在，请明确取消或重新选择；原模式与名单已保留。</p>
    <p v-if="loaded && modelValue.models.mode === 'denylist' && missingModels.length" class="access-warning" role="status">可保留已有的模型黑名单 ID；同 ID 模型重新配置后仍禁止，也可明确取消排除规则。</p>
  </div>
</template>

<style scoped>
.client-key-access-fields { display: grid; gap: 14px; min-width: 0; }
.access-intro p, .access-scope p { margin: 6px 0 0; }
.access-scope { min-width: 0; margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 12px; display: grid; gap: 10px; }
.access-scope legend { padding: 0 5px; font-weight: 600; }
.scope-head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.scope-icon { flex: 0 0 auto; color: var(--brand); }
.scope-pill {
  flex: 0 1 auto; padding: 2px 9px; border-radius: 999px; font-size: 11px;
  color: var(--muted); background: var(--glass-bg); border: 1px solid var(--line);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.scope-stat { flex: 0 0 auto; margin-left: auto; font-size: 12px; color: var(--muted); }
.scope-stat strong { color: var(--brand); font-weight: 600; }
.unrestricted-hint { color: var(--muted); }
.access-notice {
  margin: 0; padding: 8px 10px; border-radius: 8px; font-size: 12px; overflow-wrap: anywhere;
  color: var(--warning); background: color-mix(in srgb, var(--warning) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--warning) 30%, transparent);
}
.access-error {
  margin: 0; padding: 8px 10px; border-radius: 8px; font-size: 12px; overflow-wrap: anywhere;
  color: var(--danger); background: var(--danger-soft); border: 1px solid color-mix(in srgb, var(--danger) 30%, transparent);
  font-weight: 500;
}
.access-link-hint {
  margin: 0; padding: 7px 10px 7px 24px; border-radius: 8px; font-size: 12px; position: relative;
  color: var(--ink); background: color-mix(in srgb, var(--brand) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--brand) 24%, transparent);
}
.access-link-hint::before {
  content: ''; position: absolute; left: 10px; top: 50%; transform: translateY(-50%);
  width: 7px; height: 7px; border-radius: 999px; background: var(--brand);
}
.access-link-hint strong { color: var(--brand); }
.access-catalog-state { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }
.model-toolbar { display: flex; align-items: center; gap: 10px; }
.model-search {
  flex: 1 1 auto; min-width: 0; display: flex; align-items: center; gap: 6px;
  padding: 5px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--glass-field);
}
.model-search input { flex: 1 1 auto; min-width: 0; border: 0; background: transparent; color: var(--ink); font-size: 12px; outline: none; }
.model-actions { flex: 0 0 auto; }
.access-options { display: grid; max-height: 200px; overflow-y: auto; overscroll-behavior: contain; border: 1px solid var(--line); border-radius: 10px; background: var(--glass-bg-soft); }
.access-option { display: flex; align-items: center; gap: 10px; padding: 8px 10px; cursor: pointer; border-bottom: 1px solid var(--line); }
.access-option:last-child { border-bottom: 0; }
.access-option input { flex: 0 0 auto; margin: 0; accent-color: var(--brand); }
.access-option.is-model { justify-content: space-between; }
.access-model-item { display: grid; border-bottom: 1px solid var(--line); }
.access-model-item:last-child { border-bottom: 0; }
.access-model-row.access-option { border-bottom: 0; align-items: flex-start; }
.access-model-label { display: flex; align-items: center; gap: 10px; flex: 1 1 auto; min-width: 0; cursor: pointer; }
.access-model-label .access-model-stat { margin-left: auto; text-align: right; }
.route-preview-toggle {
  flex: 0 0 auto; border: 0; background: transparent; color: var(--brand);
  font-size: 11px; padding: 4px 6px; border-radius: 6px; cursor: pointer;
}
.route-preview-toggle:hover { background: var(--glass-bg-strong); }
.route-preview { padding: 8px 10px 10px 32px; background: var(--glass-bg-soft); border-top: 1px dashed var(--line); }
.route-preview-list { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; }
.route-preview-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 12px; min-width: 0; }
.route-index {
  display: inline-grid; place-items: center; flex: 0 0 auto; width: 18px; height: 18px;
  border-radius: 999px; font-size: 10px; color: var(--muted); background: var(--glass-bg-strong);
}
.route-name { min-width: 0; overflow-wrap: anywhere; }
.route-channel { color: var(--muted); }
.route-status.is-ok { color: var(--brand); font-weight: 600; }
.route-status.is-off { color: var(--warning); font-weight: 600; }
.route-upstreams { flex-basis: 100%; color: var(--muted); padding-left: 24px; overflow-wrap: anywhere; }
.access-model-id { display: grid; gap: 3px; min-width: 0; overflow-wrap: anywhere; }
.access-model-id code { color: var(--ink); font-size: 12px; font-family: ui-monospace, monospace; }
.access-model-stat { flex: 0 0 auto; color: var(--muted); font-size: 11px; }
.access-option.is-conflict { background: color-mix(in srgb, var(--warning) 8%, transparent); }
.access-option.is-conflict .access-model-id code { color: var(--warning); text-decoration: line-through; }
.access-model-conflict { flex: 0 0 auto; padding: 2px 8px; border-radius: 999px; font-size: 11px; color: var(--warning); background: color-mix(in srgb, var(--warning) 14%, transparent); border: 1px solid color-mix(in srgb, var(--warning) 30%, transparent); }
.access-option.is-unavailable .access-model-id strong { color: var(--muted); font-size: 12px; }
.access-warning { margin: 0; color: var(--warning); font-size: 12px; overflow-wrap: anywhere; }
.tree-action { border: 0; background: transparent; padding: 3px 6px; border-radius: 6px; color: var(--muted); font-size: 12px; cursor: pointer; }
.tree-action:hover { background: var(--glass-bg-strong); color: var(--ink); }
.tree-action.is-primary { color: var(--brand); font-weight: 600; }
.tree-action-divider { color: var(--line); font-size: 12px; }
@media (max-width: 640px) {
  .access-scope { padding: 10px; }
  .access-option { min-height: 44px; }
  .model-toolbar { flex-wrap: wrap; }
}
</style>

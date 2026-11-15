<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import type { Channel, ClientKeyAccessMode, ClientKeyAccessPolicy, ClientKeyAllowedChannelKey } from '../types/api'

const props = defineProps<{ modelValue: ClientKeyAccessPolicy }>()
const emit = defineEmits<{ 'update:modelValue': [value: ClientKeyAccessPolicy] }>()
const channels = ref<Channel[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const needsCatalog = computed(() => Object.values(props.modelValue).some(scope => scope.mode !== 'unrestricted'))

/** 按需读取管理目录；失败保留已有目录及名单，不自动改变授权。 */
async function loadCatalog(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    channels.value = await api.listChannels()
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}
watch(needsCatalog, needed => { if (needed && !loaded.value) void loadCatalog() }, { immediate: true })

const channelOptions = computed(() => channels.value.map(channel => ({
  id: channel.id,
  name: channel.name,
  hint: channel.enabled ? '' : '渠道停用',
})))
const keyOptions = computed(() => channels.value.flatMap(channel => channel.keys.map(key => ({
  channelId: channel.id,
  keyId: key.id,
  name: `${channel.name} / ${key.name}`,
  hint: !channel.enabled ? '渠道停用' : key.state !== 'ACTIVE' ? '密钥停用' : !key.credentialSet ? '未配置凭据' : '',
}))))
const modelOptions = computed(() => [...new Set(channels.value.flatMap(channel => channel.modelMappings.map(mapping =>
  mapping.canonicalModelId ?? mapping.upstreamModel,
)))].sort())
const missingChannels = computed(() => props.modelValue.channels.mode !== 'unrestricted'
  ? props.modelValue.channels.ids.filter(id => !channelOptions.value.some(option => option.id === id)) : [])
const missingKeys = computed(() => props.modelValue.channelKeys.mode !== 'unrestricted'
  ? props.modelValue.channelKeys.keys.filter(pair => !keyOptions.value.some(option => sameKey(pair, option))) : [])
const missingModels = computed(() => props.modelValue.models.mode !== 'unrestricted'
  ? props.modelValue.models.ids.filter(id => !modelOptions.value.includes(id)) : [])
const unavailableLabel = computed(() => error.value ? '目录读取失败，保留原选择' : !loaded.value ? '目录加载中，保留原选择' : '已删除或不存在')
const conflictingKeys = computed(() => props.modelValue.channelKeys.mode === 'allowlist'
  ? props.modelValue.channelKeys.keys.filter(pair => props.modelValue.channels.mode === 'allowlist'
    ? !props.modelValue.channels.ids.includes(pair.channelId)
    : props.modelValue.channels.mode === 'denylist' && props.modelValue.channels.ids.includes(pair.channelId)) : [])
const emptyScopes = computed(() => [
  props.modelValue.channels.mode === 'allowlist' && props.modelValue.channels.ids.length === 0 ? '渠道' : '',
  props.modelValue.channelKeys.mode === 'allowlist' && props.modelValue.channelKeys.keys.length === 0 ? '渠道密钥' : '',
  props.modelValue.models.mode === 'allowlist' && props.modelValue.models.ids.length === 0 ? '模型' : '',
].filter(Boolean))
const missingScopes = computed(() => loaded.value ? [
  props.modelValue.channels.mode === 'allowlist' && props.modelValue.channels.ids.length > 0 && missingChannels.value.length === props.modelValue.channels.ids.length ? '渠道' : '',
  props.modelValue.channelKeys.mode === 'allowlist' && props.modelValue.channelKeys.keys.length > 0 && missingKeys.value.length === props.modelValue.channelKeys.keys.length ? '渠道密钥' : '',
  props.modelValue.models.mode === 'allowlist' && props.modelValue.models.ids.length > 0 && missingModels.value.length === props.modelValue.models.ids.length ? '模型' : '',
].filter(Boolean) : [])

function sameKey(left: ClientKeyAllowedChannelKey, right: ClientKeyAllowedChannelKey): boolean {
  return left.channelId === right.channelId && left.keyId === right.keyId
}
function changeMode(scope: keyof ClientKeyAccessPolicy, event: Event): void {
  const mode = (event.target as HTMLSelectElement).value as ClientKeyAccessMode
  // 临时切回不限制也保留草稿选择，完整保存时由 helper 清空该维度数组。
  emit('update:modelValue', { ...props.modelValue, [scope]: { ...props.modelValue[scope], mode } })
}
function changeId(scope: 'channels' | 'models', id: string, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  const current = props.modelValue[scope]
  const ids = checked ? [...new Set([...current.ids, id])] : current.ids.filter(value => value !== id)
  emit('update:modelValue', { ...props.modelValue, [scope]: { ...current, ids } })
}
function changeKey(pair: ClientKeyAllowedChannelKey, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  const current = props.modelValue.channelKeys
  const keys = checked
    ? [...current.keys, { channelId: pair.channelId, keyId: pair.keyId }]
    : current.keys.filter(value => !sameKey(value, pair))
  emit('update:modelValue', { ...props.modelValue, channelKeys: { ...current, keys } })
}
</script>

<template>
  <div class="client-key-access-fields">
    <div class="access-intro">
      <strong>访问范围</strong>
      <p class="muted small">三个维度共同限定可调用的目标。白名单只允许所选项，空名单拒绝全部；黑名单排除所选项，空名单不额外限制。</p>
    </div>
    <div v-if="needsCatalog && (loading || error)" class="access-catalog-state" role="status">
      <span v-if="loading">正在读取渠道、静态密钥与模型目录…</span>
      <template v-else>
        <span>目录读取失败：{{ error }}。已有选择保留。</span>
        <button type="button" class="button mini" @click="loadCatalog">重试目录</button>
      </template>
    </div>

    <fieldset aria-label="渠道访问范围" class="access-scope">
      <legend>渠道</legend>
      <label class="field access-mode">
        <span>渠道模式</span>
        <select aria-label="渠道模式" :value="modelValue.channels.mode" @change="changeMode('channels', $event)">
          <option value="unrestricted">不限制</option>
          <option value="allowlist">白名单</option>
          <option value="denylist">黑名单</option>
        </select>
      </label>
      <p v-if="modelValue.channels.mode === 'unrestricted'" class="muted small">允许满足其他规则的渠道。</p>
      <template v-else>
        <p v-if="modelValue.channels.mode === 'denylist'" class="muted small">禁止选中渠道及其静态密钥与 OAuth 订阅账号；其他渠道按其余规则使用。</p>
        <div class="access-options" :aria-label="modelValue.channels.mode === 'denylist' ? '渠道黑名单' : '渠道白名单'">
          <label v-for="option in channelOptions" :key="option.id" class="access-option">
            <input type="checkbox" :checked="modelValue.channels.ids.includes(option.id)" @change="changeId('channels', option.id, $event)" />
            <span><strong>{{ option.name }}</strong><code>{{ option.id }}</code><small v-if="option.hint">{{ option.hint }}</small></span>
          </label>
          <label v-for="id in missingChannels" :key="id" class="access-option is-unavailable">
            <input type="checkbox" checked @change="changeId('channels', id, $event)" />
            <span><strong>{{ unavailableLabel }}</strong><code>{{ id }}</code></span>
          </label>
        </div>
        <p v-if="loaded && channelOptions.length === 0" class="muted small">暂无可选择的渠道。</p>
      </template>
    </fieldset>

    <fieldset aria-label="渠道密钥访问范围" class="access-scope">
      <legend>渠道密钥</legend>
      <label class="field access-mode">
        <span>渠道密钥模式</span>
        <select aria-label="渠道密钥模式" :value="modelValue.channelKeys.mode" @change="changeMode('channelKeys', $event)">
          <option value="unrestricted">不限制</option>
          <option value="allowlist">白名单（仅静态密钥）</option>
          <option value="denylist">黑名单（仅静态密钥）</option>
        </select>
      </label>
      <p class="muted small">{{ modelValue.channelKeys.mode === 'unrestricted' ? '沿用静态密钥与 OAuth 订阅账号的候选规则。' : modelValue.channelKeys.mode === 'denylist' ? '只排除选中的静态密钥，OAuth 订阅账号仍按其他规则参与候选。' : '只允许选中的静态密钥，OAuth 订阅账号不参与候选。' }}</p>
      <template v-if="modelValue.channelKeys.mode !== 'unrestricted'">
        <div class="access-options" :aria-label="modelValue.channelKeys.mode === 'denylist' ? '渠道密钥黑名单' : '渠道密钥白名单'">
          <label v-for="option in keyOptions" :key="JSON.stringify([option.channelId, option.keyId])" class="access-option">
            <input type="checkbox" :checked="modelValue.channelKeys.keys.some(pair => sameKey(pair, option))" @change="changeKey(option, $event)" />
            <span><strong>{{ option.name }}</strong><code>{{ option.channelId }} / {{ option.keyId }}</code><small v-if="option.hint">{{ option.hint }}</small></span>
          </label>
          <label v-for="pair in missingKeys" :key="JSON.stringify([pair.channelId, pair.keyId])" class="access-option is-unavailable">
            <input type="checkbox" checked @change="changeKey(pair, $event)" />
            <span><strong>{{ unavailableLabel }}</strong><code>{{ pair.channelId }} / {{ pair.keyId }}</code></span>
          </label>
        </div>
        <p v-if="loaded && keyOptions.length === 0" class="muted small">暂无可选择的静态密钥。</p>
        <p v-if="conflictingKeys.length" class="access-warning" role="status">已选密钥 {{ conflictingKeys.map(pair => `${pair.channelId} / ${pair.keyId}`).join('、') }} {{ modelValue.channels.mode === 'allowlist' ? '不在渠道白名单中' : '被渠道黑名单排除' }}，请调整后保存。</p>
      </template>
    </fieldset>

    <fieldset aria-label="模型访问范围" class="access-scope">
      <legend>对外模型 ID</legend>
      <label class="field access-mode">
        <span>模型模式</span>
        <select aria-label="模型模式" :value="modelValue.models.mode" @change="changeMode('models', $event)">
          <option value="unrestricted">不限制</option>
          <option value="allowlist">白名单</option>
          <option value="denylist">黑名单</option>
        </select>
      </label>
      <p class="muted small">按调用请求中的对外模型 ID 精确匹配，区分大小写，不支持通配符。</p>
      <p v-if="modelValue.models.mode === 'denylist'" class="muted small">禁止选中的对外模型 ID；其他已配置模型按其余规则使用。</p>
      <template v-if="modelValue.models.mode !== 'unrestricted'">
        <div class="access-options" :aria-label="modelValue.models.mode === 'denylist' ? '模型黑名单' : '模型白名单'">
          <label v-for="id in modelOptions" :key="id" class="access-option">
            <input type="checkbox" :checked="modelValue.models.ids.includes(id)" @change="changeId('models', id, $event)" />
            <span><code>{{ id }}</code></span>
          </label>
          <label v-for="id in missingModels" :key="id" class="access-option is-unavailable">
            <input type="checkbox" checked @change="changeId('models', id, $event)" />
            <span><strong>{{ unavailableLabel }}</strong><code>{{ id }}</code></span>
          </label>
        </div>
        <p v-if="loaded && modelOptions.length === 0" class="muted small">暂无已配置的对外模型 ID。</p>
      </template>
    </fieldset>

    <p v-if="emptyScopes.length" class="access-warning" role="status">无授权目标：{{ emptyScopes.join('、') }}白名单为空，保存后该调用方密钥不能调用任何模型。</p>
    <p v-if="missingScopes.length" class="access-warning" role="status">无授权目标：当前目录中找不到所选{{ missingScopes.join('、') }}，原 ID 与受限模式已保留。</p>
    <p v-if="loaded && (missingChannels.length || missingKeys.length || missingModels.length)" class="access-warning" role="status">部分已选目标已删除或不存在，请明确取消或重新选择；原模式与名单已保留。</p>
  </div>
</template>

<style scoped>
.client-key-access-fields { display: grid; gap: 14px; min-width: 0; }
.access-intro p, .access-scope p { margin: 6px 0 0; }
.access-scope { min-width: 0; margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 12px; }
.access-scope legend { padding: 0 5px; font-weight: 600; }
.access-mode { gap: 5px; }
.access-options { display: grid; max-height: 200px; overflow-y: auto; margin-top: 10px; overscroll-behavior: contain; }
.access-option { display: flex; align-items: flex-start; gap: 10px; padding: 9px 4px; cursor: pointer; border-bottom: 1px solid var(--line); }
.access-option:last-child { border-bottom: 0; }
.access-option input { flex: 0 0 auto; margin-top: 3px; }
.access-option > span { display: grid; gap: 3px; min-width: 0; overflow-wrap: anywhere; }
.access-option strong { font-size: 13px; }
.access-option code { color: var(--muted); white-space: normal; overflow-wrap: anywhere; font-size: 12px; }
.access-option small, .is-unavailable strong { color: var(--muted); }
.access-warning { margin: 0; color: var(--warning); font-size: 12px; overflow-wrap: anywhere; }
.access-catalog-state { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }
@media (max-width: 640px) { .access-scope { padding: 10px; } .access-option { min-height: 44px; } }
</style>

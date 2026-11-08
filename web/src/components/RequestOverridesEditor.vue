<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import type { UpstreamHeaderProfile, UpstreamRequestOverrides } from '../types/api'
import {
  buildRequestOverrides,
  createRequestOverrideDrafts,
  requestOverrideDraftSnapshot,
  REQUEST_OVERRIDE_SCOPES,
} from '../utils/requestOverrides'
import type { RequestOverrideDrafts } from '../utils/requestOverrides'

const props = withDefaults(defineProps<{
  value?: Partial<UpstreamRequestOverrides>
  global?: boolean
  disabled?: boolean
  profiles?: UpstreamHeaderProfile[] | null
}>(), {
  value: () => ({}),
  global: false,
  disabled: false,
  profiles: null,
})
const emit = defineEmits<{
  (event: 'changed'): void
}>()
const scope = ref('common')
const inheritGlobal = ref(true)
const drafts = reactive<RequestOverrideDrafts>(createRequestOverrideDrafts(props.value))
const availableProfiles = ref<UpstreamHeaderProfile[]>(props.profiles || [])
const profileError = ref('')

watch(() => props.value, (value) => {
  Object.assign(drafts, createRequestOverrideDrafts(value))
  inheritGlobal.value = value.inheritGlobal !== false
}, { immediate: true })
watch(() => props.profiles, (profiles) => {
  if (profiles) availableProfiles.value = profiles
}, { deep: true })
watch([drafts, inheritGlobal], () => emit('changed'), { deep: true })

if (props.profiles === null) {
  api.getUpstreamRequestSettings().then((settings) => {
    availableProfiles.value = settings.profiles
  }).catch((cause) => { profileError.value = errorMessage(cause) })
}

const current = computed(() => drafts[scope.value])
const scopeHints: Record<string, string> = {
  common: '应用于全部出站协议，也用于模型发现、连通性测试和协议探测。适合团队标识等通用请求头。',
  chat_completions: '仅作用于实际发往上游的 Chat Completions 请求；覆盖同层通用配置。',
  responses: '仅作用于实际发往上游的 Responses 请求；可单独设置 reasoning、store 等参数。',
  messages: '仅作用于实际发往上游的 Anthropic Messages 请求；可单独设置 anthropic-* 请求头。',
  gemini: '仅作用于实际发往上游的 Gemini 请求；可单独设置 generationConfig 等参数。',
}
const scopeHint = computed(() => scopeHints[scope.value] || '')
const scopeSummaries = computed(() => REQUEST_OVERRIDE_SCOPES.map(({ id, label, errorLabel }) => {
  const layer = drafts[id]
  const fields = []
  if (layer.headerProfile) {
    fields.push(layer.headerProfile === 'none' ? '无预设' : layer.headerProfile === 'auto' ? '自动预设' : layer.headerProfile)
  }
  if (layer.omitUserAgent) fields.push('清空 UA')
  else if (layer.userAgent?.trim()) fields.push('UA')
  if (layer.headersText?.trim() && layer.headersText.trim() !== '{}') fields.push('请求头')
  if (layer.bodyText?.trim() && layer.bodyText.trim() !== '{}') fields.push('请求体')
  return {
    id,
    label: errorLabel || label,
    summary: fields.length ? fields.join(' · ') : '继承 / 未配置',
  }
}))
const previewProfile = computed(() => {
  const selected = current.value.headerProfile || drafts.common.headerProfile || 'auto'
  if (selected === 'auto' && scope.value === 'common') return null
  const id = selected === 'auto' ? ({ messages: 'claude', gemini: 'google' }[scope.value] || 'codex') : selected
  return availableProfiles.value.find((profile) => profile.id === id)
})
const uaPlaceholder = computed(() => previewProfile.value?.headers?.['User-Agent'] || '留空使用全局配置或协议预设')
const previewHeaders = computed(() => JSON.stringify(previewProfile.value?.headers || {}, null, 2))

function getValue(): UpstreamRequestOverrides {
  return buildRequestOverrides(drafts, props.global || inheritGlobal.value)
}

function getSnapshot(): string {
  return requestOverrideDraftSnapshot(drafts, props.global || inheritGlobal.value)
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

defineExpose({ getValue, getSnapshot })
</script>

<template>
  <div class="request-overrides-editor">
    <label v-if="!global" class="check-row">
      <input v-model="inheritGlobal" type="checkbox" :disabled="disabled" />
      <span>继承全局请求配置</span>
    </label>
    <label class="field">
      <span>配置范围</span>
      <select v-model="scope" :disabled="disabled">
        <option v-for="item in REQUEST_OVERRIDE_SCOPES" :key="item.id" :value="item.id">{{ item.label }}</option>
      </select>
      <small>{{ scopeHint }}</small>
    </label>
    <div class="request-scope-summary" aria-label="各配置范围状态">
      <button
        v-for="item in scopeSummaries"
        :key="item.id"
        type="button"
        class="request-scope-chip"
        :class="{ active: scope === item.id, configured: item.summary !== '继承 / 未配置' }"
        :disabled="disabled"
        @click="scope = item.id"
      >
        <span>{{ item.label }}</span>
        <small>{{ item.summary }}</small>
      </button>
    </div>
    <label class="field">
      <span>请求头预设</span>
      <select v-model="current.headerProfile" :disabled="disabled">
        <option v-if="global && scope === 'common' && !current.headerProfile" value="" disabled>请选择预设</option>
        <option v-if="!(global && scope === 'common')" value="">{{ global ? '跟随通用配置 / 下级配置' : '跟随全局 / 通用配置' }}</option>
        <option value="auto">按协议自动：Claude / Codex / Google SDK</option>
        <option value="none">不使用预设</option>
        <option v-for="profile in availableProfiles" :key="profile.id" :value="profile.id">{{ profile.name }}</option>
      </select>
      <small>“继承”表示不覆盖较低层；显式自动会按实际出站协议选择 Claude、Codex 或 Google SDK。</small>
      <small v-if="profileError" class="text-danger">预设信息加载失败：{{ profileError }}。可关闭后重新打开。</small>
    </label>
    <label class="field">
      <span>自定义 User-Agent（UA）</span>
      <input v-model="current.userAgent" :placeholder="uaPlaceholder" :disabled="disabled || current.omitUserAgent" maxlength="8192" autocomplete="off" />
      <small>留空继承默认值。渠道配置优先于全局配置。</small>
    </label>
    <label class="check-row">
      <input v-model="current.omitUserAgent" type="checkbox" :disabled="disabled" />
      <span>清空 User-Agent</span>
    </label>
    <details class="request-overrides-details">
      <summary>更多请求头与请求体覆盖</summary>
      <div class="request-overrides-fields">
        <label class="field">
          <span>请求头（JSON 对象）</span>
          <textarea v-model="current.headersText" rows="5" :disabled="disabled" spellcheck="false"></textarea>
          <small>值为字符串时设置请求头，为 null 时删除；名称不区分大小写。上游鉴权仍使用渠道密钥。</small>
        </label>
        <label class="field">
          <span>请求体（JSON 合并补丁）</span>
          <textarea v-model="current.bodyText" rows="6" :disabled="disabled" spellcheck="false"></textarea>
          <small>嵌套对象逐层合并，数组整体替换，null 删除字段。model 和 stream 由网关管理。</small>
        </label>
      </div>
    </details>
    <details v-if="previewProfile" class="request-overrides-details">
      <summary>{{ previewProfile.name }} 预设请求头</summary>
      <pre class="request-overrides-preview">{{ previewHeaders }}</pre>
      <small>此处展示当前缓存版本；完整版本历史、手动刷新和固定版本可在全局配置的「动态预设目录」中管理。</small>
    </details>
  </div>
</template>

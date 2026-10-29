<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { buildRequestOverrides, createRequestOverrideDrafts, REQUEST_OVERRIDE_SCOPES } from '../utils/requestOverrides'

const props = defineProps({
  value: { type: Object, default: () => ({}) },
  global: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  profiles: { type: Array, default: null },
})
const emit = defineEmits(['changed'])
const scope = ref('common')
const inheritGlobal = ref(true)
const drafts = reactive(createRequestOverrideDrafts(props.value))
const availableProfiles = ref(props.profiles || [])
const profileError = ref('')

watch(() => props.value, (value) => {
  Object.assign(drafts, createRequestOverrideDrafts(value))
  inheritGlobal.value = value.inheritGlobal !== false
}, { immediate: true })
watch([drafts, inheritGlobal], () => emit('changed'), { deep: true })

if (props.profiles === null) {
  api.getUpstreamRequestSettings().then((settings) => {
    availableProfiles.value = settings.profiles
  }).catch((cause) => { profileError.value = cause.message })
}

const current = computed(() => drafts[scope.value])
const previewProfile = computed(() => {
  const selected = current.value.headerProfile || drafts.common.headerProfile || 'auto'
  if (selected === 'auto' && scope.value === 'common') return null
  const id = selected === 'auto' ? ({ messages: 'claude', gemini: 'google' }[scope.value] || 'codex') : selected
  return availableProfiles.value.find((profile) => profile.id === id)
})
const uaPlaceholder = computed(() => previewProfile.value?.headers?.['User-Agent'] || '留空使用全局配置或协议预设')
const previewHeaders = computed(() => JSON.stringify(previewProfile.value?.headers || {}, null, 2))

defineExpose({ getValue: () => buildRequestOverrides(drafts, props.global || inheritGlobal.value) })
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
      <small>通用配置适用于全部请求；按协议配置会覆盖同一层的通用配置。</small>
    </label>
    <label class="field">
      <span>请求头预设</span>
      <select v-model="current.headerProfile" :disabled="disabled">
        <option value="">{{ global ? '跟随通用配置 / 按协议自动' : '跟随全局 / 通用配置' }}</option>
        <option value="auto">按协议自动：Claude / Codex / Google SDK</option>
        <option value="none">不使用预设</option>
        <option v-for="profile in availableProfiles" :key="profile.id" :value="profile.id">{{ profile.name }}</option>
      </select>
      <small>Anthropic 使用 Claude；Chat、Responses 使用 Codex；Gemini 使用 Google SDK，也可选择 Antigravity。</small>
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
      <small>预设版本固定；可通过 UA 和请求头覆盖更新版本。仅设置请求头，不改变所选协议的请求格式。</small>
    </details>
  </div>
</template>

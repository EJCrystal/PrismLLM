<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { WebSearchSettings } from '../types/api'
import Modal from './Modal.vue'

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
}>()

const providers = [
  { value: 'tavily', label: 'Tavily' },
  { value: 'brave', label: 'Brave Search' },
  { value: 'exa', label: 'Exa' },
  { value: 'deepseek', label: 'DeepSeek Search' },
  { value: 'searxng', label: 'SearXNG（自建）' },
]

const form = reactive({
  enabled: false,
  provider: 'tavily',
  apiKey: '',
  clearApiKey: false,
  baseUrl: '',
  maxResults: 6,
  timeoutMs: 15000,
  maxRounds: 6,
  maxPageChars: 1500,
})
const current = ref<WebSearchSettings | null>(null)
const loading = ref(true)
const saving = ref(false)
/** 打开弹窗时从服务端带出的地址；用户没改过时，切换后端不应把它保存为新后端的显式地址。 */
const inheritedBaseUrl = ref('')
const baseUrlTouched = ref(false)

const isSearxng = computed(() => form.provider === 'searxng')
const hasStoredKey = computed(() => current.value?.apiKeySource === 'settings')
const providerChanged = computed(() => current.value != null && form.provider !== current.value.provider)
const keyPlaceholder = computed(() => {
  if (current.value?.apiKeyUnreadable) return '已保存的密钥无法解密，请重新填写'
  if (current.value?.apiKeySource === 'settings') return '已保存密钥；留空保持不变'
  if (current.value?.apiKeySource === 'environment') return '已从环境变量读取；留空保持不变'
  return '例如 tvly-...'
})

function onProviderChange() {
  if (!baseUrlTouched.value && form.baseUrl === inheritedBaseUrl.value) {
    form.baseUrl = ''
  }
  inheritedBaseUrl.value = ''
  baseUrlTouched.value = false
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  try {
    const settings = await api.getWebSearchSettings()
    current.value = settings
    inheritedBaseUrl.value = settings.baseUrl ?? ''
    baseUrlTouched.value = false
    Object.assign(form, {
      enabled: settings.enabled,
      provider: settings.provider,
      apiKey: '',
      clearApiKey: false,
      baseUrl: inheritedBaseUrl.value,
      maxResults: settings.maxResults,
      timeoutMs: settings.timeoutMs,
      maxRounds: settings.maxRounds,
      maxPageChars: settings.maxPageChars,
    })
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    loading.value = false
  }
}

function isLoopbackUrl(value: string): boolean {
  try {
    const host = new URL(value).hostname.replace(/^\[|\]$/g, '').toLowerCase()
    return host === 'localhost' || host === '127.0.0.1' || host === '::1'
  } catch {
    return false
  }
}

async function save() {
  if (loading.value || saving.value) return
  const baseUrl = form.baseUrl.trim()
  const apiKey = form.apiKey.trim()
  if (form.enabled && isSearxng.value && !baseUrl) {
    notify('SearXNG 需要填写搜索地址', 'error')
    return
  }
  if (baseUrl.length > 500) {
    notify('搜索地址不能超过 500 个字符', 'error')
    return
  }
  if (baseUrl) {
    let parsed: URL
    try {
      parsed = new URL(baseUrl)
    } catch {
      notify('搜索地址格式无效', 'error')
      return
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      notify('搜索地址只支持 http 或 https', 'error')
      return
    }
    if (form.enabled && !isSearxng.value && parsed.protocol === 'http:' && !isLoopbackUrl(baseUrl)) {
      notify('携带 API 密钥的搜索地址必须使用 HTTPS（本机回环地址除外）', 'error')
      return
    }
  }
  if (form.enabled && !isSearxng.value && !apiKey) {
    if (!current.value?.apiKeySet) {
      notify('启用搜索代理需要填写 API 密钥', 'error')
      return
    }
    if (providerChanged.value && current.value?.apiKeySource === 'settings') {
      notify('切换搜索后端后需要重新填写 API 密钥', 'error')
      return
    }
  }
  if (form.maxResults < 1 || form.maxResults > 20) {
    notify('每次搜索返回页面数必须在 1..20 之间', 'error')
    return
  }
  if (form.timeoutMs < 1000 || form.timeoutMs > 120000) {
    notify('单次搜索超时必须在 1000..120000 毫秒之间', 'error')
    return
  }
  if (form.maxRounds < 1 || form.maxRounds > 10) {
    notify('搜索轮数必须在 1..10 之间', 'error')
    return
  }
  if (form.maxPageChars < 100 || form.maxPageChars > 10000) {
    notify('单页字符上限必须在 100..10000 之间', 'error')
    return
  }
  saving.value = true
  try {
    await api.updateWebSearchSettings({
      enabled: form.enabled,
      provider: form.provider,
      apiKey: apiKey || undefined,
      clearApiKey: form.clearApiKey,
      baseUrl: baseUrl || null,
      maxResults: form.maxResults,
      timeoutMs: form.timeoutMs,
      maxRounds: form.maxRounds,
      maxPageChars: form.maxPageChars,
    })
    notify('Web 搜索设置已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <Modal title="Web 搜索设置" @close="$emit('close')">
    <p class="muted small">
      Responses 请求带内置 <code>web_search</code> 时，网关会调用这里的搜索 API 代跑搜索。
      保存后立即生效并覆盖环境变量默认值；未填写密钥或地址时沿用环境变量。
    </p>
    <p v-if="loading" class="muted">加载中…</p>
    <form v-else class="form-grid" @submit.prevent="save">
      <label class="switch-row">
        <input v-model="form.enabled" type="checkbox" :disabled="saving" />
        <span class="switch"></span>
        <span>
          <strong>启用 Web 搜索代理</strong>
          <small>关闭后保留协议转换的原有行为，不会发起任何搜索请求。</small>
        </span>
      </label>

      <label class="field">
        <span>搜索后端</span>
        <select v-model="form.provider" :disabled="saving" @change="onProviderChange">
          <option v-for="provider in providers" :key="provider.value" :value="provider.value">
            {{ provider.label }}
          </option>
        </select>
        <small>Tavily、Brave、Exa 和 DeepSeek 需要 API 密钥；SearXNG 需要自建实例地址。</small>
      </label>

      <label v-if="!isSearxng" class="field">
        <span>API 密钥</span>
        <input
          v-model="form.apiKey"
          type="password"
          autocomplete="new-password"
          :placeholder="keyPlaceholder"
          :disabled="saving || form.clearApiKey"
        />
        <small v-if="current?.apiKeyUnreadable" class="text-danger">
          管理台保存的密钥无法解密（主密钥可能已更换），请重新填写。
        </small>
        <small v-else-if="current?.apiKeySource === 'environment'">当前密钥来自环境变量，保存后会优先使用管理台设置。</small>
      </label>

      <label v-if="hasStoredKey && !isSearxng" class="check-row">
        <input v-model="form.clearApiKey" type="checkbox" :disabled="saving" />
        <span>清除管理台保存的密钥，回退到环境变量</span>
      </label>

      <label class="field">
        <span>{{ isSearxng ? '搜索地址' : 'API 地址（可选）' }}</span>
        <input
          v-model="form.baseUrl"
          :placeholder="isSearxng ? 'https://searx.example.com' : '留空沿用环境变量或供应商默认地址'"
          :disabled="saving"
          @input="baseUrlTouched = true"
        />
        <small v-if="isSearxng">SearXNG 实例地址，不需要 API 密钥；留空时沿用环境变量中的地址。</small>
        <small v-else>携带 API 密钥的远程地址必须使用 HTTPS；本机回环地址可用 HTTP。</small>
      </label>

      <div class="web-search-grid">
        <label class="field">
          <span>每次返回页面数</span>
          <input v-model.number="form.maxResults" type="number" min="1" max="20" :disabled="saving" />
        </label>
        <label class="field">
          <span>单次搜索超时（毫秒）</span>
          <input v-model.number="form.timeoutMs" type="number" min="1000" max="120000" step="500" :disabled="saving" />
        </label>
        <label class="field">
          <span>最多搜索轮数</span>
          <input v-model.number="form.maxRounds" type="number" min="1" max="10" :disabled="saving" />
        </label>
        <label class="field">
          <span>单页字符上限</span>
          <input v-model.number="form.maxPageChars" type="number" min="100" max="10000" step="100" :disabled="saving" />
        </label>
      </div>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="loading || saving" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.web-search-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}

.check-row input {
  width: auto;
}
</style>

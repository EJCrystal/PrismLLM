<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { IqVisionSettings } from '../types/api'
import Modal from './Modal.vue'

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
}>()

const form = reactive({
  enabled: false,
  baseUrl: '',
  model: '',
  apiKey: '',
  clearApiKey: false,
  timeoutMs: 60000,
})
const current = ref<IqVisionSettings | null>(null)
const loading = ref(true)
const saving = ref(false)

const hasStoredKey = computed(
  () => current.value?.apiKeySource === 'settings' || current.value?.apiKeyUnreadable === true,
)
const baseUrlChanged = computed(
  () => current.value != null && form.baseUrl.trim().replace(/\/+$/, '') !== current.value.baseUrl.replace(/\/+$/, ''),
)
const keyPlaceholder = computed(() => {
  if (current.value?.apiKeyUnreadable) return '已保存的密钥无法解密，请重新填写'
  if (current.value?.apiKeySource === 'settings') {
    return baseUrlChanged.value ? '端点已变更，需要重新填写密钥' : '已保存密钥；留空保持不变'
  }
  if (current.value?.apiKeySource === 'environment') {
    return baseUrlChanged.value ? '端点已变更，环境变量密钥不适用' : '已从环境变量读取；留空保持不变'
  }
  return '留空表示端点不需要鉴权'
})
/** 按当前表单值估算的视觉判分最坏耗时：6 帧渲染加一次模型调用。 */
const worstCaseMs = computed(() => (current.value?.browserTimeoutMs ?? 0) * 6 + form.timeoutMs)
/** 最坏耗时是否已超出判分总超时，超出时视觉判分会先被截断。 */
const budgetShort = computed(
  () => current.value != null && worstCaseMs.value > current.value.judgeTimeoutMs,
)

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  try {
    const settings = await api.getIqVisionSettings()
    current.value = settings
    Object.assign(form, {
      enabled: settings.enabled,
      baseUrl: settings.baseUrl,
      model: settings.model,
      apiKey: '',
      clearApiKey: false,
      timeoutMs: settings.timeoutMs,
    })
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (loading.value || saving.value || current.value == null) return
  const baseUrl = form.baseUrl.trim().replace(/\/+$/, '')
  const model = form.model.trim()
  const apiKey = form.apiKey.trim()
  if (form.enabled) {
    if (!baseUrl) {
      notify('启用视觉判分需要填写模型端点', 'error')
      return
    }
    try {
      const parsed = new URL(baseUrl)
      if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        notify('模型端点只支持 http 或 https', 'error')
        return
      }
    } catch {
      notify('模型端点格式无效', 'error')
      return
    }
    if (!model) {
      notify('启用视觉判分需要填写模型名', 'error')
      return
    }
  }
  if (baseUrl.length > 500) {
    notify('模型端点不能超过 500 个字符', 'error')
    return
  }
  if (model.length > 200) {
    notify('模型名不能超过 200 个字符', 'error')
    return
  }
  if (form.timeoutMs < 1000 || form.timeoutMs > 300000) {
    notify('视觉模型超时必须在 1000..300000 毫秒之间', 'error')
    return
  }
  // 与后端一致：换端点时必须显式替换或清除已保存的密钥，否则旧密钥会被静默丢弃，
  // 重新启用时只会得到 401。关闭状态下也拦，避免留下一个改坏的配置。
  if (baseUrlChanged.value && !apiKey && !form.clearApiKey && hasStoredKey.value) {
    notify('更换模型端点后需要重新填写 API 密钥，或勾选清除已保存的密钥', 'error')
    return
  }
  saving.value = true
  try {
    await api.updateIqVisionSettings({
      enabled: form.enabled,
      baseUrl,
      model,
      apiKey: apiKey || undefined,
      clearApiKey: form.clearApiKey,
      timeoutMs: form.timeoutMs,
    })
    notify('视觉判分设置已保存')
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
  <Modal title="智商检测视觉判分" @close="$emit('close')">
    <p class="muted small">
      鹈鹕骑行题选中「无头浏览器截图 + 视觉模型」时，网关会把内联 SVG 渲染成 6 帧截图，
      再交给这里的多模态模型逐项评分。保存后立即生效，无需重启；未保存过配置时沿用环境变量。
    </p>
    <p v-if="loading" class="muted">加载中…</p>
    <form v-else class="form-grid" @submit.prevent="save">
      <p v-if="current && !current.browserAvailable" class="vision-alert">
        当前部署没有可用的无头浏览器，视觉判分只会返回「需人工查看」。
        请安装 Chrome/Chromium 或设置 <code>IQ_VISION_BROWSER_PATH</code> 后重启网关。
      </p>

      <label class="switch-row">
        <input v-model="form.enabled" type="checkbox" :disabled="saving" />
        <span class="switch"></span>
        <span>
          <strong>启用视觉判分</strong>
          <small>关闭后该判分器仍会出现在列表里，但判分会转为需人工查看并说明原因。</small>
        </span>
      </label>

      <label class="field">
        <span>模型端点</span>
        <input
          v-model="form.baseUrl"
          placeholder="例如 http://127.0.0.1:8080/v1 或 https://api.example.com/v1"
          :disabled="saving"
        />
        <small>
          OpenAI 兼容的 <code>/chat/completions</code> 基础地址，可以指向本机 PrismLLM 网关。
          容器内请用 <code>127.0.0.1</code> 而不是 <code>localhost</code>，后者会走代理。
        </small>
      </label>

      <label class="field">
        <span>模型名</span>
        <input v-model="form.model" placeholder="例如 gpt-6.1-sol" :disabled="saving" />
        <small>必须是该端点支持图片输入的多模态模型。</small>
      </label>

      <label class="field">
        <span>API 密钥（可选）</span>
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
        <small v-else-if="baseUrlChanged && hasStoredKey">
          端点已变更，需要重新填写密钥，或勾选下方清除已保存的密钥。
        </small>
        <small v-else-if="baseUrlChanged && current?.apiKeySource === 'environment'">
          端点已变更，环境变量里的密钥不会发给新端点。
        </small>
        <small v-else-if="current?.apiKeySource === 'environment'">
          当前密钥来自环境变量，保存后会优先使用管理台设置。
        </small>
      </label>

      <label v-if="hasStoredKey" class="check-row">
        <input v-model="form.clearApiKey" type="checkbox" :disabled="saving" />
        <span>清除管理台保存的密钥（端点与环境变量一致时才回退到环境变量）</span>
      </label>

      <label class="field">
        <span>视觉模型超时（毫秒）</span>
        <input v-model.number="form.timeoutMs" type="number" min="1000" max="300000" step="1000" :disabled="saving" />
        <small v-if="budgetShort" class="text-danger">
          该超时下最坏耗时约 {{ Math.round(worstCaseMs / 1000) }} 秒，
          超过判分总超时 {{ Math.round((current?.judgeTimeoutMs ?? 0) / 1000) }} 秒（<code>IQ_JUDGE_TIMEOUT_MS</code>），
          视觉判分会被提前截断。请调小超时或调大判分总超时。
        </small>
        <small v-else-if="current">
          最坏耗时约 {{ Math.round(worstCaseMs / 1000) }} 秒，
          判分总超时 {{ Math.round(current.judgeTimeoutMs / 1000) }} 秒。
          浏览器单帧超时 {{ current.browserTimeoutMs }} 毫秒来自部署配置。
        </small>
      </label>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="loading || saving || !current" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.check-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}

.check-row input {
  width: auto;
}

.vision-alert {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid var(--warning-border, #d9b45b);
  background: var(--warning-bg, #fdf6e3);
  font-size: 13px;
  line-height: 1.5;
}
</style>

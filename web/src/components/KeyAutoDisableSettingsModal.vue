<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import {
  DEFAULT_KEY_AUTO_DISABLE_SETTINGS,
  buildKeyAutoDisableSettingsPayload,
  normalizeKeyAutoDisableSettings,
} from '../utils/keyAutoDisable'
import Modal from './Modal.vue'

const emit = defineEmits(['close', 'saved'])
const form = reactive({ ...DEFAULT_KEY_AUTO_DISABLE_SETTINGS })
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    Object.assign(form, normalizeKeyAutoDisableSettings(await api.getKeyAutoDisableSettings()))
  } catch (cause) {
    loadError.value = cause.message
  } finally {
    loading.value = false
  }
}

async function save() {
  if (loading.value || saving.value || loadError.value) return
  let payload
  try {
    payload = buildKeyAutoDisableSettingsPayload(form)
  } catch (cause) {
    notify(cause.message, 'error')
    return
  }
  saving.value = true
  try {
    await api.updateKeyAutoDisableSettings(payload)
    notify('密钥自动禁用规则已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <Modal title="密钥自动禁用" @close="$emit('close')">
    <p v-if="loading" class="muted" role="status">正在读取自动禁用规则…</p>
    <div v-else-if="loadError" class="callout danger" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="button ghost mini" @click="load">重新加载</button>
    </div>
    <form v-else id="key-auto-disable-settings-form" class="form-grid" @submit.prevent="save">
      <label class="switch-row">
        <input v-model="form.enabled" type="checkbox" :disabled="saving" />
        <span class="switch"></span>
        <span>
          <strong>启用密钥自动禁用</strong>
          <small>应用于所有渠道下的密钥；满足任一条件后停止路由到该密钥。</small>
        </span>
      </label>
      <fieldset class="key-auto-disable-rule" :disabled="!form.enabled || saving">
        <legend>连续鉴权失败</legend>
        <label class="field">
          <span>连续 401/403 次数</span>
          <input v-model.number="form.authFailureThreshold" type="number" min="1" max="100" step="1" required />
          <small>每次实际尝试计数，成功或其他上游结果会打断连续次数。</small>
        </label>
      </fieldset>
      <fieldset class="key-auto-disable-rule" :disabled="!form.enabled || saving">
        <legend>时间窗口内多次失败</legend>
        <div class="key-auto-disable-window-fields">
          <label class="field">
            <span>统计窗口（分钟）</span>
            <input v-model.number="form.failureWindowMinutes" type="number" min="1" max="1440" step="1" required />
          </label>
          <label class="field">
            <span>窗口内失败次数</span>
            <input v-model.number="form.failureThreshold" type="number" min="1" max="1000" step="1" required />
          </label>
        </div>
        <p class="muted small">计入 401/403、429、5xx、连接失败、超时和流异常。成功不会清除窗口内的失败；404、其他请求错误和客户端取消不计入。</p>
      </fieldset>
      <div class="callout info">
        保存后立即生效，修改规则后重新计数。禁用原因会记入操作审计；已禁用的密钥需更换凭据后手动启用。
      </div>
      <p v-if="!form.enabled" class="muted small">关闭自动禁用后仍执行原有冷却规则，已禁用的密钥保持禁用。</p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" :disabled="saving" @click="$emit('close')">取消</button>
      <button
        type="submit" form="key-auto-disable-settings-form" class="button primary"
        :disabled="loading || saving || !!loadError"
      >
        {{ saving ? '保存中…' : '保存规则' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.key-auto-disable-rule {
  display: grid;
  gap: 10px;
  min-width: 0;
  margin: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.key-auto-disable-rule legend {
  padding: 0 6px;
  font-weight: 700;
}

.key-auto-disable-rule p { margin: 0; line-height: 1.6; }

.key-auto-disable-window-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 480px) {
  .key-auto-disable-window-fields { grid-template-columns: 1fr; }
}
</style>

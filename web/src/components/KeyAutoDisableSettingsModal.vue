<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import {
  DEFAULT_KEY_AUTO_DISABLE_SETTINGS,
  buildKeyAutoDisableSettingsPayload,
  fromKeyDisableRuleDraft,
  normalizeKeyAutoDisableSettings,
  toKeyDisableRuleDraft,
} from '../utils/keyAutoDisable'
import type { KeyDisableRuleDraft } from '../utils/keyAutoDisable'
import Modal from './Modal.vue'
import KeyDisableRuleFields from './KeyDisableRuleFields.vue'

const emit = defineEmits(['close', 'saved'])
const form = reactive<{ enabled: boolean; rules: KeyDisableRuleDraft[] }>({
  enabled: DEFAULT_KEY_AUTO_DISABLE_SETTINGS.enabled,
  rules: DEFAULT_KEY_AUTO_DISABLE_SETTINGS.rules.map(toKeyDisableRuleDraft),
})
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const normalized = normalizeKeyAutoDisableSettings(await api.getKeyAutoDisableSettings())
    form.enabled = normalized.enabled
    form.rules = normalized.rules.map(toKeyDisableRuleDraft)
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (loading.value || saving.value || loadError.value) return
  let payload: ReturnType<typeof buildKeyAutoDisableSettingsPayload>
  try {
    payload = buildKeyAutoDisableSettingsPayload({
      enabled: form.enabled,
      rules: form.rules.map(fromKeyDisableRuleDraft),
    })
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
    return
  }
  saving.value = true
  try {
    await api.updateKeyAutoDisableSettings(payload)
    notify('密钥自动禁用规则已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
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
          <small>规则按顺序匹配，首条命中生效；临时停用到期自动恢复，永久停用不会自动恢复，可保留当前凭据人工启用。</small>
        </span>
      </label>

      <KeyDisableRuleFields :rules="form.rules" :enabled="form.enabled" :saving="saving" />

      <div class="callout info">
        保存后立即生效，修改规则后重新计数。状态码与错误文本同时填写时取交集；未命中任何规则的上游响应不计数。
      </div>
      <p v-if="!form.enabled" class="muted small">关闭自动禁用后仍执行原有冷却规则，已停用的密钥保持停用。</p>
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


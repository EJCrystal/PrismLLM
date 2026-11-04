<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import {
  DEFAULT_KEY_AUTO_DISABLE_SETTINGS,
  buildKeyAutoDisableSettingsPayload,
  fromKeyDisableRuleDraft,
  newKeyDisableRule,
  normalizeKeyAutoDisableSettings,
  toKeyDisableRuleDraft,
} from '../utils/keyAutoDisable'
import type { KeyDisableRuleDraft } from '../utils/keyAutoDisable'
import Modal from './Modal.vue'

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

function addRule() {
  form.rules.push(newKeyDisableRule(form.rules))
}

function removeRule(index: number) {
  form.rules.splice(index, 1)
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

      <div v-for="(rule, index) in form.rules" :key="rule.id" class="key-auto-disable-rule">
        <div class="key-auto-disable-rule-head">
          <label class="field grow">
            <span>规则名称</span>
            <input v-model="rule.name" maxlength="40" :disabled="saving || !form.enabled" />
          </label>
          <button
            type="button" class="button ghost mini" :disabled="saving || form.rules.length <= 1"
            @click="removeRule(index)"
          >删除</button>
        </div>

        <div class="key-auto-disable-grid">
          <label class="field">
            <span>状态码</span>
            <input v-model="rule.statusCodesText" placeholder="401,403" :disabled="saving || !form.enabled" />
          </label>
          <label class="field">
            <span>状态码下限</span>
            <input v-model.number="rule.statusCodeAtLeast" type="number" min="100" max="599" placeholder="例如 500" :disabled="saving || !form.enabled" />
          </label>
          <label class="field">
            <span>触发次数</span>
            <input v-model.number="rule.threshold" type="number" min="1" max="1000" required :disabled="saving || !form.enabled" />
          </label>
          <label class="field">
            <span>统计窗口（分钟）</span>
            <input v-model.number="rule.windowMinutes" type="number" min="1" max="1440" required :disabled="saving || !form.enabled" />
          </label>
        </div>

        <label class="field">
          <span>错误文本匹配（每行一个，RE2 正则）</span>
          <textarea
            v-model="rule.bodyPatternsText" rows="3"
            placeholder="invalid_api_key&#10;insufficient_quota"
            :disabled="saving || !form.enabled"
          ></textarea>
        </label>

        <div class="key-auto-disable-options">
          <label class="check-row">
            <input v-model="rule.streak" type="checkbox" :disabled="saving || !form.enabled" />
            <span>连续计数（成功清零）</span>
          </label>
          <label class="check-row">
            <input v-model="rule.includeTransportFailures" type="checkbox" :disabled="saving || !form.enabled" />
            <span>计入连接失败 / 超时</span>
          </label>
        </div>

        <div class="key-auto-disable-grid">
          <label class="field">
            <span>动作</span>
            <select v-model="rule.action" :disabled="saving || !form.enabled">
              <option value="TEMPORARY">临时停用</option>
              <option value="PERMANENT">永久停用</option>
            </select>
          </label>
          <label v-if="rule.action === 'TEMPORARY'" class="field">
            <span>停用时长（分钟）</span>
            <input v-model.number="rule.disableMinutes" type="number" min="1" max="10080" required :disabled="saving || !form.enabled" />
          </label>
        </div>
      </div>

      <button type="button" class="button ghost" :disabled="saving || !form.enabled || form.rules.length >= 50" @click="addRule">
        添加规则
      </button>

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

<style scoped>
.key-auto-disable-rule {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.key-auto-disable-rule-head {
  display: flex;
  align-items: end;
  gap: 10px;
}

.key-auto-disable-rule-head .grow { flex: 1; }

.key-auto-disable-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.key-auto-disable-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}

.check-row input { width: auto; }

.key-auto-disable-rule textarea {
  width: 100%;
  resize: vertical;
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--bg);
  color: var(--text);
  font: inherit;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

@media (max-width: 520px) {
  .key-auto-disable-grid { grid-template-columns: 1fr; }
  .key-auto-disable-rule-head { align-items: stretch; flex-direction: column; }
}
</style>

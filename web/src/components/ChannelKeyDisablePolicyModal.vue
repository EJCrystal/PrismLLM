<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import {
  buildChannelKeyDisablePolicyPayload,
  buildKeyAutoDisableSettingsPayload,
  fromKeyDisableRuleDraft,
  normalizeKeyAutoDisableSettings,
  toKeyDisableRuleDraft,
} from '../utils/keyAutoDisable'
import type { KeyDisableRuleDraft } from '../utils/keyAutoDisable'
import type { ChannelKeyDisablePolicyResponse, KeyAutoDisableSettingsRequest } from '../types/api'
import Modal from './Modal.vue'
import KeyDisableRuleFields from './KeyDisableRuleFields.vue'

/**
 * 渠道级密钥自动禁用策略。默认继承全局；切换为自定义时用全局规则做初始值，
 * 避免从空列表开始编辑。
 */
const props = defineProps<{ channelId: string; channelName: string }>()
const emit = defineEmits(['close', 'saved'])

const form = reactive<{ enabled: boolean; rules: KeyDisableRuleDraft[] }>({ enabled: true, rules: [] })
const state = ref<ChannelKeyDisablePolicyResponse | null>(null)
const inherit = ref(true)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')

const effectiveLabel = computed(() => {
  const policy = state.value?.effective
  if (!policy) return '—'
  return policy.enabled ? `${policy.rules.length} 条规则` : '已关闭自动禁用'
})

function applyPolicy(policy: KeyAutoDisableSettingsRequest) {
  const normalized = normalizeKeyAutoDisableSettings(policy)
  form.enabled = normalized.enabled
  form.rules = normalized.rules.map(toKeyDisableRuleDraft)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const loaded = await api.getChannelKeyDisablePolicy(props.channelId)
    state.value = loaded
    inherit.value = loaded.override == null
    applyPolicy(loaded.override ?? loaded.effective)
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

/** 切到自定义时用当前生效规则初始化；切回继承时保留草稿，方便反悔。 */
function changeScope(custom: boolean) {
  inherit.value = !custom
  if (custom && state.value && !form.rules.length) applyPolicy(state.value.override ?? state.value.effective)
}

async function save() {
  if (loading.value || saving.value || loadError.value) return
  let payload: ReturnType<typeof buildChannelKeyDisablePolicyPayload>
  try {
    const override = inherit.value
      ? null
      : buildKeyAutoDisableSettingsPayload({
        enabled: form.enabled,
        rules: form.rules.map(fromKeyDisableRuleDraft),
      })
    payload = buildChannelKeyDisablePolicyPayload(override)
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
    return
  }
  saving.value = true
  try {
    await api.updateChannelKeyDisablePolicy(props.channelId, payload)
    notify(inherit.value ? '渠道已恢复继承全局规则' : '渠道自动禁用规则已保存')
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
  <Modal :title="`密钥自动禁用 · ${channelName}`" wide @close="$emit('close')">
    <p v-if="loading" class="muted" role="status">正在读取渠道自动禁用规则…</p>
    <div v-else-if="loadError" class="callout danger" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="button ghost mini" @click="load">重新加载</button>
    </div>
    <form v-else id="channel-key-disable-policy-form" class="form-grid" @submit.prevent="save">
      <div class="key-auto-disable-scope" role="radiogroup" aria-label="规则来源">
        <label class="check-row">
          <input
            type="radio" name="key-disable-scope" :checked="inherit" :disabled="saving"
            @change="changeScope(false)"
          />
          <span>继承全局规则（{{ effectiveLabel }}）</span>
        </label>
        <label class="check-row">
          <input
            type="radio" name="key-disable-scope" :checked="!inherit" :disabled="saving"
            @change="changeScope(true)"
          />
          <span>自定义渠道规则（整表替换全局，不合并）</span>
        </label>
      </div>

      <div class="callout info">
        渠道规则只作用于本渠道的上游密钥。上游特有的错误特征（例如活动 Key 失效）应配置在渠道规则里，不要放进全局。
      </div>

      <template v-if="!inherit">
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" :disabled="saving" />
          <span class="switch"></span>
          <span>
            <strong>启用本渠道的密钥自动禁用</strong>
            <small>关闭后本渠道不执行自动禁用，仍执行候选冷却。</small>
          </span>
        </label>

        <KeyDisableRuleFields :rules="form.rules" :enabled="form.enabled" :saving="saving" />

        <div class="callout info">
          保存后立即生效，本渠道按旧规则累计的失败计数会清零。规则按顺序匹配，首条命中生效。
        </div>
      </template>
      <p v-else class="muted small">
        当前使用全局设置：{{ effectiveLabel }}。要修改全局默认规则，请到「配置 → 密钥自动禁用」。
      </p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" :disabled="saving" @click="$emit('close')">取消</button>
      <button
        type="submit" form="channel-key-disable-policy-form" class="button primary"
        :disabled="loading || saving || !!loadError"
      >
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.key-auto-disable-scope {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
</style>

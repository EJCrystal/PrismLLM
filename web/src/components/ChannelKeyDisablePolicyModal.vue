<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import {
  buildChannelKeyDisablePolicyPayload,
  buildKeyAutoDisableSettingsPayload,
  fromKeyDisableRuleDraft,
  newKeyDisableRule,
  normalizeKeyAutoDisableSettings,
  toKeyDisableRuleDraft,
} from '../utils/keyAutoDisable'
import type { KeyDisableRuleDraft } from '../utils/keyAutoDisable'
import type { ChannelKeyDisableMode, ChannelKeyDisablePolicyResponse, KeyAutoDisableSettingsRequest } from '../types/api'
import Modal from './Modal.vue'
import KeyDisableRuleFields from './KeyDisableRuleFields.vue'

/**
 * 渠道级密钥自动禁用策略，三选一：
 * - 继承全局：完全使用系统设置里的规则；
 * - 自定义：渠道规则排在前面，未命中时回落到全局规则；
 * - 关闭：本渠道的渠道规则和全局规则都不执行。
 *
 * 自定义只保存渠道自己的规则，不再把全局规则复制一份，避免全局规则更新后渠道停在旧副本上。
 */
const props = defineProps<{ channelId: string; channelName: string }>()
const emit = defineEmits(['close', 'saved'])

const form = reactive<{ rules: KeyDisableRuleDraft[] }>({ rules: [] })
const state = ref<ChannelKeyDisablePolicyResponse | null>(null)
const mode = ref<ChannelKeyDisableMode>('inherit')
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')

const globalRules = computed(() => state.value?.globalPolicy.rules ?? [])
const globalEnabled = computed(() => state.value?.globalPolicy.enabled ?? false)

const globalLabel = computed(() => {
  if (!state.value) return '—'
  return globalEnabled.value ? `${globalRules.value.length} 条规则` : '全局已关闭自动禁用'
})

const effectiveLabel = computed(() => {
  const policy = state.value?.effective
  if (!policy) return '—'
  return policy.enabled ? `${policy.rules.length} 条规则生效` : '已关闭自动禁用'
})

function applyRules(policy: KeyAutoDisableSettingsRequest) {
  form.rules = normalizeKeyAutoDisableSettings(policy).rules.map(toKeyDisableRuleDraft)
}

/**
 * 切到自定义时给编辑器一份可用的草稿：已有渠道规则就沿用，否则放一条全新的渠道规则。
 * 不拿全局规则做初始值——那会把每条全局规则都复制成渠道版本并接管同 id 规则，
 * 等于回到「整表替换」，全局后续更新也就不再自动生效了。
 */
const globalRuleIds = computed(() => globalRules.value.map((rule) => rule.id))

function seedCustomRules() {
  if (form.rules.length) return
  const own = state.value?.override
  // 关闭形态可能带着旧版保存的规则，切到自定义时沿用，别让管理员重新配一遍。
  if (own?.rules.length) {
    applyRules(own)
    return
  }
  form.rules = [newKeyDisableRule([], globalRuleIds.value)]
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const loaded = await api.getChannelKeyDisablePolicy(props.channelId)
    state.value = loaded
    mode.value = loaded.mode
    // 自定义模式只编辑渠道自己的规则；其它形态等切过去时再给草稿。
    if (loaded.override?.enabled && loaded.override.rules.length) applyRules(loaded.override)
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

/** 切到自定义时保留已编辑的草稿，草稿为空才初始化。 */
function changeMode(next: ChannelKeyDisableMode) {
  mode.value = next
  if (next === 'custom') seedCustomRules()
}

async function save() {
  if (loading.value || saving.value || loadError.value) return
  let payload: ReturnType<typeof buildChannelKeyDisablePolicyPayload>
  try {
    // 关闭模式不执行任何规则，因此直接提交空规则列表，不留无意义的占位规则。
    const override = mode.value === 'inherit'
      ? null
      : buildKeyAutoDisableSettingsPayload({
        enabled: mode.value === 'custom',
        rules: mode.value === 'custom' ? form.rules.map(fromKeyDisableRuleDraft) : [],
      })
    payload = buildChannelKeyDisablePolicyPayload(override)
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
    return
  }
  saving.value = true
  try {
    await api.updateChannelKeyDisablePolicy(props.channelId, payload)
    notify(
      mode.value === 'inherit'
        ? '渠道已恢复继承全局规则'
        : mode.value === 'disabled' ? '渠道已关闭密钥自动禁用' : '渠道自动禁用规则已保存',
    )
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
            type="radio" name="key-disable-mode" value="inherit" :checked="mode === 'inherit'" :disabled="saving"
            @change="changeMode('inherit')"
          />
          <span>
            <strong>继承全局规则</strong>
            <small>只执行系统设置里的全局自动禁用规则（{{ globalLabel }}）。</small>
          </span>
        </label>
        <label class="check-row">
          <input
            type="radio" name="key-disable-mode" value="custom" :checked="mode === 'custom'" :disabled="saving"
            @change="changeMode('custom')"
          />
          <span>
            <strong>自定义规则</strong>
            <small>先匹配本渠道规则，未命中时回落全局规则。</small>
          </span>
        </label>
        <label class="check-row">
          <input
            type="radio" name="key-disable-mode" value="disabled" :checked="mode === 'disabled'" :disabled="saving"
            @change="changeMode('disabled')"
          />
          <span>
            <strong>关闭</strong>
            <small>本渠道规则和全局规则都不执行。</small>
          </span>
        </label>
      </div>

      <div class="callout info">
        渠道规则只作用于本渠道的上游密钥，适合承载某个上游特有的错误特征（例如活动 Key 失效）。规则按顺序匹配，首条命中生效。
      </div>

      <template v-if="mode === 'custom'">
        <KeyDisableRuleFields :rules="form.rules" :enabled="true" :saving="saving" :reserved-ids="globalRuleIds" />

        <div class="callout warn">
          渠道规则命中后只按它计数，即使还没达到阈值也不会再落到全局规则。因此一条过宽的规则会遮蔽全局更精确的规则；与全局同 id 的规则由渠道版本接管，可用于收紧阈值。
        </div>
        <div class="callout info">
          未命中渠道规则时回落到全局的 {{ globalEnabled ? `${globalRules.length} 条规则` : '规则（当前全局已关闭自动禁用，不会兜底）' }}。保存后立即生效，本渠道已累计的失败计数会清零。当前有效：{{ effectiveLabel }}。
        </div>
      </template>
      <div v-else-if="mode === 'disabled'" class="callout warn">
        关闭后本渠道不执行任何自动禁用，包括全局规则；坏密钥不会被自动摘除，只剩候选冷却在限制重试。仍可随时改回继承或自定义。
      </div>
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
  border: 1px solid var(--glass-line);
  border-radius: 10px;
}

.key-auto-disable-scope .check-row {
  align-items: start;
}

.key-auto-disable-scope small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { OAuthAccount, PluginUiContribution } from '../types/api'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { oauthProviderLabel, oauthProviderProtocols } from '../utils/oauthAccount'
import { PLUGIN_STATE_CHANGED } from '../utils/pluginForm'
import Modal from './Modal.vue'
import PluginDiagnosticHost from './PluginDiagnosticHost.vue'
import TestEmptyState from './TestEmptyState.vue'

const props = defineProps<{ account: OAuthAccount; models: string[] }>()
const emit = defineEmits<{ (event: 'close'): void }>()
const entries = ref<PluginUiContribution[]>([])
const selectedEntry = ref('')
const loading = ref(true)
const loadError = ref('')
const guard = createLatestRequestGuard()
const invalidContributions = ref(new Set<string>())
const diagnosticGenerations = ref(new Map<string, number>())
let controller: AbortController | null = null
let disposed = false
const protocols = computed(() => oauthProviderProtocols(props.account.provider))
function entryKey(item: PluginUiContribution) { return `${item.pluginId}:${item.entry.id}` }
function contributionVersionKey(item: PluginUiContribution) { return `${entryKey(item)}:${item.activeVersion}` }
const contribution = computed(() => entries.value.find((item) => entryKey(item) === selectedEntry.value && !invalidContributions.value.has(contributionVersionKey(item))))
const hostKey = computed(() => {
  const key = contribution.value ? contributionVersionKey(contribution.value) : selectedEntry.value
  return `${key}:${props.account.id}:${diagnosticGenerations.value.get(key) ?? 0}`
})

async function load(recheck = false) {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loading.value = true
  loadError.value = ''
  try {
    const result = await guard.run(() => api.getPluginContributions({ signal: current.signal }))
    if (!result.isLatest || disposed) return
    const available = result.value.filter((item) => item.entry.placement === 'oauth-account-tests' && item.entry.contexts.includes('oauth-account'))
    if (recheck) {
      const resultKeys = new Set(available.map(contributionVersionKey))
      for (const key of invalidContributions.value) {
        if (resultKeys.has(key)) diagnosticGenerations.value.set(key, (diagnosticGenerations.value.get(key) ?? 0) + 1)
      }
      invalidContributions.value = new Set()
    }
    entries.value = available
    if (!entries.value.some((item) => entryKey(item) === selectedEntry.value)) selectedEntry.value = entries.value[0] ? entryKey(entries.value[0]) : ''
    loading.value = false
  } catch (cause) {
    if (!disposed && (cause as { isLatest?: boolean }).isLatest !== false && !current.signal.aborted) {
      loadError.value = cause instanceof Error ? cause.message : String(cause)
      loading.value = false
    }
  } finally { if (controller === current) controller = null }
}
function refresh() { void load() }
function recheck() { void load(true) }
function markUnavailable(value: { pluginId: string; activeVersion: string; entryId: string }) {
  const key = `${value.pluginId}:${value.entryId}:${value.activeVersion}`
  invalidContributions.value = new Set([...invalidContributions.value, key])
}
watch(() => props.account.id, () => { selectedEntry.value = ''; void load() })
onMounted(() => { void load(); window.addEventListener(PLUGIN_STATE_CHANGED, refresh) })
onBeforeUnmount(() => { disposed = true; guard.invalidate(); controller?.abort(); window.removeEventListener(PLUGIN_STATE_CHANGED, refresh) })
</script>

<template>
  <Modal title="订阅账号模型诊断" full :body-scrollable="false" @close="emit('close')">
    <template #subhead>
      <div class="oauth-plugin-summary"><div><span class="muted small">测试账号</span><code>{{ account.accountEmail || account.name }}</code></div><span class="badge neutral">{{ oauthProviderLabel(account.provider) }}</span></div>
      <label v-if="entries.length > 1" class="field oauth-plugin-selector"><span>诊断方式</span><select v-model="selectedEntry"><option v-for="item in entries" :key="entryKey(item)" :value="entryKey(item)">{{ item.entry.label }} · {{ item.pluginId }}</option></select></label>
    </template>
    <p v-if="loading && !contribution" class="muted">正在读取账号诊断插件…</p>
    <div v-if="loadError" class="callout danger" role="alert">{{ loadError }} <button type="button" class="link-button" @click="recheck">重试</button></div>
    <PluginDiagnosticHost v-if="contribution" :key="hostKey"
      :contribution="contribution" :context="{ kind: 'oauth-account', id: account.id }" :account-models="models" :account-protocols="protocols" @unavailable="markUnavailable" />
    <TestEmptyState v-else-if="!loading && invalidContributions.size" icon="bolt" title="诊断入口已失效" description="此生效版本的账号诊断入口已不可用。可手动重新检查入口状态。"><button type="button" class="button ghost" @click="recheck">重新检查</button></TestEmptyState>
    <TestEmptyState v-else-if="!loading && !loadError" icon="bolt" title="没有已生效的账号诊断插件" description="请在「插件」页检查安装与启用状态。账号诊断仍使用账号自己的凭据、代理与官方端点，不需要选择渠道密钥。" />
    <template #footer><button type="button" class="button ghost" @click="emit('close')">关闭</button></template>
  </Modal>
</template>

<style scoped>
.oauth-plugin-summary { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 12px 0; }
.oauth-plugin-summary > div { display: grid; gap: 4px; min-width: 0; }
.oauth-plugin-summary code { overflow-wrap: anywhere; }
.oauth-plugin-selector { max-width: 480px; margin-bottom: 12px; }
</style>

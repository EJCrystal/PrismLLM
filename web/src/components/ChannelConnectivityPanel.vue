<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import type { Channel, PluginUiContribution } from '../types/api'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { PLUGIN_STATE_CHANGED } from '../utils/pluginForm'
import PluginDiagnosticHost from './PluginDiagnosticHost.vue'
import TestEmptyState from './TestEmptyState.vue'

const props = defineProps<{ channel: Channel; initialModel?: string }>()
const contributions = ref<PluginUiContribution[]>([])
const loading = ref(true)
const loadError = ref('')
const guard = createLatestRequestGuard()
let controller: AbortController | null = null
let disposed = false
const invalidContributions = ref(new Set<string>())
const diagnosticGenerations = ref(new Map<string, number>())
const contributionKey = (item: PluginUiContribution) => `${item.pluginId}:${item.activeVersion}:${item.entry.id}`
const contribution = computed<PluginUiContribution | undefined>((previous) => {
  const available = contributions.value.filter((item) => item.pluginId === 'prism.connectivity' && item.entry.placement === 'channel-tests' && item.entry.contexts.includes('channel') && !invalidContributions.value.has(contributionKey(item)))
  return available.find((item) => previous && contributionKey(item) === contributionKey(previous)) ?? available[0]
})
const hostKey = computed(() => {
  const key = contribution.value ? contributionKey(contribution.value) : ''
  return `${key}:${props.channel.id}:${diagnosticGenerations.value.get(key) ?? 0}`
})

/** 旧组件名只保留上下文适配，不再包含连通性执行或静态表单。 */
async function load(recheck = false) {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loading.value = true
  loadError.value = ''
  try {
    const invalid = new Set(invalidContributions.value)
    const result = await guard.run(async () => {
      const available = await api.getPluginContributions({ signal: current.signal })
      const recovered: string[] = []
      if (recheck) {
        for (const item of available) {
          const key = contributionKey(item)
          if (!invalid.has(key) || item.pluginId !== 'prism.connectivity' || item.entry.placement !== 'channel-tests' || !item.entry.contexts.includes('channel')) continue
          const live = await api.getPlugin(item.pluginId, { signal: current.signal })
          if (live.id === item.pluginId && live.admissionEnabled && live.activeVersion === item.activeVersion &&
            live.activeUi?.entries.some((entry) => entry.id === item.entry.id && entry.action.id === item.entry.action.id &&
              entry.placement === 'channel-tests' && entry.contexts.includes('channel'))) recovered.push(key)
        }
      }
      return { available, recovered }
    })
    if (!result.isLatest || disposed) return
    const remaining = new Set(invalidContributions.value)
    for (const key of result.value.recovered) {
      if (remaining.delete(key)) diagnosticGenerations.value.set(key, (diagnosticGenerations.value.get(key) ?? 0) + 1)
    }
    invalidContributions.value = remaining
    contributions.value = result.value.available
    loading.value = false
  } catch (cause) {
    if (!disposed && (cause as { isLatest?: boolean }).isLatest !== false && !current.signal.aborted) {
      loadError.value = cause instanceof Error ? cause.message : String(cause)
      loading.value = false
    }
  } finally { if (controller === current) controller = null }
}
function refresh() { void load(true) }
function recheck() { void load(true) }
function markUnavailable(value: { pluginId: string; activeVersion: string; entryId: string }) {
  invalidContributions.value = new Set([...invalidContributions.value, `${value.pluginId}:${value.activeVersion}:${value.entryId}`])
}
onMounted(() => { void load(); window.addEventListener(PLUGIN_STATE_CHANGED, refresh) })
onBeforeUnmount(() => { disposed = true; guard.invalidate(); controller?.abort(); window.removeEventListener(PLUGIN_STATE_CHANGED, refresh) })
</script>

<template>
  <p v-if="loading && !contribution" class="muted">正在读取连通性插件…</p>
  <div v-if="loadError" class="callout danger" role="alert">{{ loadError }} <button type="button" class="link-button" @click="recheck">重试</button></div>
  <PluginDiagnosticHost v-if="contribution" :key="hostKey" :contribution="contribution"
    :context="{ kind: 'channel', id: props.channel.id }" :initial-model="initialModel" :channel-enabled="channel.enabled" @unavailable="markUnavailable" />
  <TestEmptyState v-else-if="!loading && invalidContributions.size" icon="bolt" title="诊断入口已失效" description="此生效版本的诊断入口已不可用。可手动重新检查入口状态。"><button type="button" class="button ghost" @click="recheck">重新检查</button></TestEmptyState>
  <TestEmptyState v-else-if="!loading && !loadError" icon="bolt" title="连通性插件未生效" description="请在「插件」页安装并启用官方连通性插件，然后人工重启宿主。不会回退到内置连通性实现。" />
</template>

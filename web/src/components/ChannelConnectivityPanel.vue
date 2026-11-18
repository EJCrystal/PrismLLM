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
let disposed = false
const contribution = computed(() => contributions.value.find((item) => item.pluginId === 'prism.connectivity' && item.entry.placement === 'channel-tests' && item.entry.contexts.includes('channel')))

/** 旧组件名只保留上下文适配，不再包含连通性执行或静态表单。 */
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await guard.run(() => api.getPluginContributions())
    if (!result.isLatest || disposed) return
    contributions.value = result.value
    loading.value = false
  } catch (cause) {
    if (!disposed && (cause as { isLatest?: boolean }).isLatest !== false) {
      loadError.value = cause instanceof Error ? cause.message : String(cause)
      loading.value = false
    }
  }
}
function refresh() { void load() }
onMounted(() => { void load(); window.addEventListener(PLUGIN_STATE_CHANGED, refresh) })
onBeforeUnmount(() => { disposed = true; guard.invalidate(); window.removeEventListener(PLUGIN_STATE_CHANGED, refresh) })
</script>

<template>
  <p v-if="loading" class="muted">正在读取连通性插件…</p>
  <div v-else-if="loadError" class="callout danger" role="alert">{{ loadError }} <button type="button" class="link-button" @click="load">重试</button></div>
  <PluginDiagnosticHost v-else-if="contribution" :key="contribution.activeVersion" :contribution="contribution"
    :context="{ kind: 'channel', id: props.channel.id }" :initial-model="initialModel" :channel-enabled="channel.enabled" @unavailable="load" />
  <TestEmptyState v-else icon="bolt" title="连通性插件未生效" description="请在「插件」页安装并启用官方连通性插件，然后人工重启宿主。不会回退到内置连通性实现。" />
</template>

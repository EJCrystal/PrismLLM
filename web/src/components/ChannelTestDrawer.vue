<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, useId, type Component } from 'vue'
import { api } from '../api'
import type { Channel, PluginUiContribution } from '../types/api'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { PLUGIN_STATE_CHANGED } from '../utils/pluginForm'
import { currentInvalidPluginContributions, pluginContributionVersionKey, recheckPluginContributions } from '../utils/pluginDiscovery'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PluginDiagnosticHost from './PluginDiagnosticHost.vue'
import TestRecordsView from './TestRecordsView.vue'

const props = defineProps<{ channel: Channel; initialModel?: string }>()
const emit = defineEmits<{ (event: 'close'): void }>()
const legacyTabs = [
  { id: 'iq', label: '智商', icon: 'brain' },
  { id: 'fingerprint', label: '指纹', icon: 'fingerprint' },
  { id: 'records', label: '记录', icon: 'logs' },
]
const contributions = ref<PluginUiContribution[]>([])
const invalidContributions = ref(new Set<string>())
const diagnosticGenerations = ref(new Map<string, number>())
const loadError = ref('')
const retryAction = ref<HTMLButtonElement | null>(null)
const guard = createLatestRequestGuard()
let disposed = false
let initialized = false
let userSelectedTab = false
let controller: AbortController | null = null

function contributionKey(item: PluginUiContribution) { return `plugin:${item.pluginId}:${item.entry.id}` }
function contributionVersionKey(item: PluginUiContribution) { return pluginContributionVersionKey(item) }
const availableContributions = computed(() => contributions.value.filter((item) =>
  item.entry.placement === 'channel-tests' && item.entry.contexts.includes('channel') && !invalidContributions.value.has(contributionVersionKey(item))))
const tabs = computed(() => [
  ...availableContributions.value.map((item) => ({ id: contributionKey(item), label: item.entry.label, icon: item.entry.icon })),
  ...legacyTabs,
])
const components: Record<string, Component> = {
  iq: defineAsyncComponent(() => import('./IqTestView.vue')),
  fingerprint: defineAsyncComponent(() => import('./FingerprintTestView.vue')),
}
const activeTab = ref('iq')
const tabId = useId()
const tabList = ref<HTMLElement | null>(null)
const tabPanel = ref<HTMLElement | null>(null)
const activeContribution = computed(() => availableContributions.value.find((item) => contributionKey(item) === activeTab.value))
const activeComponent = computed(() => activeContribution.value ? PluginDiagnosticHost : components[activeTab.value] ?? null)
const activeProps = computed(() => activeContribution.value ? {
  contribution: activeContribution.value,
  context: { kind: 'channel' as const, id: props.channel.id },
  initialModel: props.initialModel,
  channelEnabled: props.channel.enabled,
} : { channel: props.channel, initialModel: props.initialModel })
const activeKey = computed(() => {
  const contribution = activeContribution.value
  const key = contribution ? contributionVersionKey(contribution) : ''
  return `${activeTab.value}:${props.channel.id}:${contribution?.activeVersion ?? 'legacy'}:${key ? diagnosticGenerations.value.get(key) ?? 0 : ''}`
})

/** 记录页每次重新进入都重读，保证刚跑完的检测立刻出现。 */
const recordsKey = ref(0)
const recordTarget = ref<{ type: 'iq' | 'fingerprint'; id: number } | null>(null)
function preserveTabOnInteraction() {
  // 后续刷新可能主动恢复焦点；只有首次发现完成前的交互会阻止默认切换。
  if (!initialized) userSelectedTab = true
}
function selectTab(id: string) {
  userSelectedTab = true
  if (id === activeTab.value) return
  if (id === 'records') recordsKey.value += 1
  activeTab.value = id
}
function findTab(id: string) {
  return [...(tabList.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])]
    .find((tab) => tab.id === `${tabId}-${id}`)
}

async function navigateTabs(event: KeyboardEvent, index: number) {
  if (event.altKey || event.ctrlKey || event.metaKey) return
  let targetIndex: number
  switch (event.key) {
    case 'ArrowRight': targetIndex = (index + 1) % tabs.value.length; break
    case 'ArrowLeft': targetIndex = (index - 1 + tabs.value.length) % tabs.value.length; break
    case 'Home': targetIndex = 0; break
    case 'End': targetIndex = tabs.value.length - 1; break
    default: return
  }
  event.preventDefault()
  const targetId = tabs.value[targetIndex]!.id
  selectTab(targetId)
  await nextTick()
  if (disposed || activeTab.value !== targetId) return
  findTab(targetId)?.focus()
}

/** 智商 / 指纹视图只回传记录 id，这里补上当前页签的类型。 */
function openRecord(id: number) {
  const type = activeTab.value
  if (type !== 'iq' && type !== 'fingerprint') return
  recordTarget.value = { type, id }
  recordsKey.value += 1
  userSelectedTab = true
  activeTab.value = 'records'
}

async function focusIqIfRemoved(
  focusedTabId: string,
  focusWasInPanel: boolean,
  removedTabIds: string[],
  activeTabWasRemoved: boolean,
  replacedTabId = '',
) {
  const focusWasRemoved = removedTabIds.includes(focusedTabId) || (activeTabWasRemoved && (!!focusedTabId || focusWasInPanel))
  const shouldRestoreReplacedTab = !focusWasRemoved && !!replacedTabId && focusWasInPanel
  if (!focusWasRemoved && !shouldRestoreReplacedTab) return
  const fallbackTabId = activeTabWasRemoved ? 'iq' : activeTab.value
  if (focusWasRemoved && activeTabWasRemoved) activeTab.value = 'iq'
  await nextTick()
  if (disposed) return
  if (focusWasRemoved) {
    if (activeTab.value !== fallbackTabId) return
    const fallbackTab = findTab(fallbackTabId)
    fallbackTab?.focus()
    fallbackTab?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    return
  }
  if (activeTab.value !== replacedTabId) return
  const currentFocus = typeof document === 'undefined' ? null : document.activeElement
  if (currentFocus && currentFocus !== document.body && tabPanel.value?.contains(currentFocus)) return
  findTab(replacedTabId)?.focus()
}

async function load(recheck = false) {
  controller?.abort()
  const current = new AbortController()
  controller = current
  const retryElement = typeof document !== 'undefined' && document.activeElement === retryAction.value ? retryAction.value : null
  loadError.value = ''
  try {
    const result = await guard.run(() => api.getPluginContributions({ signal: current.signal }))
    if (!result.isLatest || disposed) return
    const previousAvailable = availableContributions.value
    const previousActiveTab = activeTab.value
    const previousActiveContribution = previousAvailable.find((item) => contributionKey(item) === previousActiveTab)
    const activeElement = typeof document === 'undefined' ? null : document.activeElement
    const focusedTabId = activeElement && tabList.value?.contains(activeElement) ? activeElement.id : ''
    const focusWasInPanel = !!activeElement && !!tabPanel.value?.contains(activeElement)
    invalidContributions.value = currentInvalidPluginContributions(result.value, invalidContributions.value)
    contributions.value = result.value
    const removedContributions = previousAvailable.filter((item) =>
      !availableContributions.value.some((next) => contributionKey(next) === contributionKey(item)))
    const removedTabIds = removedContributions.map((item) => `${tabId}-${contributionKey(item)}`)
    const activeTabWasRemoved = !!previousActiveContribution && removedContributions.some((item) =>
      contributionKey(item) === contributionKey(previousActiveContribution))
    const activeContributionReplaced = previousActiveContribution && availableContributions.value.some((item) =>
      contributionKey(item) === contributionKey(previousActiveContribution) &&
      contributionVersionKey(item) !== contributionVersionKey(previousActiveContribution))
      ? contributionKey(previousActiveContribution)
      : ''
    if (!tabs.value.some((tab) => tab.id === activeTab.value)) activeTab.value = 'iq'
    else if (!initialized && !userSelectedTab) activeTab.value = tabs.value[0]?.id ?? 'iq'
    initialized = true
    await focusIqIfRemoved(focusedTabId, focusWasInPanel, removedTabIds, activeTabWasRemoved, activeContributionReplaced)
    if (recheck && !disposed && controller === current && !current.signal.aborted) {
      const invalidSnapshot = invalidContributions.value
      const checked = await recheckPluginContributions(result.value.filter(item =>
        item.entry.placement === 'channel-tests' && item.entry.contexts.includes('channel')), invalidSnapshot, current.signal)
      if (disposed || controller !== current || current.signal.aborted) return
      if (invalidContributions.value === invalidSnapshot) {
        const remaining = new Set(invalidSnapshot)
        for (const key of checked.recovered) {
          if (remaining.delete(key)) diagnosticGenerations.value.set(key, (diagnosticGenerations.value.get(key) ?? 0) + 1)
        }
        invalidContributions.value = remaining
      }
      loadError.value = checked.error
    }
  } catch (cause) {
    if (!disposed && (cause as { isLatest?: boolean }).isLatest !== false && !current.signal.aborted) {
      loadError.value = cause instanceof Error ? cause.message : String(cause)
    }
  } finally {
    if (controller === current) {
      await nextTick()
      if (controller === current) {
        if (!disposed && retryElement && !retryElement.isConnected && document.activeElement === document.body) {
          const target = retryAction.value ?? tabList.value?.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')
          target?.focus()
        }
        controller = null
      }
    }
  }
}
function refresh() { void load(true) }
function markUnavailable(value: { pluginId: string; activeVersion: string; entryId: string }) {
  const versionKey = `${value.pluginId}:${value.activeVersion}:${value.entryId}`
  const tabKey = `plugin:${value.pluginId}:${value.entryId}`
  const current = availableContributions.value.find((item) => contributionKey(item) === tabKey)
  if (!current || current.activeVersion !== value.activeVersion) return
  const focused = document.activeElement
  const focusedTabId = focused instanceof HTMLElement && tabList.value?.contains(focused) ? focused.id : ''
  const focusWasInPanel = !!focused && !!tabPanel.value?.contains(focused)
  const removedTabId = `${tabId}-${tabKey}`
  const activeTabWasRemoved = activeTab.value === tabKey
  invalidContributions.value = new Set([...invalidContributions.value, versionKey])
  if (activeTabWasRemoved) {
    userSelectedTab = true
    activeTab.value = 'iq'
  }
  void focusIqIfRemoved(focusedTabId, focusWasInPanel, [removedTabId], activeTabWasRemoved)
}
onMounted(() => { void load(); window.addEventListener(PLUGIN_STATE_CHANGED, refresh) })
onBeforeUnmount(() => { disposed = true; guard.invalidate(); controller?.abort(); window.removeEventListener(PLUGIN_STATE_CHANGED, refresh) })
</script>

<template>
  <Modal :title="`模型测试 · ${channel.name}`" eyebrow="检测" full :body-scrollable="activeTab === 'records'" @close="emit('close')">
    <template #title-extra><span class="badge channel-test-status" :class="channel.enabled ? 'success' : 'neutral'" :aria-label="channel.enabled ? '渠道路由已启用' : '渠道路由已停用，仍可手动测试'">{{ channel.enabled ? '启用中' : '已停用' }}</span></template>
    <template #subhead>
      <div ref="tabList" class="channel-test-tabs" role="tablist" aria-label="模型测试方式"
        @focusin="preserveTabOnInteraction" @pointerdown="preserveTabOnInteraction">
        <button v-for="(tab, index) in tabs" :key="tab.id" :id="`${tabId}-${tab.id}`" type="button" role="tab" class="channel-test-tab"
          :class="{ active: activeTab === tab.id }" :aria-selected="activeTab === tab.id" :aria-controls="`${tabId}-panel`"
          :tabindex="activeTab === tab.id ? 0 : -1" @click="selectTab(tab.id)" @keydown="navigateTabs($event, index)"><Icon :name="tab.icon" :size="17" /><span>{{ tab.label }}</span></button>
      </div>
      <p v-if="loadError" class="channel-test-plugin-note" role="alert">诊断插件入口读取失败：{{ loadError }} <button ref="retryAction" type="button" class="link-button" @click="refresh">重试</button></p>
      <p v-else-if="invalidContributions.size" class="channel-test-plugin-note" role="status">此生效版本的诊断入口已不可用。可手动重新检查入口状态。<button ref="retryAction" type="button" class="link-button" @click="refresh">重新检查</button></p>
      <p v-else-if="initialized && !availableContributions.length" class="channel-test-plugin-note">没有已生效的渠道诊断插件，可在「插件」页检查。智商、指纹与记录仍可使用。</p>
    </template>
    <section :id="`${tabId}-panel`" ref="tabPanel" class="channel-test-panel" :class="{ 'is-records': activeTab === 'records' }" role="tabpanel" :aria-labelledby="`${tabId}-${activeTab}`" tabindex="0"
      @focusin="preserveTabOnInteraction" @pointerdown.capture="preserveTabOnInteraction" @keydown.capture="preserveTabOnInteraction"
      @input="preserveTabOnInteraction" @change="preserveTabOnInteraction" @click.capture="preserveTabOnInteraction">
      <TestRecordsView v-if="activeTab === 'records'" :key="'records-' + recordsKey" :channel-id="channel.id" :initial-record="recordTarget" @record-opened="recordTarget = null" />
      <KeepAlive :max="8"><component v-if="activeComponent" :is="activeComponent" :key="activeKey" v-bind="activeProps" @open-record="openRecord" @unavailable="markUnavailable" /></KeepAlive>
    </section>
  </Modal>
</template>

<style scoped>
.channel-test-tabs { display: flex; gap: 6px; padding: 8px 0; min-width: 0; overflow-x: auto; overscroll-behavior-x: contain; }
.channel-test-tab { display: flex; flex: 1 0 auto; align-items: center; justify-content: center; gap: 8px; min-width: 90px; min-height: 44px; padding: 9px 12px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: inherit; font: inherit; cursor: pointer; }
.channel-test-tab:hover { background: var(--glass-field); }
.channel-test-tab.active { border-color: color-mix(in srgb, var(--brand) 30%, transparent); background: color-mix(in srgb, var(--brand) 8%, transparent); color: var(--brand); }
.channel-test-tab > span { flex: 0 0 auto; font-size: 13px; font-weight: 650; }
.channel-test-status { flex: 0 0 auto; white-space: nowrap; }
.channel-test-panel { display: flex; flex: 1 1 0; flex-direction: column; min-width: 0; min-height: 0; }
.channel-test-panel.is-records { flex: 0 0 auto; }
.channel-test-plugin-note { margin: 0 0 10px; color: var(--muted); font-size: 12px; line-height: 1.6; }
.channel-test-tab:focus-visible, .channel-test-panel:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }
@media (max-width: 720px) { .channel-test-tabs { gap: 4px; } .channel-test-tab { gap: 5px; padding: 8px; } }
@media (prefers-reduced-transparency: reduce) { .channel-test-tab:hover, .channel-test-tab.active { background: var(--panel); } }
</style>

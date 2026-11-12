<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, ref, useId, type Component } from 'vue'
import type { Channel } from '../types/api'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import TestRecordsView from './TestRecordsView.vue'

const props = defineProps<{
  channel: Channel
  initialModel?: string
}>()
const emit = defineEmits<{
  (event: 'close'): void
}>()

const tabs = [
  { id: 'connectivity', label: '连通性', icon: 'bolt' },
  { id: 'iq', label: '智商', icon: 'brain' },
  { id: 'fingerprint', label: '指纹', icon: 'fingerprint' },
  { id: 'records', label: '记录', icon: 'logs' },
] as const

type TabId = (typeof tabs)[number]['id']

const components: Record<string, Component> = {
  connectivity: defineAsyncComponent(() => import('./ChannelConnectivityPanel.vue')),
  iq: defineAsyncComponent(() => import('./IqTestView.vue')),
  fingerprint: defineAsyncComponent(() => import('./FingerprintTestView.vue')),
}

const activeTab = ref<TabId>('connectivity')
const tabId = useId()
const tabList = ref<HTMLElement | null>(null)
const activeComponent = computed(() => (activeTab.value === 'records' ? null : components[activeTab.value]))

/** 记录页每次重新进入都重读，保证刚跑完的检测立刻出现。 */
const recordsKey = ref(0)
const recordTarget = ref<{ type: 'iq' | 'fingerprint'; id: number } | null>(null)

function selectTab(id: TabId) {
  if (id === activeTab.value) return
  if (id === 'records') recordsKey.value += 1
  activeTab.value = id
}

async function navigateTabs(event: KeyboardEvent, index: number) {
  if (event.altKey || event.ctrlKey || event.metaKey) return
  let targetIndex: number
  switch (event.key) {
    case 'ArrowRight': targetIndex = (index + 1) % tabs.length; break
    case 'ArrowLeft': targetIndex = (index - 1 + tabs.length) % tabs.length; break
    case 'Home': targetIndex = 0; break
    case 'End': targetIndex = tabs.length - 1; break
    default: return
  }
  event.preventDefault()
  selectTab(tabs[targetIndex]!.id)
  await nextTick()
  tabList.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[targetIndex]?.focus()
}

/** 智商 / 指纹视图只回传记录 id，这里补上当前页签的类型，并切到记录页展示详情。 */
function openRecord(id: number) {
  const type = activeTab.value
  if (type !== 'iq' && type !== 'fingerprint') return
  recordTarget.value = { type, id }
  activeTab.value = 'records'
}
</script>

<template>
  <Modal :title="`模型测试 · ${channel.name}`" eyebrow="检测" full :body-scrollable="activeTab === 'records'" @close="$emit('close')">
    <template #title-extra>
      <span class="badge channel-test-status" :class="channel.enabled ? 'success' : 'neutral'"
        :aria-label="channel.enabled ? '渠道路由已启用' : '渠道路由已停用，仍可手动测试'">
        {{ channel.enabled ? '启用中' : '已停用' }}
      </span>
    </template>
    <template #subhead>
      <div ref="tabList" class="channel-test-tabs" role="tablist" aria-label="模型测试方式">
        <button
          v-for="(tab, index) in tabs"
          :key="tab.id"
          :id="`${tabId}-${tab.id}`"
          type="button"
          role="tab"
          class="channel-test-tab"
          :class="{ active: activeTab === tab.id }"
          :aria-selected="activeTab === tab.id"
          :aria-controls="`${tabId}-panel`"
          :tabindex="activeTab === tab.id ? 0 : -1"
          @click="selectTab(tab.id)"
          @keydown="navigateTabs($event, index)"
        >
          <Icon :name="tab.icon" :size="17" />
          <span>{{ tab.label }}</span>
        </button>
      </div>
    </template>

    <section :id="`${tabId}-panel`" class="channel-test-panel" :class="{ 'is-records': activeTab === 'records' }"
      role="tabpanel" :aria-labelledby="`${tabId}-${activeTab}`" tabindex="0">
      <TestRecordsView
        v-if="activeTab === 'records'"
        :key="'records-' + recordsKey"
        :channel-id="channel.id"
        :initial-record="recordTarget"
        @record-opened="recordTarget = null"
      />
      <KeepAlive>
        <component
          v-if="activeComponent"
          :is="activeComponent"
          :key="activeTab"
          :channel="channel"
          :initial-model="initialModel"
          @open-record="openRecord"
        />
      </KeepAlive>
    </section>
  </Modal>
</template>

<style scoped>
.channel-test-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  padding: 8px 0;
}

.channel-test-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 0;
  min-height: 44px;
  padding: 9px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.channel-test-tab:hover {
  background: var(--glass-field);
}

.channel-test-tab.active {
  border-color: color-mix(in srgb, var(--brand) 30%, transparent);
  background: color-mix(in srgb, var(--brand) 8%, transparent);
  color: var(--brand);
}

.channel-test-tab > span {
  flex: 0 0 auto;
  font-size: 13px;
  font-weight: 650;
}

.channel-test-status {
  flex: 0 0 auto;
  white-space: nowrap;
}

.channel-test-panel {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.channel-test-panel.is-records {
  flex: 0 0 auto;
}

.channel-test-tab:focus-visible,
.channel-test-panel:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: -2px;
}

@media (max-width: 720px) {
  .channel-test-tabs {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 4px;
  }

  .channel-test-tab {
    justify-content: center;
    gap: 5px;
    min-height: 44px;
    padding: 8px 4px;
  }

}

@media (prefers-reduced-transparency: reduce) {
  .channel-test-tab:hover,
  .channel-test-tab.active {
    background: var(--panel);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>

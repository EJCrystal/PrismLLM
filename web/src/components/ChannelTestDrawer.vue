<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import type { Channel } from '../types/api'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps<{
  channel: Channel
  initialModel?: string
}>()
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'open-record', payload: { type: 'iq' | 'fingerprint'; id: number }): void
}>()

const tabs = [
  { id: 'connectivity', label: '连通性', icon: 'bolt', summary: '发一条真实请求，确认渠道现在能不能调通' },
  { id: 'iq', label: '智商', icon: 'brain', summary: '鹈鹕与糖果题，观察推理和指令遵循' },
  { id: 'fingerprint', label: '指纹', icon: 'fingerprint', summary: '随机数字序列比对模型指纹参考库' },
] as const

type TabId = (typeof tabs)[number]['id']

const components = {
  connectivity: defineAsyncComponent(() => import('./ChannelConnectivityPanel.vue')),
  iq: defineAsyncComponent(() => import('./IqTestView.vue')),
  fingerprint: defineAsyncComponent(() => import('./FingerprintTestView.vue')),
}

const activeTab = ref<TabId>('connectivity')
const activeComponent = computed(() => components[activeTab.value])

/** 智商 / 指纹视图只回传记录 id，这里补上当前页签的类型。 */
function openRecord(id: number) {
  const type = activeTab.value
  if (type !== 'iq' && type !== 'fingerprint') return
  emit('open-record', { type, id })
}
</script>

<template>
  <Modal :title="`模型测试 · ${channel.name}`" eyebrow="检测" full @close="$emit('close')">
    <template #subhead>
      <div class="channel-test-tabs" role="tablist" aria-label="模型测试方式">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          role="tab"
          class="channel-test-tab"
          :class="{ active: activeTab === tab.id }"
          :aria-selected="activeTab === tab.id"
          @click="activeTab = tab.id"
        >
          <Icon :name="tab.icon" :size="17" />
          <span>{{ tab.label }}</span>
          <small>{{ tab.summary }}</small>
        </button>
      </div>
    </template>

    <KeepAlive>
      <component
        :is="activeComponent"
        :key="activeTab"
        :channel="channel"
        :initial-model="initialModel"
        @open-record="openRecord"
      />
    </KeepAlive>
  </Modal>
</template>

<style scoped>
.channel-test-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 0;
}

.channel-test-tab {
  display: flex;
  flex: 1 1 180px;
  align-items: center;
  gap: 8px;
  min-width: 0;
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

.channel-test-tab small {
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .channel-test-tab {
    flex-basis: 100%;
  }

  .channel-test-tab small {
    white-space: normal;
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

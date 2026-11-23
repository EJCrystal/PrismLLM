<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Channel } from '../types/api'
import type { OAuthAccountStatusFilter } from '../utils/oauthAccount'
import type { ProviderListItem } from '../utils/providerDisplay'
import ChannelProtocols from './ChannelProtocols.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import OAuthAccountsView from './OAuthAccountsView.vue'
import OAuthProviderMappingsPanel from './OAuthProviderMappingsPanel.vue'

export type OAuthProviderDrawerTab = 'accounts' | 'mappings'

const props = withDefaults(defineProps<{
  item: ProviderListItem
  channels: Channel[]
  initialTab?: OAuthProviderDrawerTab
  initialStatusFilter?: OAuthAccountStatusFilter
  autoOpenCreate?: boolean
  togglingEnabled?: boolean
}>(), {
  initialTab: 'accounts',
  initialStatusFilter: 'all',
  autoOpenCreate: false,
  togglingEnabled: false,
})

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'changed'): void
  (event: 'auto-create-consumed'): void
  (event: 'toggle-enabled', item: ProviderListItem): void
  (event: 'open-prices', channel: Channel): void
  (event: 'open-health', channel: Channel): void
}>()

const activeTab = ref<OAuthProviderDrawerTab>(props.initialTab)
const mappingsPanel = ref<InstanceType<typeof OAuthProviderMappingsPanel> | null>(null)
/** 映射面板一旦打开就保持挂载，仅用 v-show 切换，避免丢失未保存草稿。 */
const mappingsMounted = ref(activeTab.value === 'mappings')
/** 账号池发生变更时映射目录可能过期，等下次进入映射标签再刷新。 */
const mappingsStale = ref(false)

watch(() => props.initialTab, (tab) => {
  activeTab.value = tab
})

watch(activeTab, (tab) => {
  if (tab !== 'mappings') return
  mappingsMounted.value = true
  if (mappingsStale.value) {
    mappingsStale.value = false
    void mappingsPanel.value?.reload()
  }
})

// 提供商身份而不是列表 key：渠道置备后 key 会变化，不应重置当前标签页。
watch(() => props.item.providerId, () => {
  activeTab.value = props.initialTab
})

function requestClose() {
  if (mappingsMounted.value && mappingsPanel.value?.hasDirty()) {
    if (!window.confirm('共享模型映射还有未保存的修改，确定关闭并放弃这些草稿吗？')) return
  }
  emit('close')
}

function onAccountsChanged() {
  emit('changed')
  if (!mappingsMounted.value) return
  if (activeTab.value === 'mappings') {
    void mappingsPanel.value?.reload()
  } else {
    mappingsStale.value = true
  }
}

function onMappingsSaved() {
  emit('changed')
}
</script>

<template>
  <Modal
    :title="item.displayName"
    eyebrow="官方订阅提供商"
    roomy
    mobile-fullscreen
    @close="requestClose"
  >
    <template #title-extra>
      <span class="badge info small">官方订阅</span>
      <span v-if="!item.configured" class="badge warning small">未配置 OAuth</span>
      <button
        v-if="item.channel"
        type="button"
        class="channel-status-toggle"
        role="switch"
        :class="item.enabled ? 'is-enabled' : 'is-disabled'"
        :aria-checked="item.enabled"
        :aria-label="item.displayName + (item.enabled ? '：已启用，点击停用' : '：已停用，点击启用')"
        :disabled="togglingEnabled"
        @click="$emit('toggle-enabled', item)"
      >
        <span class="channel-status-track" aria-hidden="true"><span></span></span>
        <span class="channel-status-label">{{ item.enabled ? '启用' : '停用' }}</span>
      </button>
    </template>

    <template #subhead>
      <div class="provider-drawer-subhead">
        <div class="provider-drawer-meta">
          <div class="provider-drawer-endpoint">
            <span class="muted small">官方端点</span>
            <code class="mono small">{{ item.baseUrl || '由系统托管' }}</code>
          </div>
          <ChannelProtocols v-if="item.protocols.length" :protocols="item.protocols" />
        </div>
        <div class="provider-drawer-bar">
          <div class="provider-drawer-tabs" role="tablist" aria-label="订阅提供商管理视图">
            <button
              type="button"
              role="tab"
              class="provider-tab"
              :class="{ 'is-active': activeTab === 'accounts' }"
              :aria-selected="activeTab === 'accounts'"
              @click="activeTab = 'accounts'"
            >
              <Icon name="users" :size="15" />
              <span>账号池（{{ item.accountSummary.total }}）</span>
            </button>
            <button
              type="button"
              role="tab"
              class="provider-tab"
              :class="{ 'is-active': activeTab === 'mappings' }"
              :aria-selected="activeTab === 'mappings'"
              @click="activeTab = 'mappings'"
            >
              <Icon name="list" :size="15" />
              <span>共享模型映射</span>
            </button>
          </div>
          <div v-if="item.channel" class="provider-drawer-quick-actions">
            <button type="button" class="button ghost mini" @click="$emit('open-prices', item.channel)">
              <Icon name="price" :size="14" />
              模型单价
            </button>
            <button type="button" class="button ghost mini" @click="$emit('open-health', item.channel)">
              <Icon name="chart" :size="14" />
              健康详情
            </button>
          </div>
        </div>
      </div>
    </template>

    <div v-show="activeTab === 'accounts'" class="provider-drawer-pane" role="tabpanel">
      <OAuthAccountsView
        :fixed-provider="item.providerId"
        :initial-status-filter="initialStatusFilter"
        :auto-open-create="autoOpenCreate"
        embedded
        @changed="onAccountsChanged"
        @auto-create-consumed="$emit('auto-create-consumed')"
      />
    </div>
    <div v-if="mappingsMounted && item.providerId" v-show="activeTab === 'mappings'" class="provider-drawer-pane" role="tabpanel">
      <OAuthProviderMappingsPanel
        ref="mappingsPanel"
        :provider="item.providerId"
        :provider-label="item.displayName"
        :channels="channels"
        @saved="onMappingsSaved"
      />
    </div>
  </Modal>
</template>

<style scoped>
.provider-drawer-subhead {
  display: grid;
  gap: 10px;
}

.provider-drawer-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 14px;
}

.provider-drawer-endpoint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.provider-drawer-endpoint code {
  overflow-wrap: anywhere;
}

.provider-drawer-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.provider-drawer-tabs {
  display: inline-flex;
  gap: 6px;
  padding: 3px;
  border-radius: 10px;
  background: var(--surface-subtle, rgba(127, 127, 127, 0.08));
}

.provider-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.provider-tab.is-active {
  background: var(--surface, #fff);
  color: var(--ink);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

.provider-drawer-quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.provider-drawer-pane {
  min-width: 0;
}
</style>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import Icon from './Icon.vue'

const tabs = [
  {
    id: 'connectivity',
    label: '连通性检测',
    icon: 'bolt',
    summary: '用一条真实请求确认渠道可达、鉴权有效',
  },
  {
    id: 'iq',
    label: '智商检测',
    icon: 'brain',
    summary: '鹈鹕与糖果题，观察模型的推理和指令遵循',
  },
  {
    id: 'fingerprint',
    label: '指纹检测',
    icon: 'fingerprint',
    summary: '从随机数字序列比对模型指纹参考库',
  },
] as const

type TabId = (typeof tabs)[number]['id']

const components = {
  connectivity: defineAsyncComponent(() => import('./ModelConnectivityView.vue')),
  iq: defineAsyncComponent(() => import('./IqTestView.vue')),
  fingerprint: defineAsyncComponent(() => import('./FingerprintTestView.vue')),
}

const activeTab = ref<TabId>('connectivity')
const activeComponent = computed(() => components[activeTab.value])
</script>

<template>
  <section class="page-stack model-test-page">
    <div class="model-test-tabs panel" role="tablist" aria-label="模型检测方式">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        role="tab"
        class="model-test-tab"
        :class="{ active: activeTab === tab.id }"
        :aria-selected="activeTab === tab.id"
        @click="activeTab = tab.id"
      >
        <span class="model-test-tab-icon"><Icon :name="tab.icon" :size="18" /></span>
        <span class="model-test-tab-copy">
          <strong>{{ tab.label }}</strong>
          <small>{{ tab.summary }}</small>
        </span>
      </button>
    </div>

    <KeepAlive>
      <component :is="activeComponent" :key="activeTab" />
    </KeepAlive>
  </section>
</template>

<style scoped>
.model-test-page {
  min-width: 0;
}

.model-test-tabs {
  display: flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 6px;
  padding: 8px;
}

.model-test-tab {
  display: flex;
  flex: 1 1 220px;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.model-test-tab:hover {
  background: var(--bg);
}

.model-test-tab.active {
  border-color: color-mix(in srgb, var(--brand) 30%, transparent);
  background: color-mix(in srgb, var(--brand) 8%, transparent);
}

.model-test-tab-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--bg);
  color: var(--muted);
}

.model-test-tab.active .model-test-tab-icon {
  background: color-mix(in srgb, var(--brand) 14%, transparent);
  color: var(--brand);
}

.model-test-tab-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.model-test-tab-copy strong {
  font-size: 13px;
}

.model-test-tab-copy small {
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .model-test-tab {
    flex-basis: 100%;
  }

  .model-test-tab-copy small {
    white-space: normal;
  }
}
</style>
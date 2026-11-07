<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type {
  KeyAutoDisableSettingsResponse,
  ModelTestSettings,
  RetentionPolicy,
  SystemConfigResponse,
  UpstreamRequestSettings,
  WebSearchApiKeySource,
  WebSearchSettings,
} from '../types/api'
import { formatNumber, formatTime } from '../utils/format'
import {
  configSourceLabel,
  configSourceTone,
  countUpstreamOverrides,
  formatConfigValue,
} from '../utils/systemConfig'
import Icon from './Icon.vue'
import KeyAutoDisableSettingsModal from './KeyAutoDisableSettingsModal.vue'
import ModelTestSettingsModal from './ModelTestSettingsModal.vue'
import UpstreamRequestSettingsModal from './UpstreamRequestSettingsModal.vue'
import WebSearchSettingsModal from './WebSearchSettingsModal.vue'

const emit = defineEmits<{
  (event: 'navigate', view: 'usage-reports'): void
}>()

const config = ref<SystemConfigResponse | null>(null)
const configLoading = ref(true)
const configError = ref('')
const refreshing = ref(false)

const cards = reactive({
  modelTest: { loading: true, error: '', data: null as ModelTestSettings | null },
  upstream: { loading: true, error: '', data: null as UpstreamRequestSettings | null },
  webSearch: { loading: true, error: '', data: null as WebSearchSettings | null },
  autoDisable: { loading: true, error: '', data: null as KeyAutoDisableSettingsResponse | null },
  retention: { loading: true, error: '', data: null as RetentionPolicy | null },
})

const showModelTest = ref(false)
const showUpstream = ref(false)
const showWebSearch = ref(false)
const showAutoDisable = ref(false)

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function loadConfig() {
  configLoading.value = true
  configError.value = ''
  try {
    config.value = await api.getSystemConfig()
  } catch (cause) {
    configError.value = errorMessage(cause)
  } finally {
    configLoading.value = false
  }
}

async function loadModelTest() {
  cards.modelTest.loading = true
  cards.modelTest.error = ''
  try {
    cards.modelTest.data = await api.getModelTestSettings()
  } catch (cause) {
    cards.modelTest.error = errorMessage(cause)
  } finally {
    cards.modelTest.loading = false
  }
}

async function loadUpstream() {
  cards.upstream.loading = true
  cards.upstream.error = ''
  try {
    cards.upstream.data = await api.getUpstreamRequestSettings()
  } catch (cause) {
    cards.upstream.error = errorMessage(cause)
  } finally {
    cards.upstream.loading = false
  }
}

async function loadWebSearch() {
  cards.webSearch.loading = true
  cards.webSearch.error = ''
  try {
    cards.webSearch.data = await api.getWebSearchSettings()
  } catch (cause) {
    cards.webSearch.error = errorMessage(cause)
  } finally {
    cards.webSearch.loading = false
  }
}

async function loadAutoDisable() {
  cards.autoDisable.loading = true
  cards.autoDisable.error = ''
  try {
    cards.autoDisable.data = await api.getKeyAutoDisableSettings()
  } catch (cause) {
    cards.autoDisable.error = errorMessage(cause)
  } finally {
    cards.autoDisable.loading = false
  }
}

async function loadRetention() {
  cards.retention.loading = true
  cards.retention.error = ''
  try {
    cards.retention.data = await api.getRetentionPolicy()
  } catch (cause) {
    cards.retention.error = errorMessage(cause)
  } finally {
    cards.retention.loading = false
  }
}

async function loadAll(refresh = false) {
  if (refresh) refreshing.value = true
  await Promise.allSettled([
    loadConfig(),
    loadModelTest(),
    loadUpstream(),
    loadWebSearch(),
    loadAutoDisable(),
    loadRetention(),
  ])
  refreshing.value = false
}

function promptSummary(prompt: string): string {
  const normalized = prompt.replace(/\s+/g, ' ').trim()
  return normalized.length > 120 ? `${normalized.slice(0, 120)}…` : normalized
}

function updatedLabel(timestamp: number): string {
  return timestamp > 0 ? `更新于 ${formatTime(timestamp)}` : '使用默认设置'
}

function apiKeySourceLabel(source: WebSearchApiKeySource): string {
  if (source === 'settings') return '管理台'
  if (source === 'environment') return '环境变量'
  return '无'
}

onMounted(() => { void loadAll() })
</script>

<template>
  <section class="page-stack system-config-page">
    <div class="section-head">
      <div>
        <h2>全局业务设置</h2>
        <p>保存后立即生效，作用于所有渠道与调用方。</p>
      </div>
      <button type="button" class="button subtle" :disabled="refreshing" @click="loadAll(true)">
        {{ refreshing ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <div class="system-config-grid">
      <article class="panel system-config-card">
        <div class="system-config-card-head">
          <div class="system-config-card-title">
            <Icon name="bolt" :size="18" />
            <div>
              <h3>模型测试</h3>
              <small>全局默认测试提示词</small>
            </div>
          </div>
          <span class="badge neutral">全局</span>
        </div>
        <div class="system-config-card-body">
          <p v-if="cards.modelTest.loading" class="muted small">加载中…</p>
          <div v-else-if="cards.modelTest.error" class="system-config-card-error" role="alert">
            <p class="text-danger small">{{ cards.modelTest.error }}</p>
            <button type="button" class="button ghost mini" @click="loadModelTest">重新加载</button>
          </div>
          <template v-else-if="cards.modelTest.data">
            <p class="system-config-prompt" :title="cards.modelTest.data.defaultPrompt">
              {{ promptSummary(cards.modelTest.data.defaultPrompt) }}
            </p>
            <p class="muted small">{{ updatedLabel(cards.modelTest.data.updatedAt) }}</p>
          </template>
        </div>
        <div class="system-config-card-foot">
          <button type="button" class="button subtle" @click="showModelTest = true">编辑</button>
        </div>
      </article>

      <article class="panel system-config-card">
        <div class="system-config-card-head">
          <div class="system-config-card-title">
            <Icon name="bolt" :size="18" />
            <div>
              <h3>上游请求</h3>
              <small>请求头预设与全局覆盖</small>
            </div>
          </div>
          <span class="badge neutral">全局</span>
        </div>
        <div class="system-config-card-body">
          <p v-if="cards.upstream.loading" class="muted small">加载中…</p>
          <div v-else-if="cards.upstream.error" class="system-config-card-error" role="alert">
            <p class="text-danger small">{{ cards.upstream.error }}</p>
            <button type="button" class="button ghost mini" @click="loadUpstream">重新加载</button>
          </div>
          <template v-else-if="cards.upstream.data">
            <p>
              {{ cards.upstream.data.profiles.length }} 个请求头模板 · 全局覆盖
              {{ countUpstreamOverrides(cards.upstream.data.overrides) }} 项
            </p>
            <p class="muted small">
              自动刷新{{ cards.upstream.data.headerCatalog.autoRefreshEnabled ? '已开启' : '已关闭' }} ·
              {{ updatedLabel(cards.upstream.data.updatedAt) }}
            </p>
            <p v-if="cards.upstream.data.headerCatalog.lastError" class="text-danger small">
              最近刷新失败：{{ cards.upstream.data.headerCatalog.lastError }}
            </p>
          </template>
        </div>
        <div class="system-config-card-foot">
          <button type="button" class="button subtle" @click="showUpstream = true">编辑</button>
        </div>
      </article>

      <article class="panel system-config-card">
        <div class="system-config-card-head">
          <div class="system-config-card-title">
            <Icon name="search" :size="18" />
            <div>
              <h3>Web 搜索</h3>
              <small>内置 web_search 的搜索后端</small>
            </div>
          </div>
          <span class="badge neutral">全局</span>
        </div>
        <div class="system-config-card-body">
          <p v-if="cards.webSearch.loading" class="muted small">加载中…</p>
          <div v-else-if="cards.webSearch.error" class="system-config-card-error" role="alert">
            <p class="text-danger small">{{ cards.webSearch.error }}</p>
            <button type="button" class="button ghost mini" @click="loadWebSearch">重新加载</button>
          </div>
          <template v-else-if="cards.webSearch.data">
            <p>
              {{ cards.webSearch.data.enabled ? '已启用' : '已关闭' }} ·
              {{ cards.webSearch.data.provider }}
            </p>
            <p class="muted small">
              <template v-if="cards.webSearch.data.apiKeySet">
                密钥已配置（{{ apiKeySourceLabel(cards.webSearch.data.apiKeySource) }}）·
              </template>
              <template v-else>密钥未配置 · </template>
              {{ cards.webSearch.data.source === 'settings' ? updatedLabel(cards.webSearch.data.updatedAt) : '使用环境变量默认值' }}
            </p>
          </template>
        </div>
        <div class="system-config-card-foot">
          <button type="button" class="button subtle" @click="showWebSearch = true">编辑</button>
        </div>
      </article>

      <article class="panel system-config-card">
        <div class="system-config-card-head">
          <div class="system-config-card-title">
            <Icon name="shield" :size="18" />
            <div>
              <h3>密钥自动禁用</h3>
              <small>渠道密钥失败保护规则</small>
            </div>
          </div>
          <span class="badge neutral">全局</span>
        </div>
        <div class="system-config-card-body">
          <p v-if="cards.autoDisable.loading" class="muted small">加载中…</p>
          <div v-else-if="cards.autoDisable.error" class="system-config-card-error" role="alert">
            <p class="text-danger small">{{ cards.autoDisable.error }}</p>
            <button type="button" class="button ghost mini" @click="loadAutoDisable">重新加载</button>
          </div>
          <template v-else-if="cards.autoDisable.data">
            <p>
              {{ cards.autoDisable.data.enabled ? '已启用' : '已关闭' }} ·
              {{ cards.autoDisable.data.rules.length }} 条规则
            </p>
            <p class="muted small">{{ updatedLabel(cards.autoDisable.data.updatedAt) }}</p>
          </template>
        </div>
        <div class="system-config-card-foot">
          <button type="button" class="button subtle" @click="showAutoDisable = true">编辑</button>
        </div>
      </article>

      <article class="panel system-config-card">
        <div class="system-config-card-head">
          <div class="system-config-card-title">
            <Icon name="logs" :size="18" />
            <div>
              <h3>请求日志保留</h3>
              <small>自动清理与历史报表范围</small>
            </div>
          </div>
          <span class="badge neutral">全局</span>
        </div>
        <div class="system-config-card-body">
          <p v-if="cards.retention.loading" class="muted small">加载中…</p>
          <div v-else-if="cards.retention.error" class="system-config-card-error" role="alert">
            <p class="text-danger small">{{ cards.retention.error }}</p>
            <button type="button" class="button ghost mini" @click="loadRetention">重新加载</button>
          </div>
          <template v-else-if="cards.retention.data">
            <p>
              保留 {{ formatNumber(cards.retention.data.retentionDays) }} 天 · 每批
              {{ formatNumber(cards.retention.data.batchSize) }} 条
            </p>
            <p class="muted small">
              自动清理{{ cards.retention.data.cleanupEnabled ? '已开启' : '已关闭' }}。每日额度账本不受影响。
            </p>
          </template>
        </div>
        <div class="system-config-card-foot">
          <button type="button" class="button subtle" @click="emit('navigate', 'usage-reports')">前往用量报表</button>
        </div>
      </article>
    </div>

    <div class="section-head system-config-runtime-head">
      <div>
        <h2>运行参数</h2>
        <p>来自环境变量，启动时加载；修改后需要重启网关。</p>
      </div>
    </div>

    <p class="callout info">
      主密钥、数据库密码、管理员密码、OAuth client secret 和搜索 API 密钥不会在此展示；此处仅显示运行参数与配置来源。
    </p>

    <p v-if="configLoading" class="muted">正在读取运行参数…</p>
    <div v-else-if="configError" class="callout danger" role="alert">
      <p>运行参数加载失败：{{ configError }}</p>
      <button type="button" class="button ghost mini" @click="loadConfig">重新加载</button>
    </div>
    <div v-else-if="config" class="system-config-sections">
      <details
        v-for="(section, index) in config.sections"
        :key="section.id"
        class="panel system-config-section"
        :open="index < 2"
      >
        <summary>
          <div>
            <h3>{{ section.title }}</h3>
            <p class="muted small">{{ section.description }}</p>
          </div>
          <div class="system-config-summary-side">
            <span class="badge neutral">{{ section.items.length }} 项</span>
            <Icon class="system-config-chevron" name="chevron" :size="16" />
          </div>
        </summary>
        <div class="system-config-rows">
          <div v-for="item in section.items" :key="item.key" class="system-config-row">
            <div class="system-config-item-copy">
              <div class="system-config-item-title">
                <strong>{{ item.label }}</strong>
                <code>{{ item.key }}</code>
              </div>
              <p v-if="item.description" class="muted small">{{ item.description }}</p>
            </div>
            <div class="system-config-item-value">
              <span class="system-config-value">{{ formatConfigValue(item) }}</span>
              <span class="badge" :class="configSourceTone(item.source)">{{ configSourceLabel(item.source) }}</span>
              <span v-if="item.restartRequired" class="badge warning">需重启</span>
            </div>
          </div>
        </div>
      </details>
    </div>

    <ModelTestSettingsModal v-if="showModelTest" @close="showModelTest = false" @saved="loadModelTest" />
    <UpstreamRequestSettingsModal v-if="showUpstream" @close="showUpstream = false" @saved="loadUpstream" />
    <WebSearchSettingsModal v-if="showWebSearch" @close="showWebSearch = false" @saved="loadWebSearch" />
    <KeyAutoDisableSettingsModal v-if="showAutoDisable" @close="showAutoDisable = false" @saved="loadAutoDisable" />
  </section>
</template>

<style scoped>
.system-config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 14px;
}

.system-config-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.system-config-card-head,
.system-config-card-title,
.system-config-item-title,
.system-config-item-value {
  display: flex;
  align-items: center;
  gap: 8px;
}

.system-config-card-head {
  justify-content: space-between;
  align-items: flex-start;
}

.system-config-card-title {
  align-items: flex-start;
  min-width: 0;
}

.system-config-card-title h3,
.system-config-section h3 {
  margin: 0;
  font-size: 15px;
}

.system-config-card-title small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
}

.system-config-card-body {
  flex: 1;
  min-width: 0;
}

.system-config-card-body p {
  margin: 0 0 6px;
}

.system-config-card-foot {
  display: flex;
  justify-content: flex-end;
}

.system-config-card-error {
  display: grid;
  justify-items: start;
  gap: 6px;
}

.system-config-prompt {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-height: 1.5;
}

.system-config-runtime-head {
  margin-top: 8px;
}

.system-config-sections {
  display: grid;
  gap: 10px;
}

.system-config-section summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  list-style: none;
}

.system-config-section summary::-webkit-details-marker {
  display: none;
}

.system-config-section summary p {
  margin: 4px 0 0;
}

.system-config-summary-side {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.system-config-chevron {
  color: var(--muted);
  transition: transform 0.15s ease;
}

.system-config-section[open] .system-config-chevron {
  transform: rotate(180deg);
}

.system-config-rows {
  display: grid;
  gap: 0;
  margin-top: 12px;
  border-top: 1px solid var(--line);
}

.system-config-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: start;
  padding: 11px 0;
  border-bottom: 1px solid var(--line);
}

.system-config-row:last-child {
  border-bottom: 0;
}

.system-config-item-copy {
  min-width: 0;
}

.system-config-item-title {
  flex-wrap: wrap;
}

.system-config-item-title code {
  padding: 1px 5px;
  border-radius: 5px;
  background: color-mix(in srgb, var(--ink) 6%, transparent);
  color: var(--muted);
  font-size: 11px;
}

.system-config-item-copy p {
  margin: 4px 0 0;
}

.system-config-item-value {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.system-config-value {
  max-width: 42vw;
  overflow-wrap: anywhere;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 640px) {
  .system-config-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .system-config-item-value {
    justify-content: flex-start;
  }

  .system-config-value {
    max-width: 100%;
    text-align: left;
  }
}
</style>

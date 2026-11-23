<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import type {
  ChannelEgressSettings,
  KeyAutoDisableSettingsResponse,
  ModelTestSettings,
  SystemPromptSettings,
  NotificationSettings,
  RetentionPolicy,
  UpstreamRequestSettings,
  WebSearchApiKeySource,
  WebSearchSettings,
} from '../types/api'
import { formatTime } from '../utils/format'
import { countUpstreamOverrides } from '../utils/systemConfig'
import { clearAllUpstreamModelCache } from '../upstreamModelCache'
import Icon from './Icon.vue'
import ChannelEgressSettingsModal from './ChannelEgressSettingsModal.vue'
import KeyAutoDisableSettingsModal from './KeyAutoDisableSettingsModal.vue'
import ModelTestSettingsModal from './ModelTestSettingsModal.vue'
import NotificationSettingsModal from './NotificationSettingsModal.vue'
import RetentionSettingsModal from './RetentionSettingsModal.vue'
import UpstreamRequestSettingsModal from './UpstreamRequestSettingsModal.vue'
import SystemPromptSettingsModal from './SystemPromptSettingsModal.vue'
import WebSearchSettingsModal from './WebSearchSettingsModal.vue'

const refreshing = ref(false)

const cards = reactive({
  modelTest: { loading: true, error: '', data: null as ModelTestSettings | null },
  upstream: { loading: true, error: '', data: null as UpstreamRequestSettings | null },
  systemPrompt: { loading: true, error: '', data: null as SystemPromptSettings | null },
  webSearch: { loading: true, error: '', data: null as WebSearchSettings | null },
  channelEgress: { loading: true, error: '', data: null as ChannelEgressSettings | null },
  autoDisable: { loading: true, error: '', data: null as KeyAutoDisableSettingsResponse | null },
  notification: { loading: true, error: '', data: null as NotificationSettings | null },
  retention: { loading: true, error: '', data: null as RetentionPolicy | null },
})

const showModelTest = ref(false)
const showUpstream = ref(false)
const showSystemPrompt = ref(false)
const showWebSearch = ref(false)
const showChannelEgress = ref(false)
const showAutoDisable = ref(false)
const showNotification = ref(false)
const showRetention = ref(false)

interface SettingRow {
  id: string
  title: string
  description: string
  icon: string
  loading: boolean
  error: string
  value: string
  valueTitle: string
  meta: string
  alert: string
  onAction: () => void
  onRetry: () => void
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
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

async function loadChannelEgress() {
  cards.channelEgress.loading = true
  cards.channelEgress.error = ''
  try {
    cards.channelEgress.data = await api.getChannelEgressSettings()
  } catch (cause) {
    cards.channelEgress.error = errorMessage(cause)
  } finally {
    cards.channelEgress.loading = false
  }
}

async function loadSystemPrompt() {
  cards.systemPrompt.loading = true
  cards.systemPrompt.error = ''
  try {
    cards.systemPrompt.data = await api.getSystemPromptSettings()
  } catch (cause) {
    cards.systemPrompt.error = errorMessage(cause)
  } finally {
    cards.systemPrompt.loading = false
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

async function loadNotification() {
  cards.notification.loading = true
  cards.notification.error = ''
  try {
    cards.notification.data = await api.getNotificationSettings()
  } catch (cause) {
    cards.notification.error = errorMessage(cause)
  } finally {
    cards.notification.loading = false
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
    loadModelTest(),
    loadUpstream(),
    loadSystemPrompt(),
    loadWebSearch(),
    loadChannelEgress(),
    loadAutoDisable(),
    loadNotification(),
    loadRetention(),
  ])
  refreshing.value = false
}

function updatedLabel(timestamp: number): string {
  return timestamp > 0 ? `更新于 ${formatTime(timestamp)}` : '使用默认设置'
}

function promptValue(prompt: string): string {
  const normalized = prompt.replace(/\s+/g, ' ').trim()
  if (!normalized) return '未设置默认提示词'
  return `“${normalized.length > 90 ? `${normalized.slice(0, 90)}…` : normalized}”`
}

function apiKeySourceLabel(source: WebSearchApiKeySource): string {
  if (source === 'settings') return '管理台'
  if (source === 'environment') return '环境变量'
  return '无'
}

const settingRows = computed<SettingRow[]>(() => {
  const modelTest = cards.modelTest
  const upstream = cards.upstream
  const systemPrompt = cards.systemPrompt
  const webSearch = cards.webSearch
  const channelEgress = cards.channelEgress
  const autoDisable = cards.autoDisable
  const notification = cards.notification
  const retention = cards.retention
  return [
    {
      id: 'model-test',
      title: '模型测试',
      description: '全局默认测试提示词',
      icon: 'models',
      loading: modelTest.loading,
      error: modelTest.error,
      value: modelTest.data ? promptValue(modelTest.data.defaultPrompt) : '',
      valueTitle: modelTest.data?.defaultPrompt ?? '',
      meta: modelTest.data ? updatedLabel(modelTest.data.updatedAt) : '',
      alert: '',
      onAction: () => { showModelTest.value = true },
      onRetry: loadModelTest,
    },
    {
      id: 'system-prompt',
      title: '系统提示词',
      description: '按出口协议或渠道覆盖系统提示词',
      icon: 'edit',
      loading: systemPrompt.loading,
      error: systemPrompt.error,
      value: systemPrompt.data
        ? (systemPrompt.data.enabled ? '已启用' : '已关闭') + ' · ' + systemPrompt.data.rules.length + ' 条规则'
        : '',
      valueTitle: '',
      meta: systemPrompt.data ? updatedLabel(systemPrompt.data.updatedAt) : '',
      alert: '',
      onAction: () => { showSystemPrompt.value = true },
      onRetry: loadSystemPrompt,
    },
    {
      id: 'upstream',
      title: '上游请求',
      description: '请求头预设与全局覆盖',
      icon: 'channels',
      loading: upstream.loading,
      error: upstream.error,
      value: upstream.data
        ? `${upstream.data.profiles.length} 个请求头模板 · 覆盖 ${countUpstreamOverrides(upstream.data.overrides)} 项`
        : '',
      valueTitle: '',
      meta: upstream.data ? updatedLabel(upstream.data.updatedAt) : '',
      alert: upstream.data?.headerCatalog.lastError
        ? `最近刷新失败：${upstream.data.headerCatalog.lastError}`
        : '',
      onAction: () => { showUpstream.value = true },
      onRetry: loadUpstream,
    },
    {
      id: 'web-search',
      title: 'Web 搜索',
      description: '内置 web_search 的搜索后端',
      icon: 'globe',
      loading: webSearch.loading,
      error: webSearch.error,
      value: webSearch.data
        ? `${webSearch.data.enabled ? '已启用' : '已关闭'} · ${webSearch.data.provider}`
        : '',
      valueTitle: '',
      meta: webSearch.data
        ? [
            webSearch.data.apiKeySet
              ? `密钥已配置（${apiKeySourceLabel(webSearch.data.apiKeySource)}）`
              : '密钥未配置',
            webSearch.data.source === 'settings'
              ? updatedLabel(webSearch.data.updatedAt)
              : '使用环境变量默认值',
          ].join(' · ')
        : '',
      alert: '',
      onAction: () => { showWebSearch.value = true },
      onRetry: loadWebSearch,
    },
    {
      id: 'channel-egress',
      title: '渠道出口代理',
      description: '非回环代理主机的放行名单，保存后立即生效',
      icon: 'layers',
      loading: channelEgress.loading,
      error: channelEgress.error,
      value: channelEgress.data
        ? `${channelEgress.data.hosts.length} 个管理台主机 · ${channelEgress.data.effectiveHosts.length} 个生效`
        : '',
      valueTitle: channelEgress.data?.effectiveHosts.join('\n') || '',
      meta: channelEgress.data?.updatedAt
        ? updatedLabel(channelEgress.data.updatedAt)
        : (channelEgress.data?.deploymentHosts.length ? '仅使用环境变量默认值' : '尚未放行非回环主机'),
      alert: '',
      onAction: () => { showChannelEgress.value = true },
      onRetry: loadChannelEgress,
    },
    {
      id: 'auto-disable',
      title: '密钥自动禁用',
      description: '渠道密钥失败保护规则',
      icon: 'shield',
      loading: autoDisable.loading,
      error: autoDisable.error,
      value: autoDisable.data
        ? `${autoDisable.data.enabled ? '已启用' : '已关闭'} · ${autoDisable.data.rules.length} 条规则`
        : '',
      valueTitle: '',
      meta: autoDisable.data ? updatedLabel(autoDisable.data.updatedAt) : '',
      alert: '',
      onAction: () => { showAutoDisable.value = true },
      onRetry: loadAutoDisable,
    },
    {
      id: 'notifications',
      title: '运行通知',
      description: '渠道、密钥及连续失败事件的通知目标',
      icon: 'bell',
      loading: notification.loading,
      error: notification.error,
      value: notification.data
        ? `${notification.data.enabled ? '已启用' : '已关闭'} · ${notification.data.consecutiveFailureThreshold} 次连续失败阈值`
        : '',
      valueTitle: '',
      meta: notification.data ? updatedLabel(notification.data.updatedAt) : '',
      alert: '',
      onAction: () => { showNotification.value = true },
      onRetry: loadNotification,
    },
    {
      id: 'retention',
      title: '请求日志保留',
      description: '自动清理过期请求日志明细',
      icon: 'logs',
      loading: retention.loading,
      error: retention.error,
      value: retention.data
        ? `自动清理${retention.data.cleanupEnabled ? '已开启' : '已关闭'}`
        : '',
      valueTitle: '',
      meta: retention.data ? '每日额度账本不受影响' : '',
      alert: '',
      onAction: () => { showRetention.value = true },
      onRetry: loadRetention,
    },
  ]
})

function onUpstreamSaved() {
  clearAllUpstreamModelCache()
  void loadUpstream()
}

onMounted(() => { void loadAll() })
</script>

<template>
  <section class="page-stack system-config-page">
    <div class="section-head">
      <div>
        <h2>全局业务设置</h2>
        <p>按模块划分全局策略，点击右侧即可编辑并即时生效。</p>
      </div>
      <button
        type="button" class="button ghost" :disabled="refreshing"
        title="重新读取全局设置"
        @click="loadAll(true)"
      >
        <Icon name="refresh" :size="15" />
        {{ refreshing ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <div class="panel system-config-settings">
      <div v-for="row in settingRows" :key="row.id" class="system-config-setting">
        <span class="system-config-icon">
          <Icon :name="row.icon" :size="18" />
        </span>
        <div class="system-config-setting-copy">
          <h3>{{ row.title }}</h3>
          <p>{{ row.description }}</p>
        </div>
        <div class="system-config-setting-state">
          <span v-if="row.error" class="text-danger" role="alert">{{ row.error }}</span>
          <span v-else-if="row.loading" class="system-config-setting-muted">加载中…</span>
          <template v-else>
            <strong :title="row.valueTitle || undefined">{{ row.value }}</strong>
            <small v-if="row.meta">{{ row.meta }}</small>
            <small v-if="row.alert" class="text-danger">{{ row.alert }}</small>
          </template>
        </div>
        <div class="system-config-setting-action">
          <button v-if="row.error" type="button" class="button ghost mini" @click="row.onRetry">重新加载</button>
          <button
            v-else
            type="button" class="button ghost mini" :disabled="row.loading"
            @click="row.onAction"
          >
            <Icon name="edit" :size="13" />
            编辑
          </button>
        </div>
      </div>
    </div>

    <p class="system-config-footnote">
      部署级环境变量不在管理台展示，请通过部署配置调整，重启网关后生效。
    </p>

    <ModelTestSettingsModal v-if="showModelTest" @close="showModelTest = false" @saved="loadModelTest" />
    <UpstreamRequestSettingsModal v-if="showUpstream" @close="showUpstream = false" @saved="onUpstreamSaved" />
    <SystemPromptSettingsModal v-if="showSystemPrompt" @close="showSystemPrompt = false" @saved="loadSystemPrompt" />
    <WebSearchSettingsModal v-if="showWebSearch" @close="showWebSearch = false" @saved="loadWebSearch" />
    <ChannelEgressSettingsModal
      v-if="showChannelEgress"
      @close="showChannelEgress = false"
      @saved="loadChannelEgress"
    />
    <KeyAutoDisableSettingsModal v-if="showAutoDisable" @close="showAutoDisable = false" @saved="loadAutoDisable" />
    <NotificationSettingsModal
      v-if="showNotification"
      @close="showNotification = false"
      @saved="loadNotification"
    />
    <RetentionSettingsModal
      v-if="showRetention && cards.retention.data"
      :policy="cards.retention.data"
      @close="showRetention = false"
      @saved="loadRetention"
    />
  </section>
</template>

<style scoped>
.system-config-settings {
  overflow: hidden;
}

.system-config-setting {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) minmax(0, 1.1fr) 132px;
  align-items: center;
  gap: 8px 18px;
  min-height: 78px;
  padding: 15px 20px;
}

.system-config-setting + .system-config-setting {
  border-top: 1px solid var(--glass-line);
}

.system-config-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--brand) 12%, var(--glass-line));
  border-radius: 11px;
  background: var(--info-soft);
  color: var(--brand);
}

.system-config-setting-copy,
.system-config-setting-state {
  min-width: 0;
}

.system-config-setting-copy h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.system-config-setting-copy p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.system-config-setting-state {
  display: grid;
  gap: 3px;
}

.system-config-setting-state strong {
  display: block;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.system-config-setting-state small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.system-config-setting-state .text-danger {
  overflow-wrap: anywhere;
  font-size: 12px;
  line-height: 1.55;
}

.system-config-setting-muted {
  color: var(--muted);
  font-size: 12px;
}

.system-config-setting-action {
  display: flex;
  justify-content: flex-end;
}

.system-config-setting-action .button {
  min-height: 32px;
}

.system-config-footnote {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 1024px) {
  .system-config-setting {
    grid-template-columns: auto minmax(0, 1fr) auto;
    grid-template-areas:
      "icon copy action"
      "state state state";
    align-items: start;
  }

  .system-config-setting .system-config-icon {
    grid-area: icon;
  }

  .system-config-setting-copy {
    grid-area: copy;
  }

  .system-config-setting-state {
    grid-area: state;
    padding-left: 54px;
  }

  .system-config-setting-action {
    grid-area: action;
  }
}

@media (max-width: 640px) {
  .system-config-setting {
    grid-template-columns: auto minmax(0, 1fr) auto;
    grid-template-areas:
      "icon copy action"
      "state state state";
    gap: 10px 14px;
    padding: 14px 16px;
  }

  .system-config-setting-state {
    padding-left: 0;
  }

  .system-config-setting-action .button {
    min-height: 38px;
  }

  .system-config-setting-state strong {
    white-space: normal;
  }
}

@media (max-width: 360px) {
  .system-config-setting {
    grid-template-columns: auto minmax(0, 1fr);
    grid-template-areas:
      "icon copy"
      "state state"
      "action action";
  }

  .system-config-setting-action {
    justify-content: flex-end;
  }
}
</style>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/vue'
import { api } from '../api'
import { notify } from '../toast'
import type { RequestLog, RequestLogAttempt } from '../types/api'
import CopyButton from './CopyButton.vue'
import CursorLoadMore from './CursorLoadMore.vue'
import Icon from './Icon.vue'
import InfoPopover from './InfoPopover.vue'
import ProtocolEndpoints from './ProtocolEndpoints.vue'
import ProtocolIcon from './ProtocolIcon.vue'
import { vScrollHint } from '../directives/scrollHint'
import TablePlaceholder from './TablePlaceholder.vue'
import ErrorState from './ErrorState.vue'
import FilterSelect from './FilterSelect.vue'
import { useAnchoredPanel } from '../composables/useAnchoredPanel'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { createCursorPager } from '../utils/cursorPager'
import { requestModeClass, requestModeLabel } from '../utils/requestMode'
import {
  attemptFailureReason,
  attemptHasError,
  attemptResultLabel,
  protocolConversion,
  protocolEndpoint,
  protocolLabel,
  reasoningEffortLabel,
  reportedModelStatus,
  tokenUsageLabel,
  tokenUsageState,
} from '../utils/requestLogDisplay'
import { filterRequestLogs, requestLogFilterOptions } from '../utils/requestLogSearch'
import {
  formatTime,
  formatDuration,
  formatNumber as formatTokens,
  formatTokensCompact,
  formatCost as formatCostUtil,
  formatUnitPrice,
  formatPriceSummary,
  formatPriceSnapshot,
} from '../utils/format'

const { items: logs, pageSize, loading, loadingMore, error, hasMore, load, loadMore, setPageSize } = createCursorPager<RequestLog>({
  fetchPage: (options) => api.listRequestLogs(options),
})
const query = ref('')
const channelFilter = ref('')
const modelFilter = ref('')
const statusFilter = ref('')
const autoRefreshOptions = [
  { label: '关闭', value: 0 },
  { label: '每 5 秒', value: 5_000 },
  { label: '每 10 秒', value: 10_000 },
  { label: '每 30 秒', value: 30_000 },
] as const
const autoRefreshStorageKey = 'prism-llm.logs.autoRefreshIntervalMs'
function readAutoRefreshInterval(): number {
  try {
    const raw = localStorage.getItem(autoRefreshStorageKey) ?? localStorage.getItem('llm-gateway.logs.autoRefreshIntervalMs')
    const interval = Number(raw)
    return autoRefreshOptions.some(option => option.value === interval) ? interval : 0
  } catch {
    return 0
  }
}
const autoRefreshInterval = ref(readAutoRefreshInterval())
const autoRefreshing = ref(false)
const { anchor: refreshMenuAnchor, panelStyle: refreshMenuStyle, place: placeRefreshMenu } = useAnchoredPanel(220, 300)
const filterOptions = computed(() => requestLogFilterOptions(logs.value))
const hasFilters = computed(() => Boolean(channelFilter.value || modelFilter.value || statusFilter.value))
function clearFilters() {
  channelFilter.value = ''
  modelFilter.value = ''
  statusFilter.value = ''
}
type LogColumnId = 'time' | 'request' | 'model' | 'channel' | 'clientIp' | 'clientUa' | 'status' | 'latency' | 'usage' | 'cost'
const logColumns: Array<{ id: LogColumnId; label: string }> = [
  { id: 'time', label: '时间' },
  { id: 'request', label: '请求 ID' },
  { id: 'model', label: '请求模型' },
  { id: 'channel', label: '渠道与上游模型' },
  { id: 'clientIp', label: '来源 IP' },
  { id: 'clientUa', label: 'User-Agent' },
  { id: 'status', label: '状态与错误' },
  { id: 'latency', label: '耗时' },
  { id: 'usage', label: 'Token 用量' },
  { id: 'cost', label: '费用' },
]
const defaultLogColumns: Record<LogColumnId, boolean> = { time: true, request: true, model: true, channel: true, clientIp: false, clientUa: false, status: true, latency: true, usage: true, cost: true }
function readLogColumns(): Record<LogColumnId, boolean> {
  try {
    const raw = localStorage.getItem('prism-llm.logs.columns') ?? localStorage.getItem('llm-gateway.logs.columns')
    const parsed = JSON.parse(raw || '{}')
    return { ...defaultLogColumns, ...Object.fromEntries(logColumns.map(({ id }) => [id, parsed[id] !== false])) } as Record<LogColumnId, boolean>
  } catch {
    return { ...defaultLogColumns }
  }
}
function readLogDensity(): 'comfortable' | 'compact' {
  try {
    const raw = localStorage.getItem('prism-llm.logs.density') ?? localStorage.getItem('llm-gateway.logs.density')
    return raw === 'compact' ? 'compact' : 'comfortable'
  } catch { return 'comfortable' }
}
const visibleLogColumns = ref<Record<LogColumnId, boolean>>(readLogColumns())
const logDensity = ref<'comfortable' | 'compact'>(readLogDensity())
function logColumnVisible(id: LogColumnId): boolean { return visibleLogColumns.value[id] }
function toggleLogColumn(id: LogColumnId): void {
  if (visibleLogColumns.value[id] && Object.values(visibleLogColumns.value).filter(Boolean).length <= 1) return
  visibleLogColumns.value[id] = !visibleLogColumns.value[id]
  try { localStorage.setItem('prism-llm.logs.columns', JSON.stringify(visibleLogColumns.value)) } catch { /* ignore storage failures */ }
}
function setLogDensity(value: 'comfortable' | 'compact'): void {
  logDensity.value = value
  try { localStorage.setItem('prism-llm.logs.density', value) } catch { /* ignore storage failures */ }
}
const selected = ref<RequestLog | null>(null)
const detailDialog = ref<HTMLDivElement | null>(null)
const tableScroll = ref<HTMLElement | null>(null)
let detailTrigger: HTMLElement | null = null

const stageLabels: Record<string, string> = {
  request: '请求',
  routing: '路由',
  upstream: '上游',
  streaming: '流式',
  client: '客户端',
}

function attemptProtocolLabel(attempt: RequestLogAttempt, log: RequestLog): string {
  // 出站协议缺失时保持未知，不要把入站协议当成出站协议展示。
  return protocolLabel(attempt.upstreamProtocol || log.upstreamProtocol)
}

const selectedConversion = computed(() =>
  protocolConversion(
    selected.value?.protocol,
    selected.value?.upstreamProtocol,
    selected.value?.stream,
    selected.value?.upstreamAttempts,
  ),
)
const selectedInboundEndpoint = computed(() =>
  protocolEndpoint(selected.value?.protocol, selected.value?.stream),
)
const selectedOutboundEndpoint = computed(() =>
  selected.value?.upstreamProtocol
    ? protocolEndpoint(selected.value.upstreamProtocol, selected.value?.stream)
    : null,
)

function attemptStatusClass(attempt: RequestLogAttempt): string {
  if (attempt.skipped) return 'neutral'
  if (attemptHasError(attempt)) return 'danger'
  if (attempt.statusCode == null) return 'neutral'
  return statusClass(attempt.statusCode)
}

function attemptStatusAriaLabel(attempt: RequestLogAttempt, hasFollowingAttempt: boolean): string {
  if (attempt.skipped) return `路由跳过，${attemptFailureReason(attempt)}，未发起上游请求`
  const status = attempt.statusCode == null ? '无上游 HTTP 状态码' : `上游 HTTP 状态码 ${attempt.statusCode}`
  const errorStatus = attempt.upstreamErrorStatusCode == null ? '' : `，错误状态码 ${attempt.upstreamErrorStatusCode}`
  return `${status}${errorStatus}，${attemptResultLabel(attempt, hasFollowingAttempt)}`
}

function reportedModelTitle(log: RequestLog): string {
  const report = reportedModelStatus(log)
  if (!report) return ''
  if (report.state === 'unknown') return '上游未返回自报模型，或该日志早于此功能，无法核验'
  if (report.state === 'match') return '上游自报模型：' + report.reportedModel + '（与请求模型一致）'
  return '上游自报模型：' + report.reportedModel + '；实际请求模型：' + log.upstreamModel
}

// 历史日志没有逐次明细（V39 回填为空数组）。不要用汇总字段伪造一条尝试记录：
// totalMs 是整个请求耗时，upstreamAttempts 也可能是多次调用。
const selectedAttempts = computed<RequestLogAttempt[]>(() => selected.value?.attempts || [])
const hasAttemptDetail = computed(() => selectedAttempts.value.length > 0)
const skippedAttemptCount = computed(() => selectedAttempts.value.filter((attempt) => attempt.skipped).length)

const filtered = computed(() => {
  return filterRequestLogs(logs.value, query.value, { channelId: channelFilter.value, model: modelFilter.value, status: statusFilter.value })
})

const errorCount = computed(() => logs.value.filter((log) => log.statusCode >= 400).length)
const averageLatency = computed(() => {
  if (logs.value.length === 0) return '—'
  const total = logs.value.reduce((sum, log) => sum + log.totalMs, 0)
  return `${Math.round(total / logs.value.length)} ms`
})

async function refresh() {
  if (loading.value || loadingMore.value) return
  const loaded = await load()
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
  if (loaded) notify('请求日志已刷新')
}

async function autoRefresh(): Promise<void> {
  autoRefreshing.value = true
  try {
    await load()
  } finally {
    autoRefreshing.value = false
  }
}

function setAutoRefreshInterval(interval: number): void {
  autoRefreshInterval.value = interval
  try {
    localStorage.setItem(autoRefreshStorageKey, String(interval))
  } catch { /* ignore storage failures */ }
}

useAutoRefresh(autoRefreshInterval, () => { void autoRefresh() }, () => loading.value || loadingMore.value)

watch(pageSize, async () => {
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
})

watch([query, channelFilter, modelFilter, statusFilter], async () => {
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
})

function formatCostAmount(log: RequestLog): string | null {
  return formatCostUtil(log.costAmount, '')
}

function formatThroughput(log: RequestLog): string {
  if (log.outputTokens == null || log.totalMs <= 0) return '—'
  return (log.outputTokens / (log.totalMs / 1000)).toFixed(1)
}

function formatCacheHit(log: RequestLog): string {
  if (log.cachedTokens == null || log.inputTokens == null || log.inputTokens <= 0) return '—'
  return `${Math.round((log.cachedTokens / log.inputTokens) * 100)}%`
}

function cacheTitle(log: RequestLog): string {
  if (log.cachedTokens == null) return '上游未返回缓存用量'
  return `缓存 ${formatTokens(log.cachedTokens)} / 输入 ${formatTokens(log.inputTokens)}`
}

function costTitle(log: RequestLog): string {
  if (log.costAmount == null) {
    if (!log.usageKnown) return '用量未知，费用无法计算'
    return log.channelId && log.upstreamModel ? '该渠道和上游模型尚未配置计费单价' : '没有可计费的路由信息'
  }
  return formatPriceSummary(log)
}

function usageDetailTitle(log: RequestLog): string {
  return `推理 ${formatTokens(log.reasoningTokens)} · 输出速率 ${formatThroughput(log)} tok/s · ${cacheTitle(log)}`
}

function usageStateClass(log: RequestLog): string {
  return `usage-${tokenUsageState(log)}`
}

function stageLabel(stage: string | null): string {
  return stage ? stageLabels[stage] || stage : '—'
}

function statusClass(code: number): string {
  if (code >= 500 || code === 499) return 'danger'
  if (code >= 400) return 'warning'
  return 'success'
}

function openDetail(log: RequestLog, event: MouseEvent): void {
  detailTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  selected.value = log
}

function closeDetail(): void {
  selected.value = null
}

function onDetailKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDetail()
    return
  }
  if (event.key !== 'Tab' || !detailDialog.value) return
  const focusables = Array.from(
    detailDialog.value.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  ).filter((element) => !element.hasAttribute('disabled'))
  if (focusables.length === 0) {
    event.preventDefault()
    detailDialog.value.focus()
    return
  }
  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  const active = document.activeElement
  if (!detailDialog.value.contains(active)) {
    event.preventDefault()
    first.focus()
    return
  }
  if (event.shiftKey && (active === first || active === detailDialog.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(selected, async (value) => {
  if (value) {
    await nextTick()
    const autofocus = detailDialog.value?.querySelector<HTMLElement>('[data-autofocus]')
    ;(autofocus || detailDialog.value)?.focus()
    return
  }
  const trigger = detailTrigger
  detailTrigger = null
  trigger?.focus()
})

onMounted(load)
</script>

<template>
  <section class="page-stack page-compact">
    <div class="logs-overview">
      <dl class="list-summary logs-summary" aria-label="当前已加载日志统计">
        <div><dt>已加载</dt><dd>{{ logs.length }}</dd></div>
        <div><dt>异常响应</dt><dd :class="errorCount ? 'text-danger' : 'text-success'" title="HTTP 4xx / 5xx / 499">{{ errorCount }}</dd></div>
        <div><dt>平均总耗时</dt><dd title="当前已加载记录的简单平均">{{ averageLatency }}</dd></div>
      </dl>
      <div class="logs-toolbar-meta logs-page-actions">
        <Menu as="div" class="logs-refresh-menu">
          <MenuButton as="template">
            <button ref="refreshMenuAnchor" type="button" class="button ghost refresh-button logs-refresh" :aria-busy="loading" aria-label="请求日志刷新选项" @click="placeRefreshMenu" @keydown.down="placeRefreshMenu" @keydown.up="placeRefreshMenu" @keydown.enter="placeRefreshMenu" @keydown.space="placeRefreshMenu">
              <Icon name="refresh" :size="16" class="logs-refresh-icon" :class="{ 'is-spinning': autoRefreshInterval > 0 || loading }" />
              <span class="logs-refresh-label"><span>{{ loading ? '刷新中…' : '刷新' }}</span><span v-if="autoRefreshInterval" class="logs-refresh-interval">{{ autoRefreshInterval / 1000 }}s</span></span>
              <Icon name="chevron" :size="14" />
            </button>
          </MenuButton>
          <Teleport to="body">
            <MenuItems class="floating-menu logs-refresh-menu-panel" :style="refreshMenuStyle" aria-label="请求日志刷新选项">
              <MenuItem v-slot="{ active }" :disabled="loading || loadingMore" as="template">
                <button type="button" class="floating-menu-item" :class="{ active }" :disabled="loading || loadingMore" @click="refresh">
                  <Icon name="refresh" :size="16" /><span>立即刷新</span>
                </button>
              </MenuItem>
              <div class="logs-refresh-menu-heading" aria-hidden="true">自动刷新</div>
              <MenuItem v-for="option in autoRefreshOptions" :key="option.value" v-slot="{ active }" as="template">
                <button type="button" :aria-label="'自动刷新：' + option.label + (autoRefreshInterval === option.value ? '（已选中）' : '')" class="floating-menu-item logs-refresh-option" :class="{ active, 'is-selected': autoRefreshInterval === option.value }" @click="setAutoRefreshInterval(option.value)">
                  <span>{{ option.label }}</span><Icon v-if="autoRefreshInterval === option.value" name="check" :size="16" />
                </button>
              </MenuItem>
            </MenuItems>
          </Teleport>
        </Menu>
        <details class="table-view-menu">
          <summary class="button ghost"><Icon name="settings" :size="16" /> 表格视图</summary>
          <div class="table-view-popover">
            <strong>显示列</strong>
            <label v-for="column in logColumns" :key="column.id"><input type="checkbox" :checked="logColumnVisible(column.id)" @change="toggleLogColumn(column.id)" /> {{ column.label }}</label>
            <div class="table-view-divider"></div>
            <strong>行密度</strong>
            <div class="density-switch" role="group" aria-label="日志行密度">
              <button type="button" :class="{ active: logDensity === 'comfortable' }" @click="setLogDensity('comfortable')">舒适</button>
              <button type="button" :class="{ active: logDensity === 'compact' }" @click="setLogDensity('compact')">紧凑</button>
            </div>
          </div>
        </details>
      </div>
    </div>

    <ErrorState v-if="error" :message="error" :busy="loading" @retry="refresh" />

    <div class="panel table-panel">
      <div class="table-toolbar logs-toolbar">
        <div class="logs-search-group">
          <label class="search-box logs-search">
            <Icon name="search" :size="17" />
            <input
              v-model="query"
              type="search"
              aria-label="搜索已加载的请求日志"
              aria-describedby="logs-filter-scope"
              placeholder="搜索 ID、模型、渠道、状态码或错误码"
            />
          </label>
          <span id="logs-filter-scope" class="muted small logs-filter-scope">仅筛选已加载记录 · 显示 {{ filtered.length }} / {{ logs.length }} 条</span>
        </div>
        <div class="logs-filter-controls" role="group" aria-label="请求日志筛选">
          <FilterSelect v-model="channelFilter" label="渠道" searchable :options="[{ value: '', label: '全部渠道' }, ...filterOptions.channels]" />
          <FilterSelect v-model="modelFilter" label="请求模型" searchable :options="[{ value: '', label: '全部请求模型' }, ...filterOptions.models]" />
          <FilterSelect v-model="statusFilter" label="状态" :options="[{ value: '', label: '全部状态' }, { value: 'success', label: 'HTTP 成功（2xx）' }, { value: 'error', label: 'HTTP 异常（4xx / 5xx）' }, ...filterOptions.statuses]" />
          <button v-if="hasFilters" type="button" class="filter-reset" @click="clearFilters">清除筛选</button>
        </div>
      </div>

      <TablePlaceholder
        v-if="(loading && !autoRefreshing) || logs.length === 0"
        :loading="loading"
        loading-text="正在读取请求日志…"
        :empty="logs.length === 0"
        icon="logs"
        title="还没有请求日志"
        desc="通过网关发起一次 /v1/chat/completions 请求后，这里会出现记录。"
      />
      <TablePlaceholder
        v-else-if="filtered.length === 0"
        :empty="true"
        compact
        title="没有匹配的请求"
        desc="调整关键词或筛选条件，或加载更多记录后重试。"
      />
      <div v-else ref="tableScroll" v-scroll-hint class="table-scroll table-scroll-paged">
        <table class="data-table logs-table" :class="`logs-density-${logDensity}`">
          <thead>
            <tr>
              <th v-if="logColumnVisible('time')" class="time-col log-col-time">时间</th>
              <th v-if="logColumnVisible('request')" class="log-col-request">请求 ID</th>
              <th v-if="logColumnVisible('model')" class="log-col-model">请求模型 / 协议 / 模式</th>
              <th v-if="logColumnVisible('channel')" class="log-col-channel">渠道 / 上游模型</th>
              <th v-if="logColumnVisible('clientIp')" class="log-col-client-ip">来源 IP</th>
              <th v-if="logColumnVisible('clientUa')" class="log-col-client-ua">User-Agent</th>
              <th v-if="logColumnVisible('status')" class="log-col-status">状态 / 错误</th>
              <th v-if="logColumnVisible('latency')" class="log-col-latency">首 token / 总耗时</th>
              <th v-if="logColumnVisible('usage')" class="log-col-usage">Token 用量</th>
              <th v-if="logColumnVisible('cost')" class="log-col-cost">费用</th>
              <th class="actions-col"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in filtered" :key="log.requestId">
              <td v-if="logColumnVisible('time')" class="time-col log-col-time muted nowrap">{{ formatTime(log.createdAt, { style: 'medium' }) }}</td>
              <td v-if="logColumnVisible('request')" class="log-col-request">
                <div class="request-id-cell">
                  <code class="request-id" :title="log.requestId">{{ log.requestId.slice(0, 12) }}…</code>
                  <CopyButton :text="log.requestId" title="复制请求 ID" variant="list" :size="13" />
                </div>
              </td>
              <td v-if="logColumnVisible('model')" class="log-col-model">
                <div class="stacked-cell">
                  <span class="badge info">{{ log.alias }}</span>
                  <div class="request-protocol">
                    <ProtocolEndpoints
                      :inbound="log.protocol"
                      :outbound="log.upstreamProtocol"
                      :stream="log.stream"
                      :upstream-attempts="log.upstreamAttempts"
                    />
                  </div>
                  <div class="request-meta request-mode-row">
                    <span class="mode-badge" :class="`mode-${requestModeClass(log.stream)}`">{{ requestModeLabel(log.stream) }}</span>
                    <span class="reasoning-badge">
                      推理 {{ reasoningEffortLabel(log.reasoningEffort) }}
                    </span>
                  </div>
                </div>
              </td>
              <td v-if="logColumnVisible('channel')" class="log-col-channel">
                <div class="stacked-cell channel-cell">
                  <div class="channel-name-cell" :title="log.channelName || log.channelId || ''">{{ log.channelName || (log.channelId ? log.channelId.slice(0, 8) : '—') }}</div>
                  <div v-if="log.channelKeyName" class="channel-key-chip" :title="`上游密钥：${log.channelKeyName}`">
                    <Icon name="keys" :size="10" />
                    <span>{{ log.channelKeyName }}</span>
                  </div>
                  <div class="upstream-model-line">
                    <span class="upstream-model-chip mono" :title="log.upstreamModel || '未记录上游模型'">{{ log.upstreamModel || '—' }}</span>
                    <InfoPopover
                      v-if="reportedModelStatus(log)"
                      label="上游模型核验"
                      :tone="reportedModelStatus(log)?.state === 'match' ? 'success' : reportedModelStatus(log)?.state === 'mismatch' ? 'danger' : 'warning'"
                    >
                      <template #trigger>
                        <Icon v-if="reportedModelStatus(log)?.state === 'match'" name="check" :size="12" />
                        <Icon v-else-if="reportedModelStatus(log)?.state === 'mismatch'" name="close" :size="12" />
                        <Icon v-else name="info" :size="12" />
                      </template>
                      <dl class="model-check-values">
                        <dt>请求上游模型</dt><dd>{{ log.upstreamModel || '—' }}</dd>
                        <dt>上游自报模型</dt><dd>{{ reportedModelStatus(log)?.reportedModel || '未返回' }}</dd>
                      </dl>
                      <p class="model-check-result" :class="reportedModelStatus(log)?.state === 'match' ? 'text-success' : reportedModelStatus(log)?.state === 'mismatch' ? 'text-danger' : 'text-warning'">{{ reportedModelStatus(log)?.state === 'match' ? '与请求模型一致' : reportedModelStatus(log)?.state === 'mismatch' ? '与请求模型不一致' : '暂时无法核验' }}</p>
                      <p v-if="reportedModelStatus(log)?.state === 'unknown'" class="muted small">上游未返回自报模型，或该日志早于此功能。</p>
                    </InfoPopover>
                  </div>
                  <span v-if="log.upstreamAttempts != null && log.upstreamAttempts > 1" class="badge warning attempt-chip">尝试 {{ log.upstreamAttempts }} 次</span>
                </div>
              </td>
              <td v-if="logColumnVisible('clientIp')" class="log-col-client-ip">
                <div class="mono small client-ip-line" :title="log.clientIp || '未记录 IP'">{{ log.clientIp || '—' }}</div>
              </td>
              <td v-if="logColumnVisible('clientUa')" class="log-col-client-ua">
                <div v-if="log.clientUserAgent" class="muted small client-ua-line" :title="log.clientUserAgent">{{ log.clientUserAgent }}</div>
                <div v-else class="muted small">—</div>
              </td>
              <td v-if="logColumnVisible('status')" class="log-col-status">
                <div class="stacked-cell">
                  <span class="badge" :class="statusClass(log.statusCode)">{{ log.statusCode }}</span>
                  <template v-if="log.errorCode">
                    <span class="badge warning" :title="log.errorCode">{{ log.errorCode }}</span>
                    <span v-if="log.failureStage" class="stage-text">{{ stageLabel(log.failureStage) }}阶段</span>
                  </template>
                </div>
              </td>
              <td v-if="logColumnVisible('latency')" class="nowrap log-col-latency">
                <div class="duration-cell">
                  <span class="duration-first">首 {{ formatDuration(log.firstTokenMs) }}</span>
                  <span class="muted small">总 {{ formatDuration(log.totalMs) }}</span>
                </div>
              </td>
              <td v-if="logColumnVisible('usage')" class="usage-cell log-col-usage" :class="usageStateClass(log)">
                <div class="usage-cell-body">
                  <template v-if="log.usageKnown">
                    <span :title="`输入 ${formatTokens(log.inputTokens)} · 输出 ${formatTokens(log.outputTokens)} · 总 ${formatTokens(log.totalTokens)}`">
                      输入 {{ formatTokensCompact(log.inputTokens) }} · 输出 {{ formatTokensCompact(log.outputTokens) }} · 总
                      {{ formatTokensCompact(log.totalTokens) }}
                    </span>
                    <span class="muted" :title="usageDetailTitle(log)">
                      推理 {{ formatTokensCompact(log.reasoningTokens) }} · 速率 {{ formatThroughput(log) }} tok/s · 缓存
                      {{ formatCacheHit(log) }}
                    </span>
                  </template>
                  <span v-else class="usage-state-label">{{ tokenUsageLabel(log) }}</span>
                </div>
              </td>
              <td v-if="logColumnVisible('cost')" class="nowrap log-col-cost" :title="costTitle(log)">
                <span v-if="log.costAmount != null" class="cost-line log-cost">
                  <span class="cost-amount">{{ formatCostAmount(log) }}</span>
                  <span class="cost-currency">{{ log.costCurrency || '—' }}</span>
                </span>
                <span v-else-if="log.usageKnown && log.channelId && log.upstreamModel" class="muted">未配置</span>
                <span v-else class="muted">—</span>
              </td>
              <td class="actions-col">
                <button type="button" class="button subtle mini" @click="openDetail(log, $event)">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <CursorLoadMore
        :loaded="logs.length"
        :has-more="hasMore"
        :loading="loading"
        :loading-more="loadingMore"
        :page-size="pageSize"
        @load-more="loadMore"
        @page-size-change="setPageSize"
      />
    </div>

    <Teleport to="body">
      <div v-if="selected" class="modal-backdrop" @click.self="closeDetail">
        <div
          ref="detailDialog"
          class="modal wide"
          role="dialog"
          aria-modal="true"
          aria-labelledby="request-detail-title"
          tabindex="-1"
          @keydown="onDetailKeydown"
        >
          <div class="modal-head">
            <div>
              <h2 id="request-detail-title">请求详情</h2>
              <p class="muted small">{{ formatTime(selected.createdAt) }}</p>
            </div>
            <button type="button" class="icon-button" title="关闭" data-autofocus @click="closeDetail">
              <Icon name="close" :size="18" />
            </button>
          </div>

          <div class="modal-body detail-stack">
            <div class="detail-id">
              <span>请求 ID</span>
              <div class="request-id-cell">
                <code class="detail-id-value">{{ selected.requestId }}</code>
                <CopyButton :text="selected.requestId" title="复制请求 ID" :size="14" />
              </div>
            </div>

            <div class="detail-id">
              <span>Trace ID</span>
              <div class="request-id-cell">
                <code class="detail-id-value">{{ selected.traceId || '—' }}</code>
                <CopyButton v-if="selected.traceId" :text="selected.traceId" title="复制 Trace ID" :size="14" />
              </div>
            </div>

            <div class="detail-grid">
              <div class="detail-item">
                <span>状态</span>
                <strong><span class="badge" :class="statusClass(selected.statusCode)">{{ selected.statusCode }}</span></strong>
              </div>
              <div class="detail-item detail-protocol-item">
                <span>协议</span>
                <template v-if="selectedConversion.converted">
                  <div class="detail-protocol-flow" :title="selectedConversion.title">
                    <span class="detail-protocol-node">
                      <ProtocolIcon v-if="selectedInboundEndpoint.known" :protocol="selectedInboundEndpoint.value" :size="14" />
                      <strong>{{ selectedInboundEndpoint.shortName }}</strong>
                    </span>
                    <span class="detail-protocol-arrow" aria-hidden="true">→</span>
                    <span class="detail-protocol-node">
                      <ProtocolIcon v-if="selectedOutboundEndpoint?.known" :protocol="selectedOutboundEndpoint.value" :size="14" />
                      <strong>{{ selectedOutboundEndpoint?.shortName }}</strong>
                    </span>
                    <span class="badge warning small">已转换</span>
                  </div>
                  <span class="mono muted small detail-endpoint">
                    {{ selectedInboundEndpoint.endpointPath || '—' }} → {{ selectedOutboundEndpoint?.endpointPath || '—' }}
                  </span>
                </template>
                <template v-else>
                  <div class="detail-protocol-header" :title="selectedConversion.title">
                    <template v-if="!selectedInboundEndpoint.value && selectedOutboundEndpoint">
                      <ProtocolIcon v-if="selectedOutboundEndpoint.known" :protocol="selectedOutboundEndpoint.value" :size="16" />
                      <strong>{{ selectedOutboundEndpoint.label }}</strong>
                      <span class="badge neutral small">出站协议</span>
                    </template>
                    <template v-else>
                      <ProtocolIcon v-if="selectedInboundEndpoint.known" :protocol="selectedInboundEndpoint.value" :size="16" />
                      <strong>{{ selectedInboundEndpoint.label }}</strong>
                      <span v-if="!selectedConversion.outbound" class="badge neutral small">{{ (selected.upstreamAttempts ?? 0) > 0 ? '出站协议未知' : '未发起上游请求' }}</span>
                    </template>
                  </div>
                  <span v-if="!selectedInboundEndpoint.value && selectedOutboundEndpoint?.endpointPath" class="mono muted small detail-endpoint">
                    {{ selectedOutboundEndpoint.endpointPath }}
                  </span>
                </template>
              </div>
              <div class="detail-item">
                <span>模式</span>
                <strong><span class="mode-badge" :class="`mode-${requestModeClass(selected.stream)}`">{{ requestModeLabel(selected.stream) }}</span></strong>
              </div>
              <div class="detail-item">
                <span>失败阶段</span>
                <strong>{{ stageLabel(selected.failureStage) }}</strong>
              </div>
              <div class="detail-item">
                <span>错误码</span>
                <strong class="mono">{{ selected.errorCode || '—' }}</strong>
              </div>
              <div class="detail-item">
                <span>上游错误码</span>
                <strong class="mono">{{ selected.upstreamErrorCode || '—' }}</strong>
              </div>
              <div class="detail-item">
                <span>请求模型</span>
                <strong>{{ selected.alias }}</strong>
              </div>
              <div class="detail-item">
                <span>渠道名称</span>
                <strong>{{ selected.channelName || '—' }}</strong>
              </div>
              <div class="detail-item">
                <span>上游密钥</span>
                <strong>{{ selected.channelKeyName || '—' }}</strong>
              </div>
              <div class="detail-item">
                <span>渠道 ID</span>
                <div v-if="selected.channelId" class="detail-value-copy">
                  <strong class="mono detail-wrap">{{ selected.channelId }}</strong>
                  <CopyButton :text="selected.channelId" title="复制渠道 ID" :size="13" />
                </div>
                <strong v-else class="muted">—</strong>
              </div>
              <div class="detail-item">
                <span>上游尝试</span>
                <strong>{{ selected.upstreamAttempts ?? '—' }}</strong>
              </div>
              <div class="detail-item">
                <span>上游模型</span>
                <strong class="mono detail-wrap">{{ selected.upstreamModel || '—' }}</strong>
              </div>
              <div class="detail-item">
                <span>调用方密钥</span>
                <strong>{{ selected.clientKeyName || (selected.clientKeyId ? '未命名密钥' : '—') }}</strong>
              </div>
              <div class="detail-item">
                <span>调用方密钥 ID</span>
                <div v-if="selected.clientKeyId" class="detail-value-copy">
                  <strong class="mono detail-wrap">{{ selected.clientKeyId }}</strong>
                  <CopyButton :text="selected.clientKeyId" title="复制调用方密钥 ID" :size="13" />
                </div>
                <strong v-else class="muted">—</strong>
              </div>
              <div class="detail-item">
                <span>来源 IP</span>
                <strong v-if="selected.clientIp" class="mono detail-wrap" :title="selected.clientIp">{{ selected.clientIp }}</strong>
                <strong v-else class="muted">—</strong>
              </div>
              <div class="detail-item">
                <span>User-Agent</span>
                <strong v-if="selected.clientUserAgent" class="mono detail-wrap" :title="selected.clientUserAgent">{{ selected.clientUserAgent }}</strong>
                <strong v-else class="muted">—</strong>
              </div>
              <div class="detail-item">
                <span>首 token</span>
                <strong>{{ formatDuration(selected.firstTokenMs) }}</strong>
              </div>
              <div class="detail-item">
                <span>总耗时</span>
                <strong>{{ formatDuration(selected.totalMs) }}</strong>
              </div>
              <div class="detail-item">
                <span>输入 / 输出</span>
                <strong v-if="selected.usageKnown">{{ formatTokens(selected.inputTokens) }} / {{ formatTokens(selected.outputTokens) }}</strong>
                <strong v-else class="muted">{{ tokenUsageLabel(selected) }}</strong>
              </div>
              <div class="detail-item">
                <span>推理 / 总量</span>
                <strong v-if="selected.usageKnown">{{ formatTokens(selected.reasoningTokens) }} / {{ formatTokens(selected.totalTokens) }}</strong>
                <strong v-else class="muted">{{ tokenUsageLabel(selected) }}</strong>
              </div>
              <div class="detail-item">
                <span>推理强度</span>
                <strong><span class="reasoning-badge">{{ reasoningEffortLabel(selected.reasoningEffort) }}</span></strong>
              </div>
              <div class="detail-item">
                <span>缓存命中</span>
                <strong :title="cacheTitle(selected)">
                  {{ formatCacheHit(selected) }}
                  <span v-if="selected.cachedTokens != null" class="muted small">（{{ formatTokens(selected.cachedTokens) }}）</span>
                </strong>
              </div>
              <div class="detail-item">
                <span>输出速率</span>
                <strong>{{ formatThroughput(selected) }} tok/s</strong>
              </div>
              <div class="detail-item">
                <span>费用</span>
                <strong v-if="selected.costAmount != null" class="cost-line detail-cost">
                  <span class="cost-amount">{{ formatCostAmount(selected) }}</span>
                  <span class="cost-currency">{{ selected.costCurrency || '—' }}</span>
                </strong>
                <strong v-else>{{ selected.usageKnown ? '未配置' : '—' }}</strong>
              </div>
              <div v-if="selected.costAmount != null" class="detail-block detail-price-snapshot">
                <span>
                  计费单价快照 · {{ formatPriceSummary(selected) }}
                </span>
                <dl class="detail-price-list">
                  <div v-for="item in formatPriceSnapshot(selected)" :key="item.label" class="detail-price-row">
                    <dt>{{ item.label }}</dt>
                    <dd>{{ item.value }}</dd>
                  </div>
                </dl>
              </div>
            </div>

            <div class="detail-block">
              <span>错误信息</span>
              <p v-if="selected.errorMessage" class="detail-message">{{ selected.errorMessage }}</p>
              <p v-else class="muted">无错误信息</p>
            </div>

            <div class="detail-block">
              <span v-if="hasAttemptDetail">
                尝试记录（{{ selectedAttempts.length }} 条<span v-if="skippedAttemptCount"> · 跳过 {{ skippedAttemptCount }}</span> · 上游调用 {{ selected.upstreamAttempts ?? selectedAttempts.filter((attempt) => !attempt.skipped).length }} 次）
              </span>
              <span v-else>尝试记录</span>
              <p v-if="!hasAttemptDetail" class="muted small">无尝试明细</p>
              <div v-else class="attempt-list">
                <div v-for="(attempt, attemptIndex) in selectedAttempts" :key="attempt.index + '-' + attemptIndex" class="attempt-row">
                  <div class="attempt-head">
                    <span class="attempt-index">{{ attempt.skipped ? '跳过' : '#' + attempt.index }}</span>
                    <span v-if="attempt.skipped" class="badge attempt-status neutral" aria-label="路由跳过，未发起上游请求">跳过</span>
                    <span
                      v-else
                      class="badge attempt-status"
                      :class="attemptStatusClass(attempt)"
                      :aria-label="attemptStatusAriaLabel(attempt, attemptIndex < selectedAttempts.length - 1)"
                    >
                      HTTP {{ attempt.statusCode ?? '—' }}
                    </span>
                    <span
                      v-if="attempt.upstreamErrorStatusCode != null && attempt.upstreamErrorStatusCode !== attempt.statusCode"
                      class="badge danger"
                    >错误状态 {{ attempt.upstreamErrorStatusCode }}</span>
                    <span class="attempt-result" role="status">{{ attemptResultLabel(attempt, attemptIndex < selectedAttempts.length - 1) }}</span>
                    <span class="sr-only">{{ attempt.skipped ? '路由跳过，未发起上游请求' : '第 ' + attempt.index + ' 次尝试' }}</span>
                    <span v-if="!attempt.skipped" class="attempt-duration">{{ formatDuration(attempt.elapsedMs) }}</span>
                  </div>
                  <div class="attempt-route">
                    <div class="attempt-field">
                      <span class="attempt-label">渠道</span>
                      <span class="attempt-value" :title="attempt.channelName || attempt.channelId || ''">
                        {{ attempt.channelName || (attempt.channelId ? attempt.channelId.slice(0, 8) : '—') }}
                      </span>
                    </div>
                    <div class="attempt-field">
                      <span class="attempt-label">密钥</span>
                      <span class="attempt-value" :title="attempt.channelKeyName || ''">{{ attempt.channelKeyName || '—' }}</span>
                    </div>
                    <div class="attempt-field">
                      <span class="attempt-label">上游模型</span>
                      <span class="attempt-value mono" :title="attempt.upstreamModel || ''">{{ attempt.upstreamModel || '—' }}</span>
                    </div>
                    <div class="attempt-field">
                      <span class="attempt-label">出站协议</span>
                      <span class="attempt-value">{{ attemptProtocolLabel(attempt, selected) }}</span>
                    </div>
                  </div>
                  <div class="attempt-detail">
                    <div class="attempt-field">
                      <span class="attempt-label">首 token</span>
                      <span class="attempt-value">{{ formatDuration(attempt.firstTokenMs) }}</span>
                    </div>
                    <div class="attempt-field">
                      <span class="attempt-label">上游错误码</span>
                      <span class="attempt-value mono">{{ attempt.upstreamErrorCode || '—' }}</span>
                    </div>
                    <div class="attempt-field attempt-field-wide">
                      <span class="attempt-label">原因</span>
                      <span class="attempt-value">{{ attemptFailureReason(attempt) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="selected.upstreamErrorBody" class="detail-block">
              <div class="detail-block-head">
                <span>上游响应正文</span>
                <CopyButton :text="selected.upstreamErrorBody" title="复制上游响应正文" :size="13" />
              </div>
              <pre class="detail-code">{{ selected.upstreamErrorBody }}</pre>
            </div>

            <p class="muted small detail-hint">
              输出速率为输出 token / 总耗时；缓存命中为 cached / input。费用按渠道 + 上游模型的单价快照计算，推理 token 已包含在输出 token 中。缺失字段显示“—”，不做估算。日志不保存 prompt 与成功响应正文；失败请求的上游错误正文会截断并脱敏后保存。
            </p>
          </div>

          <div class="modal-foot">
            <button type="button" class="button ghost" @click="closeDetail">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

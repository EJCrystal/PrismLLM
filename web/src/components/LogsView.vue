<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/vue'
import { api } from '../api'
import { notify } from '../toast'
import type { RequestLog, RequestLogAttempt, RequestLogFilterOptions, RequestLogPageOptions } from '../types/api'
import RequestLogListItem from './RequestLogListItem.vue'
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
  attemptDurationPercent,
  attemptHasError,
  attemptResultLabel,
  protocolConversion,
  protocolEndpoint,
  protocolLabel,
  reasoningEffortLabel,
  reportedModelStatus,
  requestLogStatusLabel,
  requestLogTone,
  requestLogOutcome,
  tokenUsageLabel,
  tokenUsageState,
  summarizeAttemptTimings,
} from '../utils/requestLogDisplay'
import { requestLogCustomRange, requestLogPresetRange, requestLogTimePresets, shouldOpenRequestLog } from '../utils/requestLogQuery'
import { toDatetimeLocalValue } from '../utils/usageReport'
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

const appliedFilters = ref<RequestLogPageOptions>({ ...requestLogPresetRange('last24h') })
const { items: logs, pageSize, loading, loadingMore, error, hasMore, load, loadMore, setPageSize } = createCursorPager<RequestLog>({
  fetchPage: (options) => api.listRequestLogs({ ...appliedFilters.value, ...options }),
})
const query = ref('')
const channelFilter = ref('')
const modelFilter = ref('')
const statusFilter = ref('')
const outcomeFilter = ref<NonNullable<RequestLogPageOptions['outcome']>>('')
const timePreset = ref('last24h')
const initialRange = requestLogPresetRange('last24h')
const rangeDraft = ref({ from: toDatetimeLocalValue(initialRange.from!), to: toDatetimeLocalValue(initialRange.to!) })
const rangeError = ref('')
const filterOptions = ref<RequestLogFilterOptions>({ channels: [], models: [], statuses: [] })
const optionsError = ref('')
const wideLayout = ref(false)
let mediaQuery: MediaQueryList | undefined
let searchTimer: ReturnType<typeof setTimeout> | undefined
let filterTimer: ReturnType<typeof setTimeout> | undefined
let optionsGeneration = 0
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
function keepSelectedOption(options: Array<{ value: string; label: string }>, value: string) {
  return value && !options.some(option => option.value === value) ? [...options, { value, label: value === '__unrouted__' ? '未路由到渠道' : value }] : options
}
const channelOptions = computed(() => keepSelectedOption(filterOptions.value.channels, channelFilter.value))
const modelOptions = computed(() => keepSelectedOption(filterOptions.value.models, modelFilter.value))
const statusOptions = computed(() => keepSelectedOption(filterOptions.value.statuses, statusFilter.value))
async function refreshOptions() {
  const generation = ++optionsGeneration
  optionsError.value = ''
  try {
    const options = await api.listRequestLogFilterOptions(appliedFilters.value)
    if (generation === optionsGeneration) filterOptions.value = options
  } catch (cause) {
    if (generation === optionsGeneration) optionsError.value = cause instanceof Error ? cause.message : String(cause)
  }
}
function renewRange() {
  if (timePreset.value !== 'custom') {
    appliedFilters.value = { ...appliedFilters.value, from: undefined, to: undefined, ...requestLogPresetRange(timePreset.value) }
  }
}
async function applyFilters() {
  clearTimeout(searchTimer)
  closeDetail()
  const previousRange = [appliedFilters.value.from, appliedFilters.value.to].join(':')
  renewRange()
  appliedFilters.value = { from: appliedFilters.value.from, to: appliedFilters.value.to, q: query.value.trim(), channelId: channelFilter.value,
    model: modelFilter.value, httpStatus: statusFilter.value, outcome: outcomeFilter.value }
  logs.value = []
  hasMore.value = false
  if (previousRange !== [appliedFilters.value.from, appliedFilters.value.to].join(':') || filterOptions.value.models.length === 0) void refreshOptions()
  await load()
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
}
function applyCustomRange() {
  try {
    const range = requestLogCustomRange(rangeDraft.value.from, rangeDraft.value.to)
    rangeError.value = ''
    appliedFilters.value = { ...appliedFilters.value, ...range }
    void refreshOptions()
    void applyFilters()
  } catch (cause) {
    rangeError.value = cause instanceof Error ? cause.message : String(cause)
  }
}
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
const hasFilters = computed(() => Boolean(query.value || channelFilter.value || modelFilter.value || statusFilter.value || outcomeFilter.value || timePreset.value !== 'last24h'))
function clearFilters() {
  query.value = ''
  channelFilter.value = ''
  modelFilter.value = ''
  statusFilter.value = ''
  outcomeFilter.value = ''
  timePreset.value = 'last24h'
  rangeError.value = ''
  clearTimeout(searchTimer)
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => { void applyFilters() }, 0)
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
    return Object.fromEntries(logColumns.map(({ id }) => [id, typeof parsed[id] === 'boolean' ? parsed[id] : defaultLogColumns[id]])) as Record<LogColumnId, boolean>
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

function attemptCandidateLabel(attempt: RequestLogAttempt): string {
  const channel = attempt.channelName || (attempt.channelId ? attempt.channelId.slice(0, 8) : '—')
  return attempt.channelKeyName ? channel + ' · ' + attempt.channelKeyName : channel
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
const attemptTimings = computed(() => summarizeAttemptTimings(selectedAttempts.value))
const selectedReasoningEffort = computed(() => {
  const effort = reasoningEffortLabel(selected.value?.reasoningEffort)
  return effort === 'none' ? null : effort
})
const hasAttemptDetail = computed(() => selectedAttempts.value.length > 0)
const skippedAttemptCount = computed(() => selectedAttempts.value.filter((attempt) => attempt.skipped).length)
const compactSingleAttempt = computed(() => selected.value && requestLogOutcome(selected.value) === 'success' && selectedAttempts.value.length === 1 && !selectedAttempts.value[0].skipped && !attemptHasError(selectedAttempts.value[0]))
const detailIndex = computed(() => selected.value ? logs.value.findIndex(log => log.id === selected.value!.id) : -1)
const navigating = ref(false)
const canNext = computed(() => detailIndex.value >= 0 && (detailIndex.value < logs.value.length - 1 || hasMore.value))
async function navigateDetail(direction: -1 | 1) {
  if (navigating.value || loading.value || loadingMore.value || detailIndex.value < 0) return
  const currentId = selected.value?.id
  const targetIndex = detailIndex.value + direction
  if (targetIndex < 0) return
  if (targetIndex >= logs.value.length && hasMore.value) {
    navigating.value = true
    try { await loadMore() } finally { navigating.value = false }
  }
  if (selected.value?.id !== currentId) return
  if (logs.value[targetIndex]) selected.value = logs.value[targetIndex]
  await nextTick()
  if (detailDialog.value && !detailDialog.value.contains(document.activeElement)) detailDialog.value.focus()
}

const filtered = computed(() => logs.value)

const errorCount = computed(() => logs.value.filter((log) => ['danger', 'warning'].includes(requestLogTone(log) || '')).length)
const averageLatency = computed(() => {
  if (logs.value.length === 0) return '—'
  const total = logs.value.reduce((sum, log) => sum + log.totalMs, 0)
  return formatDuration(total / logs.value.length)
})

async function reloadCurrentRange(): Promise<boolean> {
  const previousFilters = appliedFilters.value
  renewRange()
  const requestedFilters = appliedFilters.value
  const loaded = await load()
  if (appliedFilters.value !== requestedFilters) return false
  if (!loaded) appliedFilters.value = previousFilters
  else await refreshOptions()
  return loaded
}

async function refresh() {
  if (loading.value || loadingMore.value) return
  const loaded = await reloadCurrentRange()
  await nextTick()
  if (selected.value) selected.value = logs.value.find(log => log.id === selected.value!.id) || selected.value
  else tableScroll.value?.scrollTo({ top: 0 })
  if (loaded) notify('请求日志已刷新')
}

async function autoRefresh(): Promise<void> {
  autoRefreshing.value = true
  try {
    await reloadCurrentRange()
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

useAutoRefresh(autoRefreshInterval, () => { void autoRefresh() }, () => loading.value || loadingMore.value || Boolean(selected.value))

watch(pageSize, async () => {
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
})

watch(query, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { void applyFilters() }, 300)
})
watch([channelFilter, modelFilter, statusFilter, outcomeFilter, timePreset], (values, previous) => {
  if (values[4] === 'custom' && values.slice(0, 4).every((value, index) => value === previous[index])) return
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => { void applyFilters() }, 0)
})

function formatCostAmount(log: RequestLog): string | null {
  return formatCostUtil(log.costAmount, log.costCurrency || 'USD')
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

function openDetail(log: RequestLog, event: MouseEvent | KeyboardEvent): void {
  detailTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  selected.value = log
}

function openRow(log: RequestLog, event: MouseEvent | KeyboardEvent): void {
  if (!shouldOpenRequestLog(event)) return
  if ('key' in event) event.preventDefault()
  openDetail(log, event)
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
  const target = event.target
  if (!event.altKey && !event.ctrlKey && !event.metaKey && ['ArrowUp', 'ArrowDown'].includes(event.key)
    && !(target instanceof Element && target.closest('input, select, textarea, summary, [contenteditable="true"]'))) {
    event.preventDefault()
    void navigateDetail(event.key === 'ArrowUp' ? -1 : 1)
    return
  }
  if (wideLayout.value || event.key !== 'Tab' || !detailDialog.value) return
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

watch(selected, async (value, previous) => {
  if (value) {
    await nextTick()
    detailDialog.value?.querySelector('.modal-body')?.scrollTo({ top: 0 })
    if (previous) return
    const autofocus = detailDialog.value?.querySelector<HTMLElement>('[data-autofocus]')
    ;(autofocus || detailDialog.value)?.focus()
    return
  }
  const trigger = detailTrigger
  detailTrigger = null
  await nextTick()
  const matchingRow = Array.from(document.querySelectorAll<HTMLElement>('[data-request-id]')).find(element => element.dataset.requestId === previous?.requestId && element.offsetParent !== null)
  if (trigger?.isConnected && trigger.offsetParent !== null) trigger.focus()
  else (matchingRow || document.querySelector<HTMLElement>('.logs-search input'))?.focus()
})

watch([selected, wideLayout], async ([value, wide], _previous, onCleanup) => {
  if (!value || wide || typeof document === 'undefined') return
  const original = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  onCleanup(() => { document.body.style.overflow = original })
  await nextTick()
  if (selected.value && !wideLayout.value && detailDialog.value && !detailDialog.value.contains(document.activeElement)) detailDialog.value.focus()
})

function updateLayout() { wideLayout.value = mediaQuery?.matches ?? false }
onMounted(() => {
  mediaQuery = window.matchMedia('(min-width: 1200px)')
  updateLayout()
  mediaQuery.addEventListener('change', updateLayout)
  void refreshOptions()
  void load()
})
onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  clearTimeout(filterTimer)
  optionsGeneration++
  mediaQuery?.removeEventListener('change', updateLayout)
})
</script>

<template>
  <section class="page-stack page-compact" :inert="selected && !wideLayout ? true : undefined">
    <div class="logs-overview">
      <dl class="list-summary logs-summary" aria-label="当前查询已加载日志统计">
        <div><dt>已加载</dt><dd>{{ logs.length }}</dd></div>
        <div><dt>异常响应</dt><dd><button type="button" class="log-summary-filter" :class="errorCount ? 'text-danger' : 'text-success'" :aria-pressed="outcomeFilter === 'abnormal'" title="筛选失败、取消或断开的请求，包含 HTTP 200 流式失败" @click="outcomeFilter = outcomeFilter === 'abnormal' ? '' : 'abnormal'">{{ errorCount }}</button></dd></div>
        <div><dt>平均总耗时</dt><dd title="当前已加载记录的简单平均">{{ averageLatency }}</dd></div>
      </dl>
      <div class="logs-toolbar-meta logs-page-actions">
        <Menu as="div" class="logs-refresh-menu">
          <MenuButton as="template">
            <button ref="refreshMenuAnchor" type="button" class="button ghost refresh-button logs-refresh" :aria-busy="loading" aria-label="请求日志刷新选项" @click="placeRefreshMenu" @keydown.down="placeRefreshMenu" @keydown.up="placeRefreshMenu" @keydown.enter="placeRefreshMenu" @keydown.space="placeRefreshMenu">
              <Icon name="refresh" :size="16" class="logs-refresh-icon" :class="{ 'is-spinning': (autoRefreshInterval > 0 && !selected) || loading }" />
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
        <span v-if="selected && autoRefreshInterval" class="logs-refresh-paused muted small" role="status">查看详情时暂停自动刷新</span>
        <details class="table-view-menu logs-table-settings" v-if="!selected">
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
    <ErrorState v-if="optionsError" :message="'筛选选项加载失败：' + optionsError" @retry="refreshOptions" />

    <div class="logs-workspace" :class="{ 'has-detail': selected }">
    <div class="panel table-panel">
      <div class="table-toolbar logs-toolbar">
        <div class="logs-search-group">
          <label class="search-box logs-search">
            <Icon name="search" :size="17" />
            <input
              v-model="query"
              type="search"
              aria-label="搜索保留期内的请求日志"
              aria-describedby="logs-filter-scope"
              placeholder="搜索请求 ID、错误信息、IP、UA 等"
              maxlength="2000"
              @keydown.enter.prevent="applyFilters"
            />
          </label>
        </div>
        <div class="logs-filter-controls" role="group" aria-label="请求日志筛选">
          <span class="logs-filter-label" aria-hidden="true">筛选</span>
          <FilterSelect v-model="timePreset" label="时间范围" default-value="__none__" :clearable="false" :options="requestLogTimePresets" />
          <FilterSelect v-model="outcomeFilter" label="请求结果" :options="[{ value: '', label: '全部请求结果' }, { value: 'success', label: '成功（无重试）' }, { value: 'recovered', label: '重试成功' }, { value: 'failed', label: '失败' }, { value: 'cancelled', label: '取消或断开' }, { value: 'other', label: '其他结果' }, { value: 'abnormal', label: '异常（失败＋取消）' }]" />
          <FilterSelect v-model="channelFilter" label="渠道" searchable :options="[{ value: '', label: '全部渠道' }, ...channelOptions]" />
          <FilterSelect v-model="modelFilter" label="请求模型" searchable :options="[{ value: '', label: '全部请求模型' }, ...modelOptions]" />
          <FilterSelect v-model="statusFilter" label="HTTP 状态" :options="[{ value: '', label: '全部 HTTP 状态' }, { value: '2xx', label: 'HTTP 成功（2xx）' }, { value: '4xx_5xx', label: 'HTTP 异常（4xx / 5xx）' }, ...statusOptions]" />
          <button v-if="hasFilters" type="button" class="filter-reset" @click="clearFilters">清除筛选</button>
        </div>
        <form v-if="timePreset === 'custom'" class="logs-time-range" @submit.prevent="applyCustomRange">
          <label>开始时间<input v-model="rangeDraft.from" type="datetime-local" required :max="rangeDraft.to" /></label>
          <label>结束时间（含整分钟）<input v-model="rangeDraft.to" type="datetime-local" required :min="rangeDraft.from" /></label>
          <button type="submit" class="button subtle">应用时间</button>
          <span class="muted small">本地时区：{{ timezone }}</span>
          <span v-if="rangeError" class="text-danger small" role="alert">{{ rangeError }}</span>
        </form>
        <span id="logs-filter-scope" class="muted small logs-filter-scope">服务端查询 · 当前结果已加载 {{ logs.length }} 条<span v-if="appliedFilters.from != null && appliedFilters.to != null"> · {{ formatTime(appliedFilters.from) }} — {{ formatTime(appliedFilters.to - 1) }}</span><span v-else> · 全部保留日志</span></span>
      </div>

      <TablePlaceholder
        v-if="(loading && !autoRefreshing) || logs.length === 0"
        :loading="loading"
        loading-text="正在读取请求日志…"
        :empty="logs.length === 0"
        icon="logs"
        title="当前查询没有请求日志"
        desc="尝试扩大时间范围、清除筛选，或通过网关发起请求。"
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
              <th v-if="logColumnVisible('latency')" class="log-col-latency">总耗时 / 首 token</th>
              <th v-if="logColumnVisible('usage')" class="log-col-usage">Token 用量</th>
              <th v-if="logColumnVisible('cost')" class="log-col-cost">费用</th>
              <th class="actions-col"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in filtered" :key="log.requestId" :data-request-id="log.requestId" :class="[requestLogTone(log) ? 'log-row-' + requestLogTone(log) : '', { 'is-selected': selected?.id === log.id }]"
              tabindex="0" :aria-label="log.alias + '，HTTP ' + log.statusCode" @click="openRow(log, $event)" @keydown="openRow(log, $event)">
              <td v-if="logColumnVisible('time')" class="time-col log-col-time nowrap">
                <div class="time-cell">
                  <span class="time-clock">{{ formatTime(log.createdAt, { style: 'timeOnly' }) }}</span>
                  <span class="time-date">{{ formatTime(log.createdAt, { style: 'dateOnly' }) }}</span>
                </div>
              </td>
              <td v-if="logColumnVisible('request')" class="log-col-request">
                <div class="request-id-cell">
                  <code class="request-id" :title="log.requestId">{{ log.requestId.slice(0, 12) }}…</code>
                  <CopyButton :text="log.requestId" title="复制请求 ID" variant="list" :size="13" />
                </div>
              </td>
              <td v-if="logColumnVisible('model')" class="log-col-model">
                <div class="stacked-cell">
                  <strong class="log-model-name" :title="log.alias">{{ log.alias }}</strong>
                  <div class="request-meta request-mode-row log-model-meta">
                    <ProtocolEndpoints
                      :inbound="log.protocol"
                      :outbound="log.upstreamProtocol"
                      :stream="log.stream"
                      :upstream-attempts="log.upstreamAttempts"
                    />
                    <span class="mode-badge" :class="`mode-${requestModeClass(log.stream)}`">{{ requestModeLabel(log.stream) }}</span>
                    <span v-if="log.reasoningEffort && reasoningEffortLabel(log.reasoningEffort) !== 'none'" class="reasoning-badge">
                      推理 {{ reasoningEffortLabel(log.reasoningEffort) }}
                    </span>
                  </div>
                </div>
              </td>
              <td v-if="logColumnVisible('channel')" class="log-col-channel">
                <div class="stacked-cell channel-cell">
                  <div class="log-channel-heading">
                    <div class="channel-name-cell" :title="log.channelName || log.channelId || ''">{{ log.channelName || (log.channelId ? log.channelId.slice(0, 8) : '—') }}</div>
                    <div v-if="log.channelKeyName" class="channel-key-chip" :title="`上游密钥：${log.channelKeyName}`">
                      <Icon name="keys" :size="10" />
                      <span>{{ log.channelKeyName }}</span>
                    </div>
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
                  <div class="status-head">
                    <span class="badge" :class="requestLogTone(log) === 'danger' ? 'danger' : statusClass(log.statusCode)">{{ log.statusCode }}</span>
                    <span v-if="requestLogStatusLabel(log)" class="log-outcome-label" :class="requestLogTone(log) === 'danger' ? 'text-danger' : 'text-warning'">{{ requestLogStatusLabel(log) }}</span>
                  </div>
                  <code v-if="log.errorCode || log.upstreamErrorCode" class="error-code" :title="log.errorMessage || log.errorCode || log.upstreamErrorCode || ''">{{ log.errorCode || log.upstreamErrorCode }}</code>
                  <span v-else-if="log.upstreamAttempts != null && log.upstreamAttempts > 1" class="log-attempt-count">尝试 {{ log.upstreamAttempts }} 次</span>
                  <span v-if="log.errorCode && log.failureStage" class="log-failure-stage">{{ stageLabel(log.failureStage) }}阶段</span>
                </div>
              </td>
              <td v-if="logColumnVisible('latency')" class="nowrap log-col-latency">
                <div class="duration-cell">
                  <span class="duration-row is-total">
                    <span class="duration-label">总</span>
                    <span class="duration-value">{{ formatDuration(log.totalMs) }}</span>
                  </span>
                  <span class="duration-row is-first-token">
                    <span class="duration-label">首</span>
                    <span class="duration-value">{{ formatDuration(log.firstTokenMs) }}</span>
                  </span>
                </div>
              </td>
              <td v-if="logColumnVisible('usage')" class="usage-cell log-col-usage" :class="usageStateClass(log)">
                <div class="usage-cell-body">
                  <template v-if="log.usageKnown">
                    <span class="usage-tokens" :title="`总 ${formatTokens(log.totalTokens)} · ${usageDetailTitle(log)}`">
                      <span><em>总计</em><b>{{ formatTokensCompact(log.totalTokens) }}</b></span>
                      <span v-if="log.outputTokens != null && log.totalMs > 0" class="usage-throughput">{{ formatThroughput(log) }} tok/s</span>
                    </span>
                    <span class="usage-detail" :title="`输入 ${formatTokens(log.inputTokens)} · 输出 ${formatTokens(log.outputTokens)}`">
                      <span><em>入</em><b>{{ formatTokensCompact(log.inputTokens) }}</b></span>
                      <span><em>出</em><b>{{ formatTokensCompact(log.outputTokens) }}</b></span>
                    </span>
                    <span v-if="(log.reasoningTokens ?? 0) > 0 || (log.cachedTokens ?? 0) > 0" class="usage-extras" :title="usageDetailTitle(log)">
                      <span v-if="(log.reasoningTokens ?? 0) > 0"><em>推理</em><b>{{ formatTokensCompact(log.reasoningTokens) }}</b></span>
                      <span v-if="(log.cachedTokens ?? 0) > 0"><em>缓存</em><b>{{ formatCacheHit(log) }}</b></span>
                    </span>
                  </template>
                  <span v-else class="usage-state-label">{{ tokenUsageLabel(log) }}</span>
                </div>
              </td>
              <td v-if="logColumnVisible('cost')" class="nowrap log-col-cost" :title="costTitle(log)">
                <span v-if="log.costAmount != null" class="cost-line log-cost">
                  <span class="cost-amount">{{ formatCostAmount(log) }}</span>
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
      <div v-if="logs.length && !(loading && !autoRefreshing)" class="logs-card-list" aria-label="请求日志摘要列表">
        <RequestLogListItem v-for="log in logs" :key="log.id" :log="log" :active="selected?.id === log.id" @open="openDetail(log, $event)" />
      </div>
      <CursorLoadMore
        :loaded="logs.length"
        :has-more="hasMore"
        :loading="loading"
        :loading-more="loadingMore"
        :page-size="pageSize"
        @load-more="loadMore"
        @page-size-change="closeDetail(); setPageSize($event)"
      />
    </div>

    <Teleport to="body" :disabled="wideLayout">
      <div v-if="selected" class="request-detail-shell" :class="{ 'is-fullscreen': !wideLayout }">
        <div
          ref="detailDialog"
          class="modal wide request-detail-panel"
          role="dialog"
          :aria-modal="wideLayout ? undefined : true"
          aria-labelledby="request-detail-title"
          tabindex="-1"
          @keydown="onDetailKeydown"
        >
          <div class="modal-head">
            <div>
              <div class="detail-title-line">
                <h2 id="request-detail-title">请求详情</h2>
                <span class="badge" :class="requestLogTone(selected) === 'danger' ? 'danger' : statusClass(selected.statusCode)">{{ selected.statusCode }}</span>
                <span v-if="requestLogStatusLabel(selected)" class="small" :class="requestLogTone(selected) === 'danger' ? 'text-danger' : 'text-warning'">{{ requestLogStatusLabel(selected) }}</span>
              </div>
              <p class="muted small">{{ formatTime(selected.createdAt) }}</p>
            </div>
            <button type="button" class="icon-button" title="关闭" data-autofocus @click="closeDetail">
              <Icon name="close" :size="18" />
            </button>
          </div>

          <div class="log-detail-navigation" aria-label="连续浏览请求">
            <button type="button" class="button ghost mini" :disabled="detailIndex <= 0 || navigating || loading || loadingMore" @click="navigateDetail(-1)">上一条</button>
            <span class="muted small" role="status">{{ detailIndex >= 0 ? (detailIndex + 1) + ' / ' + logs.length + (hasMore ? '＋' : '') : '当前请求已不在查询结果中' }}</span>
            <button type="button" class="button ghost mini" :disabled="!canNext || navigating || loading || loadingMore" @click="navigateDetail(1)">{{ navigating ? '加载中…' : detailIndex === logs.length - 1 && hasMore ? '下一条 · 加载更早' : '下一条' }}</button>
          </div>
          <p v-if="error" class="log-detail-error text-danger small" role="alert">{{ error }} <button v-if="detailIndex >= 0" type="button" class="button ghost mini" :disabled="loading || loadingMore" @click="detailIndex === logs.length - 1 && hasMore ? navigateDetail(1) : refresh()">重试</button></p>

          <div class="detail-meta-bar" role="group" aria-label="请求标识">
            <div class="detail-meta-item">
              <span>请求 ID</span>
              <div class="detail-meta-value">
                <code :title="selected.requestId">{{ selected.requestId }}</code>
                <CopyButton :text="selected.requestId" title="复制请求 ID" :size="14" />
              </div>
            </div>
            <div v-if="selected.traceId" class="detail-meta-item">
              <span>Trace ID</span>
              <div class="detail-meta-value">
                <code :title="selected.traceId || ''">{{ selected.traceId || '—' }}</code>
                <CopyButton v-if="selected.traceId" :text="selected.traceId" title="复制 Trace ID" :size="14" />
              </div>
            </div>
          </div>

          <div class="modal-body detail-stack">
            <dl class="detail-summary" aria-label="请求摘要">
              <div class="detail-summary-item">
                <dt>请求模型</dt>
                <dd :title="selected.alias">{{ selected.alias }}</dd>
              </div>
              <div class="detail-summary-item">
                <dt>渠道</dt>
                <dd :title="selected.channelName || ''">{{ selected.channelName || '—' }}</dd>
              </div>
              <div class="detail-summary-item">
                <dt>总耗时</dt>
                <dd>{{ formatDuration(selected.totalMs) }}</dd>
              </div>
              <div class="detail-summary-item">
                <dt>费用</dt>
                <dd>
                  <span v-if="selected.costAmount != null" class="cost-line detail-cost">
                    <span class="cost-amount">{{ formatCostAmount(selected) }}</span>
                  </span>
                  <span v-else>{{ selected.usageKnown ? '未配置' : '—' }}</span>
                </dd>
              </div>
            </dl>

            <div v-if="selected.errorMessage" class="detail-block detail-error">
              <span>错误信息</span>
              <p class="detail-message">{{ selected.errorMessage }}</p>
            </div>

            <section class="detail-card" aria-labelledby="request-overview-title">
              <h3 id="request-overview-title" class="detail-card-title">请求概况</h3>
              <div class="detail-grid">
              <div class="detail-item" :class="{ 'detail-protocol-item': selectedConversion.converted }">
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
              <div v-if="selected.failureStage" class="detail-item">
                <span>失败阶段</span>
                <strong>{{ stageLabel(selected.failureStage) }}</strong>
              </div>
              <div v-if="selected.errorCode" class="detail-item">
                <span>错误码</span>
                <strong class="mono">{{ selected.errorCode || '—' }}</strong>
              </div>
              <div v-if="selected.upstreamErrorCode" class="detail-item">
                <span>上游错误码</span>
                <strong class="mono">{{ selected.upstreamErrorCode || '—' }}</strong>
              </div>
              <div v-if="selected.clientIp" class="detail-item">
                <span>来源 IP</span>
                <strong class="mono detail-wrap" :title="selected.clientIp">{{ selected.clientIp }}</strong>
              </div>
              </div>
            </section>
            <section class="detail-card" aria-labelledby="request-route-title">
              <h3 id="request-route-title" class="detail-card-title">路由与渠道</h3>
              <div class="detail-grid">
              <div v-if="selected.channelKeyName" class="detail-item">
                <span>上游密钥</span>
                <strong>{{ selected.channelKeyName }}</strong>
              </div>
              <div v-if="selected.upstreamAttempts != null" class="detail-item">
                <span>上游尝试</span>
                <strong>{{ selected.upstreamAttempts }}</strong>
              </div>
              <div v-if="selected.upstreamModel" class="detail-item">
                <span>上游模型</span>
                <strong class="mono detail-wrap">{{ selected.upstreamModel }}</strong>
              </div>
              <div v-if="selected.clientKeyName || selected.clientKeyId" class="detail-item">
                <span>调用方密钥</span>
                <strong>{{ selected.clientKeyName || '未命名密钥' }}</strong>
              </div>
              <div v-if="selected.clientUserAgent" class="detail-item detail-item-span-2">
                <span>User-Agent</span>
                <strong class="mono detail-wrap" :title="selected.clientUserAgent">{{ selected.clientUserAgent }}</strong>
              </div>
              <p v-if="!selected.channelId && !selected.upstreamModel" class="muted small detail-item-wide">{{ (selected.upstreamAttempts ?? 0) > 0 ? '未记录上游路由信息' : '未路由到上游渠道' }}</p>
              </div>
            </section>
            <section class="detail-card" aria-labelledby="request-usage-title">
              <h3 id="request-usage-title" class="detail-card-title">耗时与用量</h3>
              <div class="detail-grid">
              <div v-if="selected.firstTokenMs != null" class="detail-item">
                <span>首 token</span>
                <strong>{{ formatDuration(selected.firstTokenMs) }}</strong>
              </div>
              <div v-if="selected.usageKnown" class="detail-item">
                <span>输入 / 输出</span>
                <strong>{{ formatTokens(selected.inputTokens) }} / {{ formatTokens(selected.outputTokens) }}</strong>
              </div>
              <div v-else class="detail-item">
                <span>Token 用量</span>
                <strong class="muted">{{ tokenUsageLabel(selected) }}</strong>
              </div>
              <div v-if="selected.usageKnown && selected.totalTokens != null" class="detail-item">
                <span>总 Token</span>
                <strong>{{ formatTokens(selected.totalTokens) }}</strong>
              </div>
              <div v-if="selected.usageKnown && selected.reasoningTokens != null" class="detail-item">
                <span>推理 Token</span>
                <strong>{{ formatTokens(selected.reasoningTokens) }}</strong>
              </div>
              <div v-if="selectedReasoningEffort" class="detail-item">
                <span>推理强度</span>
                <strong><span class="reasoning-badge">{{ selectedReasoningEffort }}</span></strong>
              </div>
              <div v-if="selected.usageKnown && selected.cachedTokens != null" class="detail-item">
                <span>缓存读取</span>
                <strong :title="cacheTitle(selected)">
                  {{ formatTokens(selected.cachedTokens) }}
                  <span v-if="selected.inputTokens != null && selected.inputTokens > 0" class="muted small">（命中 {{ formatCacheHit(selected) }}）</span>
                </strong>
              </div>
              <div v-if="selected.usageKnown && selected.cachedWriteTokens != null" class="detail-item">
                <span>缓存写入</span>
                <strong>{{ formatTokens(selected.cachedWriteTokens) }}</strong>
              </div>
              <div v-if="selected.usageKnown && selected.outputTokens != null && selected.totalMs > 0" class="detail-item">
                <span>输出速率</span>
                <strong>{{ formatThroughput(selected) }} tok/s</strong>
              </div>
              <div v-if="selected.costAmount != null" class="detail-block detail-price-snapshot">
                <div class="detail-block-head">
                  <span>计费单价快照</span>
                  <span class="muted small">{{ selected.pricingMode === 'per_request' ? '按次计费' : selected.pricingMode === 'tiered' ? '阶梯计费' : selected.pricingMode === 'volume' ? '阶梯总量计费' : '按 Token 计费' }}</span>
                </div>
                <dl class="detail-price-list" :class="{ 'is-tiered': ['tiered', 'volume'].includes(selected.pricingMode), 'is-per-request': selected.pricingMode === 'per_request' }">
                  <div v-for="item in formatPriceSnapshot(selected)" :key="item.label" class="detail-price-row">
                    <dt>{{ item.label }}</dt>
                    <dd>{{ item.value }}</dd>
                  </div>
                </dl>
              </div>
              </div>
            </section>

            <details v-if="selected.channelId || selected.clientKeyId" class="detail-card detail-block detail-identifiers">
              <summary>标识符与追踪信息</summary>
              <div class="detail-identifiers-grid">
                <div v-if="selected.channelId" class="detail-item detail-item-wide">
                  <span>渠道 ID</span>
                  <div v-if="selected.channelId" class="detail-value-copy"><strong class="mono detail-wrap">{{ selected.channelId }}</strong><CopyButton :text="selected.channelId" title="复制渠道 ID" :size="13" /></div>
                  <strong v-else class="muted">—</strong>
                </div>
                <div v-if="selected.clientKeyId" class="detail-item detail-item-wide">
                  <span>调用方密钥 ID</span>
                  <div v-if="selected.clientKeyId" class="detail-value-copy"><strong class="mono detail-wrap">{{ selected.clientKeyId }}</strong><CopyButton :text="selected.clientKeyId" title="复制调用方密钥 ID" :size="13" /></div>
                  <strong v-else class="muted">—</strong>
                </div>
              </div>
            </details>

            <details :key="selected.requestId" class="detail-card detail-block log-attempt-disclosure" :open="!compactSingleAttempt">
              <summary v-if="hasAttemptDetail">
                尝试记录（{{ selectedAttempts.length }} 条<span v-if="skippedAttemptCount"> · 跳过 {{ skippedAttemptCount }}</span> · 上游调用 {{ selected.upstreamAttempts ?? selectedAttempts.filter((attempt) => !attempt.skipped).length }} 次）
                <span v-if="compactSingleAttempt" class="muted small">成功 · {{ selectedAttempts[0].channelName || selected.channelName || '未命名渠道' }} · {{ formatDuration(selectedAttempts[0].elapsedMs) }}</span>
              </summary>
              <summary v-else>尝试记录</summary>
              <p v-if="!hasAttemptDetail" class="muted small">无尝试明细</p>
              <p v-if="attemptTimings.callCount > 1 && attemptTimings.measuredCount" class="attempt-timing-summary">
                已记录上游调用耗时 <strong>{{ formatDuration(attemptTimings.recordedMs) }}</strong>
                <span v-if="attemptTimings.measuredCount < attemptTimings.callCount">（{{ attemptTimings.measuredCount }} / {{ attemptTimings.callCount }} 次有记录）</span>
                <span class="muted small">条形长度按最长单次调用比较，不含路由与退避等待。</span>
              </p>
              <ol v-if="hasAttemptDetail" class="attempt-list" aria-label="按发生顺序排列的上游尝试">
                <li
                  v-for="(attempt, attemptIndex) in selectedAttempts"
                  :key="attempt.index + '-' + attemptIndex"
                  class="attempt-row"
                  :class="{ 'attempt-row-skipped': attempt.skipped, 'attempt-row-failed': !attempt.skipped && attemptHasError(attempt), 'attempt-row-success': !attempt.skipped && !attemptHasError(attempt) && (attempt.statusCode ?? 0) >= 200 && (attempt.statusCode ?? 0) < 300 }"
                >
                  <div v-if="attempt.skipped" class="attempt-head">
                    <span class="badge attempt-status neutral" aria-label="路由跳过，未发起上游请求">跳过</span>
                    <div class="attempt-skip-body">
                      <div class="attempt-skip-line">
                        <span class="attempt-skip-target" :title="attemptCandidateLabel(attempt)">{{ attemptCandidateLabel(attempt) }}</span>
                        <span class="attempt-skip-reason">{{ attemptFailureReason(attempt) }}</span>
                      </div>
                      <div class="attempt-skip-meta">
                        <span class="mono" :title="attempt.upstreamModel || ''">{{ attempt.upstreamModel || '—' }}</span>
                        <span aria-hidden="true">·</span>
                        <span>{{ attemptProtocolLabel(attempt, selected) }}</span>
                      </div>
                    </div>
                  </div>
                  <template v-else>
                    <div class="attempt-head">
                      <span class="attempt-index">#{{ attempt.index }}</span>
                      <span
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
                      <span class="sr-only">第 {{ attempt.index }} 次尝试</span>
                      <span class="attempt-duration">{{ attemptDurationPercent(attempt, attemptTimings.maxMs) == null ? '耗时未记录' : formatDuration(attempt.elapsedMs) }}</span>
                    </div>
                    <div v-if="attemptTimings.callCount > 1 && attemptTimings.maxMs > 0 && attemptDurationPercent(attempt, attemptTimings.maxMs) != null" class="attempt-duration-track" aria-hidden="true">
                      <span :style="{ width: attemptDurationPercent(attempt, attemptTimings.maxMs) + '%' }"></span>
                    </div>
                    <div class="attempt-route">
                      <div class="attempt-field">
                        <span class="attempt-label">渠道</span>
                        <span class="attempt-value" :title="attempt.channelName || attempt.channelId || ''">
                          {{ attempt.channelName || (attempt.channelId ? attempt.channelId.slice(0, 8) : '—') }}
                        </span>
                      </div>
                      <div v-if="attempt.channelKeyName" class="attempt-field">
                        <span class="attempt-label">密钥</span>
                        <span class="attempt-value" :title="attempt.channelKeyName || ''">{{ attempt.channelKeyName || '—' }}</span>
                      </div>
                      <div v-if="attempt.upstreamModel" class="attempt-field">
                        <span class="attempt-label">上游模型</span>
                        <span class="attempt-value mono" :title="attempt.upstreamModel || ''">{{ attempt.upstreamModel || '—' }}</span>
                      </div>
                      <div class="attempt-field">
                        <span class="attempt-label">出站协议</span>
                        <span class="attempt-value">{{ attemptProtocolLabel(attempt, selected) }}</span>
                      </div>
                    </div>
                    <div class="attempt-detail">
                      <div v-if="attempt.firstTokenMs != null" class="attempt-field">
                        <span class="attempt-label">首 token</span>
                        <span class="attempt-value">{{ formatDuration(attempt.firstTokenMs) }}</span>
                      </div>
                      <div v-if="attempt.upstreamErrorCode" class="attempt-field">
                        <span class="attempt-label">上游错误码</span>
                        <span class="attempt-value mono">{{ attempt.upstreamErrorCode || '—' }}</span>
                      </div>
                      <div v-if="attemptHasError(attempt) || attempt.errorMessage" class="attempt-field attempt-field-wide">
                        <span class="attempt-label">原因</span>
                        <span class="attempt-value">{{ attemptFailureReason(attempt) }}</span>
                      </div>
                    </div>
                  </template>
                </li>
              </ol>
            </details>

            <div v-if="selected.upstreamErrorBody" class="detail-block">
              <div class="detail-block-head">
                <span>上游响应正文</span>
                <CopyButton :text="selected.upstreamErrorBody" title="复制上游响应正文" :size="13" />
              </div>
              <pre class="detail-code">{{ selected.upstreamErrorBody }}</pre>
            </div>

            <details class="detail-hint muted small">
              <summary>字段与计费说明</summary>
              <p>输出速率为输出 token / 总耗时；缓存命中为 cached / input。费用按渠道 + 上游模型的单价快照计算，推理 token 已包含在输出 token 中。未提供的可选字段不展示，已记录的 0 值保留；用量未知或未发起请求会明确标注，不做估算。尝试耗时仅比较已记录的上游调用，不代表请求总耗时。日志不保存 prompt 与成功响应正文；失败请求的上游错误正文会截断并脱敏后保存。</p>
            </details>
          </div>

          <div class="modal-foot">
            <button type="button" class="button ghost" @click="closeDetail">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
    </div>
  </section>
</template>

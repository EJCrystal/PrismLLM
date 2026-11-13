<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { Channel, ClientKey, UsageGroup, UsageReport, UsageTotal, UsageTrend } from '../types/api'
import Icon from './Icon.vue'
import CopyButton from './CopyButton.vue'
import FilterPanel from './FilterPanel.vue'
import { vScrollHint } from '../directives/scrollHint'
import TablePlaceholder from './TablePlaceholder.vue'
import ErrorState from './ErrorState.vue'
import FilterSelect from './FilterSelect.vue'
import { formatNumber, formatTokensCompact, formatDuration } from '../utils/format'
import {
  usageDeltaTone,
  USAGE_REPORT_PRESETS,
  usageReportPresetRange,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  validateUsageReportRange,
  reportTimezoneOffset,
  fillTrendBuckets,
  buildTrendSegments,
  formatUsageCost,
  usageErrorRate,
  usageErrorDistribution,
  usageTrendMax,
  usageModelOptions,
  usagePercent,
  usageCacheHitRate,
  usageGroupLabel,
  usageGranularityLabel,
  usageReportCsv,
} from '../utils/usageReport'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { usageChartBucketRange, usageChartIndex, usageChartPoint, usageChartScale } from '../utils/usageChart'

type DimensionId = 'clientKey' | 'channel' | 'model' | 'channelKey' | 'protocol' | 'error'
type DimensionKey = 'byClientKey' | 'byChannel' | 'byModel' | 'byChannelKey' | 'byProtocol' | 'byErrorCode'
type TrendMetricId = 'requests' | 'errorRate' | 'tokens' | 'cost'

interface RangeDraft {
  from: string
  to: string
}

interface ExactRange { from: number; to: number }

interface UsageFilters {
  clientKeyId: string
  channelId: string
  model: string
  protocol: string
}

interface DimensionOption {
  id: DimensionId
  label: string
  title: string
  key: DimensionKey
}

type TrendSeriesId = 'requests' | 'errors' | 'errorRate' | 'input' | 'output' | 'cachedRead' | 'cachedWrite' | 'cost'

interface TrendSeries {
  id: TrendSeriesId
  label: string
  value: (row: UsageTrend) => number | null
}

interface TrendMetric {
  id: TrendMetricId
  label: string
  series: TrendSeries[]
}

const initialRange = usageReportPresetRange('last7d')
const preset = ref('last7d')
const rangeDraft = ref<RangeDraft>(createRangeDraft(initialRange))
const rangeError = ref('')
const maxDatetimeLocal = ref(toDatetimeLocalValue(Date.now(), true))
const filters = ref<UsageFilters>({ clientKeyId: '', channelId: '', model: '', protocol: '' })
const report = ref<UsageReport | null>(null)
const exactRange = ref<ExactRange>(initialRange)
const previousReport = ref<UsageReport | null>(null)
const channels = ref<Channel[]>([])
const clientKeys = ref<ClientKey[]>([])
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const requests = createLatestRequestGuard()
const comparisonRequests = createLatestRequestGuard()
const dimension = ref<DimensionId>('clientKey')
const trendMetric = ref<TrendMetricId>('requests')

const dimensions: DimensionOption[] = [
  { id: 'clientKey', label: '调用方密钥', title: '按调用方密钥', key: 'byClientKey' },
  { id: 'channel', label: '渠道', title: '按渠道', key: 'byChannel' },
  { id: 'model', label: '模型', title: '按模型', key: 'byModel' },
  { id: 'channelKey', label: '渠道密钥', title: '按渠道密钥', key: 'byChannelKey' },
  { id: 'protocol', label: '协议', title: '按协议', key: 'byProtocol' },
  { id: 'error', label: '错误', title: '按错误码', key: 'byErrorCode' },
]
const trendMetrics: TrendMetric[] = [
  {
    id: 'requests',
    label: '请求与错误',
    series: [
      { id: 'requests', label: '请求', value: (row) => row.requests },
      { id: 'errors', label: '错误', value: (row) => row.errors },
    ],
  },
  {
    id: 'errorRate',
    label: '错误率',
    series: [{ id: 'errorRate', label: '错误率', value: usageErrorRate }],
  },
  {
    id: 'tokens',
    label: 'Token 用量',
    series: [
      { id: 'input', label: '输入 token（含缓存）', value: (row) => row.inputTokens },
      { id: 'output', label: '输出 token', value: (row) => row.outputTokens },
      { id: 'cachedRead', label: '缓存读', value: (row) => row.cachedTokens },
      { id: 'cachedWrite', label: '缓存写', value: (row) => row.cachedWriteTokens },
    ],
  },
  {
    id: 'cost',
    label: '费用',
    series: [{ id: 'cost', label: '费用', value: (row) => Number(row.costUsd ?? '') || 0 }],
  },
]
const protocolLabels: Record<string, string> = { chat_completions: 'Chat Completions', responses: 'Responses', messages: 'Messages' }

const emptyTotal: UsageTotal = {
  requests: 0, errors: 0, inputTokens: 0, outputTokens: 0, cachedTokens: 0, cachedWriteTokens: 0,
  reasoningTokens: 0, totalTokens: 0, activeClientKeys: 0, failovers: 0, avgFirstTokenMs: null, avgTotalMs: null, costUsd: null,
}
const total = computed<UsageTotal>(() => report.value?.total || emptyTotal)
const successRate = computed(() => usagePercent(total.value.requests, total.value.errors))
const cacheHitText = computed(() => formatCacheHit(total.value))
const activeDimension = computed(() => dimensions.find((item) => item.id === dimension.value) || dimensions[0])
const dimensionRows = computed<UsageGroup[]>(() => report.value?.[activeDimension.value.key] || [])
const trendRows = computed(() => fillTrendBuckets(
  report.value?.trend || [],
  report.value?.from ?? NaN,
  report.value?.to ?? NaN,
  report.value?.granularity ?? 'day',
  reportTimezoneOffset(),
))
const activeTrendMetric = computed(() => trendMetrics.find((item) => item.id === trendMetric.value) || trendMetrics[0])
const trendSeriesClasses = ['', 'secondary', 'tertiary', 'quaternary']
function trendSeriesClass(index: number): string {
  return trendSeriesClasses[index] || ''
}
const trendSeriesMax = computed(() => activeTrendMetric.value.series.map((series) => (
  Math.max(0, ...trendRows.value.map((row) => Number(series.value(row)) || 0))
)))
const trendMaxValue = computed(() => usageTrendMax(
  trendRows.value,
  ...activeTrendMetric.value.series.map((series) => series.value),
))
const chartScale = computed(() => usageChartScale(trendMaxValue.value))
const chartIndex = ref<number | null>(null)
const chartSliderIndex = computed(() => Math.max(0, Math.min(trendRows.value.length - 1, chartIndex.value ?? trendRows.value.length - 1)))
const chartValueText = computed(() => {
  const row = trendRows.value[chartSliderIndex.value]
  if (!row) return ''
  const parts = activeTrendMetric.value.series.map((series) => `${series.label} ${formatTrendValue(series.value(row))}`)
  if (trendMetric.value === 'tokens') parts.push(`缓存命中率 ${formatCacheHit(row)}`)
  return `${formatTimeRange(row.bucketStart)}，${parts.join('，')}`
})
const chartRow = computed(() => chartIndex.value == null ? null : trendRows.value[chartIndex.value] || null)
const chartSeriesPoints = computed(() => {
  const row = chartRow.value
  if (!row) return []
  return activeTrendMetric.value.series.flatMap((series, index) => {
    const value = series.value(row)
    if (value == null) return []
    const point = usageChartPoint(chartIndex.value!, trendRows.value.length, value, chartScale.value.max)
    return [{ id: series.id, x: point.x, y: point.y, className: trendSeriesClass(index) }]
  })
})
function inspectChart(event: PointerEvent) {
  const rect = (event.currentTarget as SVGElement).getBoundingClientRect()
  const index = usageChartIndex(event.clientX, rect.left, rect.width, trendRows.value.length)
  chartIndex.value = index < 0 ? null : index
}
function chartKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && chartRow.value) {
    event.preventDefault()
    selectChartBucket()
    return
  }
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'Escape'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Escape') { chartIndex.value = null; return }
  const last = trendRows.value.length - 1
  if (last < 0) return
  chartIndex.value = event.key === 'Home' ? 0 : event.key === 'End' ? last : Math.max(0, Math.min(last, (chartIndex.value ?? last) + (['ArrowLeft', 'ArrowDown'].includes(event.key) ? -1 : 1)))
}
function chartFocusOut(event: FocusEvent) {
  const container = event.currentTarget as HTMLElement
  if (!container.contains(event.relatedTarget as Node | null)) chartIndex.value = null
}
function selectChartBucket(): void {
  const row = chartRow.value
  if (!row || !report.value) return
  const range = usageChartBucketRange(report.value, row.bucketStart)
  if (!range) return
  preset.value = ''
  exactRange.value = range
  rangeDraft.value = { from: toDatetimeLocalValue(range.from), to: toDatetimeLocalValue(range.to) }
  rangeError.value = ''
  load({ range })
}
watch([trendRows, trendMetric], () => { chartIndex.value = null })
const trendSeriesPoints = computed(() => activeTrendMetric.value.series.map((series, index) => ({
  id: series.id,
  label: series.label,
  segments: buildTrendSegments(trendRows.value, series.value, chartScale.value.max),
  max: trendMetric.value === 'errorRate' && !trendRows.value.some(row => row.requests > 0) ? null : trendSeriesMax.value[index] || 0,
  className: trendSeriesClass(index),
})))
const trendArea = computed(() => {
  if (trendMetric.value === 'errorRate') return ''
  const seriesPoints = trendSeriesPoints.value[0]?.segments[0]
  if (!seriesPoints) return ''
  const lastPoint = seriesPoints.split(' ').at(-1)
  if (!lastPoint) return ''
  return `8,142 ${seriesPoints} ${lastPoint.split(',')[0]},142`
})
const modelOptions = computed(() => usageModelOptions(report.value, channels.value))
const hasFilters = computed(() => Object.values(filters.value).some(Boolean))
const filterChips = computed(() => {
  const labels: Record<keyof UsageFilters, string> = {
    clientKeyId: '密钥：' + (clientKeys.value.find(item => item.id === filters.value.clientKeyId)?.name || filters.value.clientKeyId),
    channelId: '渠道：' + (channels.value.find(item => item.id === filters.value.channelId)?.name || filters.value.channelId),
    model: '模型：' + filters.value.model,
    protocol: '协议：' + protocolLabel(filters.value.protocol),
  }
  return (Object.keys(filters.value) as (keyof UsageFilters)[]).filter(key => filters.value[key]).map(key => ({ key, label: labels[key] }))
})
function removeFilter(key: keyof UsageFilters) { filters.value[key] = ''; load() }
const filterRangeSummary = computed(() => USAGE_REPORT_PRESETS.find(item => item.id === preset.value)?.label || '自定义时间')
const rangeLabel = computed(() => report.value ? `${formatTimeRange(report.value.from)} — ${formatTimeRange(report.value.to)}` : '')
const activePreset = computed(() => preset.value)
const errorDistribution = computed(() => usageErrorDistribution(report.value?.byErrorCode || [], total.value.errors))
const failoverRate = computed(() => total.value.requests ? `${((total.value.failovers / total.value.requests) * 100).toFixed(1)}%` : '0%')
function deltaPercent(current: number, previous: number): number | null {
  if (previous === 0) return null
  return ((current - previous) / previous) * 100
}
function deltaText(current: number, previous: number): string {
  const delta = deltaPercent(current, previous)
  if (delta == null) return '—'
  return `${delta >= 0 ? '↑' : '↓'} ${Math.abs(delta).toFixed(1)}%`
}
const comparison = computed(() => {
  const previous = previousReport.value?.total
  if (!previous) return null
  const currentCost = Number(total.value.costUsd ?? '') || 0
  const previousCost = Number(previous.costUsd ?? '') || 0
  return {
    from: previousReport.value?.from || 0,
    to: previousReport.value?.to || 0,
    requests: deltaText(total.value.requests, previous.requests),
    requestsTone: usageDeltaTone(total.value.requests, previous.requests),
    errors: deltaText(total.value.errors, previous.errors),
    errorsTone: usageDeltaTone(total.value.errors, previous.errors, true),
    tokens: deltaText(total.value.totalTokens, previous.totalTokens),
    latency: total.value.avgFirstTokenMs != null && previous.avgFirstTokenMs != null ? deltaText(total.value.avgFirstTokenMs, previous.avgFirstTokenMs) : '—',
    latencyTone: total.value.avgFirstTokenMs != null && previous.avgFirstTokenMs != null ? usageDeltaTone(total.value.avgFirstTokenMs, previous.avgFirstTokenMs, true) : 'neutral',
    cost: total.value.costUsd != null && previous.costUsd != null ? deltaText(currentCost, previousCost) : '—',
    costTone: total.value.costUsd != null && previous.costUsd != null ? usageDeltaTone(currentCost, previousCost, true) : 'neutral',
  }
})

function formatTimeRange(value: number): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function formatBucket(value: number): string {
  const options: Intl.DateTimeFormatOptions = report.value?.granularity === 'hour'
    ? { month: 'numeric', day: 'numeric', hour: '2-digit' }
    : { month: 'numeric', day: 'numeric' }
  return new Intl.DateTimeFormat('zh-CN', options).format(new Date(value))
}

function formatTrendValue(value: number | null): string {
  if (value == null) return '—'
  if (trendMetric.value === 'errorRate') return value.toFixed(1) + '%'
  if (trendMetric.value === 'cost') {
    return formatUsageCost(value)
  }
  return trendMetric.value === 'requests' ? formatNumber(value) : formatTokensCompact(value)
}

function formatCacheHit(row: Pick<UsageTrend, 'inputTokens' | 'cachedTokens'>): string {
  const rate = usageCacheHitRate(row.inputTokens, row.cachedTokens)
  return rate == null ? '—' : `${(rate * 100).toFixed(1)}%`
}

function displayName(row: UsageGroup): string {
  if (dimension.value === 'protocol') return protocolLabel(row.id)
  if (['clientKey', 'channel', 'channelKey'].includes(dimension.value) && (!row.name || row.name === row.id)) return dimensionId(row)
  return usageGroupLabel(row)
}

function protocolLabel(value: string): string {
  return protocolLabels[value] || value
}

function costText(row: UsageGroup): string {
  return formatUsageCost(row.costUsd)
}

function latency(value: number | null | undefined): string {
  return value == null ? '—' : formatDuration(value)
}

function errorRate(row: UsageGroup): string {
  return usagePercent(row.requests, row.errors)
}

function dimensionId(row: UsageGroup): string {
  const value = protocolLabel(row.id)
  return value.length > 24 ? value.slice(0, 10) + '…' + value.slice(-8) : value
}

const comparisonTitle = computed(() => comparison.value
  ? '较上一周期：' + formatTimeRange(comparison.value.from) + ' — ' + formatTimeRange(comparison.value.to)
  : '上一周期无可用对比数据')

function createRangeDraft({ from, to }: ExactRange): RangeDraft {
  return { from: toDatetimeLocalValue(from), to: toDatetimeLocalValue(to) }
}

function currentRange(): { from: number; to: number } {
  // 预设使用毫秒精度，避免 datetime-local 截断到分钟而漏掉最后一分钟。
  if (preset.value) return usageReportPresetRange(preset.value)
  return exactRange.value
}

function selectPreset(presetId: string) {
  preset.value = presetId
  exactRange.value = usageReportPresetRange(presetId)
  rangeDraft.value = createRangeDraft(exactRange.value)
  rangeError.value = ''
  load()
}

function refresh() {
  if (preset.value) {
    exactRange.value = usageReportPresetRange(preset.value)
    rangeDraft.value = createRangeDraft(exactRange.value)
  }
  maxDatetimeLocal.value = toDatetimeLocalValue(Date.now(), true)
  load({ quiet: true })
}

function onRangeInput(boundary: keyof ExactRange) {
  // 手动修改时间后退出快捷项高亮，提交时再统一校验。
  preset.value = ''
  exactRange.value = { ...exactRange.value, [boundary]: fromDatetimeLocalValue(rangeDraft.value[boundary]) }
}

function onRangeChange() {
  const { from, to } = currentRange()
  const message = validateUsageReportRange(from, to)
  rangeError.value = message
  if (!message) load()
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function load({ quiet = false, range }: { quiet?: boolean; range?: ExactRange | null } = {}): Promise<void> {
  const { from, to } = range ?? currentRange()
  const message = validateUsageReportRange(from, to)
  rangeError.value = message
  if (message) {
    loading.value = false
    refreshing.value = false
    return
  }
  exactRange.value = { from, to }
  if (preset.value) rangeDraft.value = createRangeDraft(exactRange.value)
  if (quiet) refreshing.value = true
  else loading.value = true
  error.value = ''
  const previousSpan = Math.max(to - from, 60_000)
  comparisonRequests.invalidate()
  try {
    const result = await requests.run(async () => {
      return api.getUsageReport({
        from,
        to,
        ...filters.value,
        timezoneOffsetMinutes: reportTimezoneOffset(),
      })
    })
    if (!result.isLatest) return
    report.value = result.value
    previousReport.value = null
    void comparisonRequests.run(async () => api.getUsageReport({
      from: from - previousSpan,
      to: from,
      ...filters.value,
      timezoneOffsetMinutes: reportTimezoneOffset(),
    }).catch(() => null)).then((comparison) => {
      if (comparison.isLatest) previousReport.value = comparison.value
    })
  } catch (cause) {
    const isLatest = !(cause instanceof Error && 'isLatest' in cause) || Boolean(cause.isLatest)
    if (isLatest) error.value = errorMessage(cause)
  } finally {
    if (!requests.hasActive()) {
      loading.value = false
      refreshing.value = false
    }
  }
}

async function loadFilters() {
  try {
    const [nextChannels, nextKeys] = await Promise.all([api.listChannels(), api.listClientKeys()])
    channels.value = nextChannels || []
    clientKeys.value = nextKeys || []
  } catch {
    // 报表仍可加载，筛选选项稍后由手动刷新补齐。
  }
}

function resetFilters(): void {
  filters.value = { clientKeyId: '', channelId: '', model: '', protocol: '' }
  load()
}

function exportCsv() {
  if (!report.value) return
  const csv = usageReportCsv(report.value, dimensionRows.value, activeDimension.value.title)
  const blob = new Blob(['\ufeff', csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `usage-${activeDimension.value.id}-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  loadFilters()
  load()
})
</script>

<template>
  <section class="page-stack usage-report-page">
    <div class="section-head report-head">
      <span class="report-head-note">{{ rangeLabel }} · {{ usageGranularityLabel(report?.granularity) }}</span>
      <div class="report-head-actions">
        <button type="button" class="button ghost refresh-button" :disabled="loading || refreshing" :aria-busy="refreshing" @click="refresh">
          <Icon name="refresh" :size="16" /> {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
        <button type="button" class="button subtle" :disabled="!report" @click="exportCsv">
          <Icon name="down" :size="16" /> 导出 CSV
        </button>
      </div>
    </div>

    <FilterPanel label="时间与筛选" :summary="filterRangeSummary" :count="filterChips.length">
    <div class="panel usage-filter-control">
    <div class="report-range-bar">
      <div class="report-presets" role="group" aria-label="快捷时间范围">
        <button
          v-for="item in USAGE_REPORT_PRESETS"
          :key="item.id"
          type="button"
          class="report-preset"
          :class="{ active: activePreset === item.id }"
          @click="selectPreset(item.id)"
        >{{ item.label }}</button>
      </div>
      <div class="report-custom-range">
        <label class="report-range-field">
          <span>开始</span>
          <input v-model="rangeDraft.from" type="datetime-local" step="60" :max="rangeDraft.to" @input="onRangeInput('from')" @change="onRangeChange" />
        </label>
        <span class="report-range-sep">—</span>
        <label class="report-range-field">
          <span>结束</span>
          <input v-model="rangeDraft.to" type="datetime-local" step="60" :min="rangeDraft.from" :max="maxDatetimeLocal" @input="onRangeInput('to')" @change="onRangeChange" />
        </label>
      </div>
      <div class="report-range-hint">
        <span>最长 30 天</span>
        <span v-if="rangeError" class="report-range-error">{{ rangeError }}</span>
      </div>
    </div>

    <div class="report-filter-bar">
      <div class="report-filter-fields">
        <FilterSelect v-model="filters.clientKeyId" label="调用方密钥" searchable :disabled="loading || refreshing" :options="[{ value: '', label: '全部密钥' }, ...clientKeys.map(key => ({ value: key.id, label: key.name + ' · ' + key.keyPrefix }))]" @update:model-value="load()" />
        <FilterSelect v-model="filters.channelId" label="渠道" searchable :disabled="loading || refreshing" :options="[{ value: '', label: '全部渠道' }, ...channels.map(channel => ({ value: channel.id, label: channel.name }))]" @update:model-value="load()" />
        <FilterSelect v-model="filters.model" label="模型" searchable :disabled="loading || refreshing" :options="[{ value: '', label: '全部模型' }, ...modelOptions.map(model => ({ value: model, label: model }))]" @update:model-value="load()" />
        <FilterSelect v-model="filters.protocol" label="协议" :disabled="loading || refreshing" :options="[{ value: '', label: '全部协议' }, { value: 'chat_completions', label: 'Chat Completions' }, { value: 'responses', label: 'Responses' }, { value: 'messages', label: 'Messages' }]" @update:model-value="load()" />
        <button v-if="hasFilters" type="button" class="button ghost report-reset" @click="resetFilters">清除筛选</button>
      </div>
    </div>
    </div>

    <template #chips>
      <button v-if="preset !== 'last7d'" type="button" class="filter-chip" aria-label="恢复默认时间：最近 7 天" @click="selectPreset('last7d')"><span>{{ filterRangeSummary }}</span><Icon name="close" :size="13" /></button>
      <button v-for="chip in filterChips" :key="chip.key" type="button" class="filter-chip" :aria-label="'移除筛选：' + chip.label" @click="removeFilter(chip.key)"><span>{{ chip.label }}</span><Icon name="close" :size="13" /></button>
    </template>
    </FilterPanel>

    <ErrorState v-if="error" :message="error" :busy="loading || refreshing" @retry="load" />
    <TablePlaceholder v-if="loading && !report" :loading="true" loading-text="正在汇总请求日志…" />
    <template v-else-if="report">
      <div class="stat-row four usage-kpis">
        <div class="stat-card">
          <span>请求</span><strong>{{ formatNumber(total.requests) }}</strong>
          <span class="usage-kpi-delta" :title="comparisonTitle">较上期 <b :class="'text-' + (comparison?.requestsTone || 'neutral')">{{ comparison?.requests || '—' }}</b></span>
          <small>{{ total.errors }} 错误 · 成功率 {{ successRate }}</small>
          <small :title="comparisonTitle">错误较上期 <b :class="'text-' + (comparison?.errorsTone || 'neutral')">{{ comparison?.errors || '—' }}</b></small>
        </div>
        <div class="stat-card">
          <span>总 token</span><strong>{{ formatTokensCompact(total.totalTokens) }}</strong>
          <span class="usage-kpi-delta" :title="comparisonTitle">较上期 <b>{{ comparison?.tokens || '—' }}</b></span>
          <small>输入 {{ formatTokensCompact(total.inputTokens) }} · 输出 {{ formatTokensCompact(total.outputTokens) }}</small>
        </div>
        <div class="stat-card accent">
          <span>费用</span>
          <strong v-if="total.costUsd != null" class="cost-amount report-cost usage-exact-cost" tabindex="0" :data-exact="total.costUsd + ' USD'" :aria-label="'费用 ' + total.costUsd + ' USD'">{{ formatUsageCost(total.costUsd) }}</strong>
          <strong v-else class="report-cost muted">—</strong>
          <span class="usage-kpi-delta" :title="comparisonTitle">较上期 <b :class="'text-' + (comparison?.costTone || 'neutral')">{{ comparison?.cost || '—' }}</b></span>
          <small>USD 计费合计</small>
        </div>
        <div class="stat-card">
          <span>平均首 token</span><strong>{{ latency(total.avgFirstTokenMs) }}</strong>
          <span class="usage-kpi-delta" :title="comparisonTitle">较上期 <b :class="'text-' + (comparison?.latencyTone || 'neutral')">{{ comparison?.latency || '—' }}</b></span>
          <small>平均总耗时 {{ latency(total.avgTotalMs) }}</small>
        </div>
      </div>
      <div class="usage-support-metrics">
        <div class="usage-support-group"><span class="usage-support-label">缓存效率</span><strong title="缓存命中率 = 缓存读 token / 输入 token；输入 token 已包含缓存读写">命中率 {{ cacheHitText }}</strong><span>读 {{ formatTokensCompact(total.cachedTokens) }}</span><span>写 {{ formatTokensCompact(total.cachedWriteTokens) }}</span></div>
        <div class="usage-support-group"><span class="usage-support-label">推理用量</span><strong>{{ formatTokensCompact(total.reasoningTokens) }} token</strong><span>已含在输出用量中</span></div>
        <div class="usage-support-group"><span class="usage-support-label">路由运行</span><strong>{{ formatNumber(total.failovers) }} 次故障转移</strong><span>{{ failoverRate }} / 请求</span><span>{{ total.activeClientKeys }} 个活跃密钥</span></div>
      </div>

      <div class="panel usage-trend-panel">
        <div class="report-panel-head">
          <div class="report-panel-title">
            <h3>用量趋势</h3>
            <span class="report-count">{{ trendRows.length }} 个时间点</span>
          </div>
          <div class="usage-trend-controls">
            <div class="usage-segmented" role="tablist" aria-label="趋势指标">
              <button v-for="metric in trendMetrics" :key="metric.id" type="button" :class="{ active: trendMetric === metric.id }" @click="trendMetric = metric.id">{{ metric.label }}</button>
            </div>
          </div>
        </div>
        <div v-if="!trendRows.length" class="usage-chart-empty">该时间范围没有请求。</div>
        <div v-else class="usage-chart-wrap" @focusout="chartFocusOut">
          <div class="usage-chart-readout">
            <div class="usage-chart-values" aria-live="polite" aria-atomic="true"><strong>{{ chartRow ? formatTimeRange(chartRow.bucketStart) : '选择时间点' }}</strong><span v-for="series in activeTrendMetric.series" :key="series.id"><span class="usage-chart-series-label" :title="series.label">{{ series.label }}</span><b>{{ chartRow ? formatTrendValue(series.value(chartRow)) : '—' }}</b></span></div>
            <button type="button" class="button subtle usage-chart-filter" :disabled="!chartRow" @click="selectChartBucket">只看此时间段</button>
          </div>
          <div class="usage-chart-with-axis">
          <div class="usage-chart-y-axis" aria-hidden="true"><span v-for="tick in chartScale.ticks" :key="tick" :style="{ top: `${(142 - tick / chartScale.max * 134) / 150 * 100}%` }">{{ trendMetric === 'errorRate' ? tick + '%' : trendMetric === 'cost' ? Number(tick.toPrecision(6)).toLocaleString('zh-CN', { maximumFractionDigits: 8 }) : formatTokensCompact(tick) }}</span></div>
          <svg class="usage-chart" viewBox="0 0 640 150" preserveAspectRatio="none" role="slider" tabindex="0" aria-orientation="horizontal" :aria-valuemin="1" :aria-valuemax="trendRows.length" :aria-valuenow="chartSliderIndex + 1" :aria-valuetext="chartValueText" @focus="chartIndex = chartSliderIndex" :aria-label="`${activeTrendMetric.label}趋势，${trendMetric === 'errorRate' ? '单位 百分比' : trendMetric === 'cost' ? '单位 USD' : trendMetric === 'tokens' ? '单位 token' : '单位 次'}，使用左右方向键查看读数，按 Enter 选中时间段`" @pointermove="inspectChart" @pointerdown="inspectChart" @keydown="chartKey">
            <defs><linearGradient id="usage-fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stop-color="var(--brand)" stop-opacity="0.22"/><stop offset="100%" stop-color="var(--brand)" stop-opacity="0.01"/></linearGradient></defs>
            <line v-for="tick in chartScale.ticks" :key="tick" x1="8" :y1="142 - tick / chartScale.max * 134" x2="632" :y2="142 - tick / chartScale.max * 134" class="usage-chart-grid" />
            <polygon v-if="trendArea" :points="trendArea" class="usage-chart-area" />
            <template v-for="series in trendSeriesPoints" :key="series.id"><template v-for="(points, index) in series.segments" :key="index"><polyline :points="points" :class="['usage-chart-line', series.className]" /><circle v-if="!points.includes(' ')" :cx="points.split(',')[0]" :cy="points.split(',')[1]" r="3" :class="['usage-chart-point', series.className]" /></template></template>
            <template v-if="chartSeriesPoints.length"><line :x1="chartSeriesPoints[0].x" :x2="chartSeriesPoints[0].x" y1="8" y2="142" class="usage-chart-crosshair" /><circle v-for="point in chartSeriesPoints" :key="point.id" :cx="point.x" :cy="point.y" :r="point.className ? 3.5 : 4" :class="['usage-chart-point', point.className]" /></template>
          </svg>
          </div>
          <div class="usage-chart-axis">
            <span v-for="(row, index) in trendRows" :key="row.bucketStart" :class="{ 'is-hidden': trendRows.length > 12 && index % Math.ceil(trendRows.length / 8) !== 0 }">{{ formatBucket(row.bucketStart) }}</span>
          </div>
          <div class="usage-chart-legend">
            <span v-for="series in trendSeriesPoints" :key="series.id"><i :class="['dot', series.className]"></i>{{ series.label }} 最高 {{ formatTrendValue(series.max) }}</span>
            <span v-if="trendMetric === 'tokens'" class="usage-chart-note" title="输入 token 包含缓存读与缓存写；缓存命中率只统计缓存读">缓存读 / 写是输入 token 的子集 · 整体缓存命中率 {{ cacheHitText }}</span>
            <span v-if="trendMetric === 'errorRate'">错误数 / 请求数 · 无请求的时段不连线</span>
            <span v-if="trendMetric === 'cost'">仅统计 USD 费用</span>
          </div>
        </div>
      </div>

      <div class="panel report-panel usage-dimension-panel">
        <div class="report-panel-head usage-dimension-head">
          <div class="report-panel-title">
            <h3>{{ activeDimension.title }}</h3>
            <span class="report-count">{{ dimensionRows.length }} 项</span>
          </div>
          <div class="usage-segmented usage-dimension-tabs" role="tablist" aria-label="报表分组维度">
            <button v-for="item in dimensions" :key="item.id" type="button" :class="{ active: dimension === item.id }" @click="dimension = item.id">{{ item.label }}</button>
          </div>
        </div>
        <TablePlaceholder v-if="!dimensionRows.length" :empty="true" compact title="该维度没有数据" desc="调整筛选条件或时间范围后再试。" />
        <div v-else-if="dimension === 'error'" v-scroll-hint class="table-scroll">
          <table class="data-table compact-table usage-dimension-table">
            <thead><tr><th>错误码</th><th>失败请求</th><th>占全部请求</th></tr></thead>
            <tbody>
              <tr v-for="row in dimensionRows" :key="row.id">
                <td><code class="usage-error-code">{{ row.id }}</code></td>
                <td><strong>{{ formatNumber(row.requests) }}</strong></td>
                <td>{{ total.requests ? `${((row.requests / total.requests) * 100).toFixed(1)}%` : '0%' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else v-scroll-hint class="table-scroll">
          <table class="data-table compact-table usage-dimension-table">
            <thead>
              <tr><th>{{ activeDimension.label }}</th><th>请求</th><th>成功率</th><th>Token</th><th>延迟</th><th>费用</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in dimensionRows" :key="row.id">
                <td class="usage-dimension-name">
                  <div class="usage-dimension-title"><strong :title="row.name || row.id">{{ displayName(row) }}</strong><CopyButton v-if="['clientKey', 'channel', 'channelKey'].includes(dimension) && (!row.name || row.name === row.id)" :text="row.id" :title="'复制' + activeDimension.label + ' ID'" /></div>
                  <div v-if="row.name && row.id !== row.name" class="usage-dimension-id"><code :title="row.id">{{ dimensionId(row) }}</code><CopyButton v-if="['clientKey', 'channel', 'channelKey'].includes(dimension)" :text="row.id" :title="'复制' + activeDimension.label + ' ID'" /></div>
                </td>
                <td><strong>{{ formatNumber(row.requests) }}</strong><small class="muted"> / {{ row.errors }} 错误</small></td>
                <td><span class="badge" :class="row.errors ? 'warning' : 'success'">{{ errorRate(row) }}</span></td>
                <td>{{ formatTokensCompact(row.totalTokens) }}<small class="muted"> 入 {{ formatTokensCompact(row.inputTokens) }} / 出 {{ formatTokensCompact(row.outputTokens) }}</small></td>
                <td>{{ latency(row.avgTotalMs) }}<small class="muted"> 首 token {{ latency(row.avgFirstTokenMs) }}</small></td>
                <td class="small"><span class="usage-exact-cost" tabindex="0" :data-exact="row.costUsd != null ? row.costUsd + ' USD' : '无计费数据'" :aria-label="row.costUsd != null ? row.costUsd + ' USD' : '无计费数据'">{{ costText(row) }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="usage-insight-grid">
        <div class="panel usage-insight-card">
          <div class="report-panel-head"><div class="report-panel-title"><h3>错误分布</h3><span class="report-count">{{ report.byErrorCode.length }} 种</span></div></div>
          <div v-if="!report.byErrorCode.length" class="usage-chart-empty">该时间范围没有失败请求。</div>
          <div v-else class="usage-error-list">
            <div v-for="row in errorDistribution" :key="row.id" class="usage-error-row">
              <div><code :title="row.id">{{ row.id }}</code><span>{{ formatNumber(row.requests) }} 次（{{ row.percent.toFixed(1) }}%）</span></div>
              <div class="usage-error-track"><span :style="{ width: row.percent + '%' }"></span></div>
            </div>
          </div>
        </div>
        <div class="panel usage-insight-card usage-latency-panel">
          <div class="report-panel-head"><div class="report-panel-title"><h3>成功请求耗时分位</h3><span class="report-count">总耗时</span></div></div>
          <div class="usage-percentiles"><div><span>P50</span><strong>{{ latency(total.p50TotalMs) }}</strong><small>中位耗时</small></div><div><span>P90</span><strong>{{ latency(total.p90TotalMs) }}</strong><small>90% 请求不超过</small></div><div><span>P99</span><strong>{{ latency(total.p99TotalMs) }}</strong><small>99% 请求不超过</small></div></div>
          <p class="usage-latency-note" v-if="total.successfulLatencySamples != null">基于 {{ formatNumber(total.successfulLatencySamples) }} 个最终成功请求（含重试成功）；排除失败、取消、中断及负耗时。{{ total.successfulLatencySamples === 0 ? '暂无有效样本。' : '按连续分位数插值计算。' }}</p>
          <p class="usage-latency-note" v-else>当前接口未提供耗时分位数据，请更新后端；不会使用平均值估算。</p>
        </div>
      </div>

    </template>
  </section>
</template>

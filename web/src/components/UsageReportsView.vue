<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatNumber, formatTokensCompact, formatDuration } from '../utils/format'
import {
  usageCostEntries,
  usageCostForCurrency,
  USAGE_REPORT_PRESETS,
  usageReportPresetRange,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  validateUsageReportRange,
  reportTimezoneOffset,
  fillTrendBuckets,
  buildTrendPoints,
  usageTrendMax,
  usageModelOptions,
  usagePercent,
  usageGranularityLabel,
  usageReportCsv,
} from '../utils/usageReport'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { createRetentionReportState } from '../utils/retentionReportState'

const preset = ref('last7d')
const rangeDraft = ref(createRangeDraft('last7d'))
const rangeError = ref('')
const maxDatetimeLocal = ref(toDatetimeLocalValue(Date.now()))
const filters = ref({ clientKeyId: '', channelId: '', model: '', protocol: '' })
const report = ref(null)
const retention = ref(null)
const channels = ref([])
const clientKeys = ref([])
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const cleaning = ref(false)
const updatingRetention = ref(false)
const retentionState = createRetentionReportState()
const requests = createLatestRequestGuard()
const dimension = ref('clientKey')
const trendMetric = ref('requests')
const costMetricCurrency = ref('')

const dimensions = [
  { id: 'clientKey', label: '调用方密钥', title: '按调用方密钥', key: 'byClientKey' },
  { id: 'channel', label: '渠道', title: '按渠道', key: 'byChannel' },
  { id: 'model', label: '模型', title: '按模型', key: 'byModel' },
  { id: 'channelKey', label: '渠道密钥', title: '按渠道密钥', key: 'byChannelKey' },
  { id: 'protocol', label: '协议', title: '按协议', key: 'byProtocol' },
  { id: 'error', label: '错误', title: '按错误码', key: 'byErrorCode' },
]
const trendMetrics = [
  { id: 'requests', label: '请求与错误', primaryLabel: '请求', secondaryLabel: '错误', value: (row) => row.requests, secondary: (row) => row.errors },
  { id: 'tokens', label: '输入 / 输出 token', primaryLabel: '输入 token', secondaryLabel: '输出 token', value: (row) => row.inputTokens, secondary: (row) => row.outputTokens },
  { id: 'cost', label: '费用', primaryLabel: '费用', value: (row) => usageCostForCurrency(row.costs, costMetricCurrency.value) },
]
const protocolLabels = { chat_completions: 'Chat Completions', responses: 'Responses', messages: 'Messages' }

const total = computed(() => report.value?.total || {
  requests: 0, errors: 0, inputTokens: 0, outputTokens: 0, cachedTokens: 0, cachedWriteTokens: 0,
  reasoningTokens: 0, totalTokens: 0, activeClientKeys: 0, failovers: 0, avgFirstTokenMs: null, avgTotalMs: null, costs: {},
})
const successRate = computed(() => usagePercent(total.value.requests, total.value.errors))
const activeDimension = computed(() => dimensions.find((item) => item.id === dimension.value) || dimensions[0])
const dimensionRows = computed(() => report.value?.[activeDimension.value.key] || [])
const trendRows = computed(() => fillTrendBuckets(
  report.value?.trend || [],
  report.value?.from,
  report.value?.to,
  report.value?.granularity,
  reportTimezoneOffset(),
))
const activeTrendMetric = computed(() => trendMetrics.find((item) => item.id === trendMetric.value) || trendMetrics[0])
const trendMaxValue = computed(() => usageTrendMax(
  trendRows.value,
  activeTrendMetric.value.value,
  activeTrendMetric.value.secondary,
))
const trendPrimaryPoints = computed(() => buildTrendPoints(
  trendRows.value,
  activeTrendMetric.value.value,
  640,
  150,
  8,
  trendMaxValue.value,
))
const trendSecondaryPoints = computed(() => {
  if (!activeTrendMetric.value.secondary) return ''
  return buildTrendPoints(trendRows.value, activeTrendMetric.value.secondary, 640, 150, 8, trendMaxValue.value)
})
const trendSecondaryMax = computed(() => activeTrendMetric.value.secondary
  ? Math.max(0, ...trendRows.value.map((row) => Number(activeTrendMetric.value.secondary(row)) || 0))
  : 0)
const trendArea = computed(() => {
  if (!trendPrimaryPoints.value) return ''
  const points = trendPrimaryPoints.value.split(' ')
  return `8,142 ${trendPrimaryPoints.value} ${points.at(-1).split(',')[0]},142`
})
const costCurrencies = computed(() => usageCostEntries(total.value.costs).map((entry) => entry.currency))
const modelOptions = computed(() => usageModelOptions(report.value, channels.value))
const hasFilters = computed(() => Object.values(filters.value).some(Boolean))
const rangeLabel = computed(() => report.value ? `${formatTimeRange(report.value.from)} — ${formatTimeRange(report.value.to)}` : '')
const activePreset = computed(() => preset.value)
const topError = computed(() => (report.value?.byErrorCode || [])[0] || null)
const failoverRate = computed(() => total.value.requests ? `${((total.value.failovers / total.value.requests) * 100).toFixed(1)}%` : '0%')

function formatTimeRange(value) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function formatBucket(value) {
  const options = report.value?.granularity === 'hour'
    ? { month: 'numeric', day: 'numeric', hour: '2-digit' }
    : { month: 'numeric', day: 'numeric' }
  return new Intl.DateTimeFormat('zh-CN', options).format(new Date(value))
}

function formatTrendValue(value) {
  if (trendMetric.value === 'cost') {
    return `${costMetricCurrency.value} ${Number(value).toFixed(4)}`
  }
  return formatTokensCompact(value)
}

function displayName(row) {
  const fallback = {
    __unassigned_key__: '未关联调用方密钥',
    __unrouted__: '未完成路由',
    __unresolved_model__: '未解析模型',
    __unassigned_channel_key__: '未使用渠道密钥',
    __unknown_protocol__: '未知协议',
  }
  if (dimension.value === 'protocol') return protocolLabel(row.id)
  return fallback[row.id] || row.name || row.id
}

function protocolLabel(value) {
  return protocolLabels[value] || value
}

function costText(costs) {
  const entries = usageCostEntries(costs)
  return entries.length ? entries.map((entry) => `${entry.currency} ${entry.value}`).join(' / ') : '—'
}

function latency(value) {
  return value == null ? '—' : formatDuration(value)
}

function errorRate(row) {
  return usagePercent(row.requests, row.errors)
}

function createRangeDraft(presetId, now = Date.now()) {
  const { from, to } = usageReportPresetRange(presetId, now)
  return { from: toDatetimeLocalValue(from), to: toDatetimeLocalValue(to) }
}

function currentRange() {
  // 预设使用毫秒精度，避免 datetime-local 截断到分钟而漏掉最后一分钟。
  if (preset.value) return usageReportPresetRange(preset.value)
  const from = fromDatetimeLocalValue(rangeDraft.value.from)
  const to = fromDatetimeLocalValue(rangeDraft.value.to)
  return { from, to }
}

function selectPreset(presetId) {
  preset.value = presetId
  rangeDraft.value = createRangeDraft(presetId)
  rangeError.value = ''
  load()
}

function refresh() {
  if (preset.value) rangeDraft.value = createRangeDraft(preset.value)
  maxDatetimeLocal.value = toDatetimeLocalValue(Date.now())
  load({ quiet: true })
}

function onRangeInput() {
  // 手动修改时间后退出快捷项高亮，提交时再统一校验。
  preset.value = ''
}

function onRangeChange() {
  const { from, to } = currentRange()
  const message = validateUsageReportRange(from, to)
  rangeError.value = message
  if (!message) load()
}

async function load({ quiet = false } = {}) {
  const { from, to } = currentRange()
  const message = validateUsageReportRange(from, to)
  rangeError.value = message
  if (message) {
    loading.value = false
    refreshing.value = false
    return
  }
  if (quiet) refreshing.value = true
  else loading.value = true
  error.value = ''
  const requestRetentionVersion = retentionState.beginLoad()
  try {
    const result = await requests.run(async () => {
      const [nextReport, nextRetention] = await Promise.all([
        api.getUsageReport({
          from,
          to,
          ...filters.value,
          timezoneOffsetMinutes: reportTimezoneOffset(),
        }),
        api.getRetentionPolicy(),
      ])
      return { nextReport, nextRetention }
    }).catch((cause) => ({ error: cause, isLatest: cause.isLatest }))
    if (!result.isLatest) return
    if (result.error) error.value = result.error.message
    else {
      report.value = result.value.nextReport
      const currencies = usageCostEntries(result.value.nextReport?.total?.costs).map((entry) => entry.currency)
      if (!currencies.includes(costMetricCurrency.value)) costMetricCurrency.value = currencies[0] || 'USD'
      if (retentionState.shouldApplyRetention(requestRetentionVersion)) {
        retention.value = result.value.nextRetention
      }
    }
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

function resetFilters() {
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

async function cleanup() {
  if (!window.confirm('清理超过保留期的请求日志？当天的额度账本不会删除。')) return
  cleaning.value = true
  try {
    const result = await api.cleanupRequestLogs()
    notify(`已清理 ${formatNumber(result.deleted)} 条请求日志`)
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    cleaning.value = false
  }
}

async function toggleCleanup(event) {
  if (!retention.value || updatingRetention.value) return
  const input = event.currentTarget
  const enabled = input.checked
  updatingRetention.value = true
  retentionState.beginToggle()
  try {
    retention.value = await api.updateRetentionPolicy({ cleanupEnabled: enabled })
    notify('自动清理已' + (enabled ? '开启' : '关闭'))
  } catch (cause) {
    input.checked = retention.value.cleanupEnabled
    notify(cause.message, 'error')
  } finally {
    retentionState.endToggle()
    updatingRetention.value = false
  }
}

onMounted(() => {
  loadFilters()
  load()
})
</script>

<template>
  <section class="page-stack usage-report-page">
    <div class="section-head report-head">
      <p class="report-head-note">费用按计费币种分别汇总，不做汇率换算。</p>
      <div class="report-head-actions">
        <button type="button" class="button subtle" :disabled="loading || refreshing" @click="refresh">
          <Icon name="refresh" :size="16" /> {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
        <button type="button" class="button subtle" :disabled="!report" @click="exportCsv">
          <Icon name="down" :size="16" /> 导出 CSV
        </button>
      </div>
    </div>

    <div class="panel report-range-bar">
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
          <input v-model="rangeDraft.from" type="datetime-local" :max="rangeDraft.to" @input="onRangeInput" @change="onRangeChange" />
        </label>
        <span class="report-range-sep">—</span>
        <label class="report-range-field">
          <span>结束</span>
          <input v-model="rangeDraft.to" type="datetime-local" :min="rangeDraft.from" :max="maxDatetimeLocal" @input="onRangeInput" @change="onRangeChange" />
        </label>
      </div>
      <div class="report-range-hint">
        <span>最长 30 天</span>
        <span v-if="rangeError" class="report-range-error">{{ rangeError }}</span>
      </div>
    </div>

    <div class="panel report-filter-bar">
      <div class="report-filter-fields">
        <label class="report-filter-field"><span>调用方密钥</span>
          <select v-model="filters.clientKeyId" class="select-input" @change="load()">
            <option value="">全部密钥</option>
            <option v-for="key in clientKeys" :key="key.id" :value="key.id">{{ key.name }} · {{ key.keyPrefix }}</option>
          </select>
        </label>
        <label class="report-filter-field"><span>渠道</span>
          <select v-model="filters.channelId" class="select-input" @change="load()">
            <option value="">全部渠道</option>
            <option v-for="channel in channels" :key="channel.id" :value="channel.id">{{ channel.name }}</option>
          </select>
        </label>
        <label class="report-filter-field"><span>模型</span>
          <select v-model="filters.model" class="select-input" @change="load()">
            <option value="">全部模型</option>
            <option v-for="model in modelOptions" :key="model" :value="model">{{ model }}</option>
          </select>
        </label>
        <label class="report-filter-field"><span>协议</span>
          <select v-model="filters.protocol" class="select-input" @change="load()">
            <option value="">全部协议</option>
            <option value="chat_completions">Chat Completions</option>
            <option value="responses">Responses</option>
            <option value="messages">Messages</option>
          </select>
        </label>
        <button v-if="hasFilters" type="button" class="button ghost report-reset" @click="resetFilters">清除筛选</button>
      </div>
      <div class="report-range-copy"><span>{{ rangeLabel }}</span><span>{{ usageGranularityLabel(report?.granularity) }}</span></div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>
    <TablePlaceholder v-if="loading && !report" :loading="true" loading-text="正在汇总请求日志…" />
    <template v-else-if="report">
      <div class="stat-row four usage-kpis">
        <div class="stat-card">
          <span>请求</span><strong>{{ formatNumber(total.requests) }}</strong>
          <small>{{ total.errors }} 错误 · 成功率 {{ successRate }}</small>
        </div>
        <div class="stat-card">
          <span>总 token</span><strong>{{ formatTokensCompact(total.totalTokens) }}</strong>
          <small>输入 {{ formatTokensCompact(total.inputTokens) }} · 输出 {{ formatTokensCompact(total.outputTokens) }}</small>
        </div>
        <div class="stat-card accent">
          <span>费用</span>
          <div class="cost-lines" v-if="usageCostEntries(total.costs).length">
            <div class="cost-line" v-for="entry in usageCostEntries(total.costs)" :key="entry.currency">
              <strong class="cost-amount">{{ entry.value }}</strong>
              <span v-if="entry.currency !== 'USD'" class="cost-currency">{{ entry.currency }}</span>
            </div>
          </div>
          <strong v-else class="report-cost muted">—</strong>
          <small>按计费币种分别汇总</small>
        </div>
        <div class="stat-card">
          <span>平均首 token</span><strong>{{ latency(total.avgFirstTokenMs) }}</strong>
          <small>平均总耗时 {{ latency(total.avgTotalMs) }}</small>
        </div>
      </div>

      <div class="usage-secondary-kpis">
        <span><b>{{ formatNumber(total.cachedTokens) }}</b> 缓存读</span>
        <span><b>{{ formatNumber(total.cachedWriteTokens) }}</b> 缓存写</span>
        <span><b>{{ formatNumber(total.reasoningTokens) }}</b> 推理 token</span>
        <span><b>{{ total.activeClientKeys }}</b> 活跃调用方密钥</span>
        <span><b>{{ total.failovers }}</b> 故障转移（{{ failoverRate }}）</span>
        <span v-if="topError">主要错误 <b>{{ topError.id }}</b> · {{ topError.requests }} 次</span>
      </div>

      <div class="panel usage-trend-panel">
        <div class="report-panel-head">
          <div class="report-panel-title">
            <h3>用量趋势</h3>
            <span class="report-count">{{ trendRows.length }} 个时间点</span>
          </div>
          <div class="usage-trend-controls">
            <label v-if="trendMetric === 'cost' && costCurrencies.length > 1" class="usage-currency-select">
              <span>币种</span>
              <select v-model="costMetricCurrency" class="select-input">
                <option v-for="currency in costCurrencies" :key="currency" :value="currency">{{ currency }}</option>
              </select>
            </label>
            <div class="usage-segmented" role="tablist" aria-label="趋势指标">
              <button v-for="metric in trendMetrics" :key="metric.id" type="button" :class="{ active: trendMetric === metric.id }" @click="trendMetric = metric.id">{{ metric.label }}</button>
            </div>
          </div>
        </div>
        <div v-if="!trendRows.length" class="usage-chart-empty">该时间范围没有请求。</div>
        <div v-else class="usage-chart-wrap">
          <svg class="usage-chart" viewBox="0 0 640 150" preserveAspectRatio="none" role="img" :aria-label="`${activeTrendMetric.label}趋势`">
            <defs><linearGradient id="usage-fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stop-color="var(--brand)" stop-opacity="0.22"/><stop offset="100%" stop-color="var(--brand)" stop-opacity="0.01"/></linearGradient></defs>
            <line v-for="n in 4" :key="n" x1="8" :y1="8 + (n - 1) * 42" x2="632" :y2="8 + (n - 1) * 42" class="usage-chart-grid" />
            <polygon :points="trendArea" class="usage-chart-area" />
            <polyline v-if="trendSecondaryPoints" :points="trendSecondaryPoints" class="usage-chart-line secondary" />
            <polyline :points="trendPrimaryPoints" class="usage-chart-line" />
          </svg>
          <div class="usage-chart-axis">
            <span v-for="(row, index) in trendRows" :key="row.bucketStart" :class="{ 'is-hidden': trendRows.length > 12 && index % Math.ceil(trendRows.length / 8) !== 0 }">{{ formatBucket(row.bucketStart) }}</span>
          </div>
          <div class="usage-chart-legend">
            <span><i class="dot primary"></i>{{ activeTrendMetric.primaryLabel }} 最高 {{ formatTrendValue(Math.max(...trendRows.map(activeTrendMetric.value))) }}</span>
            <span v-if="trendMetric === 'cost'">仅统计 {{ costMetricCurrency }}，不合并其他币种</span>
            <span v-if="activeTrendMetric.secondary"><i class="dot secondary"></i>{{ activeTrendMetric.secondaryLabel }} 最高 {{ formatTrendValue(trendSecondaryMax) }}</span>
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
        <div v-else-if="dimension === 'error'" class="table-scroll">
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
        <div v-else class="table-scroll">
          <table class="data-table compact-table usage-dimension-table">
            <thead>
              <tr><th>{{ activeDimension.label }}</th><th>请求</th><th>成功率</th><th>Token</th><th>延迟</th><th>费用</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in dimensionRows" :key="row.id">
                <td class="usage-dimension-name">
                  <strong>{{ displayName(row) }}</strong>
                  <small v-if="row.id !== row.name">{{ protocolLabel(row.id) }}</small>
                </td>
                <td><strong>{{ formatNumber(row.requests) }}</strong><small class="muted"> / {{ row.errors }} 错误</small></td>
                <td><span class="badge" :class="row.errors ? 'warning' : 'success'">{{ errorRate(row) }}</span></td>
                <td>{{ formatTokensCompact(row.totalTokens) }}<small class="muted"> 入 {{ formatTokensCompact(row.inputTokens) }} / 出 {{ formatTokensCompact(row.outputTokens) }}</small></td>
                <td>{{ latency(row.avgTotalMs) }}<small class="muted"> 首 token {{ latency(row.avgFirstTokenMs) }}</small></td>
                <td class="small">{{ costText(row.costs) }}</td>
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
            <div v-for="row in report.byErrorCode.slice(0, 6)" :key="row.id" class="usage-error-row">
              <div><code>{{ row.id }}</code><span>{{ formatNumber(row.requests) }} 次</span></div>
              <div class="usage-error-track"><span :style="{ width: `${Math.max(4, (row.requests / report.byErrorCode[0].requests) * 100)}%` }"></span></div>
            </div>
          </div>
        </div>
        <div class="panel usage-insight-card">
          <div class="report-panel-head"><div class="report-panel-title"><h3>运行摘要</h3><span class="report-count">实时聚合</span></div></div>
          <div class="usage-summary-list">
            <div><span>成功请求</span><strong>{{ formatNumber(total.requests - total.errors) }}</strong></div>
            <div><span>失败请求</span><strong>{{ formatNumber(total.errors) }}</strong></div>
            <div><span>故障转移次数</span><strong>{{ formatNumber(total.failovers) }}</strong></div>
            <div><span>平均总耗时</span><strong>{{ latency(total.avgTotalMs) }}</strong></div>
            <div><span>数据粒度</span><strong>{{ usageGranularityLabel(report.granularity) }}</strong></div>
          </div>
        </div>
      </div>

      <div v-if="retention" class="panel retention-panel">
        <div class="retention-copy">
          <div class="retention-title">
            <h3>请求日志保留</h3>
            <label class="retention-toggle" :class="{ 'is-saving': updatingRetention }">
              <input
                type="checkbox"
                :checked="retention.cleanupEnabled"
                :disabled="updatingRetention"
                :aria-label="retention.cleanupEnabled ? '关闭自动清理' : '开启自动清理'"
                @change="toggleCleanup"
              />
              <span class="switch"></span>
              <span class="retention-status" :class="retention.cleanupEnabled ? 'is-on' : 'is-off'">
                {{ updatingRetention ? '正在更新…' : (retention.cleanupEnabled ? '自动清理已开启' : '自动清理已关闭') }}
              </span>
            </label>
          </div>
          <p class="muted small">
            保留 {{ retention.retentionDays }} 天 · 每批 {{ formatNumber(retention.batchSize) }} 条。清理日志会影响历史报表，但每日额度账本不受影响。
          </p>
        </div>
        <button type="button" class="button subtle retention-action" :disabled="cleaning" @click="cleanup">
          {{ cleaning ? '清理中…' : '立即清理过期日志' }}
        </button>
      </div>
    </template>
  </section>
</template>

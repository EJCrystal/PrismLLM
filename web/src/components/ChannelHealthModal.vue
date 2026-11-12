<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import type { Channel, ChannelHealthDetail, HealthWindow, HealthBreakdown, UsageReport } from '../types/api'
import { HEALTH_WINDOWS, HEALTH_LABELS, healthSymbol, healthPercent, healthBucketState, healthCacheHitText, healthCacheRows, healthThroughput, healthMetricWarnings } from '../utils/channelHealth'
import { cooldownReasonLabel } from '../utils/cooldown'
import { formatDuration, formatTime, formatTokensCompact } from '../utils/format'
import Modal from './Modal.vue'
import ErrorState from './ErrorState.vue'
import { vScrollHint } from '../directives/scrollHint'

const props = defineProps<{ channel: Channel; initialWindow: HealthWindow; concurrentRequests?: number; lastProbeAt?: number }>()
const emit = defineEmits<{ close: [changed?: boolean]; changed: [] }>()
const window = ref<HealthWindow>(props.initialWindow)
const detail = ref<ChannelHealthDetail | null>(null)
const usage = ref<UsageReport | null>(null)
const usageState = ref<'idle' | 'loading' | 'ready' | 'failed'>('idle')
const loading = ref(false)
const error = ref('')
const dimension = ref<'byModel' | 'byProtocol' | 'byKey'>('byModel')
const detailView = ref<'performance' | 'cache'>('performance')
const selectedBucket = ref<number | null>(null)
const hasCooldownCleared = ref(false)
const hasManualRefreshed = ref(false)
const manualRefreshing = ref(false)
const hasUncertainCooldownClear = ref(false)
let revision = 0
let loadController: AbortController | null = null
let usageRevision = 0
let usageController: AbortController | null = null
async function load(timeoutMs = 15_000) {
  loadController?.abort()
  usageController?.abort()
  usageRevision++
  const controller = new AbortController()
  loadController = controller
  const request = ++revision
  loading.value = true
  error.value = ''
  detail.value = null
  usage.value = null
  usageState.value = 'idle'
  selectedBucket.value = null
  const timer = setTimeout(() => { controller.abort() }, timeoutMs)
  try {
    const result = await api.getChannelHealthDetail(props.channel.id, window.value, { signal: controller.signal })
    if (request === revision) {
      detail.value = result
      void loadUsage(result.report.from, result.report.to, request)
    }
  } catch (cause) {
    if (request === revision) {
      if (controller.signal.aborted) {
        error.value = '读取渠道健康度超时，请重试'
      } else {
        error.value = cause instanceof Error ? cause.message : '读取渠道健康度失败，请重试'
      }
    }
  } finally {
    clearTimeout(timer)
    if (loadController === controller) loadController = null
    if (request === revision) loading.value = false
  }
}
/** 缓存用量沿用健康度窗口，从用量报表按渠道聚合读取；失败不影响健康度主体。 */
async function loadUsage(from: number, to: number, request: number, timeoutMs = 15_000) {
  const controller = new AbortController()
  usageController = controller
  const token = ++usageRevision
  usageState.value = 'loading'
  const timer = setTimeout(() => { controller.abort() }, timeoutMs)
  try {
    const report = await api.getUsageReport(
      { from, to, channelId: props.channel.id },
      { signal: controller.signal },
    )
    if (token === usageRevision && request === revision) {
      usage.value = report
      usageState.value = 'ready'
    }
  } catch {
    if (token === usageRevision && request === revision) {
      usage.value = null
      usageState.value = 'failed'
    }
  } finally {
    clearTimeout(timer)
    if (usageController === controller) usageController = null
  }
}
watch([window, () => props.channel.id], () => void load(), { immediate: true })
onBeforeUnmount(() => {
  revision++
  usageRevision++
  loadController?.abort()
  usageController?.abort()
  clearingController?.abort()
})
const summary = computed(() => detail.value?.report.channels[0])
const metrics = computed(() => summary.value?.metrics)
const warnings = computed(() => summary.value && detail.value ? healthMetricWarnings(summary.value.state, summary.value.metrics, detail.value.report) : null)
const cacheHitText = computed(() => healthCacheHitText(usage.value?.total, usageState.value === 'loading'))
const cacheRows = computed(() => healthCacheRows(usage.value?.byModel))
const rows = computed<HealthBreakdown[]>(() => detail.value?.[dimension.value] || [])
const activeBucket = computed(() => selectedBucket.value == null ? null : detail.value?.trend[selectedBucket.value])
type AvailabilityRow = ChannelHealthDetail['availability'][number]
const unavailable = computed<AvailabilityRow[]>(() => detail.value?.availability.filter(row => row.state !== 'ACTIVE') || [])
const unavailableGroups = computed(() => {
  const groups = new Map<string, { keyId: string; keyName: string; rows: AvailabilityRow[] }>()
  for (const row of unavailable.value) {
    const group = groups.get(row.keyId) ?? { keyId: row.keyId, keyName: row.keyName, rows: [] }
    group.rows.push(row)
    groups.set(row.keyId, group)
  }
  return [...groups.values()]
})
const hasCooldown = computed(() => (summary.value?.coolingCandidates ?? 0) > 0 || unavailable.value.some(row => row.state === 'COOLDOWN'))
const clearing = ref<string | null>(null)
let clearingController: AbortController | null = null
const availabilityLabel: Record<string, string> = { CHANNEL_DISABLED: '渠道停用', MANUAL_DISABLED: '人工停用', AUTO_TEMPORARY: '自动临时停用', AUTO_PERMANENT: '自动永久停用', COOLDOWN: '冷却中' }
function groupHasCooldown(rows: AvailabilityRow[]): boolean {
  return rows.some(row => row.state === 'COOLDOWN' && row.cooldownScope === 'key')
}
function cooldownText(row: AvailabilityRow): string {
  const reason = row.state === 'COOLDOWN' ? cooldownReasonLabel(row.reason) : row.reason
  return [reason, row.until ? `截至 ${formatTime(row.until)}` : ''].filter(Boolean).join(' · ')
}
async function clearCooldown(scope: 'channel' | 'key' | 'candidate', keyId?: string, upstreamModel?: string, timeoutMs = 15_000) {
  if (clearing.value) return
  clearing.value = [scope, keyId, upstreamModel].filter(Boolean).join(':')
  error.value = ''
  clearingController?.abort()
  const controller = new AbortController()
  clearingController = controller
  const timer = setTimeout(() => { controller.abort() }, timeoutMs)
  try {
    await api.clearChannelCooldown(props.channel.id, { scope, keyId, upstreamModel }, { signal: controller.signal })
    hasCooldownCleared.value = true
    emit('changed')
    await load()
  } catch (cause) {
    hasUncertainCooldownClear.value = true
    if (controller.signal.aborted) {
      error.value = '解除冷却请求超时，请刷新以确认冷却状态'
    } else {
      error.value = cause instanceof Error ? cause.message : '解除冷却失败，请重试'
    }
  } finally {
    clearTimeout(timer)
    if (clearingController === controller) clearingController = null
    clearing.value = null
  }
}
function bucketLabel(bucket: { from: number; to: number; metrics: { attempts: number; successRate: number | null } }) {
  return `${formatTime(bucket.from)} 至 ${formatTime(bucket.to)}，${bucket.metrics.attempts} 次尝试，成功率 ${healthPercent(bucket.metrics.successRate)}`
}
async function handleManualRefresh() {
  if (manualRefreshing.value || loading.value) return
  manualRefreshing.value = true
  try {
    await load()
    hasManualRefreshed.value = true
  } finally {
    manualRefreshing.value = false
  }
}
function tryClose() {
  if (clearing.value || manualRefreshing.value) return
  emit('close', hasCooldownCleared.value || hasManualRefreshed.value || hasUncertainCooldownClear.value)
}
</script>

<template>
  <Modal :title="channel.name + ' · 渠道健康度'" wide @close="tryClose">
    <div class="health-detail">
      <div class="health-detail-toolbar">
        <div class="health-window-tabs" role="group" aria-label="健康度时间范围">
          <button v-for="option in HEALTH_WINDOWS" :key="option.id" type="button" :aria-pressed="window === option.id" :class="{ active: window === option.id }" @click="window = option.id">{{ option.label }}</button>
        </div>
        <dl v-if="concurrentRequests != null || lastProbeAt != null" class="health-runtime-meta">
          <div v-if="concurrentRequests != null"><dt>实时在途</dt><dd>{{ concurrentRequests }}</dd></div>
          <div><dt>协议探测</dt><dd>{{ lastProbeAt ? formatTime(lastProbeAt, { style: 'medium' }) : '暂无记录' }}</dd></div>
        </dl>
        <button type="button" class="button ghost mini health-refresh" :disabled="loading || Boolean(clearing) || manualRefreshing" @click="handleManualRefresh">刷新</button>
      </div>
      <ErrorState v-if="error" :message="error" @retry="load" />
      <p v-if="loading" class="muted" role="status">正在汇总渠道健康度…</p>
      <template v-if="detail && summary && metrics">
        <div class="health-verdict" :data-state="summary.state">
          <span class="health-status-icon" :data-state="summary.state" aria-hidden="true">{{ healthSymbol(summary.state) }}</span>
          <div class="health-verdict-copy"><strong>{{ HEALTH_LABELS[summary.state] }}</strong><p class="small">{{ summary.reason }}</p></div>
          <span class="muted small health-updated">{{ formatTime(detail.report.to) }} 更新</span>
        </div>
        <p v-if="detail.report.indexing" class="callout warning" role="status">历史数据正在分批补齐，当前统计尚不完整，请稍后刷新。</p>
        <p v-if="summary.requests.legacyRequests" class="callout warning">另有 {{ summary.requests.legacyRequests }} 条历史请求缺少上游尝试明细，未纳入健康度，避免误算。</p>
        <p v-if="detail.report.retentionDays < (window === '24h' ? 1 : Number(window.slice(0, -1)))" class="callout warning">当前日志保留 {{ detail.report.retentionDays }} 天，所选时间范围可能不完整。</p>
        <section class="health-overview health-card" aria-label="健康总览">
          <div class="health-metric-grid">
            <div>
              <span>上游尝试成功率</span>
              <strong :class="{ 'health-metric-warning': warnings?.failureRate }">{{ healthPercent(metrics.successRate) }}</strong>
              <small>{{ metrics.successes }} 成功 / {{ metrics.attempts }} 有效尝试</small>
              <small v-if="metrics.attempts < detail.report.minimumSamples" class="health-metric-warning">样本不足，暂不判定</small>
              <small v-else-if="warnings?.failureRate" class="health-metric-warning">失败率达到关注阈值</small>
            </div>
            <div>
              <span>首 token · P95</span>
              <strong :class="{ 'health-metric-warning': warnings?.p95 }">{{ formatDuration(metrics.p95FirstTokenMs) }}</strong>
              <small :class="{ 'health-metric-warning': warnings?.p50 }">P50 {{ formatDuration(metrics.p50FirstTokenMs) }} · {{ metrics.firstTokenSamples }} 个样本</small>
              <small v-if="warnings?.p95 || warnings?.p50" class="health-metric-warning">{{ warnings?.p95 ? 'P95 超过 ' + formatDuration(detail.report.slowFirstTokenMs) : 'P50 超过 ' + formatDuration(detail.report.slowP50FirstTokenMs) }} 阈值</small>
            </div>
            <div><span>上游耗时 · P95</span><strong>{{ formatDuration(metrics.p95TotalMs) }}</strong><small>P50 {{ formatDuration(metrics.p50TotalMs) }} · 成功尝试</small></div>
            <div><span>估算输出速度 · P50</span><strong class="health-throughput">{{ healthThroughput(metrics.outputTokensPerSecond) }}<span v-if="healthThroughput(metrics.outputTokensPerSecond) !== '—'" class="health-metric-unit">tok/s</span></strong><small>{{ metrics.throughputSamples }} 个有效流式样本</small></div>
          </div>
          <div class="health-request-summary health-overview-footer">
            <div><span>涉及该渠道的用户请求</span><strong>{{ summary.requests.requests }}</strong></div>
            <div><span>最终成功率（含其他渠道兜底）</span><strong>{{ healthPercent(summary.requests.successRate) }}</strong></div>
            <div><span>重试 / 切换率</span><strong>{{ healthPercent(summary.requests.failoverRate) }}</strong></div>
          </div>
          <section class="health-trend" aria-label="健康度历史">
            <h3>成功率趋势 <small class="muted">{{ window === '24h' ? '每小时' : '每 24 小时' }}</small></h3>
            <div class="health-history">
              <button v-for="(bucket, index) in detail.trend" :key="bucket.from" type="button" class="health-history-bar" :data-state="healthBucketState(bucket.metrics, detail.report.minimumSamples, detail.report.warningErrorRate, detail.report.criticalErrorRate)" :class="{ selected: selectedBucket === index, 'is-empty': bucket.metrics.attempts === 0 }" :title="bucketLabel(bucket)" :aria-label="bucketLabel(bucket)" :aria-pressed="selectedBucket === index" @click="selectedBucket = index" />
            </div>
            <div class="health-history-axis muted small"><span>{{ formatTime(detail.report.from) }}</span><span>{{ formatTime(detail.report.to) }}</span></div>
            <div class="health-history-legend" aria-label="成功率趋势图例">
              <span><i class="health-legend-swatch healthy" aria-hidden="true" />正常</span>
              <span><i class="health-legend-swatch warning" aria-hidden="true" />需关注 ≥ {{ healthPercent(detail.report.warningErrorRate) }} 失败</span>
              <span><i class="health-legend-swatch critical" aria-hidden="true" />异常 ≥ {{ healthPercent(detail.report.criticalErrorRate) }} 失败</span>
              <span><i class="health-legend-swatch insufficient" aria-hidden="true" />样本不足 &lt; {{ detail.report.minimumSamples }} 次</span>
              <span><i class="health-legend-swatch empty" aria-hidden="true" />无请求</span>
            </div>
            <p v-if="activeBucket" class="small health-bucket-detail" role="status">{{ bucketLabel(activeBucket) }}；失败 {{ activeBucket.metrics.failures }} 次，P95 首 token {{ formatDuration(activeBucket.metrics.p95FirstTokenMs) }}。</p>
            <p v-else class="muted small">点击时间块查看对应时段样本</p>
          </section>
          <details class="health-explanation health-overview-explanation">
            <summary>统计口径与判定规则</summary>
            <div class="health-explanation-body muted small">
              <p>真实请求观测 · 不发起额外探测，也不修改路由优先级。所有时间按请求完成时间统计。</p>
              <p>排除 {{ metrics.excluded }} 次客户端取消、参数错误或网关协议转换失败。HTTP 200 后流式失败仍算失败。未知不代表可用或不可用。</p>
              <p>至少 {{ detail.report.minimumSamples }} 个样本才判定健康度；最近一小时无有效样本显示未知。错误率 ≥ {{ healthPercent(detail.report.warningErrorRate) }} 需关注、≥ {{ healthPercent(detail.report.criticalErrorRate) }} 异常；首 token P95 超过 {{ formatDuration(detail.report.slowFirstTokenMs) }}、或 P50 超过 {{ formatDuration(detail.report.slowP50FirstTokenMs) }}，且样本足够时提示关注。这两条只影响关注提示，不触发冷却。</p>
              <p>TTFT 仅统计有首 token 的成功流式尝试；总耗时按上游单次尝试计时。输出速度为输出 token ÷（上游耗时 − 首 token），包含传输及工具等待，只供同模型、同协议比较。</p>
              <p>估算输出速度完整值：{{ healthThroughput(metrics.outputTokensPerSecond) === '—' ? '—' : metrics.outputTokensPerSecond?.toFixed(1) + ' tok/s' }}。</p>
            </div>
          </details>
        </section>
        <section class="health-section health-card" aria-label="多维明细">
          <div class="health-section-head">
            <h3>多维明细</h3>
            <div class="health-window-tabs" role="group" aria-label="明细视图">
              <button type="button" :aria-pressed="detailView === 'performance'" :class="{ active: detailView === 'performance' }" @click="detailView = 'performance'">性能</button>
              <button type="button" :aria-pressed="detailView === 'cache'" :class="{ active: detailView === 'cache' }" @click="detailView = 'cache'">缓存</button>
            </div>
          </div>
          <template v-if="detailView === 'performance'">
            <div class="health-window-tabs health-dimension-tabs" role="group" aria-label="健康度统计维度">
              <button v-for="option in ([{ id: 'byModel', label: '按模型' }, { id: 'byProtocol', label: '按出站协议' }, { id: 'byKey', label: '按渠道密钥' }] as const)" :key="option.id" type="button" :aria-pressed="dimension === option.id" :class="{ active: dimension === option.id }" @click="dimension = option.id">{{ option.label }}</button>
            </div>
            <p v-if="!rows.length" class="muted small">此时间范围没有上游尝试明细。</p>
            <div v-else v-scroll-hint class="table-scroll health-table-wrap">
              <table class="data-table health-responsive-table health-breakdown">
                <thead><tr><th scope="col">名称</th><th scope="col">有效尝试</th><th scope="col">成功率</th><th scope="col">首 token P50 / P95</th><th scope="col">耗时 P95</th></tr></thead>
                <tbody><tr v-for="row in rows" :key="row.id"><td class="health-row-name" data-label="名称">{{ row.name }}<small v-if="row.metrics.attempts < detail.report.minimumSamples" class="health-metric-warning"> 样本不足</small></td><td data-label="有效尝试">{{ row.metrics.attempts }}</td><td data-label="成功率">{{ healthPercent(row.metrics.successRate) }}</td><td data-label="首 token P50 / P95">{{ formatDuration(row.metrics.p50FirstTokenMs) }} / {{ formatDuration(row.metrics.p95FirstTokenMs) }}</td><td data-label="耗时 P95">{{ formatDuration(row.metrics.p95TotalMs) }}</td></tr></tbody>
              </table>
            </div>
          </template>
          <template v-else>
            <p class="muted small">成功请求用量 · 不参与健康度判定</p>
            <div class="health-request-summary health-cache-summary">
              <div><span>缓存命中率</span><strong>{{ cacheHitText }}</strong></div>
              <div><span>缓存读 / 输入 token</span><strong>{{ formatTokensCompact(usage?.total.cachedTokens) }} / {{ formatTokensCompact(usage?.total.inputTokens) }}</strong></div>
              <div><span>缓存写 token</span><strong>{{ formatTokensCompact(usage?.total.cachedWriteTokens) }}</strong></div>
            </div>
            <p v-if="usageState === 'failed'" class="muted small" role="status">缓存用量读取失败，不影响健康度统计。</p>
            <p v-else-if="usageState === 'loading'" class="muted small" role="status">正在汇总缓存用量…</p>
            <p v-else-if="usage && usage.total.requests === 0" class="muted small">此时间范围没有可统计的用量。</p>
            <div v-if="cacheRows.length" v-scroll-hint class="table-scroll health-table-wrap">
              <table class="data-table health-responsive-table health-cache-table">
                <thead><tr><th scope="col">模型</th><th scope="col">缓存命中率</th><th scope="col">缓存读 / 输入 token</th><th scope="col">缓存写 token</th></tr></thead>
                <tbody><tr v-for="row in cacheRows" :key="row.id"><td class="health-row-name" data-label="模型">{{ row.name }}</td><td data-label="缓存命中率"><strong>{{ row.rate }}</strong></td><td data-label="缓存读 / 输入 token">{{ formatTokensCompact(row.cachedTokens) }} / {{ formatTokensCompact(row.inputTokens) }}</td><td data-label="缓存写 token">{{ formatTokensCompact(row.cachedWriteTokens) }}</td></tr></tbody>
              </table>
            </div>
            <details class="health-explanation">
              <summary>缓存统计口径</summary>
              <div class="health-explanation-body muted small">
                <p>口径与用量报表一致：缓存读 ÷ 输入 token，输入已包含缓存读与缓存写；上游未上报缓存用量时按 0 统计，只含成功请求。</p>
                <p>按上游模型拆分，命中率各自计算，按输入 token 从多到少排列。来自请求日志，与按上游尝试统计的性能口径不同。</p>
              </div>
            </details>
          </template>
        </section>
        <div class="health-diagnostics" :class="{ 'has-unavailable': unavailable.length > 0 }">
          <section class="health-section health-card health-route" aria-label="当前路由状态">
            <div class="health-section-head">
              <h3>当前路由状态</h3>
              <button v-if="hasCooldown" type="button" class="button ghost mini" :disabled="Boolean(clearing)" @click="clearCooldown('channel')">解除全部冷却</button>
            </div>
            <p class="muted small">当前可路由性，与历史成功率分别展示</p>
            <p v-if="!unavailable.length" class="small health-route-status"><span v-if="detail.availability.length" class="health-route-dot" aria-hidden="true" />{{ detail.availability.length ? '所有已授权候选当前均可参与路由。' : '尚未配置带有授权模型的渠道密钥。' }}</p>
            <div v-else class="health-availability-groups">
              <div v-for="group in unavailableGroups" :key="group.keyId" class="health-availability-group">
                <div class="health-availability-key">
                  <span>{{ group.keyName }}</span>
                  <button v-if="groupHasCooldown(group.rows)" type="button" class="button ghost mini" :disabled="Boolean(clearing)" @click="clearCooldown('key', group.keyId)">解除该密钥冷却</button>
                </div>
                <ul class="health-availability-list">
                  <li v-for="row in group.rows" :key="row.keyId + row.model">
                    <span class="badge warning">{{ availabilityLabel[row.state] || row.state }}</span>
                    <span>{{ row.model }}</span>
                    <small class="muted">{{ cooldownText(row) }}</small>
                    <button v-if="row.state === 'COOLDOWN' && row.cooldownScope === 'candidate'" type="button" class="button ghost mini" :disabled="Boolean(clearing)" @click="clearCooldown('candidate', row.keyId, row.model)">解除</button>
                  </li>
                </ul>
              </div>
            </div>
            <dl class="health-observed-times muted small"><div><dt>历史最近成功</dt><dd>{{ formatTime(metrics.lastSuccessAt) }}</dd></div><div><dt>历史最近失败</dt><dd>{{ formatTime(metrics.lastFailureAt) }}</dd></div></dl>
          </section>
          <section class="health-section health-card" aria-label="主要上游错误">
            <h3>主要上游错误</h3>
            <p v-if="!detail.errors.length" class="muted small">此时间范围没有上游故障记录。</p>
            <ul v-else class="health-errors"><li v-for="item in detail.errors" :key="item.code"><code>{{ item.code }}</code><strong>{{ item.count }} 次</strong><time class="muted small" :datetime="new Date(item.lastAt).toISOString()">{{ formatTime(item.lastAt) }}</time></li></ul>
          </section>
        </div>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.health-detail { display: grid; gap: 12px; min-width: 0; }
.health-detail p, .health-detail h3 { margin: 0; }
.health-detail-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.health-runtime-meta { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 0 auto; font-size: 11px; }
.health-runtime-meta > div { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px; padding: 5px 8px; border: 1px solid var(--glass-line); border-radius: 999px; background: var(--glass-field); }
.health-runtime-meta dt { color: var(--muted); }
.health-runtime-meta dd { margin: 0; overflow-wrap: anywhere; }
.health-refresh { margin-left: auto; }
.health-runtime-meta + .health-refresh { margin-left: 0; }
.health-verdict { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid var(--glass-line); border-radius: 10px; background: var(--glass-field); }
.health-verdict[data-state='WARNING'], .health-verdict[data-state='UNKNOWN'] { background: var(--warning-soft); border-color: color-mix(in srgb, var(--warning) 35%, var(--glass-line)); }
.health-verdict[data-state='CRITICAL'], .health-verdict[data-state='NO_CREDENTIALS'] { background: var(--danger-soft); border-color: color-mix(in srgb, var(--danger) 35%, var(--glass-line)); }
.health-verdict[data-state='HEALTHY'] { background: var(--success-soft); }
.health-verdict-copy { min-width: 0; overflow-wrap: anywhere; }
.health-verdict-copy p { margin-top: 2px; }
.health-updated { margin-left: auto; text-align: right; }
.health-card { min-width: 0; border: 1px solid var(--glass-line); border-radius: 10px; background: var(--surface-solid); }
.health-metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.health-metric-grid > div { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; min-width: 0; padding: 14px; border-right: 1px solid var(--glass-line); }
.health-metric-grid > div:last-child { border-right: 0; }
.health-metric-grid span, .health-metric-grid small, .health-request-summary span { color: var(--muted); font-size: 12px; }
.health-metric-grid strong { font-size: 24px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.health-metric-grid .health-metric-unit { margin-left: 4px; font-size: 11px; font-weight: 400; }
.health-metric-grid .health-throughput { font-size: 22px; }
.health-detail .health-metric-warning { color: var(--warning); }
.health-request-summary { display: flex; flex-wrap: wrap; gap: 8px 24px; }
.health-request-summary > div { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 8px; }
.health-overview-footer { padding: 10px 14px; border-top: 1px solid var(--glass-line); border-bottom: 1px solid var(--glass-line); background: var(--glass-field); font-size: 13px; }
.health-section { display: grid; align-content: start; gap: 10px; padding: 14px; }
.health-detail h3 { font-size: 14px; }
.health-detail h3 small { font-size: 12px; font-weight: normal; }
.health-trend { display: grid; gap: 8px; padding: 12px 14px; }
.health-history { display: flex; gap: 4px; }
.health-history-bar { flex: 1 1 0; min-width: 0; height: 32px; padding: 0; border: 0; border-radius: 3px; cursor: pointer; }
.health-history-bar[data-state='HEALTHY'] { background: var(--success-fill); }
.health-history-bar[data-state='WARNING'] { background: var(--warning-fill); }
.health-history-bar[data-state='CRITICAL'] { background: var(--danger-fill); }
.health-history-bar[data-state='UNKNOWN'] { background: repeating-linear-gradient(135deg, color-mix(in srgb, var(--warning-fill) 22%, var(--surface-solid)) 0 7px, color-mix(in srgb, var(--warning-fill) 52%, var(--surface-solid)) 7px 11px); outline: 1px solid color-mix(in srgb, var(--warning-fill) 72%, var(--glass-line)); outline-offset: -1px; }
.health-history-bar.is-empty { background: transparent; outline: 1px dashed var(--line-strong); outline-offset: -1px; }
.health-history-bar.selected, .health-history-bar:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; opacity: 1; }
.health-history-legend { display: flex; flex-wrap: wrap; gap: 6px 12px; color: var(--muted); font-size: 11px; line-height: 1.4; }
.health-history-legend > span { display: inline-flex; align-items: center; gap: 5px; }
.health-legend-swatch { display: inline-block; width: 16px; height: 9px; flex: 0 0 16px; border-radius: 2px; }
.health-legend-swatch.healthy { background: var(--success-fill); }
.health-legend-swatch.warning { background: var(--warning-fill); }
.health-legend-swatch.insufficient { background: repeating-linear-gradient(135deg, color-mix(in srgb, var(--warning-fill) 22%, var(--surface-solid)) 0 4px, color-mix(in srgb, var(--warning-fill) 52%, var(--surface-solid)) 4px 7px); border: 1px solid color-mix(in srgb, var(--warning-fill) 72%, var(--glass-line)); }
.health-legend-swatch.critical { background: var(--danger-fill); }
.health-legend-swatch.empty { background: transparent; border: 1px dashed var(--line-strong); }
.health-history-axis { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; }
.health-bucket-detail { overflow-wrap: anywhere; }
.health-explanation summary { width: fit-content; color: var(--muted); font-size: 12px; cursor: pointer; }
.health-explanation summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; border-radius: 3px; }
.health-explanation-body { display: grid; gap: 6px; padding-top: 8px; line-height: 1.6; overflow-wrap: anywhere; }
.health-overview-explanation { padding: 9px 14px; border-top: 1px solid var(--glass-line); }
.health-section-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; min-width: 0; }
.health-dimension-tabs { justify-self: start; }
.health-table-wrap { border: 1px solid var(--glass-line); border-radius: 8px; }
.health-responsive-table { width: 100%; min-width: 0; }
.health-responsive-table th { background: var(--glass-field); }
.health-responsive-table td { background: var(--surface-solid); font-variant-numeric: tabular-nums; }
.health-responsive-table th, .health-responsive-table td { padding: 10px 12px; }
.health-responsive-table tbody tr:hover td { background: color-mix(in srgb, var(--brand) 5%, var(--surface-solid)); }
.health-responsive-table .health-row-name { max-width: 220px; overflow-wrap: anywhere; }
.health-cache-summary { padding: 10px 0; }
.health-diagnostics { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr); gap: 12px; }
.health-diagnostics.has-unavailable { grid-template-columns: minmax(0, 1fr); }
.health-route-status { display: flex; align-items: baseline; gap: 7px; }
.health-route-dot { display: inline-block; flex: 0 0 7px; width: 7px; height: 7px; border-radius: 50%; background: var(--success); }
.health-observed-times { display: grid; gap: 5px; margin: 0; }
.health-observed-times > div { display: flex; flex-wrap: wrap; gap: 4px 8px; }
.health-observed-times dd { margin: 0; }
.health-availability-groups { display: grid; gap: 12px; }
.health-availability-key { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; font-weight: 650; overflow-wrap: anywhere; }
.health-availability-group .health-availability-list { margin-top: 6px; }
.health-errors, .health-availability-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
.health-availability-list li { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; }
.health-availability-list li > span:nth-child(2) { overflow-wrap: anywhere; min-width: 0; }
.health-errors li { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: 4px 10px; }
.health-errors code { justify-self: start; max-width: 100%; padding: 2px 6px; border-radius: 5px; background: var(--danger-soft); color: var(--danger); font-size: 12px; overflow-wrap: anywhere; }
.health-errors strong { padding: 2px 6px; border-radius: 5px; background: var(--glass-field); font-size: 12px; white-space: nowrap; }
.health-errors time { grid-column: 1 / -1; text-align: right; }
@media (min-width: 901px) {
  .health-errors li { grid-template-columns: minmax(0, 1fr) auto auto; }
  .health-errors time { grid-column: auto; padding-top: 2px; white-space: nowrap; }
}
@media (max-width: 700px) {
  .health-runtime-meta { order: 3; width: 100%; margin-left: 0; }
  .health-runtime-meta + .health-refresh { margin-left: auto; }
  .health-diagnostics { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 600px) {
  .health-verdict { flex-wrap: wrap; }
  .health-updated { width: 100%; text-align: left; margin-left: 34px; }
  .health-metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .health-metric-grid > div { padding: 10px; border-bottom: 1px solid var(--glass-line); }
  .health-metric-grid > div:nth-child(even) { border-right: 0; }
  .health-metric-grid > div:nth-child(n+3) { border-bottom: 0; }
  .health-metric-grid strong, .health-metric-grid .health-throughput { font-size: 20px; }
  .health-overview-footer, .health-trend, .health-section { padding: 10px; }
  .health-overview-footer { gap: 6px; }
  .health-overview-footer > div { width: 100%; justify-content: space-between; }
  .health-history { gap: 3px; }
  .health-table-wrap { overflow: visible; }
  .health-responsive-table, .health-responsive-table tbody { display: block; }
  .health-responsive-table thead { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); }
  .health-responsive-table tr { display: grid; gap: 6px; padding: 10px; border-bottom: 1px solid var(--glass-line); }
  .health-responsive-table tr:last-child { border-bottom: 0; }
  .health-responsive-table td { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: baseline; gap: 8px; padding: 0; border: 0; white-space: normal; overflow-wrap: anywhere; text-align: right; }
  .health-responsive-table td::before { content: attr(data-label); color: var(--muted); font-size: 12px; text-align: left; }
  .health-responsive-table td.health-row-name { display: block; max-width: none; font-weight: 600; text-align: left; }
  .health-responsive-table td.health-row-name::before { content: none; }
}
@media (max-width: 360px) {
  .health-detail-toolbar > .health-window-tabs button { padding-inline: 6px; }
}
</style>

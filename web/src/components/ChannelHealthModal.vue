<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import type { Channel, ChannelHealthDetail, HealthWindow, HealthBreakdown, UsageReport } from '../types/api'
import { HEALTH_WINDOWS, HEALTH_LABELS, healthSymbol, healthPercent, healthBucketState, healthCacheHitText, healthCacheRows } from '../utils/channelHealth'
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
  <Modal :title="`${channel.name} · 渠道健康度`" wide @close="tryClose">
    <div class="health-detail">
      <div class="health-detail-toolbar">
        <div class="health-window-tabs" role="group" aria-label="健康度时间范围">
          <button v-for="option in HEALTH_WINDOWS" :key="option.id" type="button" :aria-pressed="window === option.id" :class="{ active: window === option.id }" @click="window = option.id">{{ option.label }}</button>
        </div>
        <button class="button ghost mini" :disabled="loading || Boolean(clearing) || manualRefreshing" @click="handleManualRefresh">刷新</button>
      </div>
      <p class="muted small">真实请求观测 · 不发起额外探测，也不修改路由优先级。所有时间按请求完成时间统计。</p>
      <dl v-if="concurrentRequests != null || lastProbeAt != null" class="health-runtime-meta">
        <div v-if="concurrentRequests != null"><dt>实时在途</dt><dd>{{ concurrentRequests }} 个请求</dd></div>
        <div><dt>最近协议探测</dt><dd>{{ lastProbeAt ? formatTime(lastProbeAt, { style: 'medium' }) : '暂无探测记录' }}</dd></div>
      </dl>
      <ErrorState v-if="error" :message="error" @retry="load" />
      <p v-if="loading" class="muted" role="status">正在汇总渠道健康度…</p>
      <template v-if="detail && summary && metrics">
        <div class="health-verdict">
          <span class="health-status-icon" :data-state="summary.state" aria-hidden="true">{{ healthSymbol(summary.state) }}</span>
          <div><strong>{{ HEALTH_LABELS[summary.state] }}</strong><p class="muted small">{{ summary.reason }} · {{ formatTime(detail.report.to) }} 更新</p></div>
        </div>
        <p v-if="detail.report.indexing" class="callout warning" role="status">历史数据正在分批补齐，当前统计尚不完整，请稍后刷新。</p>
        <p v-if="summary.requests.legacyRequests" class="callout warning">另有 {{ summary.requests.legacyRequests }} 条历史请求缺少上游尝试明细，未纳入健康度，避免误算。</p>
        <p v-if="detail.report.retentionDays < (window === '24h' ? 1 : Number(window.slice(0, -1)))" class="callout warning">当前日志保留 {{ detail.report.retentionDays }} 天，所选时间范围可能不完整。</p>
        <div class="health-metric-grid">
          <div><span>上游尝试成功率</span><strong>{{ healthPercent(metrics.successRate) }}</strong><small>{{ metrics.successes }} 成功 / {{ metrics.attempts }} 有效尝试</small></div>
          <div><span>首 token · P95</span><strong>{{ formatDuration(metrics.p95FirstTokenMs) }}</strong><small>P50 {{ formatDuration(metrics.p50FirstTokenMs) }} · {{ metrics.firstTokenSamples }} 个样本</small></div>
          <div><span>上游耗时 · P95</span><strong>{{ formatDuration(metrics.p95TotalMs) }}</strong><small>P50 {{ formatDuration(metrics.p50TotalMs) }} · 成功尝试</small></div>
          <div><span>估算输出速度 · P50</span><strong>{{ metrics.outputTokensPerSecond == null ? '—' : metrics.outputTokensPerSecond.toFixed(1) + ' tok/s' }}</strong><small>{{ metrics.throughputSamples }} 个有效流式样本</small></div>
        </div>
        <div class="health-request-summary">
          <div><span>涉及该渠道的用户请求</span><strong>{{ summary.requests.requests }}</strong></div>
          <div><span>最终成功率（含其他渠道兜底）</span><strong>{{ healthPercent(summary.requests.successRate) }}</strong></div>
          <div><span>重试 / 切换率</span><strong>{{ healthPercent(summary.requests.failoverRate) }}</strong></div>
        </div>
        <section class="health-section" aria-label="缓存使用">
          <h3>缓存使用 <small class="muted">来自用量报表 · 仅展示，不参与健康度判定</small></h3>
          <div class="health-request-summary">
            <div><span>缓存命中率</span><strong>{{ cacheHitText }}</strong></div>
            <div><span>缓存读 / 输入 token</span><strong>{{ formatTokensCompact(usage?.total.cachedTokens) }} / {{ formatTokensCompact(usage?.total.inputTokens) }}</strong></div>
            <div><span>缓存写 token</span><strong>{{ formatTokensCompact(usage?.total.cachedWriteTokens) }}</strong></div>
          </div>
          <p v-if="usageState === 'failed'" class="muted small">缓存用量读取失败，不影响健康度统计。</p>
          <p v-else-if="usage && usage.total.requests === 0" class="muted small">此时间范围没有可统计的用量。</p>
          <p v-else class="muted small">口径与用量报表一致：缓存读 ÷ 输入 token，输入已包含缓存读与缓存写；上游未上报缓存用量时按 0 统计，只含成功请求。</p>
          <p v-if="cacheRows.length" class="muted small">按上游模型拆分，命中率各自计算，按输入 token 从多到少排列。来自请求日志，与上方按上游尝试统计的成功率口径不同，同样不参与健康度判定。</p>
          <div v-if="cacheRows.length" v-scroll-hint class="table-scroll">
            <table class="data-table compact-table health-cache-table">
              <thead><tr><th>模型</th><th>缓存命中率</th><th>缓存读 / 输入 token</th><th>缓存写 token</th></tr></thead>
              <tbody>
                <tr v-for="row in cacheRows" :key="row.id">
                  <td>{{ row.name }}</td>
                  <td><strong>{{ row.rate }}</strong></td>
                  <td>{{ formatTokensCompact(row.cachedTokens) }} / {{ formatTokensCompact(row.inputTokens) }}</td>
                  <td>{{ formatTokensCompact(row.cachedWriteTokens) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section class="health-section" aria-label="健康度历史">
          <h3>成功率趋势 <small class="muted">{{ window === '24h' ? '每小时' : '每 24 小时' }}</small></h3>
          <div class="health-history">
            <button v-for="(bucket, index) in detail.trend" :key="bucket.from" type="button" class="health-history-bar" :data-state="healthBucketState(bucket.metrics, detail.report.minimumSamples, detail.report.warningErrorRate, detail.report.criticalErrorRate)" :class="{ selected: selectedBucket === index, 'is-empty': bucket.metrics.attempts === 0 }" :title="bucketLabel(bucket)" :aria-label="bucketLabel(bucket)" :aria-pressed="selectedBucket === index" @click="selectedBucket = index" />
          </div>
          <div class="health-history-legend" aria-label="成功率趋势图例">
            <span><i class="health-legend-swatch healthy" aria-hidden="true" />正常</span>
            <span><i class="health-legend-swatch warning" aria-hidden="true" />需关注：失败率 ≥ {{ healthPercent(detail.report.warningErrorRate) }}</span>
            <span><i class="health-legend-swatch insufficient" aria-hidden="true" />样本不足：少于 {{ detail.report.minimumSamples }} 次，暂不判定</span>
            <span><i class="health-legend-swatch critical" aria-hidden="true" />异常</span>
            <span><i class="health-legend-swatch empty" aria-hidden="true" />无请求</span>
          </div>
          <div class="health-history-axis muted small"><span>{{ formatTime(detail.report.from) }}</span><span>{{ formatTime(detail.report.to) }}</span></div>
          <p v-if="activeBucket" class="small" role="status">{{ bucketLabel(activeBucket) }}；失败 {{ activeBucket.metrics.failures }} 次，P95 首 token {{ formatDuration(activeBucket.metrics.p95FirstTokenMs) }}。</p>
          <p v-else class="muted small">点击时间块查看样本；绿色实色表示正常，深黄色实色表示需关注（失败率 ≥ {{ healthPercent(detail.report.warningErrorRate) }}），浅黄色斜纹表示样本不足（少于 {{ detail.report.minimumSamples }} 次，暂不判定），红色表示异常；虚线空白表示无请求。</p>
        </section>
        <section class="health-section">
          <div class="health-window-tabs" role="group" aria-label="健康度统计维度">
            <button v-for="option in ([{ id: 'byModel', label: '按模型' }, { id: 'byProtocol', label: '按出站协议' }, { id: 'byKey', label: '按渠道密钥' }] as const)" :key="option.id" :aria-pressed="dimension === option.id" :class="{ active: dimension === option.id }" @click="dimension = option.id">{{ option.label }}</button>
          </div>
          <p v-if="!rows.length" class="muted">此时间范围没有上游尝试明细。</p>
          <div v-else v-scroll-hint class="table-scroll">
            <table class="data-table health-breakdown">
              <thead><tr><th>名称</th><th>有效尝试</th><th>成功率</th><th>首 token P50 / P95</th><th>耗时 P95</th></tr></thead>
              <tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.name }}<small v-if="row.metrics.attempts < detail.report.minimumSamples" class="muted"> 样本不足</small></td><td>{{ row.metrics.attempts }}</td><td>{{ healthPercent(row.metrics.successRate) }}</td><td>{{ formatDuration(row.metrics.p50FirstTokenMs) }} / {{ formatDuration(row.metrics.p95FirstTokenMs) }}</td><td>{{ formatDuration(row.metrics.p95TotalMs) }}</td></tr></tbody>
            </table>
          </div>
        </section>
        <section class="health-section">
          <div class="health-section-head">
            <h3>当前路由状态 <small class="muted">与历史成功率分别展示</small></h3>
            <button v-if="hasCooldown" type="button" class="button ghost mini" :disabled="clearing === 'channel'" @click="clearCooldown('channel')">解除全部冷却</button>
          </div>
          <p v-if="!unavailable.length" class="muted small">{{ detail.availability.length ? '所有已授权候选当前均可参与路由。' : '尚未配置带有授权模型的渠道密钥。' }}</p>
          <div v-else class="health-availability-groups">
            <div v-for="group in unavailableGroups" :key="group.keyId" class="health-availability-group">
              <div class="health-availability-key">
                <span>{{ group.keyName }}</span>
                <button v-if="groupHasCooldown(group.rows)" type="button" class="button ghost mini" :disabled="clearing === `key:${group.keyId}`" @click="clearCooldown('key', group.keyId)">解除该密钥冷却</button>
              </div>
              <ul class="health-availability-list">
                <li v-for="row in group.rows" :key="row.keyId + row.model">
                  <span class="badge warning">{{ availabilityLabel[row.state] || row.state }}</span>
                  <span>{{ row.model }}</span>
                  <small class="muted">{{ cooldownText(row) }}</small>
                  <button v-if="row.state === 'COOLDOWN' && row.cooldownScope === 'candidate'" type="button" class="button ghost mini" :disabled="clearing === `candidate:${row.keyId}:${row.model}`" @click="clearCooldown('candidate', row.keyId, row.model)">解除</button>
                </li>
              </ul>
            </div>
          </div>
        </section>
        <section class="health-section">
          <h3>主要上游错误</h3>
          <p v-if="!detail.errors.length" class="muted small">此时间范围没有上游故障记录。</p>
          <ul v-else class="health-errors"><li v-for="item in detail.errors" :key="item.code"><code>{{ item.code }}</code><strong>{{ item.count }} 次</strong><span class="muted small">{{ formatTime(item.lastAt) }}</span></li></ul>
        </section>
        <div class="health-footnote muted small">
          <p>最近成功 {{ formatTime(metrics.lastSuccessAt) }} · 最近失败 {{ formatTime(metrics.lastFailureAt) }}</p>
          <p>排除 {{ metrics.excluded }} 次客户端取消、参数错误或网关协议转换失败。HTTP 200 后流式失败仍算失败。未知不代表可用或不可用。</p>
          <p>至少 {{ detail.report.minimumSamples }} 个样本才判定健康度；最近一小时无有效样本显示未知。错误率 ≥ {{ healthPercent(detail.report.warningErrorRate) }} 需关注、≥ {{ healthPercent(detail.report.criticalErrorRate) }} 异常；首 token P95 超过 {{ formatDuration(detail.report.slowFirstTokenMs) }}、或 P50 超过 {{ formatDuration(detail.report.slowP50FirstTokenMs) }}，且样本足够时提示关注。这两条只影响关注提示，不触发冷却。</p>
          <p>TTFT 仅统计有首 token 的成功流式尝试；总耗时按上游单次尝试计时。输出速度为输出 token ÷（上游耗时 − 首 token），包含传输及工具等待，只供同模型、同协议比较。</p>
        </div>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.health-detail { display: grid; gap: 16px; }
.health-detail p, .health-detail h3 { margin: 0; }
.health-runtime-meta { display: flex; flex-wrap: wrap; gap: 8px 24px; margin: 0; font-size: 12px; }
.health-runtime-meta > div { display: flex; flex-wrap: wrap; gap: 6px; }
.health-runtime-meta dt { color: var(--muted); }
.health-runtime-meta dd { margin: 0; overflow-wrap: anywhere; }
.health-detail-toolbar, .health-verdict { display: flex; align-items: center; gap: 12px; justify-content: space-between; flex-wrap: wrap; }
.health-verdict { justify-content: flex-start; }
.health-verdict p { margin-top: 4px; }
.health-metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--glass-line); border-radius: 10px; overflow: hidden; }
.health-metric-grid > div { display: flex; flex-direction: column; gap: 8px; padding: 16px; border-right: 1px solid var(--glass-line); }
.health-metric-grid > div:last-child { border-right: 0; }
.health-metric-grid span, .health-metric-grid small, .health-request-summary span { color: var(--muted); font-size: 12px; }
.health-metric-grid strong { font-size: 24px; font-variant-numeric: tabular-nums; }
.health-request-summary { display: flex; flex-wrap: wrap; gap: 16px 28px; }
.health-request-summary > div { display: grid; gap: 4px; }
.health-section { display: grid; gap: 12px; padding-top: 16px; border-top: 1px solid var(--glass-line); min-width: 0; }
.health-section h3 { font-size: 14px; }
.health-section h3 small { font-size: 12px; font-weight: normal; }
.health-history { display: flex; gap: 4px; }
.health-history-bar { flex: 1 1 0; min-width: 0; height: 38px; padding: 0; border: 0; border-radius: 3px; cursor: pointer; }
.health-history-bar[data-state='HEALTHY'] { background: var(--success-fill); }
.health-history-bar[data-state='WARNING'] { background: var(--warning-fill); }
.health-history-bar[data-state='CRITICAL'] { background: var(--danger-fill); }
.health-history-bar[data-state='UNKNOWN'] { background: repeating-linear-gradient(135deg, color-mix(in srgb, var(--warning-fill) 22%, var(--surface-solid)) 0 7px, color-mix(in srgb, var(--warning-fill) 52%, var(--surface-solid)) 7px 11px); outline: 1px solid color-mix(in srgb, var(--warning-fill) 72%, var(--glass-line)); outline-offset: -1px; }
.health-history-bar.is-empty { background: transparent; outline: 1px dashed var(--line-strong); outline-offset: -1px; }
.health-history-bar.selected, .health-history-bar:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; opacity: 1; }
.health-history-legend { display: flex; flex-wrap: wrap; gap: 7px 14px; color: var(--muted); font-size: 11px; line-height: 1.4; }
.health-history-legend > span { display: inline-flex; align-items: center; gap: 5px; }
.health-legend-swatch { display: inline-block; width: 20px; height: 10px; flex: 0 0 20px; border-radius: 2px; }
.health-legend-swatch.healthy { background: var(--success-fill); }
.health-legend-swatch.warning { background: var(--warning-fill); }
.health-legend-swatch.insufficient { background: repeating-linear-gradient(135deg, color-mix(in srgb, var(--warning-fill) 22%, var(--surface-solid)) 0 4px, color-mix(in srgb, var(--warning-fill) 52%, var(--surface-solid)) 4px 7px); border: 1px solid color-mix(in srgb, var(--warning-fill) 72%, var(--glass-line)); }
.health-legend-swatch.critical { background: var(--danger-fill); }
.health-legend-swatch.empty { background: transparent; border: 1px dashed var(--line-strong); }
.health-history-axis { display: flex; justify-content: space-between; gap: 12px; }
.health-breakdown { min-width: 620px; }
.health-breakdown td:first-child { max-width: 260px; overflow-wrap: anywhere; }
.health-cache-table { min-width: 520px; }
.health-cache-table td:first-child { max-width: 260px; overflow-wrap: anywhere; }
.health-section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.health-availability-groups { display: grid; gap: 14px; }
.health-availability-key { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-weight: 650; }
.health-availability-group .health-availability-list { margin-top: 6px; }
.health-errors, .health-availability-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
.health-errors li, .health-availability-list li { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 16px; }
.health-errors code, .health-availability-list li > span:nth-child(2) { overflow-wrap: anywhere; min-width: 0; }
.health-footnote { display: grid; gap: 7px; border-top: 1px solid var(--glass-line); padding-top: 12px; line-height: 1.6; }
@media (max-width: 600px) {
  .health-metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .health-metric-grid > div { padding: 12px; border-bottom: 1px solid var(--glass-line); }
  .health-metric-grid > div:nth-child(even) { border-right: 0; }
  .health-metric-grid > div:nth-child(n+3) { border-bottom: 0; }
  .health-metric-grid strong { font-size: 20px; }
  .health-history { gap: 3px; }
}
</style>

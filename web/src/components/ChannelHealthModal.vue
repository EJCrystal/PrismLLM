<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import type { Channel, ChannelHealthDetail, HealthWindow, HealthBreakdown } from '../types/api'
import { HEALTH_WINDOWS, HEALTH_LABELS, healthSymbol, healthPercent, healthBucketState } from '../utils/channelHealth'
import { formatDuration, formatTime } from '../utils/format'
import Modal from './Modal.vue'
import ErrorState from './ErrorState.vue'
import { vScrollHint } from '../directives/scrollHint'

const props = defineProps<{ channel: Channel; initialWindow: HealthWindow }>()
defineEmits<{ close: [] }>()
const window = ref<HealthWindow>(props.initialWindow)
const detail = ref<ChannelHealthDetail | null>(null)
const loading = ref(false)
const error = ref('')
const dimension = ref<'byModel' | 'byProtocol' | 'byKey'>('byModel')
const selectedBucket = ref<number | null>(null)
let revision = 0
async function load() {
  const request = ++revision
  loading.value = true
  error.value = ''
  detail.value = null
  selectedBucket.value = null
  try {
    const result = await api.getChannelHealthDetail(props.channel.id, window.value)
    if (request === revision) detail.value = result
  } catch (cause) {
    if (request === revision) error.value = cause instanceof Error ? cause.message : '读取渠道健康度失败，请重试'
  } finally { if (request === revision) loading.value = false }
}
watch([window, () => props.channel.id], load, { immediate: true })
onBeforeUnmount(() => { revision++ })
const summary = computed(() => detail.value?.report.channels[0])
const metrics = computed(() => summary.value?.metrics)
const rows = computed<HealthBreakdown[]>(() => detail.value?.[dimension.value] || [])
const activeBucket = computed(() => selectedBucket.value == null ? null : detail.value?.trend[selectedBucket.value])
const unavailable = computed(() => detail.value?.availability.filter(row => row.state !== 'ACTIVE') || [])
const availabilityLabel: Record<string, string> = { CHANNEL_DISABLED: '渠道停用', MANUAL_DISABLED: '人工停用', AUTO_TEMPORARY: '自动临时停用', AUTO_PERMANENT: '自动永久停用', COOLDOWN: '冷却中' }
function bucketLabel(bucket: { from: number; to: number; metrics: { attempts: number; successRate: number | null } }) {
  return `${formatTime(bucket.from)} 至 ${formatTime(bucket.to)}，${bucket.metrics.attempts} 次尝试，成功率 ${healthPercent(bucket.metrics.successRate)}`
}
</script>

<template>
  <Modal :title="`${channel.name} · 渠道健康度`" wide @close="$emit('close')">
    <div class="health-detail">
      <div class="health-detail-toolbar">
        <div class="health-window-tabs" role="group" aria-label="健康度时间范围">
          <button v-for="option in HEALTH_WINDOWS" :key="option.id" type="button" :aria-pressed="window === option.id" :class="{ active: window === option.id }" @click="window = option.id">{{ option.label }}</button>
        </div>
        <button class="button ghost mini" :disabled="loading" @click="load">刷新</button>
      </div>
      <p class="muted small">真实请求观测 · 不发起额外探测，也不修改路由优先级。所有时间按请求完成时间统计。</p>
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
        <section class="health-section" aria-label="健康度历史">
          <h3>成功率趋势 <small class="muted">{{ window === '24h' ? '每小时' : '每 24 小时' }}</small></h3>
          <div class="health-history">
            <button v-for="(bucket, index) in detail.trend" :key="bucket.from" type="button" class="health-history-bar" :data-state="healthBucketState(bucket.metrics, detail.report.minimumSamples, detail.report.warningErrorRate, detail.report.criticalErrorRate)" :class="{ selected: selectedBucket === index }" :title="bucketLabel(bucket)" :aria-label="bucketLabel(bucket)" :aria-pressed="selectedBucket === index" @click="selectedBucket = index" />
          </div>
          <div class="health-history-axis muted small"><span>{{ formatTime(detail.report.from) }}</span><span>{{ formatTime(detail.report.to) }}</span></div>
          <p v-if="activeBucket" class="small" role="status">{{ bucketLabel(activeBucket) }}；失败 {{ activeBucket.metrics.failures }} 次，P95 首 token {{ formatDuration(activeBucket.metrics.p95FirstTokenMs) }}。</p>
          <p v-else class="muted small">点击时间块查看样本；绿色正常，黄色需关注或样本不足，红色异常。无请求以虚线显示。</p>
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
          <h3>当前路由状态 <small class="muted">与历史成功率分别展示</small></h3>
          <p v-if="!unavailable.length" class="muted small">{{ detail.availability.length ? '所有已授权候选当前均可参与路由。' : '尚未配置带有授权模型的渠道密钥。' }}</p>
          <ul v-else class="health-availability-list"><li v-for="row in unavailable" :key="row.keyId + row.model"><span class="badge warning">{{ availabilityLabel[row.state] || row.state }}</span><span>{{ row.keyName }} / {{ row.model }}</span><small class="muted">{{ row.reason || '' }}{{ row.until ? ' · 截至 ' + formatTime(row.until) : '' }}</small></li></ul>
        </section>
        <section class="health-section">
          <h3>主要上游错误</h3>
          <p v-if="!detail.errors.length" class="muted small">此时间范围没有上游故障记录。</p>
          <ul v-else class="health-errors"><li v-for="item in detail.errors" :key="item.code"><code>{{ item.code }}</code><strong>{{ item.count }} 次</strong><span class="muted small">{{ formatTime(item.lastAt) }}</span></li></ul>
        </section>
        <div class="health-footnote muted small">
          <p>最近成功 {{ formatTime(metrics.lastSuccessAt) }} · 最近失败 {{ formatTime(metrics.lastFailureAt) }}</p>
          <p>排除 {{ metrics.excluded }} 次客户端取消、参数错误或网关协议转换失败。HTTP 200 后流式失败仍算失败。未知不代表可用或不可用。</p>
          <p>至少 {{ detail.report.minimumSamples }} 个样本才判定健康度；最近一小时无有效样本显示未知。错误率 ≥ {{ healthPercent(detail.report.warningErrorRate) }} 需关注、≥ {{ healthPercent(detail.report.criticalErrorRate) }} 异常；P95 首 token 超过 {{ formatDuration(detail.report.slowFirstTokenMs) }} 且样本足够时提示关注。</p>
          <p>TTFT 仅统计有首 token 的成功流式尝试；总耗时按上游单次尝试计时。输出速度为输出 token ÷（上游耗时 − 首 token），包含传输及工具等待，只供同模型、同协议比较。</p>
        </div>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.health-detail { display: grid; gap: 16px; }
.health-detail p, .health-detail h3 { margin: 0; }
.health-detail-toolbar, .health-verdict { display: flex; align-items: center; gap: 12px; justify-content: space-between; flex-wrap: wrap; }
.health-verdict { justify-content: flex-start; }
.health-verdict p { margin-top: 4px; }
.health-metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.health-metric-grid > div { display: flex; flex-direction: column; gap: 8px; padding: 16px; border-right: 1px solid var(--line); }
.health-metric-grid > div:last-child { border-right: 0; }
.health-metric-grid span, .health-metric-grid small, .health-request-summary span { color: var(--muted); font-size: 12px; }
.health-metric-grid strong { font-size: 24px; font-variant-numeric: tabular-nums; }
.health-request-summary { display: flex; flex-wrap: wrap; gap: 16px 28px; }
.health-request-summary > div { display: grid; gap: 4px; }
.health-section { display: grid; gap: 12px; padding-top: 16px; border-top: 1px solid var(--line); min-width: 0; }
.health-section h3 { font-size: 14px; }
.health-section h3 small { font-size: 12px; font-weight: normal; }
.health-history { display: flex; gap: 4px; }
.health-history-bar { flex: 1 1 0; min-width: 0; height: 14px; padding: 0; border: 0; border-radius: 3px; cursor: pointer; }
.health-history-bar[data-state='HEALTHY'] { background: var(--success); }
.health-history-bar[data-state='WARNING'] { background: var(--warning); }
.health-history-bar[data-state='CRITICAL'] { background: var(--danger); }
.health-history-bar[data-state='UNKNOWN'] { background: color-mix(in srgb, var(--warning) 55%, transparent); outline: 1px dashed var(--warning); outline-offset: -1px; }
.health-history-bar.selected, .health-history-bar:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; opacity: 1; }
.health-history-axis { display: flex; justify-content: space-between; gap: 12px; }
.health-breakdown { min-width: 620px; }
.health-breakdown td:first-child { max-width: 260px; overflow-wrap: anywhere; }
.health-errors, .health-availability-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
.health-errors li, .health-availability-list li { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 16px; }
.health-errors code, .health-availability-list li > span:nth-child(2) { overflow-wrap: anywhere; min-width: 0; }
.health-footnote { display: grid; gap: 7px; border-top: 1px solid var(--line); padding-top: 12px; line-height: 1.6; }
@media (max-width: 600px) {
  .health-metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .health-metric-grid > div { padding: 12px; border-bottom: 1px solid var(--line); }
  .health-metric-grid > div:nth-child(even) { border-right: 0; }
  .health-metric-grid > div:nth-child(n+3) { border-bottom: 0; }
  .health-metric-grid strong { font-size: 20px; }
  .health-history { gap: 3px; }
}
</style>

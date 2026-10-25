<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { requestModeClass, requestModeLabel } from '../utils/requestMode'
import { reasoningEffortClass, reasoningEffortLabel, tokenUsageLabel, tokenUsageState } from '../utils/requestLogDisplay'
import {
  formatTime,
  formatDuration,
  formatNumber as formatTokens,
  formatTokensCompact,
  formatCost as formatCostUtil,
  formatUnitPrice,
} from '../utils/format'

const logs = ref([])
const loading = ref(true)
const error = ref('')
const query = ref('')
const selected = ref(null)
const detailDialog = ref(null)
let detailTrigger = null

const stageLabels = {
  request: '请求',
  routing: '路由',
  upstream: '上游',
  streaming: '流式',
  client: '客户端',
}

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return logs.value
  return logs.value.filter((log) =>
    [
      log.requestId,
      log.traceId,
      log.alias,
      log.channelId,
      log.channelName,
      log.upstreamModel,
      log.errorCode,
      log.errorMessage,
      log.failureStage,
      log.upstreamErrorCode,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(keyword),
  )
})

const errorCount = computed(() => logs.value.filter((log) => log.statusCode >= 400).length)
const averageLatency = computed(() => {
  if (logs.value.length === 0) return '—'
  const total = logs.value.reduce((sum, log) => sum + log.totalMs, 0)
  return `${Math.round(total / logs.value.length)} ms`
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    logs.value = await api.listRequestLogs(100)
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await load()
  notify('请求日志已刷新')
}

function formatCostAmount(log) {
  return formatCostUtil(log.costAmount, '')
}

function formatThroughput(log) {
  if (log.outputTokens == null || log.totalMs <= 0) return '—'
  return (log.outputTokens / (log.totalMs / 1000)).toFixed(1)
}

function formatCacheHit(log) {
  if (log.cachedTokens == null || log.inputTokens == null || log.inputTokens <= 0) return '—'
  return `${Math.round((log.cachedTokens / log.inputTokens) * 100)}%`
}

function cacheTitle(log) {
  if (log.cachedTokens == null) return '上游未返回缓存用量'
  return `缓存 ${formatTokens(log.cachedTokens)} / 输入 ${formatTokens(log.inputTokens)}`
}

function costTitle(log) {
  if (log.costAmount == null) {
    if (!log.usageKnown) return '用量未知，费用无法计算'
    return log.channelId && log.upstreamModel ? '该渠道和上游模型尚未配置计费单价' : '没有可计费的路由信息'
  }
  return `输入 ${formatTokens(log.inputTokens)} · 输出 ${formatTokens(log.outputTokens)} · 缓存 ${formatTokens(log.cachedTokens)} · ${log.costCurrency}`
}

function usageDetailTitle(log) {
  return `推理 ${formatTokens(log.reasoningTokens)} · 输出速率 ${formatThroughput(log)} tok/s · ${cacheTitle(log)}`
}

function usageStateClass(log) {
  return `usage-${tokenUsageState(log)}`
}

function stageLabel(stage) {
  return stage ? stageLabels[stage] || stage : '—'
}

function statusClass(code) {
  if (code >= 500 || code === 499) return 'danger'
  if (code >= 400) return 'warning'
  return 'success'
}

function openDetail(log, event) {
  detailTrigger = event?.currentTarget || null
  selected.value = log
}

function closeDetail() {
  selected.value = null
}

function onDetailKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDetail()
    return
  }
  if (event.key !== 'Tab' || !detailDialog.value) return
  const focusables = Array.from(
    detailDialog.value.querySelectorAll(
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
    const autofocus = detailDialog.value?.querySelector('[data-autofocus]')
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
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>最近请求</h2>
        <p>列表只保留关键信息，点击“详情”查看完整错误与路由上下文。</p>
      </div>
      <button type="button" class="button ghost" :disabled="loading" @click="refresh">
        <Icon name="refresh" :size="17" />
        刷新
      </button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span>本次读取</span>
        <strong>{{ logs.length }}</strong>
        <small>最近 100 条请求</small>
      </div>
      <div class="stat-card">
        <span>异常响应</span>
        <strong :class="errorCount ? 'text-danger' : 'text-success'">{{ errorCount }}</strong>
        <small>HTTP 4xx / 5xx / 499</small>
      </div>
      <div class="stat-card accent">
        <span>平均总耗时</span>
        <strong>{{ averageLatency }}</strong>
        <small>当前列表的简单平均</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <div class="table-toolbar">
        <label class="search-box">
          <Icon name="search" :size="17" />
          <input v-model="query" placeholder="搜索请求 ID、Trace ID、请求模型、渠道、错误码或错误信息" />
        </label>
        <span class="muted small">显示 {{ filtered.length }} / {{ logs.length }} 条</span>
      </div>

      <TablePlaceholder
        v-if="loading || logs.length === 0"
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
        desc="换一个关键词试试。"
      />
      <div v-else class="table-scroll">
        <table class="data-table logs-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>请求 ID</th>
              <th>请求模型 / 协议 / 模式</th>
              <th>渠道 / 上游模型</th>
              <th>状态 / 错误</th>
              <th>首 token / 总耗时</th>
              <th>Token 用量</th>
              <th>费用</th>
              <th class="actions-col"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in filtered" :key="log.requestId">
              <td class="muted nowrap">{{ formatTime(log.createdAt, { style: 'medium' }) }}</td>
              <td>
                <div class="request-id-cell">
                  <code class="request-id" :title="log.requestId">{{ log.requestId.slice(0, 12) }}…</code>
                  <CopyButton :text="log.requestId" title="复制请求 ID" variant="list" :size="13" />
                </div>
              </td>
              <td>
                <div class="stacked-cell">
                  <span class="badge info">{{ log.alias }}</span>
                  <div class="request-meta">
                    <span class="muted small">{{ log.protocol || '—' }}</span>
                    <span class="mode-badge" :class="`mode-${requestModeClass(log.stream)}`">{{ requestModeLabel(log.stream) }}</span>
                    <span class="reasoning-badge" :class="reasoningEffortClass(log.reasoningEffort)">
                      推理 {{ reasoningEffortLabel(log.reasoningEffort) }}
                    </span>
                  </div>
                </div>
              </td>
              <td>
                <div class="primary-cell small">{{ log.channelName || (log.channelId ? log.channelId.slice(0, 8) : '—') }}</div>
                <div class="mono muted small">{{ log.upstreamModel || '—' }}</div>
                <span v-if="log.upstreamAttempts > 1" class="badge warning">尝试 {{ log.upstreamAttempts }} 次</span>
              </td>
              <td>
                <div class="stacked-cell">
                  <span class="badge" :class="statusClass(log.statusCode)">{{ log.statusCode }}</span>
                  <template v-if="log.errorCode">
                    <span class="badge warning">{{ log.errorCode }}</span>
                    <span v-if="log.failureStage" class="stage-text">{{ stageLabel(log.failureStage) }}阶段</span>
                  </template>
                </div>
              </td>
              <td class="nowrap">
                <div class="duration-cell">
                  <span class="duration-first">首 {{ formatDuration(log.firstTokenMs) }}</span>
                  <span class="muted small">总 {{ formatDuration(log.totalMs) }}</span>
                </div>
              </td>
              <td class="usage-cell" :class="usageStateClass(log)">
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
              </td>
              <td class="nowrap" :title="costTitle(log)">
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
              <div class="detail-item">
                <span>协议</span>
                <strong>{{ selected.protocol || '—' }}</strong>
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
                <strong><span class="reasoning-badge" :class="reasoningEffortClass(selected.reasoningEffort)">{{ reasoningEffortLabel(selected.reasoningEffort) }}</span></strong>
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
              <div v-if="selected.costAmount != null" class="detail-item">
                <span>计费单价 / 1M</span>
                <strong class="detail-wrap">
                  {{ selected.costCurrency }} 输入 {{ formatUnitPrice(selected.inputPricePerMillion) }} · 输出
                  {{ formatUnitPrice(selected.outputPricePerMillion) }} · 缓存
                  {{ formatUnitPrice(selected.cachedInputPricePerMillion) }}
                </strong>
              </div>
            </div>

            <div class="detail-block">
              <span>错误信息</span>
              <p v-if="selected.errorMessage" class="detail-message">{{ selected.errorMessage }}</p>
              <p v-else class="muted">无错误信息</p>
            </div>

            <p class="muted small detail-hint">
              输出速率为输出 token / 总耗时；缓存命中为 cached / input。费用按渠道 + 上游模型的单价快照计算，推理 token 已包含在输出 token 中。缺失字段显示“—”，不做估算。日志不保存 prompt 与响应正文。
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

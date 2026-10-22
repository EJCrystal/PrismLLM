<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { copyText } from '../clipboard'
import { notify } from '../toast'
import Icon from './Icon.vue'

const logs = ref([])
const loading = ref(true)
const error = ref('')
const query = ref('')
const selected = ref(null)

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

function formatTime(value) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(value)
    : '—'
}

function formatDuration(value) {
  return value == null ? '—' : `${value} ms`
}

function formatTokens(value) {
  return value == null ? '—' : value.toLocaleString('zh-CN')
}

function stageLabel(stage) {
  return stage ? stageLabels[stage] || stage : '—'
}

function statusClass(code) {
  if (code >= 500 || code === 499) return 'danger'
  if (code >= 400) return 'warning'
  return 'success'
}

async function copyRequestId(requestId) {
  try {
    await copyText(requestId)
    notify('请求 ID 已复制')
  } catch {
    window.prompt('复制请求 ID', requestId)
  }
}

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
          <input v-model="query" placeholder="搜索请求 ID、别名、渠道、错误码或错误信息" />
        </label>
        <span class="muted small">显示 {{ filtered.length }} / {{ logs.length }} 条</span>
      </div>

      <div v-if="loading" class="loading-state">
        <span class="spinner"></span>
        正在读取请求日志…
      </div>
      <div v-else-if="logs.length === 0" class="empty-state">
        <div class="empty-icon"><Icon name="logs" :size="26" /></div>
        <h3>还没有请求日志</h3>
        <p>通过网关发起一次 /v1/chat/completions 请求后，这里会出现记录。</p>
      </div>
      <div v-else-if="filtered.length === 0" class="empty-state compact">
        <h3>没有匹配的请求</h3>
        <p>换一个关键词试试。</p>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table logs-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>请求 ID</th>
              <th>别名</th>
              <th>协议</th>
              <th>渠道 / 上游模型</th>
              <th>状态</th>
              <th>首 token</th>
              <th>总耗时</th>
              <th>tokens</th>
              <th>错误</th>
              <th class="actions-col"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in filtered" :key="log.requestId">
              <td class="muted nowrap">{{ formatTime(log.createdAt) }}</td>
              <td>
                <div class="request-id-cell">
                  <code class="request-id" :title="log.requestId">{{ log.requestId.slice(0, 12) }}…</code>
                  <button
                    type="button"
                    class="icon-button list-copy"
                    title="复制请求 ID"
                    @click="copyRequestId(log.requestId)"
                  >
                    <Icon name="copy" :size="13" />
                  </button>
                </div>
              </td>
              <td><span class="badge info">{{ log.alias }}</span></td>
              <td><span class="badge neutral">{{ log.protocol || '—' }}</span></td>
              <td>
                <div class="primary-cell small">{{ log.channelName || (log.channelId ? log.channelId.slice(0, 8) : '—') }}</div>
                <div class="mono muted small">{{ log.upstreamModel || '—' }}</div>
              </td>
              <td><span class="badge" :class="statusClass(log.statusCode)">{{ log.statusCode }}</span></td>
              <td class="nowrap">{{ formatDuration(log.firstTokenMs) }}</td>
              <td class="nowrap">{{ formatDuration(log.totalMs) }}</td>
              <td class="nowrap">
                <span v-if="log.usageKnown">{{ formatTokens(log.totalTokens) }}</span>
                <span v-else class="muted">未知</span>
              </td>
              <td>
                <div v-if="log.errorCode" class="error-cell">
                  <span class="badge warning">{{ log.errorCode }}</span>
                  <span v-if="log.failureStage" class="stage-text">{{ stageLabel(log.failureStage) }}阶段</span>
                </div>
                <span v-else class="muted">—</span>
              </td>
              <td class="actions-col">
                <button type="button" class="button subtle mini" @click="selected = log">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="selected" class="modal-backdrop" @click.self="selected = null">
      <div class="modal wide" role="dialog" aria-modal="true" aria-label="请求详情">
        <div class="modal-head">
          <div>
            <h2>请求详情</h2>
            <p class="muted small">{{ formatTime(selected.createdAt) }}</p>
          </div>
          <button type="button" class="icon-button" title="关闭" @click="selected = null">
            <Icon name="close" :size="18" />
          </button>
        </div>

        <div class="modal-body detail-stack">
          <div class="detail-id">
            <span>请求 ID</span>
            <div class="request-id-cell">
              <code class="detail-id-value">{{ selected.requestId }}</code>
              <button
                type="button"
                class="icon-button"
                title="复制请求 ID"
                @click="copyRequestId(selected.requestId)"
              >
                <Icon name="copy" :size="14" />
              </button>
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
              <span>别名</span>
              <strong>{{ selected.alias }}</strong>
            </div>
            <div class="detail-item">
              <span>渠道名称</span>
              <strong>{{ selected.channelName || '—' }}</strong>
            </div>
            <div class="detail-item">
              <span>渠道 ID</span>
              <strong class="mono detail-wrap">{{ selected.channelId || '—' }}</strong>
            </div>
            <div class="detail-item">
              <span>上游模型</span>
              <strong class="mono detail-wrap">{{ selected.upstreamModel || '—' }}</strong>
            </div>
            <div class="detail-item">
              <span>调用方密钥 ID</span>
              <strong class="mono detail-wrap">{{ selected.clientKeyId || '—' }}</strong>
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
              <span>用量</span>
              <strong v-if="selected.usageKnown">
                {{ formatTokens(selected.inputTokens) }} / {{ formatTokens(selected.outputTokens) }} /
                {{ formatTokens(selected.cachedTokens) }} / {{ formatTokens(selected.totalTokens) }}
              </strong>
              <strong v-else class="muted">未知</strong>
            </div>
          </div>

          <div class="detail-block">
            <span>错误信息</span>
            <p v-if="selected.errorMessage" class="detail-message">{{ selected.errorMessage }}</p>
            <p v-else class="muted">无错误信息</p>
          </div>

          <p class="muted small detail-hint">用量顺序：input / output / cached / total。日志不保存 prompt 与响应正文。</p>
        </div>

        <div class="modal-foot">
          <button type="button" class="button ghost" @click="selected = null">关闭</button>
        </div>
      </div>
    </div>
  </section>
</template>

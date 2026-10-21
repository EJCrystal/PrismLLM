<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Icon from './Icon.vue'

const logs = ref([])
const loading = ref(true)
const error = ref('')
const query = ref('')

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return logs.value
  return logs.value.filter((log) =>
    [log.requestId, log.alias, log.channelId, log.upstreamModel, log.errorCode]
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

function statusClass(code) {
  if (code >= 500 || code === 499) return 'danger'
  if (code >= 400) return 'warning'
  return 'success'
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>最近请求</h2>
        <p>只记录元数据与 usage，不保存 prompt 或响应正文。</p>
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
          <input v-model="query" placeholder="搜索请求 ID、别名、渠道或错误码" />
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
              <th>渠道 / 上游模型</th>
              <th>状态</th>
              <th>首 token</th>
              <th>总耗时</th>
              <th>tokens</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in filtered" :key="log.requestId">
              <td class="muted nowrap">{{ formatTime(log.createdAt) }}</td>
              <td><code class="request-id" :title="log.requestId">{{ log.requestId.slice(0, 12) }}…</code></td>
              <td><span class="badge info">{{ log.alias }}</span></td>
              <td>
                <div class="primary-cell small">{{ log.channelId ? log.channelId.slice(0, 8) : '—' }}</div>
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
                <span v-if="log.errorCode" class="badge warning">{{ log.errorCode }}</span>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

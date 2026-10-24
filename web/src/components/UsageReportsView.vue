<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatNumber } from '../utils/format'
import { formatUsageCosts, reportRange } from '../utils/usageReport'
import { createLatestRequestGuard } from '../utils/latestRequest'

const days = ref(7)
const report = ref(null)
const retention = ref(null)
const loading = ref(true)
const error = ref('')
const cleaning = ref(false)
const requests = createLatestRequestGuard()

const total = computed(() => report.value?.total || { requests: 0, errors: 0, totalTokens: 0, costs: {} })
const errorRate = computed(() => total.value.requests ? `${((total.value.errors / total.value.requests) * 100).toFixed(1)}%` : '0%')

function range() {
  return reportRange(days.value, Date.now())
}

async function load() {
  loading.value = true
  error.value = ''
  const result = await requests.run(async () => {
    const [nextReport, nextRetention] = await Promise.all([
      api.getUsageReport(range()),
      api.getRetentionPolicy(),
    ])
    return { nextReport, nextRetention }
  }).catch((cause) => ({ error: cause }))
  if (!result.isLatest) return
  if (result.error) error.value = result.error.message
  else {
    report.value = result.value.nextReport
    retention.value = result.value.nextRetention
  }
  loading.value = false
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

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>用量报表</h2>
        <p>按调用方密钥和团队汇总；费用按原计费币种分别展示，不做汇率换算。</p>
      </div>
      <div class="form-row">
        <select v-model="days" aria-label="报表时间范围" @change="load">
          <option :value="1">最近 24 小时</option>
          <option :value="7">最近 7 天</option>
          <option :value="30">最近 30 天</option>
          <option :value="90">最近 90 天</option>
        </select>
        <button type="button" class="button subtle" :disabled="loading" @click="load">
          <Icon name="refresh" :size="16" /> 刷新
        </button>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>
    <TablePlaceholder v-if="loading && !report" :loading="true" loading-text="正在汇总请求日志…" />
    <template v-else-if="report">
      <div class="stat-row">
        <div class="stat-card"><span>请求数</span><strong>{{ formatNumber(total.requests) }}</strong><small>{{ errorRate }} 错误</small></div>
        <div class="stat-card"><span>总 token</span><strong>{{ formatNumber(total.totalTokens) }}</strong><small>输入 {{ formatNumber(total.inputTokens) }} · 输出 {{ formatNumber(total.outputTokens) }}</small></div>
        <div class="stat-card accent"><span>费用</span><strong class="report-cost">{{ formatUsageCosts(total.costs) }}</strong><small>按原计费币种</small></div>
      </div>

      <div class="report-grid">
        <div class="panel report-panel">
          <div class="report-panel-head"><h3>按团队</h3><span class="muted small">{{ report.byTeam.length }} 个团队</span></div>
          <div class="table-scroll">
            <table class="data-table compact-table">
              <thead><tr><th>团队</th><th>请求</th><th>Token</th><th>费用</th></tr></thead>
              <tbody>
                <tr v-for="row in report.byTeam" :key="row.id">
                  <td>{{ row.name || (row.id === '__unassigned_team__' ? '未分配团队' : row.id) }}</td>
                  <td>{{ formatNumber(row.requests) }} <span class="muted small">/ {{ row.errors }} 错误</span></td>
                  <td>{{ formatNumber(row.totalTokens) }}</td>
                  <td class="small">{{ formatUsageCosts(row.costs) }}</td>
                </tr>
                <tr v-if="!report.byTeam.length"><td colspan="4" class="muted">该时间范围没有请求。</td></tr>
              </tbody>
            </table>
          </div>
        </div>
        <div class="panel report-panel">
          <div class="report-panel-head"><h3>按调用方密钥</h3><span class="muted small">{{ report.byClientKey.length }} 个密钥</span></div>
          <div class="table-scroll">
            <table class="data-table compact-table">
              <thead><tr><th>密钥</th><th>请求</th><th>Token</th><th>费用</th></tr></thead>
              <tbody>
                <tr v-for="row in report.byClientKey" :key="row.id">
                  <td>{{ row.name || row.id }}</td>
                  <td>{{ formatNumber(row.requests) }} <span class="muted small">/ {{ row.errors }} 错误</span></td>
                  <td>{{ formatNumber(row.totalTokens) }}</td>
                  <td class="small">{{ formatUsageCosts(row.costs) }}</td>
                </tr>
                <tr v-if="!report.byClientKey.length"><td colspan="4" class="muted">该时间范围没有请求。</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div v-if="retention" class="panel retention-panel">
        <div>
          <h3>请求日志保留</h3>
          <p class="muted small">
            当前保留 {{ retention.retentionDays }} 天，自动清理{{ retention.cleanupEnabled ? '已开启' : '已关闭' }}；每批 {{ formatNumber(retention.batchSize) }} 条。每日额度账本不受日志清理影响。
          </p>
        </div>
        <button type="button" class="button subtle" :disabled="cleaning" @click="cleanup">
          {{ cleaning ? '清理中…' : '立即清理过期日志' }}
        </button>
      </div>
    </template>
  </section>
</template>

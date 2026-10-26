<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatNumber } from '../utils/format'
import { usageCostEntries, reportRange } from '../utils/usageReport'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { createRetentionReportState } from '../utils/retentionReportState'

const days = ref(7)
const report = ref(null)
const retention = ref(null)
const loading = ref(true)
const error = ref('')
const cleaning = ref(false)
const updatingRetention = ref(false)
const retentionState = createRetentionReportState()
const requests = createLatestRequestGuard()

const total = computed(() => report.value?.total || { requests: 0, errors: 0, totalTokens: 0, costs: {} })
const errorRate = computed(() => total.value.requests ? `${((total.value.errors / total.value.requests) * 100).toFixed(1)}%` : '0%')

function range() {
  return reportRange(days.value, Date.now())
}

async function load() {
  loading.value = true
  error.value = ''
  const requestRetentionVersion = retentionState.beginLoad()
  try {
    const result = await requests.run(async () => {
      const [nextReport, nextRetention] = await Promise.all([
        api.getUsageReport(range()),
        api.getRetentionPolicy(),
      ])
      return { nextReport, nextRetention }
    }).catch((cause) => ({ error: cause, isLatest: cause.isLatest }))
    if (!result.isLatest) return
    if (result.error) error.value = result.error.message
    else {
      report.value = result.value.nextReport
      if (retentionState.shouldApplyRetention(requestRetentionVersion)) {
        retention.value = result.value.nextRetention
      }
    }
  } finally {
    if (!requests.hasActive()) loading.value = false
  }
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
    updatingRetention.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>用量报表</h2>
        <p>按调用方密钥汇总请求、token 与费用，费用按各自计费币种汇总，不做隐含汇率换算。</p>
      </div>
      <div class="form-row report-filter-row">
        <select v-model="days" class="select-input report-range-select" aria-label="报表时间范围" @change="load">
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
        <div class="stat-card accent">
          <span>费用</span>
          <div class="cost-lines" v-if="usageCostEntries(total.costs).length">
            <div class="cost-line" v-for="entry in usageCostEntries(total.costs)" :key="entry.currency">
              <strong class="cost-amount">{{ entry.value }}</strong>
              <span v-if="entry.currency !== 'USD'" class="cost-currency">{{ entry.currency }}</span>
            </div>
          </div>
          <strong v-else class="report-cost muted">—</strong>
          <small>按计费币种分别汇总，不做汇率换算</small>
        </div>
      </div>

      <div class="report-grid">
        <div class="panel report-panel">
          <div class="report-panel-head">
            <div class="report-panel-title">
              <h3>按调用方密钥</h3>
              <span class="report-count">{{ report.byClientKey.length }} 个密钥</span>
            </div>
            <span class="muted small">按总 token 排序</span>
          </div>
          <div class="table-scroll">
            <table class="data-table compact-table">
              <thead><tr><th>密钥</th><th>请求</th><th>Token</th><th>费用</th></tr></thead>
              <tbody>
                <tr v-for="row in report.byClientKey" :key="row.id">
                  <td>{{ row.name || row.id }}</td>
                  <td>{{ formatNumber(row.requests) }} <span class="muted small">/ {{ row.errors }} 错误</span></td>
                  <td>{{ formatNumber(row.totalTokens) }}</td>
                  <td class="small">
                    <div class="cost-lines compact" v-if="usageCostEntries(row.costs).length">
                      <div class="cost-line" v-for="entry in usageCostEntries(row.costs)" :key="entry.currency">
                        <span class="cost-amount mono">{{ entry.value }}</span>
                        <span v-if="entry.currency !== 'USD'" class="cost-currency">{{ entry.currency }}</span>
                      </div>
                    </div>
                    <span v-else class="muted">—</span>
                  </td>
                </tr>
                <tr v-if="!report.byClientKey.length"><td colspan="4" class="muted">该时间范围没有请求。</td></tr>
              </tbody>
            </table>
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
            保留 {{ retention.retentionDays }} 天 · 每批 {{ formatNumber(retention.batchSize) }} 条。关闭后仍可手动清理；每日额度账本不受日志清理影响。
          </p>
        </div>
        <button type="button" class="button subtle retention-action" :disabled="cleaning" @click="cleanup">
          {{ cleaning ? '清理中…' : '立即清理过期日志' }}
        </button>
      </div>
    </template>
  </section>
</template>

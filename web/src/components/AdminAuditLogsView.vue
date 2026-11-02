<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { AdminAuditLog, AuditLogPageOptions } from '../types/api'
import { auditActionLabel, auditActionOptions, auditDetailsRows, auditDetailsSummary, auditResultClass, auditResultLabel } from '../utils/adminAudit'
import { createCursorPager } from '../utils/cursorPager'
import { formatTime } from '../utils/format'
import CopyButton from './CopyButton.vue'
import CursorLoadMore from './CursorLoadMore.vue'
import Icon from './Icon.vue'
import FilterPanel from './FilterPanel.vue'
import { toDatetimeLocalValue, usageReportPresetRange } from '../utils/usageReport'
import { vScrollHint } from '../directives/scrollHint'
import TablePlaceholder from './TablePlaceholder.vue'
import ErrorState from './ErrorState.vue'
import FilterSelect from './FilterSelect.vue'

type AppliedAuditFilters = Pick<AuditLogPageOptions, 'from' | 'to' | 'actor' | 'action' | 'result'>
type AuditDisplayRow =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'entry'; key: string; entry: AdminAuditLog }
  | { kind: 'login-group'; key: string; actor: string; result: string; entries: AdminAuditLog[] }

const filterDraft = ref({ from: '', to: '', actor: '', action: '', result: '' })
const appliedFilters = ref<AppliedAuditFilters>({})
const filterError = ref('')
const hasAppliedFilters = computed(() => Object.values(appliedFilters.value).some((value) => value != null && value !== ''))

const { items: logs, pageSize, loading, loadingMore, error, hasMore, load, loadMore, setPageSize } = createCursorPager<AdminAuditLog>({
  fetchPage: (options) => api.listAuditLogs({ ...options, ...appliedFilters.value }),
})

const filterChips = computed(() => {
  const result: { key: 'time' | 'actor' | 'action' | 'result'; label: string }[] = []
  const f = appliedFilters.value
  if (f.from || f.to) result.push({ key: 'time', label: `${f.from ? formatTime(f.from) : '不限开始'} — ${f.to ? formatTime(f.to - 60_000) : '不限结束'}` })
  if (f.actor) result.push({ key: 'actor', label: `操作者：${f.actor}` })
  if (f.action) result.push({ key: 'action', label: auditActionLabel(f.action) })
  if (f.result) result.push({ key: 'result', label: f.result === 'success' ? '成功' : '失败' })
  return result
})
async function removeFilter(key: 'time' | 'actor' | 'action' | 'result') {
  if (key === 'time') {
    filterDraft.value.from = ''; filterDraft.value.to = ''
    appliedFilters.value = { ...appliedFilters.value, from: null, to: null }
  } else {
    filterDraft.value[key] = ''
    appliedFilters.value = { ...appliedFilters.value, [key]: null }
  }
  filterError.value = ''
  await refresh()
}
function selectAuditPreset(id: string) {
  const range = usageReportPresetRange(id)
  filterDraft.value.from = toDatetimeLocalValue(range.from)
  filterDraft.value.to = toDatetimeLocalValue(range.to)
  applyFilters()
}

const failureCount = computed(() => logs.value.filter((item) => item.result === 'failure').length)
function auditDayKey(timestamp: number): string {
  const date = new Date(timestamp)
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`
}
function auditDayLabel(timestamp: number): string {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(timestamp))
}
const auditRows = computed<AuditDisplayRow[]>(() => {
  const rows: AuditDisplayRow[] = []
  let index = 0
  let lastDay = ''
  while (index < logs.value.length) {
    const entry = logs.value[index]
    const day = auditDayKey(entry.createdAt)
    if (day !== lastDay) {
      rows.push({ kind: 'day', key: `day-${day}`, label: auditDayLabel(entry.createdAt) })
      lastDay = day
    }
    if (entry.action === 'admin.login') {
      let end = index + 1
      while (end < logs.value.length) {
        const candidate = logs.value[end]
        if (auditDayKey(candidate.createdAt) !== day || candidate.action !== entry.action || candidate.actor !== entry.actor || candidate.result !== entry.result) break
        end += 1
      }
      if (end - index > 1) {
        rows.push({ kind: 'login-group', key: `login-${entry.id}`, actor: entry.actor || '未认证请求', result: entry.result, entries: logs.value.slice(index, end) })
        index = end
        continue
      }
    }
    rows.push({ kind: 'entry', key: `entry-${entry.id}`, entry })
    index += 1
  }
  return rows
})
const tableScroll = ref<HTMLDivElement | null>(null)

async function refresh() {
  await load()
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
}

function dateFilterValue(value: string, includeSelectedMinute = false): number | null {
  if (!value) return null
  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) return Number.NaN
  return timestamp + (includeSelectedMinute ? 60_000 : 0)
}

async function applyFilters() {
  filterError.value = ''
  const from = dateFilterValue(filterDraft.value.from)
  const to = dateFilterValue(filterDraft.value.to, true)
  if (Number.isNaN(from) || Number.isNaN(to)) {
    filterError.value = '时间格式无效，请重新选择时间。'
    return
  }
  if (from != null && to != null && from >= to) {
    filterError.value = '开始时间必须早于结束时间。'
    return
  }
  appliedFilters.value = {
    from,
    to,
    actor: filterDraft.value.actor.trim() || null,
    action: filterDraft.value.action || null,
    result: (filterDraft.value.result || null) as AppliedAuditFilters['result'],
  }
  await refresh()
}

async function clearFilters() {
  filterDraft.value = { from: '', to: '', actor: '', action: '', result: '' }
  appliedFilters.value = {}
  filterError.value = ''
  await refresh()
}

watch(pageSize, async () => {
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
})

onMounted(load)
</script>

<template>
  <section class="page-stack page-compact">
    <div class="page-overview">
      <div class="section-head page-actions">
        <button type="button" class="button ghost refresh-button" :disabled="loading || loadingMore" :aria-busy="loading" @click="refresh">
          <Icon name="refresh" :size="16" />
          刷新
        </button>
      </div>

      <div class="stat-row two">
        <div class="stat-card">
          <span>当前已加载</span>
          <strong>{{ logs.length }}</strong>
          <small>最近的管理写操作</small>
        </div>
        <div class="stat-card">
          <span>失败操作</span>
          <strong :class="failureCount ? 'text-danger' : 'text-success'">{{ failureCount }}</strong>
          <small>权限、校验或执行失败</small>
        </div>
      </div>

    </div>

    <FilterPanel label="审计筛选" :summary="filterChips.length ? '已选 ' + filterChips.length + ' 项条件' : '全部时间与操作'" :count="filterChips.length">
    <form class="audit-filters" @submit.prevent="applyFilters">
      <div class="audit-time-presets"><span class="muted small">快捷时间</span><button v-for="item in [{ id: 'today', label: '今天' }, { id: 'last24h', label: '最近 24 小时' }, { id: 'last7d', label: '最近 7 天' }]" :key="item.id" type="button" class="button ghost mini" :disabled="loading || loadingMore" @click="selectAuditPreset(item.id)">{{ item.label }}</button></div>
      <label class="field">
        <span>开始时间</span>
        <input v-model="filterDraft.from" type="datetime-local" />
      </label>
      <label class="field">
        <span>结束时间</span>
        <input v-model="filterDraft.to" type="datetime-local" />
      </label>
      <label class="field">
        <span>操作者</span>
        <input v-model="filterDraft.actor" type="text" placeholder="输入用户名（精确匹配）" />
      </label>
      <FilterSelect v-model="filterDraft.action" label="操作类型" :disabled="loading || loadingMore" :options="[{ value: '', label: '全部类型' }, ...auditActionOptions]" />
      <FilterSelect v-model="filterDraft.result" label="结果" :disabled="loading || loadingMore" :options="[{ value: '', label: '全部结果' }, { value: 'success', label: '成功' }, { value: 'failure', label: '失败' }]" />
      <div class="audit-filter-actions">
        <button type="submit" class="button primary mini" :disabled="loading || loadingMore">筛选</button>
        <button type="button" class="button ghost mini" :disabled="loading || loadingMore || !hasAppliedFilters" @click="clearFilters">清除</button>
      </div>
    </form>
    <template #chips>
      <button v-for="chip in filterChips" :key="chip.key" type="button" class="filter-chip" :aria-label="'移除筛选：' + chip.label" :disabled="loading || loadingMore" @click="removeFilter(chip.key)"><span>{{ chip.label }}</span><Icon name="close" :size="13" /></button>
    </template>
    </FilterPanel>
    <div v-if="filterError" class="callout warning">{{ filterError }}</div>

    <ErrorState v-if="error" :message="error" :busy="loading || loadingMore" @retry="refresh" />

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || logs.length === 0"
        :loading="loading"
        loading-text="正在读取管理操作审计…"
        :empty="logs.length === 0"
        icon="shield"
        :title="hasAppliedFilters ? '没有符合条件的审计记录' : '还没有管理操作记录'"
        :desc="hasAppliedFilters ? '调整时间范围或其他筛选条件后重试。' : '登录、配置变更和密钥生命周期操作会出现在这里。'"
      />
      <div v-else ref="tableScroll" v-scroll-hint class="table-scroll table-scroll-paged">
        <table class="data-table audit-table">
          <thead>
            <tr>
              <th>时间 / 操作者</th>
              <th>操作</th>
              <th>结果</th>
              <th>请求路径</th>
              <th>请求 ID</th>
              <th>来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in auditRows" :key="row.key" :class="`audit-row-${row.kind}`">
              <template v-if="row.kind === 'day'">
                <td colspan="6" class="audit-day-cell"><span class="audit-day-marker"></span>{{ row.label }}</td>
              </template>
              <template v-else-if="row.kind === 'login-group'">
                <td colspan="6" class="audit-login-group-cell">
                  <div class="audit-login-group">
                    <span class="badge success">{{ auditResultLabel(row.result) }}</span>
                    <strong>{{ row.actor }}</strong>
                    <span>连续登录 {{ row.entries.length }} 次，已折叠</span>
                    <details>
                      <summary>展开记录</summary>
                      <ul><li v-for="entry in row.entries" :key="entry.id">{{ formatTime(entry.createdAt) }} · {{ entry.method }} {{ entry.path }} · {{ entry.requestId }}</li></ul>
                    </details>
                  </div>
                </td>
              </template>
              <template v-else>
                <td>
                  <div class="primary-cell">{{ formatTime(row.entry.createdAt) }}</div>
                  <div class="muted small">{{ row.entry.action === 'channel_key.auto_disable' ? '系统' : (row.entry.actor || '未认证请求') }}</div>
                </td>
                <td>
                  <div>{{ auditActionLabel(row.entry.action) }}</div>
                  <div class="mono muted small">{{ row.entry.action }}</div>
                  <div v-if="auditDetailsSummary(row.entry)" class="audit-details-summary small">{{ auditDetailsSummary(row.entry) }}</div>
                  <details v-if="auditDetailsRows(row.entry).length" class="audit-details">
                    <summary>查看详情</summary>
                    <dl>
                      <template v-for="[label, value] in auditDetailsRows(row.entry)" :key="label">
                        <dt>{{ label }}</dt>
                        <dd>{{ value }}</dd>
                      </template>
                    </dl>
                  </details>
                </td>
                <td>
                  <span class="badge" :class="auditResultClass(row.entry.result)">
                    {{ auditResultLabel(row.entry.result) }}
                  </span>
                  <div class="muted small">{{ row.entry.action === 'channel_key.auto_disable' ? '系统执行' : `HTTP ${row.entry.statusCode}` }}</div>
                  <div v-if="row.entry.errorCode" class="mono muted small">{{ row.entry.errorCode }}</div>
                </td>
                <td>
                  <span class="audit-path mono">{{ row.entry.method }} {{ row.entry.path }}</span>
                </td>
                <td>
                  <div class="channel-id-cell">
                    <span class="mono muted small">{{ row.entry.requestId }}</span>
                    <CopyButton :text="row.entry.requestId" title="复制请求 ID" variant="list" :size="12" />
                  </div>
                </td>
                <td class="mono muted small">{{ row.entry.sourceIp || '—' }}</td>
              </template>
            </tr>
          </tbody>
        </table>
      </div>
      <CursorLoadMore
        :loaded="logs.length"
        :has-more="hasMore"
        :loading="loading"
        :loading-more="loadingMore"
        :page-size="pageSize"
        @load-more="loadMore"
        @page-size-change="setPageSize"
      />
    </div>
  </section>
</template>

<style scoped>
.audit-filters { display: grid; grid-template-columns: repeat(5, minmax(140px, 1fr)) auto; align-items: end; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
.audit-filters .field { min-width: 0; margin: 0; }
.audit-filters > .filter-chip-group { justify-self: start; }
.audit-filters input, .audit-filters select { width: 100%; min-width: 0; }
.audit-time-presets { grid-column: 1 / -1; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.audit-filter-actions { display: flex; gap: 8px; }
.audit-details-summary { max-width: 320px; margin-top: 6px; white-space: normal; overflow-wrap: anywhere; }
.audit-details { max-width: 320px; margin-top: 6px; font-size: 12px; }
.audit-details summary { color: var(--info); cursor: pointer; }
.audit-details dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 6px 10px; white-space: normal; }
.audit-details dt { color: var(--muted); }
.audit-details dd { margin: 0; overflow-wrap: anywhere; }
.audit-row-day td { padding-top: 10px; padding-bottom: 10px; border-bottom-color: transparent; background: color-mix(in srgb, var(--brand) 5%, var(--panel)); }
.audit-day-cell { color: var(--brand); font-size: 12px; font-weight: 750; letter-spacing: .01em; }
.audit-day-marker { display: inline-block; width: 7px; height: 7px; margin-right: 8px; border-radius: 50%; background: var(--brand); box-shadow: 0 0 0 3px color-mix(in srgb, var(--brand) 14%, transparent); }
.audit-login-group-cell { padding-top: 10px !important; padding-bottom: 10px !important; background: color-mix(in srgb, var(--info) 4%, var(--panel)); }
.audit-login-group { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--muted); font-size: 12px; }
.audit-login-group strong { color: var(--ink); }
.audit-login-group details { margin-left: auto; }
.audit-login-group summary { color: var(--info); cursor: pointer; }
.audit-login-group ul { margin: 8px 0 0; padding-left: 18px; font-size: 11px; line-height: 1.7; }
@media (max-width: 1100px) { .audit-filters { grid-template-columns: repeat(3, minmax(140px, 1fr)); } }
@media (max-width: 640px) { .audit-filters { grid-template-columns: 1fr; } .audit-filter-actions { justify-content: flex-end; } }
</style>

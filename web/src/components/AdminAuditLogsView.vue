<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { auditActionLabel, auditDetailsRows, auditDetailsSummary, auditResultClass, auditResultLabel } from '../utils/adminAudit'
import { createCursorPager } from '../utils/cursorPager'
import { formatTime } from '../utils/format'
import CopyButton from './CopyButton.vue'
import CursorLoadMore from './CursorLoadMore.vue'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'

const { items: logs, pageSize, loading, loadingMore, error, hasMore, load, loadMore, setPageSize } = createCursorPager({
  fetchPage: (options) => api.listAuditLogs(options),
})

const failureCount = computed(() => logs.value.filter((item) => item.result === 'failure').length)
const tableScroll = ref(null)

async function refresh() {
  await load()
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
}

watch(pageSize, async () => {
  await nextTick()
  tableScroll.value?.scrollTo({ top: 0 })
})

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>管理操作审计</h2>
        <p>记录管理写操作与密钥自动禁用，展示操作者、触发原因和请求 ID，不保存请求正文。</p>
      </div>
      <button type="button" class="button ghost" :disabled="loading || loadingMore" @click="refresh">
        <Icon name="refresh" :size="16" />
        刷新
      </button>
    </div>

    <div class="stat-row">
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
      <div class="stat-card accent">
        <span>记录范围</span>
        <strong>只追加</strong>
        <small>接口不提供修改或删除</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || logs.length === 0"
        :loading="loading"
        loading-text="正在读取管理操作审计…"
        :empty="logs.length === 0"
        icon="shield"
        title="还没有管理操作记录"
        desc="登录、配置变更和密钥生命周期操作会出现在这里。"
      />
      <div v-else ref="tableScroll" class="table-scroll table-scroll-paged">
        <table class="data-table">
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
            <tr v-for="entry in logs" :key="entry.id">
              <td>
                <div class="primary-cell">{{ formatTime(entry.createdAt) }}</div>
                <div class="muted small">{{ entry.action === 'channel_key.auto_disable' ? '系统' : (entry.actor || '未认证请求') }}</div>
              </td>
              <td>
                <div>{{ auditActionLabel(entry.action) }}</div>
                <div class="mono muted small">{{ entry.action }}</div>
                <div v-if="auditDetailsSummary(entry)" class="audit-details-summary small">{{ auditDetailsSummary(entry) }}</div>
                <details v-if="auditDetailsRows(entry).length" class="audit-details">
                  <summary>查看详情</summary>
                  <dl>
                    <template v-for="[label, value] in auditDetailsRows(entry)" :key="label">
                      <dt>{{ label }}</dt>
                      <dd>{{ value }}</dd>
                    </template>
                  </dl>
                </details>
              </td>
              <td>
                <span class="badge" :class="auditResultClass(entry.result)">
                  {{ auditResultLabel(entry.result) }}
                </span>
                <div class="muted small">{{ entry.action === 'channel_key.auto_disable' ? '系统执行' : `HTTP ${entry.statusCode}` }}</div>
                <div v-if="entry.errorCode" class="mono muted small">{{ entry.errorCode }}</div>
              </td>
              <td>
                <span class="audit-path mono">{{ entry.method }} {{ entry.path }}</span>
              </td>
              <td>
                <div class="channel-id-cell">
                  <span class="mono muted small">{{ entry.requestId }}</span>
                  <CopyButton :text="entry.requestId" title="复制请求 ID" variant="list" :size="12" />
                </div>
              </td>
              <td class="mono muted small">{{ entry.sourceIp || '—' }}</td>
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
.audit-details-summary { max-width: 320px; margin-top: 6px; white-space: normal; overflow-wrap: anywhere; }
.audit-details { max-width: 320px; margin-top: 6px; font-size: 12px; }
.audit-details summary { color: var(--info); cursor: pointer; }
.audit-details dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 6px 10px; white-space: normal; }
.audit-details dt { color: var(--muted); }
.audit-details dd { margin: 0; overflow-wrap: anywhere; }
</style>

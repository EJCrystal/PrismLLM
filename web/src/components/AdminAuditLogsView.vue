<script setup>
import { computed, onMounted } from 'vue'
import { api } from '../api'
import { auditActionLabel, auditResultClass, auditResultLabel } from '../utils/adminAudit'
import { createAdminAuditPager } from '../utils/adminAuditPager'
import { formatTime } from '../utils/format'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'

const { logs, loading, loadingMore, error, hasMore, load, loadMore } = createAdminAuditPager({
  fetchPage: (options) => api.listAuditLogs(options),
})

const failureCount = computed(() => logs.value.filter((item) => item.result === 'failure').length)

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>管理操作审计</h2>
        <p>记录管理 API 的写操作结果、操作者、请求 ID 与来源地址，不保存请求正文。</p>
      </div>
      <button type="button" class="button ghost" :disabled="loading || loadingMore" @click="load">
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
      <div v-else class="table-scroll">
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
                <div class="muted small">{{ entry.actor || '未认证请求' }}</div>
              </td>
              <td>
                <div>{{ auditActionLabel(entry.action) }}</div>
                <div class="mono muted small">{{ entry.action }}</div>
              </td>
              <td>
                <span class="badge" :class="auditResultClass(entry.result)">
                  {{ auditResultLabel(entry.result) }}
                </span>
                <div class="muted small">HTTP {{ entry.statusCode }}</div>
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
      <div v-if="hasMore" class="load-more-row">
        <button type="button" class="button ghost" :disabled="loading || loadingMore" @click="loadMore">
          {{ loadingMore ? '正在加载…' : '加载更多' }}
        </button>
      </div>
    </div>
  </section>
</template>

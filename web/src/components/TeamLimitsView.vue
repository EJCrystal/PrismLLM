<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { formatTime } from '../utils/format'
import {
  buildTeamLimitPayload,
  teamConcurrencySummary,
  teamLimitValidationMessage,
  teamRequestSummary,
} from '../utils/teamLimits'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import TablePlaceholder from './TablePlaceholder.vue'

const limits = ref([])
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const editingTeam = ref(null)
const saving = ref(false)

const form = reactive({
  team: '',
  requestsPerMinute: '',
  maxConcurrentRequests: '',
})

const configuredCount = computed(() => limits.value.filter((item) => item.configured).length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    limits.value = await api.listTeamLimits()
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingTeam.value = null
  Object.assign(form, { team: '', requestsPerMinute: '', maxConcurrentRequests: '' })
  showForm.value = true
}

function openEdit(item) {
  editingTeam.value = item.team
  Object.assign(form, {
    team: item.team,
    requestsPerMinute: item.requestsPerMinute ?? '',
    maxConcurrentRequests: item.maxConcurrentRequests ?? '',
  })
  showForm.value = true
}

async function save() {
  const team = form.team.trim()
  const payload = buildTeamLimitPayload(form)
  const validationMessage = teamLimitValidationMessage(team, payload)
  if (validationMessage) {
    notify(validationMessage, 'error')
    return
  }
  saving.value = true
  try {
    await api.updateTeamLimit(editingTeam.value || team, payload)
    notify(editingTeam.value ? '团队限流已更新' : '团队限流已创建')
    showForm.value = false
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function clearLimit(item) {
  if (!window.confirm('确定清除团队「' + item.team + '」的限流策略？')) return
  try {
    await api.deleteTeamLimit(item.team)
    notify('团队限流已清除')
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  }
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>团队限流</h2>
        <p>按密钥上的团队标签共享每分钟请求数和并发名额。用量按单实例内存实时统计。</p>
      </div>
      <div class="row-actions">
        <button type="button" class="button ghost" :disabled="loading" @click="load">
          <Icon name="refresh" :size="16" />
          刷新用量
        </button>
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新增团队策略
        </button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span>团队总数</span>
        <strong>{{ limits.length }}</strong>
        <small>已有密钥团队与已配置策略</small>
      </div>
      <div class="stat-card">
        <span>已配置策略</span>
        <strong class="text-success">{{ configuredCount }}</strong>
        <small>未配置团队默认不限制</small>
      </div>
      <div class="stat-card accent">
        <span>请求窗口</span>
        <strong>UTC 分钟</strong>
        <small>跨分钟自动重置请求计数</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || limits.length === 0"
        :loading="loading"
        loading-text="正在读取团队限流…"
        :empty="limits.length === 0"
        icon="users"
        title="还没有团队"
        desc="为调用方密钥设置团队后，可在这里配置团队级限流。"
      />
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>团队</th>
              <th>每分钟请求</th>
              <th>并发请求</th>
              <th>策略状态</th>
              <th>更新时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in limits" :key="item.team">
              <td>
                <div class="primary-cell">{{ item.team }}</div>
              </td>
              <td class="muted small">{{ teamRequestSummary(item) }}</td>
              <td class="muted small">{{ teamConcurrencySummary(item) }}</td>
              <td>
                <span class="badge" :class="item.configured ? 'success' : 'neutral'">
                  {{ item.configured ? '已配置' : '未配置' }}
                </span>
              </td>
              <td class="muted">{{ item.updatedAt ? formatTime(item.updatedAt) : '—' }}</td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="button mini" @click="openEdit(item)">
                    {{ item.configured ? '编辑' : '配置' }}
                  </button>
                  <button
                    v-if="item.configured"
                    type="button"
                    class="icon-button danger"
                    title="清除团队限流"
                    aria-label="清除团队限流"
                    @click="clearLimit(item)"
                  >
                    <Icon name="trash" :size="16" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showForm" :title="editingTeam ? '编辑团队限流' : '新增团队限流'" @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <label class="field">
          <span>团队名称</span>
          <input v-model="form.team" :disabled="editingTeam != null" placeholder="例如 search-team" autocomplete="off" />
          <small>必须与调用方密钥上的团队标签完全一致。</small>
        </label>
        <label class="field">
          <span>每分钟请求上限</span>
          <input v-model="form.requestsPerMinute" type="number" min="0" step="1" placeholder="留空表示不限制" />
          <small>按 UTC 固定分钟窗口计数；0 表示暂停该团队的新请求。</small>
        </label>
        <label class="field">
          <span>最大并发请求数</span>
          <input v-model="form.maxConcurrentRequests" type="number" min="1" step="1" placeholder="留空表示不限制" />
          <small>同一团队内所有密钥共享并发名额。</small>
        </label>
      </form>
      <template #footer>
        <button type="button" class="button ghost" @click="showForm = false">取消</button>
        <button type="button" class="button primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存策略' }}
        </button>
      </template>
    </Modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { ClientKey } from '../types/api'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import ActionMenu from './ActionMenu.vue'
import { vScrollHint } from '../directives/scrollHint'
import Modal from './Modal.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import ErrorState from './ErrorState.vue'
import { formatTime } from '../utils/format'
import { buildClientKeyPayload, quotaSummary } from '../utils/clientKeyQuota'
import { useClientKeyActions } from '../utils/clientKeyActions'
import { useSecretReveal } from '../utils/secretReveal'

interface ClientKeyForm {
  name: string
  enabled: boolean
  expiresAt: string
  dailyTokenLimit: number | string | null
  dailyUsdLimit: number | string | null
  dailyCnyLimit: number | string | null
  dailyEurLimit: number | string | null
  maxConcurrentRequests: number | string | null
  requestsPerMinute: number | string | null
}

const keys = ref<ClientKey[]>([])
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const { secret, secretMode, showSecret, closeSecret } = useSecretReveal()
const { pendingKeyId, pendingAction, rotate, revoke } = useClientKeyActions({
  confirmAction: (message) => window.confirm(message),
  rotateRequest: (id) => api.rotateClientKey(id),
  revokeRequest: (id) => api.revokeClientKey(id),
  notify,
  reload: load,
  showSecret,
})

const form = reactive<ClientKeyForm>({
  name: '',
  enabled: true,
  expiresAt: '',
  dailyTokenLimit: '',
  dailyUsdLimit: '',
  dailyCnyLimit: '',
  dailyEurLimit: '',
  maxConcurrentRequests: '',
  requestsPerMinute: '',
})

const enabledCount = computed(() => keys.value.filter((item) => item.enabled).length)
const revokedCount = computed(() => keys.value.filter((item) => item.revokedAt != null).length)

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    keys.value = await api.listClientKeys()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    name: '',
    enabled: true,
    expiresAt: '',
    dailyTokenLimit: '',
    dailyUsdLimit: '',
    dailyCnyLimit: '',
    dailyEurLimit: '',
    maxConcurrentRequests: '',
    requestsPerMinute: '',
  })
  showForm.value = true
}

function openEdit(key: ClientKey) {
  if (pendingKeyId.value != null || key.revokedAt != null) return
  editingId.value = key.id
  Object.assign(form, {
    name: key.name,
    enabled: key.enabled,
    expiresAt: toLocalInput(key.expiresAt),
    dailyTokenLimit: key.dailyTokenLimit ?? '',
    dailyUsdLimit: key.dailyUsdLimit ?? '',
    dailyCnyLimit: key.dailyCnyLimit ?? '',
    dailyEurLimit: key.dailyEurLimit ?? '',
    maxConcurrentRequests: key.maxConcurrentRequests ?? '',
    requestsPerMinute: key.requestsPerMinute ?? '',
  })
  showForm.value = true
}

async function save() {
  if (!form.name.trim()) {
    notify('请填写密钥名称', 'error')
    return
  }
  saving.value = true
  try {
    const payload = buildClientKeyPayload(form, fromLocalInput(form.expiresAt))
    if (editingId.value) {
      await api.updateClientKey(editingId.value, payload)
      notify('调用方密钥已更新')
    } else {
      const created = await api.createClientKey(payload)
      showSecret(created, 'create')
      notify('调用方密钥已创建')
    }
    showForm.value = false
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    saving.value = false
  }
}

async function toggle(key: ClientKey) {
  if (pendingKeyId.value != null || key.revokedAt != null) return
  try {
    await api.updateClientKey(key.id, {
      name: key.name,
      enabled: !key.enabled,
      expiresAt: key.expiresAt,
      dailyTokenLimit: key.dailyTokenLimit,
      dailyUsdLimit: key.dailyUsdLimit,
      dailyCnyLimit: key.dailyCnyLimit,
      dailyEurLimit: key.dailyEurLimit,
      maxConcurrentRequests: key.maxConcurrentRequests,
      requestsPerMinute: key.requestsPerMinute,
    })
    notify(key.enabled ? '密钥已停用' : '密钥已启用')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  }
}

async function remove(key: ClientKey) {
  if (pendingKeyId.value != null) return
  if (!window.confirm(`确定删除调用方密钥「${key.name}」？已发出的请求会立即失效。`)) return
  try {
    await api.deleteClientKey(key.id)
    notify('调用方密钥已删除')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  }
}

function toLocalInput(value: number | null): string {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function fromLocalInput(value: string): number | null {
  return value ? new Date(value).getTime() : null
}

function keyMoreAction(id: string, key: ClientKey) {
  if (id === 'rotate') rotate(key)
  if (id === 'revoke') revoke(key)
  if (id === 'delete') remove(key)
}

onMounted(load)
</script>

<template>
  <section class="page-stack page-compact">
    <div class="page-overview">
      <div class="section-head page-actions">
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建密钥
        </button>
      </div>

      <div class="stat-row two">
        <div class="stat-card">
          <span>密钥总数</span>
          <strong>{{ keys.length }}</strong>
          <small>已签发的调用方凭据</small>
        </div>
        <div class="stat-card">
          <span>启用中</span>
          <strong class="text-success">{{ enabledCount }}</strong>
          <small>可用于访问 /v1；另有 {{ revokedCount }} 个已撤销</small>
        </div>
      </div>

    </div>

    <ErrorState v-if="error" :message="error" :busy="loading" @retry="load" />

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || keys.length === 0"
        :loading="loading"
        loading-text="正在读取调用方密钥…"
        :empty="keys.length === 0"
        icon="keys"
        title="还没有调用方密钥"
        desc="创建一把密钥，交给需要调用模型的内部服务。"
        action-text="新建密钥"
        @action="openCreate"
      />
      <div v-else v-scroll-hint class="table-scroll">
        <table class="data-table client-keys-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>展示前缀</th>
              <th>状态</th>
              <th>额度 / 限流</th>
              <th>过期时间</th>
              <th>创建时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="key in keys" :key="key.id">
              <td>
                <div class="primary-cell" :title="key.name">{{ key.name }}</div>
                <div class="channel-id-cell">
                  <span class="mono muted small" :title="key.id">{{ key.id }}</span>
                  <CopyButton :text="key.id" title="复制密钥 ID" variant="list" :size="12" />
                </div>
              </td>
              <td><code class="key-prefix" :title="key.keyPrefix">{{ key.keyPrefix }}…</code></td>
              <td>
                <span
                  class="badge"
                  :class="key.revokedAt ? 'danger' : key.enabled ? 'success' : 'neutral'"
                >
                  {{ key.revokedAt ? '已撤销' : key.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="muted small">{{ quotaSummary(key) }}</td>
              <td class="muted">{{ formatTime(key.expiresAt, { fallback: '永不过期' }) }}</td>
              <td class="muted">
                <div>{{ formatTime(key.createdAt) }}</div>
                <div v-if="key.rotatedAt" class="small">轮换于 {{ formatTime(key.rotatedAt) }}</div>
                <div v-if="key.revokedAt" class="small text-danger">撤销于 {{ formatTime(key.revokedAt) }}</div>
              </td>
              <td class="actions-col">
                <div class="row-actions">
                  <button
                    v-if="key.revokedAt == null"
                    type="button"
                    class="button mini"
                    :disabled="pendingKeyId != null"
                    @click="toggle(key)"
                  >
                    {{ key.enabled ? '停用' : '启用' }}
                  </button>
                  <button
                    v-if="key.revokedAt == null"
                    type="button"
                    class="icon-button"
                    title="编辑"
                    aria-label="编辑调用方密钥"
                    :disabled="pendingKeyId != null"
                    @click="openEdit(key)"
                  >
                    <Icon name="edit" :size="16" />
                  </button>
                  <ActionMenu :label="key.name + '的更多操作'" compact :disabled="pendingKeyId != null" :items="key.revokedAt ? [{ id: 'delete', label: '删除密钥', icon: 'trash', danger: true }] : [{ id: 'rotate', label: pendingAction === 'rotate' && pendingKeyId === key.id ? '轮换中…' : '轮换密钥', icon: 'refresh' }, { id: 'revoke', label: '永久撤销', icon: 'shield', danger: true }, { id: 'delete', label: '删除密钥', icon: 'trash', danger: true }]" @select="keyMoreAction($event, key)" />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showForm" :title="editingId ? '编辑调用方密钥' : '新建调用方密钥'" @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <label class="field">
          <span>名称</span>
          <input v-model="form.name" placeholder="例如 search-service" autocomplete="off" />
          <small>用于识别调用方，不会出现在密钥明文里。</small>
        </label>
        <label class="field">
          <span>过期时间</span>
          <input v-model="form.expiresAt" type="datetime-local" />
          <small>留空表示永不过期；到期后请求会直接返回 401。</small>
        </label>
        <label class="field" for="client-key-daily-token-limit">
          <span>每日 token 上限</span>
          <input id="client-key-daily-token-limit" v-model="form.dailyTokenLimit" type="number" min="0" step="1" placeholder="留空表示不限制" />
          <small>按 UTC 自然日累计上游返回的总 token；达到上限后返回 429。</small>
        </label>
        <label class="field" for="client-key-daily-usd-limit">
          <span>每日费用上限 (USD)</span>
          <input
            id="client-key-daily-usd-limit"
            v-model="form.dailyUsdLimit"
            type="number"
            min="0"
            step="0.00000001"
            placeholder="留空表示不限制"
          />
          <small>按模型单价累计美元费用；达到上限后返回 429。</small>
        </label>
        <label class="field" for="client-key-max-concurrent">
          <span>最大并发请求数</span>
          <input id="client-key-max-concurrent" v-model="form.maxConcurrentRequests" type="number" min="1" step="1" placeholder="留空表示不限制" />
          <small>单实例内同时处理的请求数；响应结束或失败后自动释放。</small>
        </label>
        <label class="field" for="client-key-requests-per-minute">
          <span>每分钟请求上限</span>
          <input id="client-key-requests-per-minute" v-model="form.requestsPerMinute" type="number" min="0" step="1" placeholder="留空表示不限制" />
          <small>按 UTC 固定分钟窗口计算；0 表示暂停该密钥的新请求。</small>
        </label>
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>启用密钥</strong>
            <small>停用后立即拒绝新的请求。</small>
          </span>
        </label>
      </form>
      <template #footer>
        <button type="button" class="button ghost" @click="showForm = false">取消</button>
        <button type="button" class="button primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : editingId ? '保存修改' : '创建密钥' }}
        </button>
      </template>
    </Modal>

    <Modal
      v-if="secret"
      :title="secretMode === 'rotate' ? '新密钥只显示这一次' : '密钥只显示这一次'"
      @close="closeSecret"
    >
      <div class="secret-box">
        <p class="secret-warning">
          <template v-if="secretMode === 'rotate'">旧密钥已经立即失效。请现在复制并保存新密钥，关闭后服务端无法再次还原。</template>
          <template v-else>请现在复制并保存。关闭后服务端无法再次还原；如遗失，可在密钥列表轮换获取新明文。</template>
        </p>
        <div class="secret-value">
          <code>{{ secret.key }}</code>
          <CopyButton :text="secret.key" title="复制密钥" variant="subtle" :size="16" />
        </div>
        <p class="secret-usage">
          调用示例：<code>Authorization: Bearer {{ secret.key }}</code>
        </p>
      </div>
      <template #footer>
        <button type="button" class="button primary" @click="closeSecret">我已保存</button>
      </template>
    </Modal>
  </section>
</template>

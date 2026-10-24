<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatTime } from '../utils/format'
import { buildClientKeyPayload, quotaSummary } from '../utils/clientKeyQuota'

const keys = ref([])
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingId = ref(null)
const secret = ref(null)

const form = reactive({
  name: '',
  enabled: true,
  expiresAt: '',
  dailyTokenLimit: '',
  dailyUsdLimit: '',
  dailyCnyLimit: '',
  dailyEurLimit: '',
  maxConcurrentRequests: '',
})

const enabledCount = computed(() => keys.value.filter((item) => item.enabled).length)
const quotaCurrencies = [
  { code: 'USD', field: 'dailyUsdLimit' },
  { code: 'CNY', field: 'dailyCnyLimit' },
  { code: 'EUR', field: 'dailyEurLimit' },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    keys.value = await api.listClientKeys()
  } catch (cause) {
    error.value = cause.message
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
  })
  showForm.value = true
}

function openEdit(key) {
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
      secret.value = created
      notify('调用方密钥已创建')
    }
    showForm.value = false
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function toggle(key) {
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
    })
    notify(key.enabled ? '密钥已停用' : '密钥已启用')
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  }
}

async function remove(key) {
  if (!window.confirm(`确定删除调用方密钥「${key.name}」？已发出的请求会立即失效。`)) return
  try {
    await api.deleteClientKey(key.id)
    notify('调用方密钥已删除')
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  }
}

function toLocalInput(value) {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function fromLocalInput(value) {
  return value ? new Date(value).getTime() : null
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>访问凭据</h2>
        <p>调用方用这把密钥访问网关。明文只在创建时显示一次。</p>
      </div>
      <button type="button" class="button primary" @click="openCreate">
        <Icon name="plus" :size="17" />
        新建密钥
      </button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span>密钥总数</span>
        <strong>{{ keys.length }}</strong>
        <small>已签发的调用方凭据</small>
      </div>
      <div class="stat-card">
        <span>启用中</span>
        <strong class="text-success">{{ enabledCount }}</strong>
        <small>可用于访问 /v1</small>
      </div>
      <div class="stat-card accent">
        <span>存储方式</span>
        <strong>SHA-256</strong>
        <small>只保存哈希与展示前缀</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

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
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>展示前缀</th>
              <th>状态</th>
              <th>每日额度 / 并发</th>
              <th>过期时间</th>
              <th>创建时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="key in keys" :key="key.id">
              <td>
                <div class="primary-cell">{{ key.name }}</div>
                <div class="channel-id-cell">
                  <span class="mono muted small">{{ key.id }}</span>
                  <CopyButton :text="key.id" title="复制密钥 ID" variant="list" :size="12" />
                </div>
              </td>
              <td><code class="key-prefix">{{ key.keyPrefix }}…</code></td>
              <td>
                <span class="badge" :class="key.enabled ? 'success' : 'neutral'">
                  {{ key.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="muted small">{{ quotaSummary(key) }}</td>
              <td class="muted">{{ formatTime(key.expiresAt, { fallback: '永不过期' }) }}</td>
              <td class="muted">{{ formatTime(key.createdAt) }}</td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="button mini" @click="toggle(key)">
                    {{ key.enabled ? '停用' : '启用' }}
                  </button>
                  <button type="button" class="icon-button" title="编辑" @click="openEdit(key)">
                    <Icon name="edit" :size="16" />
                  </button>
                  <button type="button" class="icon-button danger" title="删除" @click="remove(key)">
                    <Icon name="trash" :size="16" />
                  </button>
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
        <div class="field">
          <span>每日费用上限</span>
          <div class="quota-currency-grid">
            <label v-for="currency in quotaCurrencies" :key="currency.code" class="field">
              <small>{{ currency.code }}</small>
              <input
                v-model="form[currency.field]"
                type="number"
                min="0"
                step="0.00000001"
                placeholder="留空表示不限制"
              />
            </label>
          </div>
          <small>按模型单价的实际币种分别累计；未配置的币种不限制费用。</small>
        </div>
        <label class="field" for="client-key-max-concurrent">
          <span>最大并发请求数</span>
          <input id="client-key-max-concurrent" v-model="form.maxConcurrentRequests" type="number" min="1" step="1" placeholder="留空表示不限制" />
          <small>单实例内同时处理的请求数；响应结束或失败后自动释放。</small>
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

    <Modal v-if="secret" title="密钥只显示这一次" @close="secret = null">
      <div class="secret-box">
        <p class="secret-warning">
          请现在复制并保存。关闭后服务端无法再次还原这把密钥，只能重新创建。
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
        <button type="button" class="button primary" @click="secret = null">我已保存</button>
      </template>
    </Modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { ClientKey, ClientKeyAccessPolicy, RuntimeOccupancy } from '../types/api'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import ActionMenu from './ActionMenu.vue'
import { vScrollHint } from '../directives/scrollHint'
import Modal from './Modal.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import ErrorState from './ErrorState.vue'
import ClientKeyAccessFields from './ClientKeyAccessFields.vue'
import { formatTime } from '../utils/format'
import { buildClientKeyPayload, concurrencySummary, quotaSummary } from '../utils/clientKeyQuota'
import { useClientKeyActions } from '../utils/clientKeyActions'
import { useSecretReveal } from '../utils/secretReveal'
import { clientKeyAccessSummary, conflictingClientKeyAccessKeys, createUnrestrictedAccessPolicy, normalizeClientKeyAccessPolicy } from '../utils/clientKeyAccess'
import { emptyAccessDimensionLabels } from '../utils/clientKeyAccessTree'
import { buildClientKeyIpPolicy, clientKeyIpPolicySummary } from '../utils/clientKeyIpPolicy'

interface ClientKeyForm {
  name: string
  enabled: boolean
  expiresAt: string
  dailyTokenLimit: number | string | null
  dailyUsdLimit: number | string | null
  maxConcurrentRequests: number | string | null
  requestsPerMinute: number | string | null
  accessPolicy: ClientKeyAccessPolicy
  ipAllowlist: string
  ipDenylist: string
}

const keys = ref<ClientKey[]>([])
const loading = ref(true)
const error = ref('')
// 实时占用独立轮询；计数只在网关单实例内存中，读取失败时保留上一次快照。
const occupancy = ref<RuntimeOccupancy | null>(null)
const occupancyRefreshMs = ref(5_000)
let occupancyRevision = 0
const liveConcurrent = computed(() => new Map((occupancy.value?.clientKeys ?? []).map((row) => [row.clientKeyId, row.concurrentRequests])))
function keyConcurrent(clientKeyId: string): number {
  return liveConcurrent.value.get(clientKeyId) ?? 0
}
async function loadOccupancy(): Promise<void> {
  const request = ++occupancyRevision
  try {
    const result = await api.getOccupancy()
    if (request === occupancyRevision) occupancy.value = result
  } catch {
    // 占用是辅助信息，保留上一次快照，避免把在途数误显示为 0。
  }
}
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
  maxConcurrentRequests: '',
  requestsPerMinute: '',
  accessPolicy: createUnrestrictedAccessPolicy(),
  ipAllowlist: '',
  ipDenylist: '',
})

const enabledCount = computed(() => keys.value.filter((item) => item.enabled && item.revokedAt == null).length)
const revokedCount = computed(() => keys.value.filter((item) => item.revokedAt != null).length)
const keyQuery = ref('')
const keyStatusFilter = ref<'all' | 'enabled' | 'disabled' | 'revoked'>('all')
const keyStatusOptions: Array<{ id: 'all' | 'enabled' | 'disabled' | 'revoked'; label: string }> = [
  { id: 'all', label: '全部状态' },
  { id: 'enabled', label: '启用中' },
  { id: 'disabled', label: '已停用' },
  { id: 'revoked', label: '已撤销' },
]
const hasKeyFilters = computed(() => keyQuery.value.trim().length > 0 || keyStatusFilter.value !== 'all')
const filteredKeys = computed(() => {
  const search = keyQuery.value.trim().toLowerCase()
  return keys.value.filter((item) => {
    if (keyStatusFilter.value === 'enabled' && (item.revokedAt != null || !item.enabled)) return false
    if (keyStatusFilter.value === 'disabled' && (item.revokedAt != null || item.enabled)) return false
    if (keyStatusFilter.value === 'revoked' && item.revokedAt == null) return false
    if (!search) return true
    return `${item.name} ${item.keyPrefix} ${item.id}`.toLowerCase().includes(search)
  })
})
// 密钥白名单与渠道范围冲突时禁止保存；后端同样校验，这里把失败前移到表单内。
const accessConflicts = computed(() => conflictingClientKeyAccessKeys(form.accessPolicy))
// 白/黑名单模式下没有任何目标时禁止保存，避免“空白名单拒绝全部”的线上误封锁。
const emptyAccessScopes = computed(() => emptyAccessDimensionLabels(form.accessPolicy))

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
    maxConcurrentRequests: '',
    requestsPerMinute: '',
    accessPolicy: createUnrestrictedAccessPolicy(),
    ipAllowlist: '',
    ipDenylist: '',
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
    maxConcurrentRequests: key.maxConcurrentRequests ?? '',
    requestsPerMinute: key.requestsPerMinute ?? '',
    accessPolicy: normalizeClientKeyAccessPolicy(key.accessPolicy),
    ipAllowlist: key.ipPolicy?.allowlist.join('\n') ?? '',
    ipDenylist: key.ipPolicy?.denylist.join('\n') ?? '',
  })
  showForm.value = true
}

async function save() {
  if (!form.name.trim()) {
    notify('请填写密钥名称', 'error')
    return
  }
  if (accessConflicts.value.length) {
    notify('已选渠道密钥与渠道访问范围冲突，请调整后保存', 'error')
    return
  }
  if (emptyAccessScopes.value.length) {
    notify('白名单/黑名单模式下请至少勾选 1 个目标；如需全部放行请选择「不限制」', 'error')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...buildClientKeyPayload(form, fromLocalInput(form.expiresAt)),
      accessPolicy: normalizeClientKeyAccessPolicy(form.accessPolicy),
      ipPolicy: buildClientKeyIpPolicy(form.ipAllowlist, form.ipDenylist),
    }
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

useAutoRefresh(occupancyRefreshMs, () => { void loadOccupancy() }, () => loading.value || document.hidden)
onMounted(() => {
  void load()
  void loadOccupancy()
})
</script>

<template>
  <section class="page-stack page-compact">
    <div class="channel-page-head">
      <dl class="list-summary" aria-label="调用方密钥概况">
        <div><dt>密钥总数</dt><dd>{{ keys.length }}</dd></div>
        <div><dt>启用中</dt><dd class="text-success">{{ enabledCount }}</dd></div>
        <div v-if="revokedCount"><dt>已撤销</dt><dd class="text-danger">{{ revokedCount }}</dd></div>
      </dl>
      <div class="section-head-actions channel-page-actions">
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建密钥
        </button>
      </div>
    </div>

    <ErrorState v-if="error" :message="error" :busy="loading" @retry="load" />

    <div class="panel table-panel">
      <div v-if="!loading && keys.length" class="channel-list-toolbar client-keys-toolbar">
        <label class="search-box channel-name-search">
          <Icon name="search" :size="15" />
          <input v-model="keyQuery" type="search" aria-label="按名称、前缀或 ID 筛选密钥" placeholder="按名称、前缀或 ID 筛选" autocomplete="off" />
        </label>
        <div class="client-keys-toolbar-right">
          <div class="client-keys-status-filter" role="group" aria-label="按密钥状态筛选">
            <button
              v-for="option in keyStatusOptions"
              :key="option.id"
              type="button"
              :class="{ active: keyStatusFilter === option.id }"
              :aria-pressed="keyStatusFilter === option.id"
              @click="keyStatusFilter = option.id"
            >{{ option.label }}</button>
          </div>
          <button v-if="hasKeyFilters" type="button" class="filter-reset" @click="keyQuery = ''; keyStatusFilter = 'all'">清除筛选</button>
        </div>
      </div>
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
            <tr v-if="!filteredKeys.length">
              <td colspan="7" class="channel-filter-empty muted">当前筛选条件下没有调用方密钥</td>
            </tr>
            <tr v-for="key in filteredKeys" :key="key.id">
              <td>
                <div class="primary-cell" :title="key.name">{{ key.name }}</div>
                <div class="channel-id-cell">
                  <span class="mono muted small" :title="key.id">{{ key.id }}</span>
                  <CopyButton :text="key.id" title="复制密钥 ID" :size="12" />
                </div>
                <div class="client-key-access-summary muted small">
                  <template v-for="(part, idx) in clientKeyAccessSummary(key.accessPolicy).split(' · ')" :key="idx">
                    <span class="access-summary-item">{{ part }}</span>
                  </template>
                  <span v-if="clientKeyIpPolicySummary(key.ipPolicy)" class="access-summary-item is-ip">{{ clientKeyIpPolicySummary(key.ipPolicy) }}</span>
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
              <td class="muted small">
                <div>{{ quotaSummary(key) }}</div>
                <div class="client-key-occupancy" :class="{ 'is-active': keyConcurrent(key.id) > 0 }">
                  <span class="client-key-occupancy-dot" aria-hidden="true"></span>
                  {{ concurrencySummary(key, keyConcurrent(key.id)) }}
                </div>
              </td>
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

    <Modal v-if="showForm" :title="editingId ? '编辑调用方密钥' : '新建调用方密钥'" wide @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <div class="form-grid-two">
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
        </div>
        <div class="form-grid-two">
          <label class="field" for="client-key-daily-token-limit">
            <span>每日 token 上限</span>
            <input id="client-key-daily-token-limit" v-model="form.dailyTokenLimit" type="number" min="0" step="1" placeholder="留空表示不限制" />
            <small>按 UTC 自然日累计上游返回的总 token；达到上限后返回 429。</small>
          </label>
          <label class="field" for="client-key-daily-usd-limit">
            <span>每日费用上限 ($)</span>
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
        </div>
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>启用密钥</strong>
            <small>停用后立即拒绝新的请求。</small>
          </span>
        </label>
        <ClientKeyAccessFields v-model="form.accessPolicy" />
        <section class="client-key-ip-policy" aria-labelledby="client-key-ip-policy-title">
          <h3 id="client-key-ip-policy-title">调用 IP 限制</h3>
          <p class="muted small">支持 IPv4、IPv6 和 CIDR 网段，每行一条或用逗号分隔，每份名单最多 256 条。两份名单同时生效，黑名单优先；拒绝的请求返回 403。</p>
          <div class="form-grid-two">
            <label class="field" for="client-key-ip-allowlist">
              <span>IP 白名单</span>
              <textarea id="client-key-ip-allowlist" v-model="form.ipAllowlist" rows="4" placeholder="203.0.113.10&#10;198.51.100.0/24&#10;2001:db8::/32" spellcheck="false" />
              <small>留空不限制来源；填写后只放行匹配的 IP 或网段。</small>
            </label>
            <label class="field" for="client-key-ip-denylist">
              <span>IP 黑名单</span>
              <textarea id="client-key-ip-denylist" v-model="form.ipDenylist" rows="4" placeholder="203.0.113.20&#10;192.0.2.0/24" spellcheck="false" />
              <small>匹配即拒绝，即使该地址也在白名单中；留空不额外拦截。</small>
            </label>
          </div>
          <p class="muted small">来源 IP 沿用网关的可信代理配置；仅信任来自可信代理的转发头。</p>
        </section>
      </form>
      <template #footer>
        <span class="access-footer-status" :class="{ 'is-error': emptyAccessScopes.length > 0 }" role="status">
          {{ emptyAccessScopes.length > 0 ? '存在未勾选的空名单，请先完成选择' : '最终可调用的模型与密钥取以上两个维度的交集' }}
        </span>
        <button type="button" class="button ghost" @click="showForm = false">取消</button>
        <button type="button" class="button primary" :disabled="saving || accessConflicts.length > 0 || emptyAccessScopes.length > 0" @click="save">
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

<style scoped>
.client-key-access-summary { max-width: 280px; margin-top: 5px; white-space: normal; overflow-wrap: anywhere; }
.client-key-occupancy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
  font-variant-numeric: tabular-nums;
}

.client-key-occupancy-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--line-strong);
}

.client-key-occupancy.is-active { color: var(--success); font-weight: 600; }
.client-key-occupancy.is-active .client-key-occupancy-dot {
  background: var(--success-fill);
  box-shadow: 0 0 0 3px var(--success-soft);
}
/* 访问范围弹窗底部左侧状态：正常为灰色说明，空名单未完成时转红。 */
.access-footer-status {
  flex: 1 1 auto;
  margin-right: auto;
  font-size: 12px;
  color: var(--muted);
  overflow-wrap: anywhere;
}
.access-footer-status.is-error {
  color: var(--danger);
  font-weight: 500;
}
@media (max-width: 640px) {
  /* 手机端状态文字独占一行，取消/保存按钮落到下一行，避免窄屏挤压。 */
  .access-footer-status {
    flex: 1 1 100%;
    margin-right: 0;
  }
}
</style>

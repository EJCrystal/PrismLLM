<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { Channel, OAuthAccount, OAuthProviderInfo } from '../types/api'
import ErrorState from './ErrorState.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { fromLocalInput, formatTime } from '../utils/format'
import {
  buildOAuthImportPayload,
  buildOAuthPolicyPayload,
  formatCooldownRemaining,
  isOAuthAccountCooling,
  isOAuthQuotaBlocked,
  oauthAuthStateBadge,
  oauthAuthStateLabel,
  oauthPolicySummary,
  oauthProviderLabel,
  oauthQuotaCreditsLabel,
  oauthQuotaTone,
  oauthQuotaWindows,
  oauthRuntimeSummary,
} from '../utils/oauthAccount'

const accounts = ref<OAuthAccount[]>([])
const providers = ref<OAuthProviderInfo[]>([])
const channels = ref<Channel[]>([])
const loading = ref(true)
const error = ref('')
const busyKeyId = ref<string | null>(null)
const now = ref(Date.now())
let clock: number | undefined

const showImport = ref(false)
const importSaving = ref(false)
const reauthTarget = ref<OAuthAccount | null>(null)
const selectedTarget = ref('')
const importForm = reactive({
  provider: 'chatgpt',
  refreshToken: '',
  accountEmail: '',
  accountPlan: '',
  proxyUrl: '',
  accessToken: '',
  accessTokenExpiresAt: '',
})

const showPolicy = ref(false)
const policySaving = ref(false)
const policyTarget = ref<OAuthAccount | null>(null)
const policyForm = reactive<{ windowLimit: string | number; windowSeconds: string | number; maxConcurrentRequests: string | number }>({
  windowLimit: '',
  windowSeconds: 10_800,
  maxConcurrentRequests: 1,
})

const activeCount = computed(() => accounts.value.filter((item) => item.authState === 'ACTIVE').length)
const reauthCount = computed(() => accounts.value.filter((item) => item.authState === 'REAUTH_REQUIRED').length)
const coolingCount = computed(() => accounts.value.filter((item) => isOAuthAccountCooling(item, now.value)).length)
const configuredProviders = computed(() => providers.value.filter((item) => item.configured))
const boundKeyIds = computed(() => new Set(accounts.value.map((item) => item.keyId)))
const keyOptions = computed(() =>
  channels.value.flatMap((channel) =>
    channel.keys.map((key) => ({
      value: `${channel.id}:${key.id}`,
      label: `${channel.name} / ${key.name}`,
      disabled: boundKeyIds.value.has(key.id),
    })),
  ),
)

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [nextAccounts, nextProviders, nextChannels] = await Promise.all([
      api.listOAuthAccounts(),
      api.listOAuthProviders(),
      api.listChannels(),
    ])
    accounts.value = nextAccounts
    providers.value = nextProviders
    channels.value = nextChannels
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openImport() {
  reauthTarget.value = null
  selectedTarget.value = ''
  Object.assign(importForm, {
    provider: configuredProviders.value[0]?.id ?? providers.value[0]?.id ?? 'chatgpt',
    refreshToken: '',
    accountEmail: '',
    accountPlan: '',
    proxyUrl: '',
    accessToken: '',
    accessTokenExpiresAt: '',
  })
  showImport.value = true
}

function openReauth(account: OAuthAccount) {
  reauthTarget.value = account
  selectedTarget.value = `${account.channelId}:${account.keyId}`
  Object.assign(importForm, {
    provider: account.provider,
    refreshToken: '',
    accountEmail: account.accountEmail ?? '',
    accountPlan: account.accountPlan ?? '',
    proxyUrl: account.proxyUrl ?? '',
    accessToken: '',
    accessTokenExpiresAt: '',
  })
  showImport.value = true
}

async function saveImport() {
  let channelId: string
  let keyId: string
  if (reauthTarget.value) {
    channelId = reauthTarget.value.channelId
    keyId = reauthTarget.value.keyId
  } else {
    const [channel, key] = selectedTarget.value.split(':')
    if (!channel || !key) {
      notify('请选择要绑定的渠道密钥', 'error')
      return
    }
    channelId = channel
    keyId = key
  }
  importSaving.value = true
  try {
    const payload = buildOAuthImportPayload({
      ...importForm,
      accessTokenExpiresAt: fromLocalInput(importForm.accessTokenExpiresAt),
    })
    await api.importOAuthAccount(channelId, keyId, payload)
    notify(reauthTarget.value ? '订阅账号已重新授权' : '订阅账号已导入')
    showImport.value = false
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    importSaving.value = false
  }
}

async function refreshToken(account: OAuthAccount) {
  if (busyKeyId.value != null) return
  busyKeyId.value = account.keyId
  try {
    await api.refreshOAuthAccount(account.keyId)
    notify('access token 已刷新')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyKeyId.value = null
  }
}

async function refreshQuota(account: OAuthAccount) {
  if (busyKeyId.value != null) return
  busyKeyId.value = account.keyId
  try {
    await api.refreshOAuthAccountQuota(account.keyId)
    notify('上游额度已更新')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyKeyId.value = null
  }
}

async function clearCooldown(account: OAuthAccount) {
  if (busyKeyId.value != null) return
  busyKeyId.value = account.keyId
  try {
    await api.clearOAuthAccountCooldown(account.keyId)
    notify('账号冷却已解除')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyKeyId.value = null
  }
}

function openPolicy(account: OAuthAccount) {
  policyTarget.value = account
  policyForm.windowLimit = account.policy.windowLimit ?? ''
  policyForm.windowSeconds = account.policy.windowSeconds
  policyForm.maxConcurrentRequests = account.policy.maxConcurrentRequests
  showPolicy.value = true
}

async function savePolicy() {
  if (!policyTarget.value) return
  policySaving.value = true
  try {
    await api.updateOAuthAccountPolicy(policyTarget.value.keyId, buildOAuthPolicyPayload(policyForm))
    notify('账号调度策略已更新')
    showPolicy.value = false
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    policySaving.value = false
  }
}

onMounted(() => {
  load()
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (clock != null) window.clearInterval(clock)
})
</script>

<template>
  <section class="page-stack page-compact">
    <div class="page-overview">
      <div class="section-head page-actions">
        <button type="button" class="button primary" @click="openImport">
          <Icon name="plus" :size="17" />
          导入账号
        </button>
      </div>

      <div class="stat-row four">
        <div class="stat-card">
          <span>订阅账号</span>
          <strong>{{ accounts.length }}</strong>
          <small>已绑定 OAuth 的渠道密钥</small>
        </div>
        <div class="stat-card">
          <span>正常</span>
          <strong class="text-success">{{ activeCount }}</strong>
          <small>可自动刷新 access token</small>
        </div>
        <div class="stat-card">
          <span>需重新授权</span>
          <strong :class="reauthCount > 0 ? 'text-danger' : ''">{{ reauthCount }}</strong>
          <small>refresh token 已失效</small>
        </div>
        <div class="stat-card">
          <span>冷却中</span>
          <strong>{{ coolingCount }}</strong>
          <small>被上游 429 限流暂停调度</small>
        </div>
      </div>
    </div>

    <ErrorState v-if="error" :message="error" :busy="loading" @retry="load" />

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || accounts.length === 0"
        :loading="loading"
        loading-text="正在读取订阅账号…"
        :empty="accounts.length === 0"
        icon="users"
        title="还没有订阅账号"
        desc="先在渠道里创建密钥，再把 ChatGPT 或 Antigravity 的 refresh token 绑定到该密钥。"
        action-text="导入账号"
        @action="openImport"
      />
      <div v-else class="table-scroll">
        <table class="data-table oauth-accounts-table">
          <thead>
            <tr>
              <th>账号</th>
              <th>提供方</th>
              <th>状态</th>
              <th>Token</th>
              <th>上游额度</th>
              <th>调度</th>
              <th>最近刷新</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.keyId">
              <td>
                <div class="primary-cell" :title="account.accountEmail || account.keyName">
                  {{ account.accountEmail || account.keyName }}
                </div>
                <div class="channel-id-cell">
                  <span class="muted small">{{ account.channelName }} / {{ account.keyName }}</span>
                </div>
              </td>
              <td>
                <span class="badge neutral">{{ oauthProviderLabel(account.provider) }}</span>
                <div v-if="account.accountPlan" class="small muted">{{ account.accountPlan }}</div>
              </td>
              <td>
                <span class="badge" :class="oauthAuthStateBadge(account.authState)">
                  {{ oauthAuthStateLabel(account.authState) }}
                </span>
                <div v-if="account.authState === 'REAUTH_REQUIRED' && account.lastRefreshError" class="small text-danger">
                  {{ account.lastRefreshError }}
                </div>
              </td>
              <td class="muted small">
                <div>{{ account.accessTokenExpiresAt ? '到期 ' + formatTime(account.accessTokenExpiresAt) : '尚未获取 token' }}</div>
                <div v-if="account.proxyUrl" class="mono" :title="account.proxyUrl">{{ account.proxyUrl }}</div>
              </td>
              <td class="oauth-quota-cell">
                <template v-if="account.quota">
                  <div v-for="window in oauthQuotaWindows(account.quota)" :key="window.key" class="quota-row">
                    <span class="quota-label">{{ window.label }}</span>
                    <span class="quota-track">
                      <span
                        class="quota-fill"
                        :class="'is-' + oauthQuotaTone(window.usedPercent)"
                        :style="{ width: window.usedPercent + '%' }"
                      ></span>
                    </span>
                    <span class="quota-value small muted">{{ Math.round(window.usedPercent) }}%</span>
                  </div>
                  <div class="small muted">
                    <span v-if="oauthQuotaCreditsLabel(account.quota)">{{ oauthQuotaCreditsLabel(account.quota) }} · </span>
                    <span>{{ account.quota.checkedAt > 0 ? formatTime(account.quota.checkedAt) + ' 更新' : '尚未成功读取' }}</span>
                  </div>
                  <div v-if="account.quota.error" class="small text-danger">读取失败：{{ account.quota.error }}</div>
                  <div v-if="isOAuthQuotaBlocked(account, now)" class="small text-danger">
                    额度用尽，等待重置（{{ formatCooldownRemaining(account.runtime.quotaBlockedUntil, now) }}）
                  </div>
                </template>
                <span v-else class="muted small">未读取</span>
              </td>
              <td class="muted small">
                <div>{{ oauthPolicySummary(account.policy) }}</div>
                <div>{{ oauthRuntimeSummary(account, now) }}</div>
              </td>
              <td class="muted">
                <div>{{ formatTime(account.lastRefreshedAt, { fallback: '从未刷新' }) }}</div>
                <div v-if="account.refreshFailureCount > 0" class="small text-danger">
                  连续失败 {{ account.refreshFailureCount }} 次
                </div>
              </td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="button mini" :disabled="busyKeyId != null" @click="openReauth(account)">
                    重新授权
                  </button>
                  <button
                    type="button"
                    class="icon-button"
                    title="刷新 access token"
                    aria-label="刷新 access token"
                    :disabled="busyKeyId != null"
                    @click="refreshToken(account)"
                  >
                    <Icon name="refresh" :size="16" />
                  </button>
                  <button
                    type="button"
                    class="icon-button"
                    title="读取上游额度"
                    aria-label="读取上游额度"
                    :disabled="busyKeyId != null || account.provider !== 'chatgpt'"
                    @click="refreshQuota(account)"
                  >
                    <Icon name="chart" :size="16" />
                  </button>
                  <button
                    type="button"
                    class="icon-button"
                    title="编辑调度策略"
                    aria-label="编辑调度策略"
                    @click="openPolicy(account)"
                  >
                    <Icon name="edit" :size="16" />
                  </button>
                  <button
                    v-if="isOAuthAccountCooling(account, now)"
                    type="button"
                    class="button mini ghost"
                    :disabled="busyKeyId != null"
                    @click="clearCooldown(account)"
                  >
                    解除冷却
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showImport" :title="reauthTarget ? '重新授权订阅账号' : '导入订阅账号'" wide @close="showImport = false">
      <form class="form-grid" @submit.prevent="saveImport">
        <label v-if="!reauthTarget" class="field">
          <span>绑定渠道密钥</span>
          <select v-model="selectedTarget">
            <option value="">请选择渠道密钥</option>
            <option v-for="option in keyOptions" :key="option.value" :value="option.value" :disabled="option.disabled">
              {{ option.label }}{{ option.disabled ? '（已绑定）' : '' }}
            </option>
          </select>
          <small>一个密钥只能绑定一个订阅账号；已绑定的密钥需要直接重新授权。</small>
        </label>
        <div v-else class="field">
          <span>绑定渠道密钥</span>
          <div class="muted">{{ reauthTarget.channelName }} / {{ reauthTarget.keyName }}</div>
          <small>重新授权会替换该密钥的 refresh token，并清空旧的账号冷却与失败计数。</small>
        </div>

        <label class="field">
          <span>提供方</span>
          <select v-model="importForm.provider" :disabled="reauthTarget != null">
            <option
              v-for="provider in providers"
              :key="provider.id"
              :value="provider.id"
              :disabled="!provider.configured"
            >
              {{ provider.label }}{{ provider.configured ? '' : '（未配置）' }}
            </option>
          </select>
          <small v-if="configuredProviders.length === 0" class="text-danger">
            尚未配置任何 OAuth 提供方，请先设置客户端参数。
          </small>
        </label>

        <label class="field">
          <span>Refresh token</span>
          <input
            v-model="importForm.refreshToken"
            type="password"
            autocomplete="off"
            placeholder="粘贴账号的 refresh token"
          />
          <small>只在导入时提交一次，服务端加密保存，之后不再回显。</small>
        </label>

        <label class="field">
          <span>账号邮箱</span>
          <input v-model="importForm.accountEmail" autocomplete="off" placeholder="可选，用于识别账号" />
        </label>
        <label class="field">
          <span>套餐</span>
          <input v-model="importForm.accountPlan" autocomplete="off" placeholder="例如 free" />
        </label>

        <label class="field">
          <span>Access token</span>
          <input v-model="importForm.accessToken" type="password" autocomplete="off" placeholder="可选，已有短期 token 时填写" />
        </label>
        <label class="field">
          <span>Access token 到期时间</span>
          <input v-model="importForm.accessTokenExpiresAt" type="datetime-local" />
          <small>access token 与到期时间必须同时填写；留空则导入后自动刷新。</small>
        </label>

        <label class="field">
          <span>账号代理</span>
          <input v-model="importForm.proxyUrl" autocomplete="off" placeholder="http://127.0.0.1:7890" />
          <small>可选。刷新 token 与调用上游使用同一出口，只支持无认证的 HTTP 正向代理。</small>
        </label>
      </form>
      <template #footer>
        <button type="button" class="button ghost" @click="showImport = false">取消</button>
        <button type="button" class="button primary" :disabled="importSaving" @click="saveImport">
          {{ importSaving ? '保存中…' : reauthTarget ? '重新授权' : '导入账号' }}
        </button>
      </template>
    </Modal>

    <Modal v-if="showPolicy && policyTarget" title="账号调度策略" @close="showPolicy = false">
      <form class="form-grid" @submit.prevent="savePolicy">
        <label class="field">
          <span>窗口请求数</span>
          <input v-model="policyForm.windowLimit" type="number" min="1" step="1" placeholder="留空表示不限制" />
          <small>按账号统计，不区分模型；留空只保留并发与 429 冷却。</small>
        </label>
        <label class="field">
          <span>窗口时长（秒）</span>
          <input v-model="policyForm.windowSeconds" type="number" min="60" max="604800" step="1" />
          <small>默认 10800 秒（3 小时），修改不会重置已有命中。</small>
        </label>
        <label class="field">
          <span>单号最大并发</span>
          <input v-model="policyForm.maxConcurrentRequests" type="number" min="1" max="1000" step="1" />
          <small>同一账号同时进行的上游请求数，超出后跳过该候选。</small>
        </label>
      </form>
      <template #footer>
        <button type="button" class="button ghost" @click="showPolicy = false">取消</button>
        <button type="button" class="button primary" :disabled="policySaving" @click="savePolicy">
          {{ policySaving ? '保存中…' : '保存策略' }}
        </button>
      </template>
    </Modal>
  </section>
</template>

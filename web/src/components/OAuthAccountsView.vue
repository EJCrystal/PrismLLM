<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { Channel, OAuthAccount, OAuthLoginStart, OAuthProviderInfo } from '../types/api'
import ActionMenu from './ActionMenu.vue'
import CopyButton from './CopyButton.vue'
import ErrorState from './ErrorState.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import OAuthModelTestModal from './OAuthModelTestModal.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { fromLocalInput, formatTime } from '../utils/format'
import {
  availableOAuthModels,
  buildOAuthImportPayload,
  buildOAuthModels,
  buildOAuthPolicyPayload,
  filterOAuthModels,
  formatCooldownRemaining,
  formatQuotaReset,
  formatUsedPercent,
  isOAuthAccountCooling,
  isOAuthQuotaBlocked,
  oauthAccountModelOptions,
  oauthAccountModelsView,
  oauthCanSyncCatalog,
  oauthAccountStatus,
  oauthLoginCallbackError,
  oauthPolicySummary,
  oauthProviderLabel,
  oauthQuotaCreditsLabel,
  oauthQuotaErrorHint,
  oauthQuotaErrorLabel,
  oauthQuotaGroups,
  oauthQuotaTone,
  oauthQuotaWindows,
  oauthRuntimeSummary,
  oauthSupportsModelCatalog,
  oauthTestModels,
} from '../utils/oauthAccount'

const accounts = ref<OAuthAccount[]>([])
const providers = ref<OAuthProviderInfo[]>([])
const channels = ref<Channel[]>([])
const loading = ref(true)
const error = ref('')
const busyId = ref<string | null>(null)
const syncingId = ref<string | null>(null)
const now = ref(Date.now())
let clock: number | undefined

const showImport = ref(false)
const importSaving = ref(false)
const importMode = ref<'login' | 'token'>('login')
const loginStarting = ref(false)
const loginCompleting = ref(false)
const loginSession = ref<OAuthLoginStart | null>(null)
const callbackUrl = ref('')
const reauthTarget = ref<OAuthAccount | null>(null)
const importForm = reactive({
  name: '',
  provider: 'chatgpt',
  refreshToken: '',
  accountEmail: '',
  accountPlan: '',
  proxyUrl: '',
  accessToken: '',
  accessTokenExpiresAt: '',
  channelIds: [] as string[],
  priority: 1,
})

const showPolicy = ref(false)
const policySaving = ref(false)
const policyTarget = ref<OAuthAccount | null>(null)
const policyForm = reactive<{ windowLimit: string | number; windowSeconds: string | number; maxConcurrentRequests: string | number }>({
  windowLimit: '',
  windowSeconds: 10_800,
  maxConcurrentRequests: 1,
})

const showTest = ref(false)
const testTarget = ref<OAuthAccount | null>(null)

const showSettings = ref(false)
const settingsSaving = ref(false)
const settingsTarget = ref<OAuthAccount | null>(null)
const settingsModelsRef = ref<HTMLElement | null>(null)
const settingsForm = reactive({
  name: '',
  priority: 1,
  enabled: true,
  proxyUrl: '',
  clearProxy: false,
  allModels: false,
  selectedModels: [] as string[],
  customModels: '',
  modelQuery: '',
  channelIds: [] as string[],
})

const activeCount = computed(() => accounts.value.filter((item) => item.enabled && item.authState === 'ACTIVE').length)
const reauthCount = computed(() => accounts.value.filter((item) => item.authState === 'REAUTH_REQUIRED').length)
const coolingCount = computed(() => accounts.value.filter((item) => isOAuthAccountCooling(item, now.value)).length)
const configuredProviders = computed(() => providers.value.filter((item) => item.configured))
const channelOptions = computed(() => [...channels.value].sort((a, b) => a.name.localeCompare(b.name)))
/** 模型授权可选项：优先用账号自己的目录，未同步时退回所关联渠道的模型。 */
const settingsModelOptions = computed(() =>
  oauthAccountModelOptions(
    settingsTarget.value,
    availableOAuthModels(channels.value, settingsForm.channelIds),
  ),
)

/** 搜索只影响可见列表，已勾选但被过滤掉的模型保持选中。 */
const filteredSettingsModels = computed(() =>
  filterOAuthModels(settingsModelOptions.value, settingsForm.modelQuery),
)

const selectedModelCount = computed(() => new Set(settingsForm.selectedModels).size)

/** 已勾选但不在账号当前目录里的模型：上游会拒绝调用，需要重同步或取消勾选。 */
function settingsModelOutsideCatalog(model: string): boolean {
  const catalog = settingsTarget.value?.modelCatalog
  return catalog != null && !catalog.includes(model)
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

function channelSummary(account: OAuthAccount): string {
  return account.channels.map((channel) => channel.name).join('、') || '未关联渠道'
}

/** 看板直接展示账号可调度的具体模型，不再只给一个数量。 */
const accountModelViews = computed(() =>
  new Map(accounts.value.map((account) => [account.id, oauthAccountModelsView(account, channels.value)])),
)

function accountModelView(account: OAuthAccount) {
  return accountModelViews.value.get(account.id) ?? oauthAccountModelsView(account, channels.value)
}

/** access token 是短期凭据，表格不再展示它的倒计时，只保留授权时间供核对。 */
function authorizedAt(account: OAuthAccount): number {
  return account.authorizedAt ?? account.createdAt
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

function providerManagesChannel(providerId: string): boolean {
  return providers.value.find((item) => item.id === providerId)?.managedChannel === true
}

/**
 * 从上游刷新该账号自己的模型目录。不同开通方式（Pixel/Jio 优惠与正价）拿到的
 * 模型集合不同，所以目录按账号保存，不能互相覆盖。
 */
async function syncAccountModels(account: OAuthAccount): Promise<OAuthAccount | null> {
  if (syncingId.value) return null
  syncingId.value = account.id
  try {
    const result = await api.refreshOAuthAccountModels(account.id)
    notify(`已同步 ${result.models.length} 个模型`)
    await load()
    const refreshed = accounts.value.find((item) => item.id === account.id) ?? null
    // 设置弹窗里的同步要立即刷新同一个模型列表，而不是让用户关掉弹窗再看表格。
    if (refreshed && showSettings.value && settingsTarget.value?.id === account.id) {
      settingsTarget.value = refreshed
    }
    return refreshed
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    return null
  } finally {
    syncingId.value = null
  }
}

/** 模型授权面板内的同步入口：同步目录后直接在同一列表里勾选。 */
async function syncSettingsModels() {
  const target = settingsTarget.value
  if (!target) return
  await syncAccountModels(target)
}

const importChannelsManaged = computed(() => providerManagesChannel(importForm.provider))
const settingsChannelsManaged = computed(() => {
  const target = settingsTarget.value
  return target ? providerManagesChannel(target.provider) : false
})

function providerSupportsLogin(providerId: string): boolean {
  return providers.value.find((item) => item.id === providerId)?.loginSupported === true
}

function resetLoginSession() {
  loginSession.value = null
  callbackUrl.value = ''
  loginStarting.value = false
  loginCompleting.value = false
}

function resetImportForm(provider: string) {
  Object.assign(importForm, {
    name: '',
    provider,
    refreshToken: '',
    accountEmail: '',
    accountPlan: '',
    proxyUrl: '',
    accessToken: '',
    accessTokenExpiresAt: '',
    channelIds: [],
    priority: 1,
  })
}

function openImport() {
  reauthTarget.value = null
  resetLoginSession()
  const provider = configuredProviders.value[0]?.id ?? providers.value[0]?.id ?? 'chatgpt'
  resetImportForm(provider)
  importMode.value = providerSupportsLogin(provider) ? 'login' : 'token'
  showImport.value = true
}

function openReauth(account: OAuthAccount) {
  reauthTarget.value = account
  resetLoginSession()
  Object.assign(importForm, {
    name: account.name,
    provider: account.provider,
    refreshToken: '',
    accountEmail: account.accountEmail ?? '',
    accountPlan: account.accountPlan ?? '',
    proxyUrl: account.proxyUrl ?? '',
    accessToken: '',
    accessTokenExpiresAt: '',
    channelIds: [...account.channelIds],
    priority: account.priority,
  })
  importMode.value = providerSupportsLogin(account.provider) ? 'login' : 'token'
  showImport.value = true
}

function switchImportMode(mode: 'login' | 'token') {
  if (mode === 'login' && !providerSupportsLogin(importForm.provider)) return
  importMode.value = mode
  resetLoginSession()
}

watch(
  () => importForm.provider,
  (provider) => {
    resetLoginSession()
    if (!providerSupportsLogin(provider)) importMode.value = 'token'
  },
)

async function beginLogin() {
  if (loginStarting.value) return
  loginStarting.value = true
  try {
    loginSession.value = await api.startOAuthLogin({
      provider: importForm.provider,
      accountId: reauthTarget.value?.id ?? null,
      name: importForm.name.trim() || null,
      channelIds: importChannelsManaged.value ? [] : [...importForm.channelIds],
      proxyUrl: importForm.proxyUrl.trim() || null,
    })
    callbackUrl.value = ''
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    loginStarting.value = false
  }
}

async function completeLogin() {
  if (loginCompleting.value) return
  if (!loginSession.value) {
    notify('请先生成登录链接并完成浏览器授权', 'error')
    return
  }
  const problem = oauthLoginCallbackError(callbackUrl.value)
  if (problem) {
    notify(problem, 'error')
    return
  }
  const callback = callbackUrl.value.trim()
  loginCompleting.value = true
  try {
    await api.completeOAuthLogin(loginSession.value.loginId, { callbackUrl: callback })
    if (reauthTarget.value) {
      await api.updateOAuthAccountSettings(reauthTarget.value.id, {
        name: importForm.name.trim() || null,
        priority: Number(importForm.priority),
      })
    }
    notify(reauthTarget.value ? '订阅账号已重新授权' : '订阅账号已通过浏览器登录')
    showImport.value = false
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    loginCompleting.value = false
  }
}

async function submitImport() {
  if (importMode.value === 'login') {
    await completeLogin()
    return
  }
  await saveImport()
}

async function saveImport() {
  importSaving.value = true
  try {
    const payload = buildOAuthImportPayload({
      ...importForm,
      accessTokenExpiresAt: fromLocalInput(importForm.accessTokenExpiresAt),
    }, Date.now(), { allowEmptyRefreshToken: reauthTarget.value != null })
    if (reauthTarget.value) {
      await api.updateOAuthAccount(reauthTarget.value.id, payload)
      await api.updateOAuthAccountSettings(reauthTarget.value.id, {
        name: importForm.name.trim() || null,
        priority: Number(importForm.priority),
      })
      if (!importChannelsManaged.value) {
        await api.updateOAuthAccountChannels(reauthTarget.value.id, { channelIds: [...importForm.channelIds] })
      }
    } else {
      await api.importOAuthAccount(payload)
    }
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
  if (busyId.value != null) return
  busyId.value = account.id
  try {
    await api.refreshOAuthAccount(account.id)
    notify('access token 已刷新')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyId.value = null
  }
}

async function refreshQuota(account: OAuthAccount) {
  if (busyId.value != null) return
  busyId.value = account.id
  try {
    await api.refreshOAuthAccountQuota(account.id)
    notify('上游额度已更新')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyId.value = null
  }
}

async function clearCooldown(account: OAuthAccount) {
  if (busyId.value != null) return
  busyId.value = account.id
  try {
    await api.clearOAuthAccountCooldown(account.id)
    notify('账号冷却已解除')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyId.value = null
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
    await api.updateOAuthAccountPolicy(policyTarget.value.id, buildOAuthPolicyPayload(policyForm))
    notify('账号调度策略已更新')
    showPolicy.value = false
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    policySaving.value = false
  }
}

function openTest(account: OAuthAccount) {
  testTarget.value = account
  showTest.value = true
}

function openSettings(account: OAuthAccount, focusModels = false) {
  settingsTarget.value = account
  Object.assign(settingsForm, {
    name: account.name,
    priority: account.priority,
    enabled: account.enabled,
    proxyUrl: account.proxyUrl ?? '',
    clearProxy: false,
    // 如实反映当前配置：空列表在后端表示“全部模型”。列表常驻可见，取消勾选即可改为只调度子集。
    allModels: account.models.length === 0,
    selectedModels: [...account.models],
    customModels: '',
    modelQuery: '',
    channelIds: [...account.channelIds],
  })
  showSettings.value = true
  if (focusModels) {
    // 「选择模型」直接从模型授权开始，免去用户在大表单里再找一遍。
    nextTick(() => settingsModelsRef.value?.scrollIntoView({ block: 'center' }))
  }
}

/** 全选只作用于当前搜索结果，未匹配的模型保持原状。 */
function selectVisibleModels() {
  const selected = new Set(settingsForm.selectedModels)
  for (const model of filteredSettingsModels.value) selected.add(model)
  settingsForm.selectedModels = [...selected]
}

function clearSelectedModels() {
  settingsForm.selectedModels = []
}

async function saveSettings() {
  if (!settingsTarget.value) return
  const models = buildOAuthModels(settingsForm.allModels, settingsForm.selectedModels, settingsForm.customModels)
  if (!settingsForm.allModels && models.length === 0) {
    notify('请至少选择一个模型，或勾选「全部模型」', 'error')
    return
  }
  settingsSaving.value = true
  try {
    const proxyUrl = settingsForm.proxyUrl.trim() || null
    // 勾选「清除代理」时输入框已禁用，以勾选为准：残留的旧地址不应阻止清除。
    const clearProxy = settingsForm.clearProxy || (proxyUrl == null && settingsTarget.value.proxyUrl != null)
    await api.updateOAuthAccountSettings(settingsTarget.value.id, {
      name: settingsForm.name.trim() || null,
      priority: Number(settingsForm.priority),
      enabled: settingsForm.enabled,
      proxyUrl: clearProxy ? null : proxyUrl,
      clearProxy,
    })
    await api.updateOAuthAccountModels(settingsTarget.value.id, { models })
    if (!settingsChannelsManaged.value) {
      await api.updateOAuthAccountChannels(settingsTarget.value.id, { channelIds: [...settingsForm.channelIds] })
    }
    notify('账号设置已更新')
    showSettings.value = false
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    settingsSaving.value = false
  }
}

async function deleteAccount(account: OAuthAccount) {
  if (!window.confirm(`删除订阅账号「${account.name}」？该账号的 token、额度和冷却状态会一并删除。`)) return
  if (busyId.value != null) return
  busyId.value = account.id
  try {
    await api.deleteOAuthAccount(account.id)
    notify('订阅账号已删除')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyId.value = null
  }
}

interface OAuthAccountActionItem {
  id: string
  label: string
  icon?: string
  danger?: boolean
  disabled?: boolean
  title?: string
}

/** 低频与破坏性操作收进更多菜单，行内只保留测试、设置与更多。 */
function accountActionItems(account: OAuthAccount): OAuthAccountActionItem[] {
  const busy = busyId.value === account.id
  return [
    {
      id: 'refresh-token',
      label: '刷新 token',
      icon: 'refresh',
      disabled: busy,
      title: '用 refresh token 换取新的 access token；正常调用时网关会自动续期',
    },
    { id: 'refresh-quota', label: '刷新额度', icon: 'chart', disabled: busy },
    {
      id: 'select-models',
      label: '选择模型',
      icon: 'models',
      title: oauthSupportsModelCatalog(account.provider)
        ? '打开账号设置，可同步上游模型目录并直接勾选该账号可调度的模型'
        : '打开账号设置，勾选该账号可调度的模型',
    },
    { id: 'policy', label: '调度策略', icon: 'bolt' },
    { id: 'reauth', label: '重新授权', icon: 'lock' },
    ...(account.runtime.cooldownUntil && account.runtime.cooldownUntil > now.value
      ? [{ id: 'clear-cooldown', label: '解除冷却', icon: 'check', disabled: busy }]
      : []),
    { id: 'delete', label: '删除账号', icon: 'trash', danger: true, disabled: busy },
  ]
}

function accountAction(id: string, account: OAuthAccount) {
  if (id === 'refresh-token') void refreshToken(account)
  else if (id === 'refresh-quota') void refreshQuota(account)
  else if (id === 'select-models') openSettings(account, true)
  else if (id === 'policy') openPolicy(account)
  else if (id === 'reauth') openReauth(account)
  else if (id === 'clear-cooldown') void clearCooldown(account)
  else if (id === 'delete') void deleteAccount(account)
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
          新建账号
        </button>
      </div>

      <div class="stat-row four">
        <div class="stat-card">
          <span>订阅账号</span>
          <strong>{{ accounts.length }}</strong>
          <small>独立凭据，可关联多个渠道</small>
        </div>
        <div class="stat-card">
          <span>可用</span>
          <strong class="text-success">{{ activeCount }}</strong>
          <small>已启用且凭据可自动续期</small>
        </div>
        <div class="stat-card">
          <span>需重新授权</span>
          <strong :class="reauthCount > 0 ? 'text-danger' : ''">{{ reauthCount }}</strong>
          <small>refresh token 失效</small>
        </div>
        <div class="stat-card">
          <span>冷却中</span>
          <strong>{{ coolingCount }}</strong>
          <small>429 限流后暂停调度</small>
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
        desc="直接新建 ChatGPT 或 Antigravity 账号，再按需关联渠道；账号不再依赖任何渠道密钥。"
        action-text="新建账号"
        @action="openImport"
      />
      <div v-else class="table-scroll">
        <table class="data-table oauth-accounts-table">
          <thead>
            <tr>
              <th>账号</th>
              <th>提供方</th>
              <th>状态</th>
              <th>上游额度</th>
              <th>调度</th>
              <th>最近刷新</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.id">
              <td>
                <div class="primary-cell" :title="account.accountEmail || account.name">
                  {{ account.accountEmail || account.name }}
                </div>
                <div class="channel-id-cell">
                  <span class="muted small">{{ channelSummary(account) }}</span>
                  <span class="badge neutral small">优先级 {{ account.priority }}</span>
                </div>
                <div
                  v-if="account.proxyUrl"
                  class="oauth-account-proxy mono small muted"
                  :title="`该账号的出口代理：${account.proxyUrl}`"
                >{{ account.proxyUrl }}</div>
                <div
                  class="oauth-account-models"
                  :title="accountModelView(account).names.join('\n') || '该账号暂无可调度的模型'"
                >
                  <template v-if="accountModelView(account).names.length">
                    <span
                      v-for="model in accountModelView(account).visible"
                      :key="model"
                      class="badge info small oauth-model-badge"
                      :title="model"
                    >{{ model }}</span>
                    <span
                      v-if="accountModelView(account).hidden.length"
                      class="badge neutral small"
                      :title="accountModelView(account).hidden.join('\n')"
                    >+{{ accountModelView(account).hidden.length }}</span>
                    <span v-if="accountModelView(account).inherited" class="oauth-model-inherited muted small">全部模型</span>
                  </template>
                  <span v-else class="muted small">暂无模型</span>
                </div>
              </td>
              <td>
                <span class="badge neutral">{{ oauthProviderLabel(account.provider) }}</span>
                <div v-if="account.accountPlan" class="small muted">{{ account.accountPlan }}</div>
              </td>
              <td>
                <span class="badge" :class="oauthAccountStatus(account).tone">
                  {{ oauthAccountStatus(account).label }}
                </span>
                <div
                  v-if="account.enabled && account.authState === 'REAUTH_REQUIRED' && account.lastRefreshError"
                  class="small text-danger"
                >
                  {{ account.lastRefreshError }}
                </div>
              </td>
              <td class="oauth-quota-cell">
                <template v-if="account.quota">
                  <div v-if="oauthQuotaGroups(account.quota).length" class="quota-groups">
                    <div v-for="group in oauthQuotaGroups(account.quota)" :key="group.key" class="quota-group">
                      <div class="quota-group-label">{{ group.label }}</div>
                      <div v-for="window in group.windows" :key="window.key" class="quota-window">
                        <div class="quota-row">
                          <span class="quota-label">{{ window.label }}</span>
                          <span class="quota-track">
                            <span
                              class="quota-fill"
                              :class="'is-' + oauthQuotaTone(window.usedPercent)"
                              :style="{ width: window.usedPercent + '%' }"
                            ></span>
                          </span>
                          <span class="quota-value small muted">已用 {{ formatUsedPercent(window.usedPercent) }}%</span>
                        </div>
                        <div v-if="formatQuotaReset(window.resetsAt, now)" class="quota-reset small muted">
                          {{ formatQuotaReset(window.resetsAt, now) }}
                        </div>
                      </div>
                    </div>
                  </div>
                  <template v-else>
                    <div v-for="window in oauthQuotaWindows(account.quota)" :key="window.key" class="quota-window">
                      <div class="quota-row">
                        <span class="quota-label">{{ window.label }}</span>
                        <span class="quota-track">
                          <span
                            class="quota-fill"
                            :class="'is-' + oauthQuotaTone(window.usedPercent)"
                            :style="{ width: window.usedPercent + '%' }"
                          ></span>
                        </span>
                        <span class="quota-value small muted">已用 {{ formatUsedPercent(window.usedPercent) }}%</span>
                      </div>
                      <div v-if="formatQuotaReset(window.resetsAt, now)" class="quota-reset small muted">
                        {{ formatQuotaReset(window.resetsAt, now) }}
                      </div>
                    </div>
                  </template>
                  <div v-if="oauthQuotaCreditsLabel(account.quota) || isOAuthQuotaBlocked(account, now)" class="small muted">
                    <span v-if="oauthQuotaCreditsLabel(account.quota)">{{ oauthQuotaCreditsLabel(account.quota) }}</span>
                    <span v-if="isOAuthQuotaBlocked(account, now)" class="text-danger">额度用尽</span>
                  </div>
                  <div v-if="account.quota.error" class="small text-danger">
                    {{ oauthQuotaErrorLabel(account.quota.error) }}
                  </div>
                  <div v-if="oauthQuotaErrorHint(account.quota.error)" class="small muted">
                    {{ oauthQuotaErrorHint(account.quota.error) }}
                  </div>
                  <span v-if="!oauthQuotaWindows(account.quota).length && !account.quota.error" class="muted small">暂无读数</span>
                </template>
                <span v-else class="muted small">暂无读数</span>
              </td>
              <td>
                <div class="small">{{ oauthPolicySummary(account.policy) }}</div>
                <div class="small muted">{{ oauthRuntimeSummary(account, now) }}</div>
                <div v-if="formatCooldownRemaining(account.runtime.cooldownUntil, now)" class="small text-warning">
                  冷却剩余 {{ formatCooldownRemaining(account.runtime.cooldownUntil, now) }}
                </div>
              </td>
              <td class="oauth-refresh-cell small">
                <div :title="'最近一次成功续期 access token 的时间'">
                  {{ account.lastRefreshedAt ? formatTime(account.lastRefreshedAt) : '尚未刷新' }}
                </div>
                <div class="muted" :title="'账号授权的建立时间；access token 到期会自动续期，不影响授权。'">
                  授权于 {{ formatTime(authorizedAt(account)) }}
                </div>
              </td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="button ghost mini" @click="openTest(account)">测试</button>
                  <button
                    type="button"
                    class="icon-button"
                    :title="'设置 ' + account.name"
                    :aria-label="'设置 ' + account.name"
                    @click="openSettings(account)"
                  >
                    <Icon name="settings" :size="16" />
                  </button>
                  <ActionMenu
                    :label="account.name + '的更多操作'"
                    compact
                    :items="accountActionItems(account)"
                    @select="accountAction($event, account)"
                  />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showImport" :title="reauthTarget ? '重新授权订阅账号' : '新建订阅账号'" wide @close="showImport = false">
      <form class="form-grid" @submit.prevent="submitImport">
        <label class="field">
          <span>账号名称</span>
          <input v-model="importForm.name" autocomplete="off" placeholder="可选，默认使用邮箱" />
        </label>

        <label class="field">
          <span>提供方</span>
          <select v-model="importForm.provider" :disabled="reauthTarget != null">
            <option v-for="provider in providers" :key="provider.id" :value="provider.id" :disabled="!provider.configured">
              {{ provider.label }}{{ provider.configured ? '' : '（未配置）' }}
            </option>
          </select>
          <small v-if="configuredProviders.length === 0" class="text-danger">
            尚未配置任何 OAuth 提供方，请先设置客户端参数。
          </small>
        </label>

        <div v-if="importChannelsManaged" class="field">
          <span>上游端点</span>
          <p class="muted small">订阅账号只服务官方端点，系统会自动创建并维护对应渠道，无需手工关联。</p>
        </div>
        <div v-else class="field">
          <span>关联渠道</span>
          <div class="oauth-channel-picker">
            <label v-for="channel in channelOptions" :key="channel.id" class="checkbox-row">
              <input v-model="importForm.channelIds" type="checkbox" :value="channel.id" />
              <span>{{ channel.name }}</span>
            </label>
            <span v-if="channelOptions.length === 0" class="muted small">还没有渠道；可以先创建账号，稍后再关联。</span>
          </div>
          <small>同一账号可以关联多个渠道；不关联时账号保留，但不会参与任何路由。</small>
        </div>

        <label class="field">
          <span>优先级</span>
          <input v-model.number="importForm.priority" type="number" min="1" max="1000" />
          <small>数值越小越优先；只影响账号之间的排序，不改变渠道优先级。</small>
        </label>

        <div v-if="providerSupportsLogin(importForm.provider)" class="field">
          <span>授权方式</span>
          <div class="health-window-tabs" role="tablist" aria-label="授权方式">
            <button type="button" role="tab" :aria-selected="importMode === 'login'" :class="{ active: importMode === 'login' }" @click="switchImportMode('login')">
              浏览器登录
            </button>
            <button type="button" role="tab" :aria-selected="importMode === 'token'" :class="{ active: importMode === 'token' }" @click="switchImportMode('token')">
              导入 refresh token
            </button>
          </div>
          <small>浏览器登录会走提供方的官方授权页，网关换取 token 后加密保存，无需手动复制凭据。</small>
        </div>

        <label class="field">
          <span>账号代理</span>
          <input v-model="importForm.proxyUrl" autocomplete="off" placeholder="http://127.0.0.1:7890" />
          <small>可选。登录换 token、刷新 token 与调用上游使用同一出口，只支持无认证的 HTTP 正向代理。</small>
        </label>

        <template v-if="importMode === 'login'">
          <div class="field">
            <span>登录链接</span>
            <div v-if="loginSession" class="callout info oauth-login-link">
              <a :href="loginSession.authorizeUrl" target="_blank" rel="noopener noreferrer">{{ loginSession.authorizeUrl }}</a>
              <CopyButton :text="loginSession.authorizeUrl" title="复制登录链接" />
            </div>
            <div v-else class="oauth-login-actions">
              <button type="button" class="button" :disabled="loginStarting" @click="beginLogin">
                {{ loginStarting ? '生成中…' : '生成登录链接' }}
              </button>
              <small>生成后在新标签页完成登录；链接 10 分钟内有效，且只能使用一次。</small>
            </div>
          </div>

          <label v-if="loginSession" class="field">
            <span>回调地址</span>
            <textarea v-model="callbackUrl" rows="3" spellcheck="false" placeholder="登录完成后把浏览器地址栏里的完整 URL 粘贴到这里"></textarea>
            <small>回调地址形如 {{ loginSession.redirectUri }}?code=…&amp;state=…</small>
          </label>
        </template>

        <template v-else>
          <label class="field">
            <span>Refresh token</span>
            <input v-model="importForm.refreshToken" type="password" autocomplete="off" placeholder="粘贴账号的 refresh token" />
            <small>{{ reauthTarget ? '留空表示保留现有 refresh token；重新授权时请填写新的 token。' : '只在导入时提交一次，服务端加密保存，之后不再回显。' }}</small>
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
          </label>
        </template>

        <div class="form-actions">
          <button type="button" class="button" @click="showImport = false">取消</button>
          <button type="submit" class="button primary" :disabled="importSaving || loginCompleting">
            {{ importSaving || loginCompleting ? '提交中…' : reauthTarget ? '重新授权' : '创建账号' }}
          </button>
        </div>
      </form>
    </Modal>

    <Modal v-if="showPolicy && policyTarget" title="账号调度策略" @close="showPolicy = false">
      <form class="form-grid" @submit.prevent="savePolicy">
        <p class="muted small policy-hint">
          窗口请求数与单号最大并发是两种独立限制：前者限制一段时间内的总请求量，后者限制同一时刻正在进行的请求数。
        </p>
        <label class="field">
          <span>窗口请求数</span>
          <input v-model="policyForm.windowLimit" type="number" min="1" max="100000" placeholder="留空表示不限" />
          <small>在「窗口时长」内最多放行多少次请求；留空表示不限总量，仍受并发与 429 冷却约束。</small>
        </label>
        <label class="field">
          <span>窗口时长（秒）</span>
          <input v-model="policyForm.windowSeconds" type="number" min="60" max="604800" />
          <small>滑动窗口长度，例如 10800 秒 = 3 小时；窗口请求数按这个时长滚动统计。</small>
        </label>
        <label class="field">
          <span>单号最大并发</span>
          <input v-model="policyForm.maxConcurrentRequests" type="number" min="1" max="1000" />
          <small>同一时刻最多有多少个请求正在使用该账号；达到上限后该账号会被跳过并改试其他账号，未占用窗口额度。</small>
        </label>
        <div class="form-actions">
          <button type="button" class="button" @click="showPolicy = false">取消</button>
          <button type="submit" class="button primary" :disabled="policySaving">{{ policySaving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </Modal>

    <OAuthModelTestModal
      v-if="showTest && testTarget"
      :account="testTarget"
      :models="oauthTestModels(testTarget, channels)"
      @close="showTest = false"
    />

    <Modal v-if="showSettings && settingsTarget" title="账号设置" @close="showSettings = false">
      <form class="form-grid" @submit.prevent="saveSettings">
        <label class="field">
          <span>账号名称</span>
          <input v-model="settingsForm.name" autocomplete="off" />
        </label>
        <label class="field">
          <span>优先级</span>
          <input v-model.number="settingsForm.priority" type="number" min="1" max="1000" />
        </label>
        <label class="checkbox-row">
          <input v-model="settingsForm.enabled" type="checkbox" />
          <span>启用账号</span>
        </label>
        <div class="field">
          <span>账号代理</span>
          <div class="oauth-proxy-row">
            <input
              v-model="settingsForm.proxyUrl"
              autocomplete="off"
              placeholder="http://127.0.0.1:7890"
              :disabled="settingsForm.clearProxy"
            />
            <label class="checkbox-row oauth-proxy-clear" title="保存后移除该账号的代理，改为直连">
              <input v-model="settingsForm.clearProxy" type="checkbox" />
              <span>清除代理</span>
            </label>
          </div>
          <small>可选，留空或勾选「清除代理」都表示直连；代理只支持无认证的 HTTP 正向代理。</small>
        </div>
        <div ref="settingsModelsRef" class="field">
          <span>模型授权</span>
          <div class="oauth-model-picker">
            <div class="oauth-model-picker-toolbar">
              <label class="oauth-model-search" :class="{ 'is-disabled': settingsForm.allModels }">
                <Icon name="search" :size="15" />
                <input
                  v-model="settingsForm.modelQuery"
                  autocomplete="off"
                  :disabled="settingsForm.allModels"
                  placeholder="搜索模型"
                />
              </label>
              <span class="muted small">
                {{
                  settingsForm.allModels
                    ? `全部 ${settingsModelOptions.length} 个模型`
                    : `已选 ${selectedModelCount} / ${settingsModelOptions.length}`
                }}
              </span>
            </div>
            <div class="oauth-model-list" :class="{ 'is-disabled': settingsForm.allModels }">
              <label
                v-for="model in filteredSettingsModels"
                :key="model"
                class="oauth-model-option"
                :class="{ 'is-disabled': settingsForm.allModels }"
              >
                <input
                  v-model="settingsForm.selectedModels"
                  type="checkbox"
                  :value="model"
                  :disabled="settingsForm.allModels"
                />
                <span class="mono">{{ model }}</span>
                <span
                  v-if="settingsModelOutsideCatalog(model)"
                  class="badge warning small oauth-model-outside"
                  title="该模型不在账号当前套餐的目录里，调用会被上游拒绝；重新同步目录后可取消勾选"
                >目录外</span>
              </label>
              <span v-if="settingsModelOptions.length === 0" class="oauth-model-empty muted small">
                {{
                  settingsTarget && oauthSupportsModelCatalog(settingsTarget.provider)
                    ? '该账号还没有可选模型。点「同步模型」读取该账号自己的目录。'
                    : '该账号暂无可选模型，请先为关联渠道配置模型，或在下方补充列表外的模型。'
                }}
              </span>
              <span v-else-if="filteredSettingsModels.length === 0" class="oauth-model-empty muted small">
                没有匹配「{{ settingsForm.modelQuery }}」的模型。
              </span>
            </div>
            <p v-if="settingsForm.allModels" class="oauth-model-all-hint muted small">
              {{ settingsTarget?.modelCatalog ? '当前使用该账号目录里的全部模型；' : '当前继承所关联渠道的全部模型；' }}取消勾选「全部模型」后可只调度勾选的模型。
            </p>
            <div class="oauth-model-picker-actions">
              <button
                v-if="!settingsForm.allModels"
                type="button"
                class="link-button"
                @click="selectVisibleModels"
              >
                {{ settingsForm.modelQuery.trim() ? '全选搜索结果' : '全选' }}
              </button>
              <button v-if="!settingsForm.allModels" type="button" class="link-button" @click="clearSelectedModels">
                清空
              </button>
              <button
                v-if="settingsTarget && oauthSupportsModelCatalog(settingsTarget.provider)"
                type="button"
                class="link-button"
                :disabled="syncingId === settingsTarget.id || !oauthCanSyncCatalog(settingsTarget) || settingsSaving"
                :title="
                  oauthCanSyncCatalog(settingsTarget)
                    ? '从上游重新读取该账号自己的模型目录，并立即刷新下方可选模型'
                    : '账号需要重新授权后才能读取上游模型目录'
                "
                @click="syncSettingsModels"
              >
                {{ syncingId === settingsTarget.id ? '同步中…' : '同步模型' }}
              </button>
              <span class="oauth-model-picker-spacer"></span>
              <label class="checkbox-row small">
                <input v-model="settingsForm.allModels" type="checkbox" />
                <span>全部模型</span>
              </label>
            </div>
            <input
              v-model="settingsForm.customModels"
              autocomplete="off"
              :disabled="settingsForm.allModels"
              placeholder="补充列表外的模型，多个用逗号分隔"
            />
          </div>
          <small v-if="settingsTarget?.modelCatalog">
            来自该账号自己的模型目录 · {{ settingsTarget.modelCatalogSyncedAt ? formatTime(settingsTarget.modelCatalogSyncedAt) : '同步时间未知' }}；不同账号的可用模型可能不同。
          </small>
          <small v-else-if="settingsTarget && oauthSupportsModelCatalog(settingsTarget.provider)">
            还没有同步过目录，列表暂时来自所关联渠道；点「同步模型」读取该账号自己的目录。
          </small>
          <small v-else>
            该提供方暂不支持同步模型目录，列表来自所关联渠道；可直接勾选要授权的模型，或在下方补充列表外的模型。
          </small>
        </div>
        <div v-if="settingsChannelsManaged" class="field">
          <span>上游端点</span>
          <p class="muted small">该账号的端点渠道由系统维护，不能手工调整。</p>
        </div>
        <div v-else class="field">
          <span>关联渠道</span>
          <div class="oauth-channel-picker">
            <label v-for="channel in channelOptions" :key="channel.id" class="checkbox-row">
              <input v-model="settingsForm.channelIds" type="checkbox" :value="channel.id" />
              <span>{{ channel.name }}</span>
            </label>
          </div>
        </div>
        <div class="form-actions">
          <button type="button" class="button" @click="showSettings = false">取消</button>
          <button type="submit" class="button primary" :disabled="settingsSaving">{{ settingsSaving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </Modal>
  </section>
</template>

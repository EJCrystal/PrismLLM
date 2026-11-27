<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { ApiError } from '../types/api'
import type { AntigravityAgyAccount, Channel, OAuthAccount, OAuthAccountSettingsRequest, OAuthLoginStart, OAuthProviderInfo, OAuthProviderModelMappingsResponse, OAuthModelMappingMode, ModelSuggestionsResponse } from '../types/api'
import { createLatestRequestGuard } from '../utils/latestRequest'
import OAuthMappingInput from './OAuthMappingInput.vue'
import ActionMenu from './ActionMenu.vue'
import CopyButton from './CopyButton.vue'
import ErrorState from './ErrorState.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import OAuthModelTestModal from './OAuthModelTestModal.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import AntigravityAgyAccounts from './AntigravityAgyAccounts.vue'
import AntigravityAgyLogin from './AntigravityAgyLogin.vue'
import { formatTime } from '../utils/format'
import type { OAuthAccountStatusFilter, OAuthMappingDraft } from '../utils/oauthAccount'
import {
  availableOAuthModels,
  buildOAuthModels,
  buildOAuthMappingChanges,
  oauthMappingDraft,
  oauthMappingDraftEquals,
  buildOAuthPolicyPayload,
  filterOAuthModels,
  formatCooldownRemaining,
  formatQuotaReset,
  formatUsedPercent,
  formatWindowSeconds,
  isOAuthAccountCooling,
  isOAuthQuotaBlocked,
  oauthAccountModelOptions,
  oauthAccountModelsView,
  oauthAccountMatchesFilter,
  oauthAccountMatchesStatus,
  oauthCanSyncCatalog,
  oauthAuthStateBadge,
  oauthAuthStateLabel,
  oauthLoginCallbackError,
  oauthPolicySummary,
  oauthProviderLabel,
  oauthQuotaCreditsLabel,
  oauthQuotaErrorHint,
  oauthQuotaErrorLabel,
  oauthQuotaPreview,
  oauthQuotaTone,
  oauthSupportsModelCatalog,
  oauthTestModels,
} from '../utils/oauthAccount'

const props = withDefaults(defineProps<{
  /** 嵌入提供商抽屉时限定为单个订阅提供方；未传时展示全部提供方。 */
  fixedProvider?: string | null
  /** 初始状态筛选（如从顶部异常提醒跳转进入）。 */
  initialStatusFilter?: OAuthAccountStatusFilter
  /** 嵌入抽屉内使用时去掉外层页级间距并精简重复信息。 */
  embedded?: boolean
  /** 打开后立即弹出「新建账号」登录弹窗。 */
  autoOpenCreate?: boolean
  createOnly?: boolean
}>(), {
  fixedProvider: null,
  initialStatusFilter: 'all',
  embedded: false,
  autoOpenCreate: false,
  createOnly: false,
})

const emit = defineEmits<{
  (event: 'changed'): void
  (event: 'close'): void
  (event: 'authorization-submitted', request: Promise<OAuthAccount>): void
  /** autoOpenCreate 触发过一次新建弹窗后通知父级清除标志，避免重挂载时重复弹出。 */
  (event: 'auto-create-consumed'): void
}>()

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
const loginStarting = ref(false)
const loginCompleting = ref(false)
const loginSession = ref<OAuthLoginStart | null>(null)
const callbackUrl = ref('')
const agyAccountsRevision = ref(0)
const agyReauthTarget = ref<AntigravityAgyAccount | null>(null)
/** 会话按发起时的提供方生成；切换提供方或重开弹窗后旧请求不得写回界面。 */
let loginGeneration = 0
const reauthTarget = ref<OAuthAccount | null>(null)
const importForm = reactive({
  name: '',
  provider: 'chatgpt',
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
const settingsWriteInFlight = ref(false)
const settingsTarget = ref<OAuthAccount | null>(null)
const settingsBaseline = ref<OAuthAccount | null>(null)
const mappingBaseline = ref<OAuthProviderModelMappingsResponse | null>(null)
const mappingDrafts = reactive<Record<string, OAuthMappingDraft>>({})
const dirtyMappings = reactive(new Set<string>())
const mappingLoading = ref(false)
const mappingError = ref('')
const mappingCandidateError = ref('')
const mappingSuggestions = ref<ModelSuggestionsResponse | null>(null)
const settingsNotice = ref('')
const conflictingMappings = reactive(new Set<string>())
const mappingConflict = computed(() => conflictingMappings.size > 0)
const conflictConfirmed = ref(false)
const settingsSyncing = ref(false)
let settingsGeneration = 0
const mappingRequests = createLatestRequestGuard()
const listRequests = createLatestRequestGuard()
const settingsForm = reactive({
  name: '',
  proxyUrl: '',
  clearProxy: false,
  allModels: false,
  selectedModels: [] as string[],
  customModels: '',
  modelQuery: '',
  channelIds: [] as string[],
})

const scopedAccounts = computed(() =>
  props.fixedProvider ? accounts.value.filter((item) => item.provider === props.fixedProvider) : accounts.value,
)
const activeCount = computed(() => scopedAccounts.value.filter((item) => oauthAccountMatchesStatus(item, 'ready', now.value)).length)
const reauthCount = computed(() => scopedAccounts.value.filter((item) => oauthAccountMatchesStatus(item, 'reauth', now.value)).length)
const coolingCount = computed(() => scopedAccounts.value.filter((item) => oauthAccountMatchesStatus(item, 'cooling', now.value)).length)
const listQuery = ref('')
const providerFilter = ref(props.fixedProvider ?? '')
const statusFilter = ref<OAuthAccountStatusFilter>(props.initialStatusFilter)
const expandedModels = ref(new Set<string>())
const expandedQuotas = ref(new Set<string>())
const hasListFilters = computed(() =>
  !!listQuery.value.trim()
  || (!props.fixedProvider && !!providerFilter.value)
  || statusFilter.value !== 'all',
)
const listProviderOptions = computed(() => [...new Set([...providers.value.map((item) => item.id), ...accounts.value.map((item) => item.provider)])]
  .sort((a, b) => oauthProviderLabel(a).localeCompare(oauthProviderLabel(b))))
const configuredProviders = computed(() => providers.value.filter((item) => item.configured))
const officialAgyLogin = computed(() => importForm.provider === 'antigravity')
const channelOptions = computed(() => [...channels.value].sort((a, b) => a.name.localeCompare(b.name)))
/** 模型授权可选项：优先用账号自己的目录，未同步时退回所关联渠道的模型。 */
const settingsModelOptions = computed(() => [...new Set([
  ...oauthAccountModelOptions(settingsTarget.value, availableOAuthModels(channels.value, settingsForm.channelIds)),
  ...settingsForm.selectedModels,
  ...buildOAuthModels(false, [], settingsForm.customModels),
  ...dirtyMappings,
])].sort())

const baselineMappingByModel = computed(() => new Map(mappingBaseline.value?.mappings.map(item => [item.upstreamModel, item]) ?? []))
const settingsOperationBusy = computed(() => settingsWriteInFlight.value || settingsSaving.value || settingsSyncing.value || mappingLoading.value)
const invalidMappingDrafts = computed(() => mappingBaseline.value ? [...dirtyMappings].filter(model => !baselineMappingByModel.value.has(model)) : [])
const savableMappingDrafts = computed(() => [...dirtyMappings].filter(model => !mappingBaseline.value || baselineMappingByModel.value.has(model)))

function discardMappingDraft(model: string) {
  dirtyMappings.delete(model)
  conflictingMappings.delete(model)
  mappingDrafts[model] = oauthMappingDraft(baselineMappingByModel.value.get(model))
  conflictConfirmed.value = false
}

/** Automatic defaults alone may change behind explicit overrides; compare actual shared policy/value. */
function sharedMappingChanged(previous: OAuthProviderModelMappingsResponse['mappings'][number], next: OAuthProviderModelMappingsResponse['mappings'][number]) {
  return previous.mode !== next.mode || previous.canonicalModelId !== next.canonicalModelId
    || previous.publicModelId !== next.publicModelId || previous.mappingSource !== next.mappingSource
    || previous.catalogActive !== next.catalogActive
}

function dirtyMappingConflicts(model: string, previous: OAuthProviderModelMappingsResponse['mappings'][number], next: OAuthProviderModelMappingsResponse['mappings'][number]) {
  return sharedMappingChanged(previous, next)
    || (mappingDraftFor(model).mode === 'auto' && previous.automaticCanonicalModelId !== next.automaticCanonicalModelId)
}

/** 候选仅使用已经可获得的对外 ID 和当前批量建议，不强制加载价格目录。 */
const mappingCandidates = computed(() => {
  const candidates = new Map<string, Set<string>>()
  const add = (value: string | null | undefined, hint: string) => {
    if (!value?.trim()) return
    const id = value.trim()
    if (!candidates.has(id)) candidates.set(id, new Set())
    candidates.get(id)!.add(hint)
  }
  for (const mapping of mappingBaseline.value?.mappings ?? []) {
    if (mapping.catalogActive) add(mapping.publicModelId, '当前提供方有效对外 ID')
  }
  for (const channel of channels.value) {
    const mapped = new Map(channel.modelMappings.map(item => [item.upstreamModel, item]))
    for (const model of channel.models) add(mapped.get(model)?.canonicalModelId ?? model, `已有对外 ID · ${channel.name}`)
  }
  for (const suggestion of mappingSuggestions.value?.data ?? []) {
    for (const candidate of suggestion.candidates) add(candidate.canonicalId, `目录建议 · ${candidate.provider} · ${candidate.developer}`)
  }
  for (const [model, draft] of Object.entries(mappingDrafts)) add(draft.canonicalModelId, `当前值 / 草稿 · ${model}`)
  return [...candidates].sort(([a], [b]) => a.localeCompare(b)).map(([value, hints]) => ({
    value, label: value, hint: [...hints].join('；'), searchText: `${value} ${[...hints].join(' ')}`,
  }))
})

function mappingDraftFor(model: string): OAuthMappingDraft {
  return mappingDrafts[model] ?? oauthMappingDraft(baselineMappingByModel.value.get(model))
}

function setMappingDraft(model: string, draft: OAuthMappingDraft) {
  mappingDrafts[model] = draft
  const baseline = oauthMappingDraft(baselineMappingByModel.value.get(model))
  if (oauthMappingDraftEquals(draft, baseline)) {
    dirtyMappings.delete(model)
    conflictingMappings.delete(model)
  } else dirtyMappings.add(model)
  conflictConfirmed.value = false
}

function changeMappingMode(model: string, mode: OAuthModelMappingMode) {
  const baseline = baselineMappingByModel.value.get(model)
  const draft = mappingDraftFor(model)
  setMappingDraft(model, { mode, canonicalModelId: draft.canonicalModelId || baseline?.automaticCanonicalModelId || model })
}

function changeManualMapping(model: string, canonicalModelId: string) {
  setMappingDraft(model, { mode: 'manual', canonicalModelId })
}

function mappingPublicId(model: string): string {
  const baseline = baselineMappingByModel.value.get(model)
  const draft = mappingDraftFor(model)
  if (!dirtyMappings.has(model) && baseline) return baseline.publicModelId
  if (draft.mode === 'manual') return draft.canonicalModelId.trim() || '未填写'
  if (draft.mode === 'upstream') return model
  return baseline?.automaticCanonicalModelId ?? model
}

function mappingSource(model: string): string {
  if (dirtyMappings.has(model)) return '待保存；当前来源：' + (baselineMappingByModel.value.get(model)?.mappingSource ?? '无')
  return baselineMappingByModel.value.get(model)?.mappingSource ?? '无'
}

/** 搜索只影响可见列表，已勾选但被过滤掉的模型保持选中。 */
const filteredSettingsModels = computed(() =>
  settingsModelOptions.value.filter(model => filterOAuthModels([model, mappingPublicId(model)], settingsForm.modelQuery).length > 0),
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

const filteredAccounts = computed(() => scopedAccounts.value.filter((account) => oauthAccountMatchesFilter(
  account, accountModelView(account).names,
  { query: listQuery.value, provider: props.fixedProvider || providerFilter.value, status: statusFilter.value }, now.value,
)))
const accountQuotaViews = computed(() => new Map(accounts.value.map((account) => [account.id, oauthQuotaPreview(account, now.value)])))

function accountQuotaView(account: OAuthAccount) {
  return accountQuotaViews.value.get(account.id) ?? oauthQuotaPreview(account, now.value)
}

function visibleQuotaGroups(account: OAuthAccount) {
  const view = accountQuotaView(account)
  return expandedQuotas.value.has(account.id) ? [...view.visible, ...view.hidden] : view.visible
}

function toggleStatusFilter(value: OAuthAccountStatusFilter) {
  statusFilter.value = statusFilter.value === value ? 'all' : value
}

function resetListFilters() {
  listQuery.value = ''
  providerFilter.value = props.fixedProvider ?? ''
  statusFilter.value = 'all'
}

function toggleDetails(set: Set<string>, id: string) {
  if (set.has(id)) set.delete(id)
  else set.add(id)
}

/** access token 是短期凭据，表格不再展示它的倒计时，只保留授权时间供核对。 */
function authorizedAt(account: OAuthAccount): number {
  return account.authorizedAt ?? account.createdAt
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await listRequests.run(() => Promise.all([
      api.listOAuthAccounts(), api.listOAuthProviders(), api.listChannels(),
    ]))
    if (!result.isLatest) return false
    const [nextAccounts, nextProviders, nextChannels] = result.value
    accounts.value = nextAccounts
    providers.value = nextProviders
    channels.value = nextChannels
    return true
  } catch (cause) {
    if ((cause as { isLatest?: boolean }).isLatest !== false) error.value = errorMessage(cause)
    return false
  } finally {
    if (!listRequests.hasActive()) loading.value = false
  }
}

function settingsContext() {
  const target = settingsTarget.value
  return target ? { id: target.id, provider: target.provider, generation: settingsGeneration } : null
}

function settingsContextCurrent(context: NonNullable<ReturnType<typeof settingsContext>>) {
  return showSettings.value && context.generation === settingsGeneration
    && settingsTarget.value?.id === context.id && settingsTarget.value?.provider === context.provider
}

/** 刷新 baseline，不改授权/名称/代理/搜索，保留全部 dirty 映射（包括目录外草稿）。 */
async function loadSettingsMappings(context = settingsContext()): Promise<boolean> {
  if (!context || !settingsContextCurrent(context)) return false
  mappingLoading.value = true
  mappingError.value = ''
  try {
    const models = [...settingsModelOptions.value]
    const result = await mappingRequests.run(async () => {
      const [mapping, suggestions] = await Promise.allSettled([
        api.getOAuthProviderModelMappings(context.provider), api.getModelSuggestions(models),
      ])
      return { mapping, suggestions }
    })
    if (!result.isLatest || !settingsContextCurrent(context)) return false
    const { mapping, suggestions } = result.value
    if (suggestions.status === 'fulfilled') {
      mappingSuggestions.value = suggestions.value
      mappingCandidateError.value = suggestions.value.error || (!suggestions.value.loaded ? '目录候选暂不可用，仍可手工输入对外 ID。' : '')
    } else {
      mappingSuggestions.value = null
      mappingCandidateError.value = '目录候选读取失败，仍可手工输入并保存对外 ID。'
    }
    if (mapping.status === 'rejected') throw mapping.reason
    if (mapping.value.provider !== context.provider || mapping.value.scope !== 'provider' || !Number.isInteger(mapping.value.revision)) {
      throw new Error('共享映射响应与当前提供方不匹配')
    }
    const previousRows = baselineMappingByModel.value
    const nextRows = new Map(mapping.value.mappings.map(item => [item.upstreamModel, item]))
    for (const model of dirtyMappings) {
      const previous = previousRows.get(model)
      const next = nextRows.get(model)
      if (!next) {
        conflictingMappings.delete(model)
        continue // Missing shared rows are invalid drafts, not an administrator-approved creation.
      }
      // 无可信旧共享行的 dirty 草稿也必须核对，不能给旧草稿直接挂上首次读取的新版本。
      if (!previous || dirtyMappingConflicts(model, previous, next)) {
        conflictingMappings.add(model)
        conflictConfirmed.value = false
      }
    }
    mappingBaseline.value = mapping.value
    const rows = new Set([...settingsModelOptions.value, ...mapping.value.mappings.map(item => item.upstreamModel)])
    for (const model of rows) {
      if (!dirtyMappings.has(model)) mappingDrafts[model] = oauthMappingDraft(nextRows.get(model))
    }
    return true
  } catch (cause) {
    if (settingsContextCurrent(context) && (cause as { isLatest?: boolean }).isLatest !== false) mappingError.value = errorMessage(cause)
    return false
  } finally {
    if (settingsContextCurrent(context) && !mappingRequests.hasActive()) mappingLoading.value = false
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
  if (syncingId.value || settingsWriteInFlight.value || settingsSaving.value || mappingLoading.value) return null
  const context = showSettings.value && settingsTarget.value?.id === account.id ? settingsContext() : null
  syncingId.value = account.id
  try {
    const result = await api.refreshOAuthAccountModels(account.id)
    await load()
    const refreshed = accounts.value.find((item) => item.id === account.id) ?? null
    if (context && !settingsContextCurrent(context)) return refreshed
    notify(`已同步 ${result.models.length} 个模型`)
    emit('changed')
    if (refreshed && context) {
      settingsTarget.value = refreshed
      await loadSettingsMappings(context)
    }
    return refreshed
  } catch (cause) {
    if (!context || settingsContextCurrent(context)) notify(errorMessage(cause), 'error')
    return null
  } finally {
    syncingId.value = null
  }
}

/** 同步和保存互斥；同步只更新目录/映射 baseline，不重置任何草稿字段。 */
async function syncSettingsModels() {
  const target = settingsTarget.value
  const context = settingsContext()
  if (!target || !context || settingsOperationBusy.value || syncingId.value) return
  settingsSyncing.value = true
  try { await syncAccountModels(target) }
  finally { if (settingsContextCurrent(context)) settingsSyncing.value = false }
}

const importChannelsManaged = computed(() => providerManagesChannel(importForm.provider))
const settingsChannelsManaged = computed(() => {
  const target = settingsTarget.value
  return target ? providerManagesChannel(target.provider) : false
})

function resetLoginSession() {
  loginGeneration++
  loginSession.value = null
  callbackUrl.value = ''
  loginStarting.value = false
  loginCompleting.value = false
}

function resetImportForm(provider: string) {
  Object.assign(importForm, {
    name: '',
    provider,
    channelIds: [],
    priority: 1,
  })
}

function openImport(providerOverride?: string) {
  agyReauthTarget.value = null
  reauthTarget.value = null
  resetLoginSession()
  const provider = providerOverride || props.fixedProvider || configuredProviders.value[0]?.id || providers.value[0]?.id || 'chatgpt'
  resetImportForm(provider)
  showImport.value = true
}

function closeImport() {
  showImport.value = false
  resetLoginSession()
  if (props.createOnly) emit('close')
}

function openReauth(account: OAuthAccount) {
  agyReauthTarget.value = null
  reauthTarget.value = account
  resetLoginSession()
  Object.assign(importForm, {
    name: account.name,
    provider: account.provider,
    channelIds: [...account.channelIds],
    priority: account.priority,
  })
  showImport.value = true
}

async function beginLogin() {
  if (officialAgyLogin.value) return
  if (loginStarting.value) return
  const generation = loginGeneration
  loginStarting.value = true
  try {
    const session = await api.startOAuthLogin({
      provider: importForm.provider,
      accountId: reauthTarget.value?.id ?? null,
      name: importForm.name.trim() || null,
      channelIds: importChannelsManaged.value ? [] : [...importForm.channelIds],
    })
    // 期间切换了提供方或重开了弹窗：丢弃旧会话，避免按旧 loginId 提交回调。
    if (generation !== loginGeneration) return
    loginSession.value = session
    callbackUrl.value = ''
  } catch (cause) {
    if (generation === loginGeneration) notify(errorMessage(cause), 'error')
  } finally {
    if (generation === loginGeneration) loginStarting.value = false
  }
}

watch(
  () => importForm.provider,
  () => resetLoginSession(),
)

async function completeLogin() {
  if (officialAgyLogin.value) return
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
  const loginId = loginSession.value.loginId
  const generation = loginGeneration
  const target = reauthTarget.value
  const settings = {
    name: importForm.name.trim() || null,
    priority: Number(importForm.priority),
  }
  loginCompleting.value = true
  try {
    const request = api.completeOAuthLogin(loginId, { callbackUrl: callback })
    emit('authorization-submitted', request)
    await request
    if (generation === loginGeneration && target) {
      await api.updateOAuthAccountSettings(target.id, settings)
    }
    if (generation !== loginGeneration) {
      if (!disposed) {
        await load()
        if (!disposed) emit('changed')
      }
      return
    }
    notify(target ? '订阅账号已重新授权' : '订阅账号已通过浏览器登录')
    showImport.value = false
    resetLoginSession()
    if (!props.createOnly) await load()
    if (disposed) return
    emit('changed')
    if (props.createOnly) emit('close')
  } catch (cause) {
    if (generation === loginGeneration) notify(errorMessage(cause), 'error')
  } finally {
    if (generation === loginGeneration) loginCompleting.value = false
  }
}

function openAgyReauth(account: AntigravityAgyAccount) {
  openImport('antigravity')
  agyReauthTarget.value = account
  importForm.name = account.name
}

function onAgyAuthenticated() {
  agyAccountsRevision.value++
  notify(reauthTarget.value
    ? '官方 agy 登录成功；旧 OAuth token 已清除，原账号路由身份和顺序已保留'
    : '官方 agy 登录成功并登记；新账号需在模型凭据路由中手动加入')
  closeImport()
  emit('changed')
}

async function refreshToken(account: OAuthAccount) {
  if (busyId.value != null) return
  busyId.value = account.id
  try {
    await api.refreshOAuthAccount(account.id)
    notify('access token 已刷新')
    await load()
    emit('changed')
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    busyId.value = null
  }
}

async function toggleAccountEnabled(account: OAuthAccount) {
  if (busyId.value != null) return
  busyId.value = account.id
  const enabled = !account.enabled
  try {
    await api.updateOAuthAccountSettings(account.id, { enabled })
    notify(`订阅账号「${account.name}」已${enabled ? '启用' : '停用'}`)
    await load()
    emit('changed')
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
    emit('changed')
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
    emit('changed')
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

function openSettings(account: OAuthAccount) {
  settingsGeneration++
  mappingRequests.invalidate()
  settingsTarget.value = account
  settingsBaseline.value = account
  mappingBaseline.value = null
  mappingSuggestions.value = null
  mappingError.value = ''
  mappingCandidateError.value = ''
  settingsNotice.value = ''
  conflictingMappings.clear()
  conflictConfirmed.value = false
  settingsSaving.value = false
  settingsSyncing.value = false
  dirtyMappings.clear()
  for (const model of Object.keys(mappingDrafts)) delete mappingDrafts[model]
  Object.assign(settingsForm, {
    name: account.name,
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
  void loadSettingsMappings()
}

function closeSettings() {
  showSettings.value = false
  settingsSaving.value = false
  settingsSyncing.value = false
  mappingLoading.value = false
  settingsGeneration++
  mappingRequests.invalidate()
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

function acceptSavedSettings(account: OAuthAccount, submittedChanges: OAuthAccountSettingsRequest['modelMappingChanges']) {
  settingsBaseline.value = account
  settingsTarget.value = account
  for (const change of submittedChanges ?? []) {
    dirtyMappings.delete(change.upstreamModel)
    conflictingMappings.delete(change.upstreamModel)
  }
}

async function saveSettings() {
  const context = settingsContext()
  const baseline = settingsBaseline.value
  if (!context || !baseline || settingsOperationBusy.value || syncingId.value) return
  if (mappingConflict.value && !conflictConfirmed.value) {
    notify('共享映射已变化或草稿尚未核对，请先确认冲突再保存。', 'error')
    return
  }
  const models = buildOAuthModels(settingsForm.allModels, settingsForm.selectedModels, settingsForm.customModels)
  if (!settingsForm.allModels && models.length === 0) {
    notify('请至少选择一个模型，或勾选「全部模型」', 'error')
    return
  }
  let payload: OAuthAccountSettingsRequest
  const channelIds = [...settingsForm.channelIds]
  const channelChanged = !settingsChannelsManaged.value && !sameModels(channelIds, baseline.channelIds)
  try {
    payload = {}
    const name = settingsForm.name.trim() || null
    if (name !== baseline.name) payload.name = name
    const proxyUrl = settingsForm.clearProxy ? null : settingsForm.proxyUrl.trim() || null
    if (proxyUrl !== baseline.proxyUrl) {
      payload.proxyUrl = proxyUrl
      payload.clearProxy = proxyUrl === null
    }
    if (!sameModels(models, baseline.models)) payload.models = models
    const changes = buildOAuthMappingChanges(mappingDrafts, savableMappingDrafts.value)
    if (changes.length) {
      if (!mappingBaseline.value || mappingError.value) throw new Error('请先重新读取共享映射，确认版本后再保存映射草稿。')
      payload.modelMappingChanges = changes
      payload.expectedModelMappingRevision = mappingBaseline.value.revision
    }
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    return
  }
  if (!Object.keys(payload).length && !channelChanged) {
    if (invalidMappingDrafts.value.length) {
      settingsNotice.value = '存在无共享映射的草稿，尚未保存；请丢弃该映射草稿或同步恢复共享模型。'
      return
    }
    closeSettings()
    return
  }
  settingsSaving.value = true
  settingsWriteInFlight.value = true
  settingsNotice.value = ''
  let settingsSaved = false
  let savedAccount = baseline
  let channelSaved = !channelChanged
  try {
    // 授权、共享映射和原账号设置一次原子 PUT；不再调用独立 models endpoint。
    savedAccount = Object.keys(payload).length ? await api.updateOAuthAccountSettings(context.id, payload) : baseline
    settingsSaved = true
    if (channelChanged) {
      savedAccount = await api.updateOAuthAccountChannels(context.id, { channelIds })
      channelSaved = true
    }
    if (!settingsContextCurrent(context)) { await load(); return }
    acceptSavedSettings(savedAccount, payload.modelMappingChanges)
    conflictConfirmed.value = false
    const mappingRefreshed = await loadSettingsMappings(context)
    const listRefreshed = await load()
    if (!settingsContextCurrent(context)) return
    if (!mappingRefreshed || !listRefreshed) {
      settingsNotice.value = '账号设置已保存成功，但刷新失败。请重新读取共享映射/账号列表，不要重复提交。'
      notify(settingsNotice.value, 'warning')
      return
    }
    if (dirtyMappings.size || conflictingMappings.size) {
      settingsNotice.value = invalidMappingDrafts.value.length
        ? '账号设置与授权已保存成功；无共享行的映射草稿未提交，仍已保留。请丢弃该草稿或同步恢复模型。'
        : '账号设置与授权已保存成功；还有未提交的映射草稿待核对，已保留。请核对最新共享映射并确认后再保存映射草稿。'
      notify(settingsNotice.value, 'warning')
      return
    }
    notify('账号设置已更新')
    emit('changed')
    closeSettings()
  } catch (cause) {
    if (!settingsContextCurrent(context)) return
    if (cause instanceof ApiError && cause.status === 409 && !settingsSaved) {
      for (const change of payload.modelMappingChanges ?? []) conflictingMappings.add(change.upstreamModel)
      conflictConfirmed.value = false
      settingsNotice.value = '共享映射发生版本冲突，本次账号设置和授权均未保存。已保留全部草稿；请核对最新映射并由管理员确认后再保存。'
      await loadSettingsMappings(context)
      notify(settingsNotice.value, 'warning')
    } else if (settingsSaved && !channelSaved) {
      settingsNotice.value = '账号设置已保存成功，但关联渠道更新失败。请核对后只重试渠道变更。'
      // 非托管渠道仍兼容旧接口；已成功部分推进 baseline，避免重复 partial 写入。
      acceptSavedSettings(savedAccount, payload.modelMappingChanges)
      await loadSettingsMappings(context)
      notify(settingsNotice.value, 'warning')
    } else notify(errorMessage(cause), 'error')
  } finally {
    settingsWriteInFlight.value = false
    if (settingsContextCurrent(context)) settingsSaving.value = false
  }
}

function sameModels(a: string[], b: string[]) {
  return JSON.stringify([...new Set(a)].sort()) === JSON.stringify([...new Set(b)].sort())
}

async function deleteAccount(account: OAuthAccount) {
  if (!window.confirm(`删除订阅账号「${account.name}」？该账号的 token、额度和冷却状态会一并删除。`)) return
  if (busyId.value != null) return
  busyId.value = account.id
  try {
    await api.deleteOAuthAccount(account.id)
    notify('订阅账号已删除')
    await load()
    emit('changed')
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
  group?: string
}

/** 低频与破坏性操作收进更多菜单，行内只保留测试、设置与更多。 */
function accountActionItems(account: OAuthAccount): OAuthAccountActionItem[] {
  const busy = busyId.value === account.id
  return [
    ...(account.authMethod === 'agy' ? [] : [{
      id: 'refresh-token', label: '刷新 token', icon: 'refresh', group: '日常运维', disabled: busy,
      title: '用 refresh token 换取新的 access token；正常调用时网关会自动续期',
    }]),
    ...(account.authMethod === 'agy' ? [] : [{ id: 'refresh-quota', label: '刷新额度', icon: 'chart', group: '日常运维', disabled: busy }]),
    ...(isOAuthAccountCooling(account, now.value)
      ? [{ id: 'clear-cooldown', label: '解除冷却', icon: 'check', group: '日常运维', disabled: busy }]
      : []),
    { id: 'policy', label: '调度策略', icon: 'bolt', group: '配置管理' },
    ...(account.authMethod === 'agy' ? [] : [{ id: 'reauth', label: account.provider === 'antigravity' ? '使用 agy 登录迁移' : '重新授权', icon: 'lock', group: '配置管理' }]),
    { id: 'delete', label: '删除账号', icon: 'trash', group: '危险操作', danger: true, disabled: busy },
  ]
}

function accountAction(id: string, account: OAuthAccount) {
  if (id === 'refresh-token') void refreshToken(account)
  else if (id === 'refresh-quota') void refreshQuota(account)
  else if (id === 'policy') openPolicy(account)
  else if (id === 'reauth') openReauth(account)
  else if (id === 'clear-cooldown') void clearCooldown(account)
  else if (id === 'delete') void deleteAccount(account)
}

let disposed = false

onMounted(() => {
  if (props.createOnly) {
    openImport()
    void load()
    return
  }
  // 计时器先于异步加载创建，保证加载期间卸载也能被 onBeforeUnmount 清理。
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 30_000)
  void load().then(() => {
    if (disposed || !props.autoOpenCreate) return
    openImport()
    emit('auto-create-consumed')
  })
})

watch(() => props.fixedProvider, (value) => {
  providerFilter.value = value ?? ''
})

watch(() => props.initialStatusFilter, (value) => {
  statusFilter.value = value
})

defineExpose({ openImport, reload: load })

onBeforeUnmount(() => {
  disposed = true
  resetLoginSession()
  settingsGeneration++
  mappingRequests.invalidate()
  listRequests.invalidate()
  if (clock != null) window.clearInterval(clock)
})
</script>

<template>
  <section v-if="!createOnly" class="page-stack page-compact oauth-accounts-page" :class="{ 'is-embedded': embedded }">
    <AntigravityAgyAccounts
      v-if="!fixedProvider || fixedProvider === 'antigravity'"
      :key="agyAccountsRevision"
      @create="openImport('antigravity')"
      @reauthorize="openAgyReauth"
    />
    <div class="stat-row four oauth-account-stats" aria-label="订阅账号状态筛选">
      <button type="button" class="stat-card" :class="{ 'is-selected': statusFilter === 'all' }" :aria-pressed="statusFilter === 'all'" @click="toggleStatusFilter('all')">
        <span>订阅账号</span><strong>{{ scopedAccounts.length }}</strong><small>全部账号 · 独立凭据</small>
      </button>
      <button type="button" class="stat-card" :class="{ 'is-selected': statusFilter === 'ready' }" :aria-pressed="statusFilter === 'ready'" title="已启用且凭据有效；未排除冷却、额度阻断或并发限制，不等于实时可调度" @click="toggleStatusFilter('ready')">
        <span>凭据就绪</span><strong class="text-success">{{ activeCount }}</strong><small>启用且有效 · 非实时可调度</small>
      </button>
      <button type="button" class="stat-card" :class="{ 'is-selected': statusFilter === 'reauth' }" :aria-pressed="statusFilter === 'reauth'" @click="toggleStatusFilter('reauth')">
        <span>需重新授权</span><strong :class="reauthCount > 0 ? 'text-danger' : ''">{{ reauthCount }}</strong><small>含已停用的失效凭据</small>
      </button>
      <button type="button" class="stat-card" :class="{ 'is-selected': statusFilter === 'cooling' }" :aria-pressed="statusFilter === 'cooling'" @click="toggleStatusFilter('cooling')">
        <span>冷却中</span><strong>{{ coolingCount }}</strong><small>429 限流后暂停调度</small>
      </button>
    </div>

    <div class="oauth-accounts-toolbar">
      <label class="oauth-account-search">
        <Icon name="search" :size="17" />
        <input v-model="listQuery" type="search" aria-label="搜索订阅账号" placeholder="搜索邮箱、账号、渠道或模型" />
      </label>
      <label v-if="!fixedProvider" class="oauth-provider-filter">
        <span class="small muted">提供方</span>
        <select v-model="providerFilter" aria-label="筛选提供方">
          <option value="">全部提供方</option>
          <option v-for="provider in listProviderOptions" :key="provider" :value="provider">{{ oauthProviderLabel(provider) }}</option>
        </select>
      </label>
      <span class="oauth-filter-count small muted" role="status">展示 {{ filteredAccounts.length }} / 共 {{ scopedAccounts.length }}</span>
      <button v-if="hasListFilters" type="button" class="button ghost mini" @click="resetListFilters">重置筛选</button>
      <button type="button" class="button primary oauth-create-account" @click="openImport()">
        <Icon name="plus" :size="17" />新建账号
      </button>
    </div>

    <ErrorState v-if="error" :message="error" :busy="loading" @retry="load" />

    <p v-if="!loading && !error && filteredAccounts.length" class="oauth-accounts-scroll-hint small muted">左右滚动可查看额度、调度及操作。</p>
    <div v-if="loading || !error || scopedAccounts.length" class="panel table-panel">
      <TablePlaceholder
        v-if="loading || scopedAccounts.length === 0"
        :loading="loading"
        loading-text="正在读取订阅账号…"
        :empty="scopedAccounts.length === 0"
        icon="users"
        title="还没有订阅账号"
        :desc="fixedProvider ? `还没有接入 ${oauthProviderLabel(fixedProvider)} 订阅账号，点击新建后通过浏览器登录授权即可使用。` : '直接新建 ChatGPT 或 Antigravity 账号，再按需关联渠道；账号不再依赖任何渠道密钥。'"
        action-text="新建账号"
        @action="openImport()"
      />
      <TablePlaceholder
        v-else-if="filteredAccounts.length === 0"
        :empty="true"
        icon="search"
        title="没有匹配的订阅账号"
        desc="调整搜索、提供方或状态筛选；账号配置和模型授权不会受筛选影响。"
        action-text="重置筛选"
        @action="resetListFilters"
      />
      <div v-else class="table-scroll" role="region" aria-label="订阅账号列表，可左右滚动" tabindex="0">
        <table class="data-table oauth-accounts-table">
          <colgroup><col class="oauth-account-col" /><col class="oauth-state-col" /><col class="oauth-quota-col" /><col class="oauth-schedule-col" /><col class="oauth-refreshed-col" /><col class="oauth-actions-col" /></colgroup>
          <thead>
            <tr>
              <th>账号</th>
              <th>状态</th>
              <th>上游额度</th>
              <th>调度</th>
              <th>最近刷新</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in filteredAccounts" :key="account.id" :data-account-id="account.id">
              <td>
                <div class="primary-cell" :title="account.accountEmail || account.name">
                  {{ account.accountEmail || account.name }}
                </div>
                <div v-if="account.accountEmail && account.accountEmail !== account.name" class="oauth-account-name small muted" :title="account.name">{{ account.name }}</div>
                <div class="oauth-account-meta">
                  <span class="badge neutral small">{{ oauthProviderLabel(account.provider) }}</span>
                  <span v-if="account.authMethod === 'agy'" class="badge info small">官方 agy ACP</span>
                  <span v-if="account.accountPlan" class="small muted">{{ account.accountPlan }}</span>
                </div>
                <div class="oauth-account-channels small muted" :title="channelSummary(account)">渠道：{{ channelSummary(account) }}</div>
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
                    <button
                      type="button"
                      class="badge neutral small oauth-models-toggle"
                      :aria-label="`${expandedModels.has(account.id) ? '收起' : '查看'} ${account.name} 的完整模型列表`"
                      :aria-expanded="expandedModels.has(account.id)"
                      :aria-controls="`oauth-models-${account.id}`"
                      @click="toggleDetails(expandedModels, account.id)"
                    >{{ expandedModels.has(account.id) ? '收起模型' : accountModelView(account).hidden.length ? '+' + accountModelView(account).hidden.length : '查看模型' }}</button>
                    <span v-if="accountModelView(account).inherited" class="oauth-model-inherited muted small">全部模型</span>
                  </template>
                  <span v-else class="muted small">暂无模型</span>
                </div>
                <ul v-if="expandedModels.has(account.id)" :id="`oauth-models-${account.id}`" class="oauth-full-models" :aria-label="account.name + '的完整模型列表'">
                  <li v-for="model in accountModelView(account).names" :key="model" class="mono small">{{ model }}</li>
                </ul>
              </td>
              <td>
                <button
                  type="button"
                  class="oauth-status-toggle"
                  role="switch"
                  :class="account.enabled ? 'is-enabled' : 'is-disabled'"
                  :aria-checked="account.enabled"
                  :aria-label="`订阅账号「${account.name}」${account.enabled ? '已启用' : '已停用'}`"
                  :title="account.enabled ? '点击停用该账号' : '点击启用该账号'"
                  :disabled="busyId === account.id"
                  @click="toggleAccountEnabled(account)"
                >
                  <span class="oauth-status-track" aria-hidden="true"><span></span></span>
                  <span class="oauth-status-label">{{ account.enabled ? '启用' : '停用' }}</span>
                </button>
                <div class="oauth-auth-state">
                  <span class="badge" :class="oauthAuthStateBadge(account.authState)">
                    {{ account.authState === 'ACTIVE' ? '凭据正常' : oauthAuthStateLabel(account.authState) }}
                  </span>
                </div>
                <div
                  v-if="account.lastRefreshError"
                  class="oauth-refresh-error small text-danger"
                >
                  {{ account.lastRefreshError }}
                </div>
              </td>
              <td class="oauth-quota-cell">
                <div v-if="account.authMethod === 'agy'" class="muted small">额度由官方 agy 会话管理；后台不读取或轮询额度</div>
                <div v-else-if="accountQuotaView(account).visible.length" :id="`oauth-quotas-${account.id}`" class="oauth-quota-groups">
                  <div v-for="group in visibleQuotaGroups(account)" :key="group.key" class="oauth-quota-group">
                    <div v-if="group.label" class="oauth-quota-group-label">{{ group.label }}</div>
                    <div v-for="window in group.windows" :key="window.key" class="oauth-quota-window">
                      <div class="oauth-quota-window-head">
                        <span class="small">{{ window.label }}</span>
                        <span class="small" :class="'text-' + oauthQuotaTone(window.usedPercent)">已用 {{ formatUsedPercent(window.usedPercent) }}%</span>
                      </div>
                      <div class="oauth-quota-window-detail">
                        <span class="quota-track"><span class="quota-fill" :class="'is-' + oauthQuotaTone(window.usedPercent)" :style="{ width: window.usedPercent + '%' }"></span></span>
                        <span v-if="formatQuotaReset(window.resetsAt, now)" class="small muted" :title="formatTime(window.resetsAt!)">{{ formatQuotaReset(window.resetsAt, now) }}</span>
                      </div>
                    </div>
                  </div>
                </div>
                <button
                  v-if="accountQuotaView(account).hidden.length"
                  type="button"
                  class="oauth-quota-toggle"
                  :aria-expanded="expandedQuotas.has(account.id)"
                  :aria-controls="`oauth-quotas-${account.id}`"
                  :aria-label="`${expandedQuotas.has(account.id) ? '收起' : '展开'} ${account.name} 的更多额度`"
                  @click="toggleDetails(expandedQuotas, account.id)"
                >{{ expandedQuotas.has(account.id) ? '收起更多额度' : '+' + accountQuotaView(account).hidden.length + ' 更多额度' }}</button>
                <div v-if="isOAuthQuotaBlocked(account, now)" class="oauth-quota-notice small text-danger">
                  额度阻断 · {{ formatCooldownRemaining(account.runtime.quotaBlockedUntil, now) }}后恢复
                </div>
                <div v-else-if="account.quota?.allowed === false" class="oauth-quota-notice small text-danger">上游额度不可用</div>
                <div v-if="oauthQuotaCreditsLabel(account.quota)" class="oauth-quota-notice small muted">{{ oauthQuotaCreditsLabel(account.quota) }}</div>
                <div v-if="account.quota?.error" class="oauth-quota-notice small text-danger">{{ oauthQuotaErrorLabel(account.quota.error) }}</div>
                <div v-if="oauthQuotaErrorHint(account.quota?.error)" class="oauth-quota-notice small muted">{{ oauthQuotaErrorHint(account.quota?.error) }}</div>
                <span v-if="!accountQuotaView(account).visible.length && !account.quota?.error" class="muted small">暂无读数</span>
              </td>
              <td class="oauth-schedule-cell" :title="oauthPolicySummary(account.policy)">
                <div class="small oauth-schedule-line">{{ account.policy.windowLimit == null ? '不限窗口' : `${account.policy.windowLimit} 次 / ${formatWindowSeconds(account.policy.windowSeconds)}` }}</div>
                <div class="small oauth-schedule-line">并发上限 {{ account.policy.maxConcurrentRequests }}</div>
                <div v-if="account.policy.windowLimit != null" class="small muted oauth-schedule-line">窗口 {{ account.runtime.windowUsed }}/{{ account.policy.windowLimit }}</div>
                <div class="small muted oauth-schedule-line">当前并发 {{ account.runtime.activeRequests }}/{{ account.policy.maxConcurrentRequests }}</div>
                <div v-if="formatCooldownRemaining(account.runtime.cooldownUntil, now)" class="small text-warning">
                  冷却剩余 {{ formatCooldownRemaining(account.runtime.cooldownUntil, now) }}
                </div>
              </td>
              <td class="oauth-refresh-cell small">
                <div v-if="account.authMethod === 'agy'" class="muted" title="CLI 凭据由官方 agy 管理和续期">官方 agy 管理凭据</div>
                <div v-else :title="'最近一次成功续期 access token 的时间'">
                  {{ account.lastRefreshedAt ? formatTime(account.lastRefreshedAt) : '尚未刷新' }}
                </div>
                <div class="muted" :title="account.authMethod === 'agy' ? 'agy CLI 授权建立时间' : '账号授权的建立时间；access token 到期会自动续期，不影响授权。'">
                  授权于 {{ formatTime(authorizedAt(account)) }}
                </div>
              </td>
              <td class="actions-col">
                <div class="row-actions">
                  <button v-if="account.authMethod !== 'agy'" type="button" class="button ghost mini" @click="openTest(account)">测试</button>
                  <span v-else class="badge neutral small">ACP</span>
                  <button
                    type="button"
                    class="icon-button"
                    :title="'设置 ' + account.name"
                    :aria-label="'设置 ' + account.name"
                    @click="openSettings(account)"
                  >
                    <Icon name="gear" :size="16" />
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

  </section>

    <Modal v-if="showImport" :title="reauthTarget ? '重新授权订阅账号' : '新建订阅账号'" wide @close="closeImport">
      <form class="form-grid" @submit.prevent="completeLogin">
        <p v-if="createOnly && loading" class="muted small" role="status">正在读取订阅提供方…</p>
        <ErrorState v-if="createOnly && error" :message="error" :busy="loading" @retry="load" />
        <label class="field">
          <span>账号名称</span>
          <input v-model="importForm.name" autocomplete="off" placeholder="可选，默认使用邮箱" />
        </label>

        <label class="field">
          <span>提供方</span>
          <select v-model="importForm.provider" :disabled="reauthTarget != null || !!fixedProvider">
            <option v-for="provider in providers" :key="provider.id" :value="provider.id" :disabled="provider.id !== 'antigravity' && !provider.configured">
              {{ provider.label }}{{ provider.id === 'antigravity' ? '（官方 agy）' : provider.configured ? '' : '（未配置）' }}
            </option>
          </select>
          <small v-if="configuredProviders.length === 0 && !officialAgyLogin" class="text-danger">
            尚未配置任何 OAuth 提供方，请先设置客户端参数。
          </small>
        </label>

        <div v-if="importChannelsManaged" class="field">
          <span>上游端点</span>
          <p class="muted small">{{ officialAgyLogin ? '官方 agy 使用独立登录档案；本次不创建旧 OAuth 记录、不自动关联或切换普通路由。' : '订阅账号只服务官方端点，系统会自动创建并维护对应渠道，无需手工关联。' }}</p>
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

        <AntigravityAgyLogin
          v-if="officialAgyLogin"
          :name="importForm.name"
          :source-account-id="reauthTarget?.id"
          :account-id="agyReauthTarget?.id"
          @authenticated="onAgyAuthenticated"
        />

        <template v-else>
        <div class="field">
          <span>授权方式</span>
          <p class="muted small">只能通过浏览器登录授权：网关走提供方的官方授权页换取并加密保存 token，无需手动复制凭据。</p>
        </div>

        <div class="field">
          <span>登录链接</span>
          <div v-if="loginSession" class="callout info oauth-login-link">
            <a :href="loginSession.authorizeUrl" target="_blank" rel="noopener noreferrer">{{ loginSession.authorizeUrl }}</a>
            <CopyButton :text="loginSession.authorizeUrl" title="复制登录链接" />
          </div>
          <div v-else class="oauth-login-actions">
            <button type="button" class="button" :disabled="loginStarting || (createOnly && (loading || !!error))" @click="beginLogin">
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

        <div class="form-actions">
          <button type="button" class="button" @click="closeImport">取消</button>
          <button v-if="!officialAgyLogin" type="submit" class="button primary" :disabled="loginCompleting">
            {{ loginCompleting ? '提交中…' : reauthTarget ? '重新授权' : '创建账号' }}
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

    <Modal v-if="showSettings && settingsTarget" title="账号设置" wide @close="closeSettings">
      <form class="form-grid oauth-account-settings" @submit.prevent="saveSettings">
        <label class="field">
          <span>账号名称</span>
          <input v-model="settingsForm.name" autocomplete="off" />
        </label>
        <p class="muted small">凭据的模型调度顺序在「模型路由」页统一配置。</p>
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
        <div class="field">
          <span>模型授权与映射</span>
          <p class="oauth-shared-mapping-hint small">
            对外 ID 映射由同一提供方 {{ oauthProviderLabel(settingsTarget.provider) }} 的账号共享
            <template v-if="mappingBaseline"> · 受影响账号 {{ mappingBaseline.affectedAccountCount }} 个 · 版本 {{ mappingBaseline.revision }}</template>。
            修改映射不会增加其他账号的模型授权；授权勾选只作用于当前账号。
          </p>
          <p v-if="mappingLoading" class="muted small" role="status">正在读取共享映射与候选…</p>
          <div v-if="mappingError" class="oauth-mapping-error small" role="alert">
            共享映射读取失败：{{ mappingError }}。账号设置与授权仍可保存；映射需重新读取版本。
            <button type="button" class="link-button" :disabled="settingsOperationBusy" @click="loadSettingsMappings()">重新读取共享映射</button>
          </div>
          <p v-if="mappingCandidateError" class="muted small" role="status">{{ mappingCandidateError }}</p>
          <div v-if="settingsNotice || mappingConflict" class="oauth-settings-notice small" role="alert">
            {{ settingsNotice }}
            <p v-if="mappingConflict">共享映射冲突：{{ conflictingMappings.size }} 个草稿行的共享值已变化或首次读取后尚未核对。草稿已保留，请核对最新值，管理员确认后才可保存。</p>
            <label v-if="mappingConflict" class="checkbox-row">
              <input v-model="conflictConfirmed" type="checkbox" :disabled="mappingLoading || !!mappingError" />
              <span>我已核对最新共享映射，确认将草稿应用到当前版本</span>
            </label>
          </div>
          <p v-if="invalidMappingDrafts.length" class="oauth-mapping-error small" role="alert">{{ invalidMappingDrafts.length }} 个映射草稿没有共享目录行，将不会提交；账号授权仍可保存。请丢弃该映射草稿或同步模型恢复共享行。</p>
          <button v-if="mappingBaseline && !mappingError" type="button" class="link-button small" :disabled="settingsOperationBusy" @click="loadSettingsMappings()">刷新共享映射</button>
          <div class="oauth-model-picker">
            <div class="oauth-model-picker-toolbar">
              <label class="oauth-model-search">
                <Icon name="search" :size="15" />
                <input
                  v-model="settingsForm.modelQuery"
                  autocomplete="off"
                  aria-label="搜索模型授权与映射"
                  placeholder="搜索上游模型或对外 ID"
                />
              </label>
              <span class="muted small">
                {{ settingsForm.allModels ? `全部 ${settingsModelOptions.length} 个模型` : `已选 ${selectedModelCount} / ${settingsModelOptions.length}` }}
              </span>
            </div>
            <div class="oauth-model-list oauth-mapping-list">
              <div class="oauth-mapping-head small" aria-hidden="true">
                <span>授权 / 上游模型</span><span>对外 ID / public ID</span><span>映射模式</span><span>映射来源</span>
              </div>
              <div v-for="model in filteredSettingsModels" :key="model" class="oauth-mapping-row" :data-upstream-model="model">
                <div class="oauth-mapping-upstream">
                  <label class="oauth-model-option">
                    <input v-model="settingsForm.selectedModels" type="checkbox" :value="model" :disabled="settingsForm.allModels" :aria-label="'授权 ' + model" />
                    <span class="mono">{{ model }}</span>
                  </label>
                  <span v-if="settingsModelOutsideCatalog(model)" class="badge warning small oauth-model-outside" title="不在当前账号目录中；保留草稿不会增加上游套餐权限">目录外</span>
                </div>
                <div class="oauth-mapping-public">
                  <span class="mono small" :title="mappingPublicId(model)">{{ mappingPublicId(model) }}</span>
                  <OAuthMappingInput
                    v-if="mappingDraftFor(model).mode === 'manual'"
                    :model-value="mappingDraftFor(model).canonicalModelId"
                    :upstream-model="model"
                    :options="mappingCandidates"
                    :disabled="settingsOperationBusy"
                    @update:model-value="changeManualMapping(model, $event)"
                  />
                  <span v-if="conflictingMappings.has(model)" class="small oauth-conflict-baseline">最新共享值：{{ baselineMappingByModel.get(model)?.publicModelId ?? model }}（{{ baselineMappingByModel.get(model)?.mode ?? 'auto' }}；来源 {{ baselineMappingByModel.get(model)?.mappingSource ?? '无' }}；{{ baselineMappingByModel.get(model)?.catalogActive ? '有效' : '目录休眠' }}；自动默认 {{ baselineMappingByModel.get(model)?.automaticCanonicalModelId ?? model }}）</span>
                  <div v-if="invalidMappingDrafts.includes(model)" class="small oauth-conflict-baseline">
                    无共享映射：此草稿不会提交，可保留账号授权。同步模型恢复共享行后再核对，或
                    <button type="button" class="link-button small" :disabled="settingsOperationBusy" @click="discardMappingDraft(model)">丢弃此映射草稿</button>
                  </div>
                </div>
                <div class="oauth-mapping-mode">
                  <select :value="mappingDraftFor(model).mode" :aria-label="model + ' 映射模式'" :disabled="settingsOperationBusy" @change="changeMappingMode(model, ($event.target as HTMLSelectElement).value as OAuthModelMappingMode)">
                    <option value="auto">自动</option><option value="manual">手工</option><option value="upstream">使用上游 ID</option>
                  </select>
                  <button v-if="mappingDraftFor(model).mode !== 'auto'" type="button" class="link-button small" :disabled="settingsOperationBusy" @click="changeMappingMode(model, 'auto')">恢复自动</button>
                </div>
                <span class="oauth-mapping-source muted small">{{ mappingSource(model) }}</span>
              </div>
              <span v-if="settingsModelOptions.length === 0" class="oauth-model-empty muted small">该账号还没有可选模型。同步模型或补充列表外的模型。</span>
              <span v-else-if="filteredSettingsModels.length === 0" class="oauth-model-empty muted small">没有匹配「{{ settingsForm.modelQuery }}」的模型。</span>
            </div>
            <p v-if="settingsForm.allModels" class="oauth-model-all-hint muted small">
              {{ settingsTarget?.modelCatalog ? '当前使用该账号目录里的全部模型；' : '当前继承所关联渠道的全部模型；' }}取消勾选「全部模型」后可只调度勾选的模型。全部模式不限制映射编辑。
            </p>
            <div class="oauth-model-picker-actions">
              <button v-if="!settingsForm.allModels" type="button" class="link-button" @click="selectVisibleModels">{{ settingsForm.modelQuery.trim() ? '全选搜索结果' : '全选' }}</button>
              <button v-if="!settingsForm.allModels" type="button" class="link-button" @click="clearSelectedModels">清空</button>
              <button v-if="oauthSupportsModelCatalog(settingsTarget.provider)" type="button" class="link-button" :disabled="!!syncingId || settingsOperationBusy || !oauthCanSyncCatalog(settingsTarget)" :title="oauthCanSyncCatalog(settingsTarget) ? '同步账号目录和共享映射；保留未保存草稿' : '账号需要重新授权后才能读取上游模型目录'" @click="syncSettingsModels">{{ settingsSyncing ? '同步中…' : '同步模型' }}</button>
              <span class="oauth-model-picker-spacer"></span>
              <label class="checkbox-row small"><input v-model="settingsForm.allModels" type="checkbox" /><span>全部模型</span></label>
            </div>
            <input v-model="settingsForm.customModels" autocomplete="off" :disabled="settingsForm.allModels" aria-label="补充列表外的模型" placeholder="补充列表外的模型，多个用逗号分隔" />
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
          <button type="button" class="button" @click="closeSettings">取消</button>
          <button type="submit" class="button primary" :disabled="settingsOperationBusy || !!syncingId || (mappingConflict && !conflictConfirmed) || (savableMappingDrafts.length > 0 && (!mappingBaseline || !!mappingError))">{{ settingsSaving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </Modal>
</template>

<style scoped>
.oauth-shared-mapping-hint, .oauth-settings-notice, .oauth-mapping-error {
  margin: 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px;
  background: var(--info-soft); line-height: 1.6;
}
.oauth-settings-notice, .oauth-mapping-error { background: var(--warning-soft); }
.oauth-settings-notice .checkbox-row { margin-top: 8px; }
.oauth-mapping-list {
  --oauth-mapping-columns: minmax(150px, 1.1fr) minmax(220px, 1.5fr) minmax(130px, .8fr) minmax(90px, .6fr);
  --oauth-mapping-min-width: 660px;
  min-width: 0;
  max-width: 100%;
  max-height: 440px;
  overflow: auto;
}
.oauth-mapping-head, .oauth-mapping-row {
  box-sizing: border-box;
  display: grid;
  grid-template-columns: var(--oauth-mapping-columns);
  min-width: var(--oauth-mapping-min-width);
  gap: 10px; align-items: start; padding: 10px;
}
.oauth-mapping-head { position: sticky; top: 0; z-index: 1; background: var(--panel); color: var(--muted); }
.oauth-mapping-row + .oauth-mapping-row { border-top: 1px solid var(--line); }
.oauth-mapping-upstream, .oauth-mapping-public, .oauth-mapping-mode {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  max-width: 100%;
  gap: 6px;
}
.oauth-mapping-upstream .oauth-model-option { min-width: 0; padding: 0; align-items: start; }
.oauth-mapping-upstream .mono, .oauth-mapping-public > .mono, .oauth-mapping-source { min-width: 0; overflow-wrap: anywhere; }
.oauth-mapping-mode select { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; }
.oauth-mapping-mode .link-button { justify-self: start; }
.oauth-conflict-baseline { color: var(--warning); overflow-wrap: anywhere; }
@media (max-width: 680px) {
  .oauth-mapping-list { --oauth-mapping-columns: minmax(0, 1fr); --oauth-mapping-min-width: 0; }
  .oauth-mapping-head { display: none; }
  .oauth-mapping-row { gap: 12px; }
  .oauth-mapping-source::before { content: '映射来源：'; }
}
</style>

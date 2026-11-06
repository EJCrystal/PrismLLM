<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { clearUpstreamModelCache } from '../upstreamModelCache'
import { ApiError } from '../types/api'
import type { AdminAuditLog, Channel, ChannelHealthReport, ChannelKeyState, HealthState, HealthWindow, ModelDefaultPrice, ModelPrice, ProtocolProbeResult, RuntimeOccupancy, UpstreamRequestOverrides } from '../types/api'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { useMediaQuery } from '../composables/useMediaQuery'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import ActionMenu from './ActionMenu.vue'
import ChannelProtocols from './ChannelProtocols.vue'
import { vScrollHint } from '../directives/scrollHint'
import Modal from './Modal.vue'
import PriceEditorModal from './PriceEditorModal.vue'
import DefaultPriceModal from './DefaultPriceModal.vue'
import ModelTestModal from './ModelTestModal.vue'
import ModelTestSettingsModal from './ModelTestSettingsModal.vue'
import UpstreamRequestSettingsModal from './UpstreamRequestSettingsModal.vue'
import RequestOverridesEditor from './RequestOverridesEditor.vue'
import KeyAutoDisableSettingsModal from './KeyAutoDisableSettingsModal.vue'
import ChannelKeyRateLimitModal from './ChannelKeyRateLimitModal.vue'
import ChannelQuotaModal from './ChannelQuotaModal.vue'
import ChannelHealthModal from './ChannelHealthModal.vue'
import ChannelKeyEditor from './ChannelKeyEditor.vue'
import { HEALTH_LABELS, healthSymbol, channelHealthSuccessRateText } from '../utils/channelHealth'
import { cooldownSummaryText } from '../utils/cooldown'
import TablePlaceholder from './TablePlaceholder.vue'
import ErrorState from './ErrorState.vue'
import FilterSelect from './FilterSelect.vue'
import { formatTime, formatDuration } from '../utils/format'
import { refreshUntilCurrent } from '../utils/latestRequest'
import {
  channelDisplayModels,
  channelLandingUrl,
  recentProbeByChannel as indexRecentProbeByChannel,
  channelMatchesFilters,
  channelModelFilterOptions,
} from '../utils/channelDisplay'
import type { ChannelFormDraft, OriginalModelMapping } from '../utils/channelForm'
import { buildChannelPayload, buildPriorityUpdatePayload, canonicalModelSnapshot, isKeyRoutable, mergeProbedProtocols, removeProbeAddedProtocols } from '../utils/channelForm'
import { channelKeyRateLimitSummary, normalizeChannelKeyRateLimit, validateChannelKeyRateLimit } from '../utils/channelKeyRateLimit'
import {
  canProbeSavedChannel,
  createRequestTracker,
  sameProtocolSelection,
  watchChannelRequestInputs,
} from '../utils/channelRequestState'
import { createRequestOverrideDrafts, normalizeRequestOverrides, requestOverrideDraftSnapshot } from '../utils/requestOverrides'
import type { ChannelDisplayModel } from '../utils/channelDisplay'
import type { ChannelKeyRateLimitDraft } from '../utils/channelKeyRateLimit'
import type { ChannelKeyEditorDraft } from '../types/channelEditor'
import { keyStateBadge, keyStateTone } from '../utils/channelKey'
import {
  buildPricePrompt,
  channelPriceSummaries,
  findModelPrice,
  resolveDisplayPriceSource,
  resolveSavedChannel,
} from '../utils/modelPrice'

interface RequestOverridesEditorExpose {
  getValue: () => UpstreamRequestOverrides
  getSnapshot: () => string
}

type NewKeyOverrides = Partial<Pick<ChannelKeyEditorDraft, 'id' | 'name' | 'state' | 'priority' | 'stateVersion' | 'autoDisableReason' | 'autoDisableUntil' | 'models'>> & {
  enabled?: boolean
  rateLimit?: unknown
}

interface ChannelFormState extends Omit<ChannelFormDraft, 'requestOverrides' | 'authType' | 'protocolAuthTypes'> {
  authType: string
  protocolAuthTypes: Record<string, string>
  requestOverrides: UpstreamRequestOverrides
}

interface ModelTestTarget {
  channel: Channel
  model: string
}

interface PriceEditorTarget {
  channels: Channel[]
  price: ModelPrice | null
  initialTarget: { channelId: string; modelId: string }
}

type ChannelStatusFilter = 'all' | 'enabled' | 'disabled'

interface RateLimitTarget {
  key: ChannelKeyEditorDraft
  rule: ChannelKeyRateLimitDraft & Record<string, unknown>
}

const channels = ref<Channel[]>([])
let listRevision = 0
let listDisposed = false
// 列表健康度固定按 24 小时汇总；时间范围切换交由健康度详情弹窗自行选择。
const healthWindow: HealthWindow = '24h'
// 健康度是 24 小时聚合，30 秒轮询足以兼顾新鲜度与查询成本；回到前台时立即补拉。
const healthRefreshMs = ref(30_000)
const healthReport = ref<ChannelHealthReport | null>(null)
const healthSuccessRateCache = ref<{ window: HealthWindow; rates: Map<string, number | null> } | null>(null)
const healthTarget = ref<Channel | null>(null)
const healthLoading = ref(false)
const healthError = ref('')
let healthRevision = 0
const healthByChannel = computed(() => new Map(healthReport.value?.window === healthWindow ? healthReport.value.channels.map(row => [row.channelId, row]) : []))
// 实时占用来自单实例内存计数，独立轮询，避免为了几秒级的在途数反复拉取渠道配置。
const occupancy = ref<RuntimeOccupancy | null>(null)
const occupancyRefreshMs = ref(5_000)
let occupancyRevision = 0
const occupancyByChannel = computed(() => new Map((occupancy.value?.channels ?? []).map((row) => [row.channelId, row])))
function channelConcurrent(channelId: string): number {
  return occupancyByChannel.value.get(channelId)?.concurrentRequests ?? 0
}
function channelKeyConcurrent(channelId: string | null, keyId: string | null): number {
  if (!channelId || !keyId) return 0
  return occupancyByChannel.value.get(channelId)?.keys.find((key) => key.keyId === keyId)?.concurrentRequests ?? 0
}
function channelOccupancyTitle(channel: Channel): string {
  const row = occupancyByChannel.value.get(channel.id)
  if (!row) return `${channel.name}：实时占用暂不可用`
  if (!row.keys.length) return `${channel.name}：当前没有在途请求`
  const detail = row.keys.map((key) => {
    const name = channel.keys.find((item) => item.id === key.keyId)?.name || key.keyId
    return `${name} ${key.concurrentRequests}`
  })
  return `${channel.name}：当前在途 ${row.concurrentRequests} 个请求（${detail.join('，')}）`
}
async function loadOccupancy(): Promise<void> {
  const request = ++occupancyRevision
  try {
    const result = await api.getOccupancy()
    if (request === occupancyRevision && !listDisposed) occupancy.value = result
  } catch {
    // 占用是辅助信息，读取失败时保留上一次快照，避免把在途数误显示为 0。
  }
}
const cooldownNow = ref(Date.now())
let cooldownTimer: ReturnType<typeof setInterval> | null = null
let cooldownRefreshAt = 0
function syncCooldownTimer() {
  const cooling = healthReport.value?.channels.some(row => (row.coolingCandidates ?? 0) > 0) ?? false
  if (cooling && cooldownTimer == null) {
    cooldownTimer = setInterval(() => {
      const now = Date.now()
      cooldownNow.value = now
      const nextExpiry = (healthReport.value?.channels ?? [])
        .map(row => row.cooldownUntil)
        .filter((value): value is number => typeof value === 'number')
        .reduce<number | null>((min, value) => (min == null || value < min ? value : min), null)
      // 最近一次冷却到期后重新拉取报告，避免列表长期停留在旧状态；限制刷新频率避免时钟偏快时每秒重复请求。
      // 健康度读取失败时交给 30 秒轮询重试，避免按冷却到期节奏放大失败请求。
      if (nextExpiry != null && now >= nextExpiry && !healthLoading.value && !healthError.value && now - cooldownRefreshAt >= 10_000) {
        cooldownRefreshAt = now
        loadHealth()
      }
    }, 1000)
  } else if (!cooling && cooldownTimer != null) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
}
function channelCooldownText(channel: Channel): string {
  return cooldownSummaryText(healthByChannel.value.get(channel.id), cooldownNow.value)
}
function channelCooldownTitle(channel: Channel): string {
  const row = healthByChannel.value.get(channel.id)
  if (!row || (row.coolingCandidates ?? 0) <= 0) return ''
  const until = row.cooldownUntil ? `，最近恢复 ${formatTime(row.cooldownUntil)}` : ''
  return `${channel.name}：${row.coolingCandidates}/${row.totalCandidates ?? 0} 个候选冷却${until}；点击查看详情`
}
function channelHealthState(channel: Channel): HealthState {
  return !channel.enabled ? 'DISABLED' : healthByChannel.value.get(channel.id)?.state || 'UNKNOWN'
}
function channelHealthTitle(channel: Channel): string {
  const row = healthByChannel.value.get(channel.id)
  return `${channel.name}：${row?.reason || (healthLoading.value ? '正在汇总' : '健康度未读取')}；点击查看成功率、延迟、密钥和模型明细`
}
function channelSuccessRateText(channel: Channel): string {
  const cachedRate = healthSuccessRateCache.value?.window === healthWindow
    ? healthSuccessRateCache.value.rates.get(channel.id)
    : undefined
  const cachedRow = cachedRate === undefined ? undefined : { metrics: { successRate: cachedRate } }
  return channelHealthSuccessRateText(healthByChannel.value.get(channel.id) ?? cachedRow, healthLoading.value, healthError.value)
}
function channelHealthAttempts(channel: Channel): number {
  return healthByChannel.value.get(channel.id)?.metrics.attempts ?? 0
}
function channelP95Text(channel: Channel): string {
  const p95 = healthByChannel.value.get(channel.id)?.metrics.p95FirstTokenMs
  return p95 != null ? formatDuration(p95) : '暂无数据'
}
function channelProbeText(channel: Channel): string {
  const probe = recentProbe(channel)
  return probe ? formatTime(probe.createdAt) : '暂无'
}
function channelAutoDisabledCount(channel: Channel): number {
  return (channel.keys ?? []).filter((key) => key.state === 'AUTO_PERMANENT' || key.state === 'AUTO_TEMPORARY').length
}
const expandedMobileChannelIds = ref<string[]>([])
function mobileDetailsOpen(channelId: string): boolean {
  return expandedMobileChannelIds.value.includes(channelId)
}
function toggleMobileDetails(channelId: string): void {
  expandedMobileChannelIds.value = mobileDetailsOpen(channelId)
    ? expandedMobileChannelIds.value.filter((id) => id !== channelId)
    : [...expandedMobileChannelIds.value, channelId]
}
function onCloseHealthModal(changed = false) {
  healthTarget.value = null
  if (changed) void loadHealth()
}
async function loadHealth() {
  const request = ++healthRevision
  healthLoading.value = true
  try {
    const result = await api.getChannelHealth(healthWindow)
    if (request === healthRevision) {
      healthReport.value = result
      healthError.value = ''
      const rates = new Map<string, number | null>()
      for (const row of result.channels) rates.set(row.channelId, row.metrics.successRate)
      healthSuccessRateCache.value = { window: result.window, rates }
      syncCooldownTimer()
    }
  } catch (cause) {
    if (request === healthRevision) {
      // 后台轮询失败时保留上一份成功快照，避免成功率和次数在错误提示下跳成 0。
      healthError.value = errorMessage(cause)
      syncCooldownTimer()
    }
  } finally { if (request === healthRevision) healthLoading.value = false }
}
onBeforeUnmount(() => {
  listDisposed = true
  healthRevision++
  listRevision++
  if (cooldownTimer != null) clearInterval(cooldownTimer)
})
const recentProbes = ref<AdminAuditLog[]>([])
const prices = ref<ModelPrice[]>([])
const defaultPrices = ref<ModelDefaultPrice[]>([])
const priceEditor = ref<PriceEditorTarget | null>(null)
const showDefaultPrices = ref(false)
const testTarget = ref<ModelTestTarget | null>(null)
const showTestSettings = ref(false)
const showRequestSettings = ref(false)
const overrideEditor = ref<RequestOverridesEditorExpose | null>(null)
const overrideRevision = ref(0)
const showAutoDisableSettings = ref(false)
const rateLimitTarget = ref<RateLimitTarget | null>(null)
const quotaTarget = ref<Channel | null>(null)
const formSnapshot = ref('')
const isFormDirty = computed(() => {
  if (!showForm.value) return false
  overrideRevision.value
  return formSnapshot.value !== channelFormSnapshot()
})
const recentProbeByChannel = computed(() => {
  return indexRecentProbeByChannel(recentProbes.value)
})

function channelFormSnapshot(useEditor = true): string {
  return JSON.stringify({
    form: {
      name: form.name,
      baseUrl: form.baseUrl,
      authType: form.authType,
      protocolAuthTypes: form.protocolAuthTypes,
      protocols: form.protocols,
      enabled: form.enabled,
      priority: form.priority,
      requestOverrides: useEditor && overrideEditor.value
        ? overrideEditor.value.getSnapshot()
        : requestOverrideDraftSnapshot(
            createRequestOverrideDrafts(form.requestOverrides),
            form.requestOverrides.inheritGlobal !== false,
            form.requestOverrides.reasoningContentPolicy ?? null,
            form.requestOverrides.developerRolePolicy ?? null,
          ),
    },
    keys: channelKeys.value.map((key) => ({
      id: key.id, name: key.name, credential: key.credential, state: key.state, priority: key.priority,
      stateVersion: key.stateVersion, models: key.models, rateLimit: key.rateLimit,
    })),
    canonicalModels: canonicalModelSnapshot(canonicalModels),
  })
}

function recentProbe(channel: Channel): AdminAuditLog | null {
  return recentProbeByChannel.value.get(channel.id) || null
}

function onQuotaSaved(channel: Channel) {
  const index = channels.value.findIndex((item) => item.id === channel.id)
  if (index >= 0) channels.value[index] = channel
  if (quotaTarget.value?.id === channel.id) quotaTarget.value = channel
  notify('额度查询设置已保存')
}
const pricePromptChannelId = ref<string | null>(null)
const loading = ref(true)
const error = ref('')
const channelStatusFilter = ref<ChannelStatusFilter>('all')
const channelModelFilter = ref('')
const channelNameFilter = ref('')
const hasChannelFilters = computed(() => channelStatusFilter.value !== 'all' || !!channelModelFilter.value || !!channelNameFilter.value.trim())
const prioritySortDirection = ref<'asc' | 'desc'>('asc')
const channelToggleSavingIds = ref<string[]>([])
const showForm = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const probing = ref(false)
const probeResults = ref<ProtocolProbeResult[]>([])
const channelKeys = ref<ChannelKeyEditorDraft[]>([])
const selectedChannelKeyId = ref<string | null>(null)
const expandedChannelKeyId = ref<string | null>(null)
type ChannelFormSectionId = 'basic' | 'protocols' | 'keys' | 'models' | 'routing'
const activeFormSection = ref<ChannelFormSectionId>('basic')
const isMobile = useMediaQuery('(max-width: 640px)')
const mappingSearch = ref('')
const showAllMappings = ref(false)
let keyDraftSeq = 0
const canonicalModels = reactive<Record<string, string>>({})
const originalMappings = reactive<Record<string, OriginalModelMapping>>({})
const probeRequests = createRequestTracker(() => {
  probing.value = false
})
const modelRequests = createRequestTracker()
let probeAddedProtocols: string[] = []

const form = reactive<ChannelFormState>({
  name: '',
  baseUrl: '',
  authType: 'bearer',
  protocolAuthTypes: {},
  protocols: ['chat_completions'],
  enabled: true,
  priority: 1,
  requestOverrides: normalizeRequestOverrides(),
})

const protocolOptions: Array<{ value: string; label: string }> = [
  { value: 'chat_completions', label: 'Chat Completions' },
  { value: 'responses', label: 'Responses' },
  { value: 'messages', label: 'Anthropic Messages' },
  { value: 'gemini', label: 'Gemini' },
]

function authTypeLabel(value: string): string {
  if (value === 'anthropic') return 'Anthropic'
  if (value === 'google') return 'Google'
  return 'Bearer'
}

function setProtocolAuthType(protocol: string, value: string): void {
  if (value) form.protocolAuthTypes[protocol] = value
  else delete form.protocolAuthTypes[protocol]
}

function setProtocolAuthTypeFromEvent(protocol: string, event: Event): void {
  if (event.target instanceof HTMLSelectElement) setProtocolAuthType(protocol, event.target.value)
}

function keepProtocolAsManual(protocol: string): void {
  probeAddedProtocols = probeAddedProtocols.filter((added) => added !== protocol)
}

function moveProtocol(index: number, delta: number): void {
  const next = index + delta
  if (next < 0 || next >= form.protocols.length) return
  const [item] = form.protocols.splice(index, 1)
  form.protocols.splice(next, 0, item)
}

watchChannelRequestInputs(form, {
  modelRequests,
  probeRequests,
  credentialSource: () => channelKeys.value.map((key) => key.credential).join('\u0000'),
  settingsSource: () => overrideRevision.value,
  onInputsChanged: () => {
    channelKeys.value.forEach((key) => {
      key.availableModels = []
      key.modelsLoading = false
      key.modelsError = ''
      key.modelsRequestId = (key.modelsRequestId || 0) + 1
    })
    if (!probeResults.value.length && !probeAddedProtocols.length) return
    probeResults.value = []
    if (probeAddedProtocols.length) form.protocols = removeProbeAddedProtocols(form.protocols, probeAddedProtocols)
    probeAddedProtocols = []
  },
})

const enabledCount = computed(() => channels.value.filter((channel) => channel.enabled).length)
const channelStatusOptions = computed(() => [
  { id: 'all' as const, label: '全部', count: channels.value.length },
  { id: 'enabled' as const, label: '启用', count: enabledCount.value },
  { id: 'disabled' as const, label: '停用', count: channels.value.length - enabledCount.value },
])
const channelModelOptions = computed(() => channelModelFilterOptions(channels.value))
watch(channelModelOptions, (models) => {
  if (channelModelFilter.value && !models.includes(channelModelFilter.value)) channelModelFilter.value = ''
})
const displayedChannels = computed(() => channels.value
  .filter((channel) => channelMatchesFilters(channel, channelStatusFilter.value, channelModelFilter.value, channelNameFilter.value))
  .sort((left, right) => {
    const comparison = (left.priority ?? 1) - (right.priority ?? 1)
    return prioritySortDirection.value === 'asc' ? comparison : -comparison
  }))
const priceSummaries = computed(() => channelPriceSummaries(channels.value, prices.value, defaultPrices.value))
const pricePrompt = computed(() => {
  const channelId = pricePromptChannelId.value
  if (!channelId) return null
  const channel = channels.value.find((item) => item.id === channelId)
  const summary = priceSummaries.value.get(channelId)
  return buildPricePrompt(channel, summary?.count ?? 0)
})
watch(pricePrompt, (value) => {
  if (!value && pricePromptChannelId.value) pricePromptChannelId.value = null
})
const authorizedModels = computed(() => {
  const models = new Set<string>()
  channelKeys.value.forEach((key) => key.models.forEach((model) => models.add(model)))
  return [...models].sort()
})
const mappingCount = computed(() => authorizedModels.value.filter((modelId) => canonicalModels[modelId]?.trim()).length)
const formSectionTabs = computed<Array<{ id: ChannelFormSectionId; label: string; count: string }>>(() => [
  { id: 'basic', label: '基础', count: '' },
  { id: 'protocols', label: '协议', count: String(form.protocols.length) },
  { id: 'keys', label: '密钥', count: String(channelKeys.value.length) },
  { id: 'models', label: '模型', count: String(authorizedModels.value.length) },
  { id: 'routing', label: '路由', count: form.enabled ? '启用' : '停用' },
])

const protocolRows = computed(() => {
  const resultByProtocol = new Map(probeResults.value.map((result) => [result.protocol, result]))
  const enabled = form.protocols
    .map((protocol) => protocolOptions.find((option) => option.value === protocol))
    .filter((option): option is { value: string; label: string } => Boolean(option))
  const disabled = protocolOptions.filter((option) => !form.protocols.includes(option.value))
  return [...enabled, ...disabled].map((option) => {
    const result = resultByProtocol.get(option.value)
    const state = result ? probeState(result) : null
    return {
      ...option,
      enabled: form.protocols.includes(option.value),
      index: form.protocols.indexOf(option.value),
      probe: result && state
        ? {
            className: state.className,
            title: result.message,
            detail: `${state.label}${result.statusCode ? ` (${result.statusCode})` : ''}`,
          }
        : null,
    }
  })
})

const selectedChannelKey = computed(() => (
  channelKeys.value.find((key) => key.localId === selectedChannelKeyId.value)
  || channelKeys.value[0]
  || null
))

function channelKeyPriority(key: ChannelKeyEditorDraft): number {
  const value = Number(key.priority)
  return Number.isFinite(value) ? value : Number.POSITIVE_INFINITY
}

const defaultChannelKey = computed(() => (
  channelKeys.value.reduce<ChannelKeyEditorDraft | null>((best, key) => (
    !best || channelKeyPriority(key) < channelKeyPriority(best) ? key : best
  ), null)
))

const visibleMappingModels = computed(() => {
  const query = mappingSearch.value.trim().toLowerCase()
  const source = query || showAllMappings.value
    ? authorizedModels.value
    : authorizedModels.value.filter((modelId) => canonicalModels[modelId]?.trim())
  return query
    ? source.filter((modelId) => modelId.toLowerCase().includes(query))
    : source
})

watch(mappingCount, (count) => {
  if (count === 0) showAllMappings.value = true
})

const canProbeProtocols = computed(() => {
  const savedChannel = channels.value.find((channel) => channel.id === editingId.value)
  if (!savedChannel?.keys?.length) return false
  let requestOverrides: UpstreamRequestOverrides
  try {
    requestOverrides = overrideEditor.value?.getValue() || form.requestOverrides
  } catch {
    return false
  }
  return canProbeSavedChannel(savedChannel, {
    baseUrl: form.baseUrl,
    credential: channelKeys.value.some((key) => key.credential.trim()) ? 'draft' : '',
    authType: form.authType,
    protocolAuthTypes: form.protocolAuthTypes,
    requestOverrides,
  })
})

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

function formSectionTabId(section: ChannelFormSectionId): string {
  return `channel-form-tab-${section}`
}

function scrollFormToTop(): void {
  nextTick(() => {
    const body = document.querySelector('.channel-form')?.closest('.modal-body')
    if (body instanceof HTMLElement) body.scrollTop = 0
  })
}

function setActiveFormSection(section: ChannelFormSectionId): void {
  if (activeFormSection.value === section) return
  activeFormSection.value = section
  scrollFormToTop()
}

function focusFormSection(section: ChannelFormSectionId): void {
  if (activeFormSection.value !== section) {
    activeFormSection.value = section
    scrollFormToTop()
  }
  nextTick(() => document.getElementById(formSectionTabId(section))?.focus())
}

function handleFormSectionKeydown(event: KeyboardEvent, index: number): void {
  let nextIndex = index
  if (event.key === 'ArrowRight') nextIndex = (index + 1) % formSectionTabs.value.length
  else if (event.key === 'ArrowLeft') nextIndex = (index - 1 + formSectionTabs.value.length) % formSectionTabs.value.length
  else if (event.key === 'Home') nextIndex = 0
  else if (event.key === 'End') nextIndex = formSectionTabs.value.length - 1
  else return

  event.preventDefault()
  focusFormSection(formSectionTabs.value[nextIndex].id)
}

function toggleKeyAccordion(key: ChannelKeyEditorDraft): void {
  if (expandedChannelKeyId.value === key.localId) {
    expandedChannelKeyId.value = null
    return
  }
  expandedChannelKeyId.value = key.localId
  selectedChannelKeyId.value = key.localId
}

function removeSelectedKey(): void {
  const key = selectedChannelKey.value
  if (!key) return
  removeKey(channelKeys.value.indexOf(key))
}

function focusChannelKeyInForm(key: ChannelKeyEditorDraft): void {
  selectedChannelKeyId.value = key.localId
  expandedChannelKeyId.value = key.localId
  focusFormSection('keys')
  nextTick(() => {
    const tab = document.getElementById(channelKeyTabId(key.localId))
    tab?.scrollIntoView({ block: 'center' })
    tab?.focus({ preventScroll: true })
  })
}

watch(isMobile, (mobile) => {
  if (!selectedChannelKeyId.value) {
    selectedChannelKeyId.value = defaultChannelKey.value?.localId || null
  }
  if (mobile && !expandedChannelKeyId.value) {
    expandedChannelKeyId.value = selectedChannelKeyId.value
  }
})

function newKeyDraft(overrides: NewKeyOverrides = {}): ChannelKeyEditorDraft {
  const state: ChannelKeyState = overrides.state || (overrides.enabled === false ? 'MANUAL_DISABLED' : 'ACTIVE')
  return {
    localId: `key-${++keyDraftSeq}`,
    id: overrides.id || null,
    name: overrides.name || '',
    credential: '',
    state,
    originalState: overrides.state || state,
    priority: overrides.priority ?? 1,
    stateVersion: overrides.stateVersion ?? null,
    autoDisableReason: overrides.autoDisableReason ?? null,
    autoDisableUntil: overrides.autoDisableUntil ?? null,
    rateLimit: normalizeChannelKeyRateLimit(overrides.rateLimit),
    models: [...(overrides.models || [])],
    availableModels: [],
    manualModels: [...(overrides.models || [])],
    showManualModels: false,
    manualModelsText: '',
    manualModelsError: '',
    modelsLoading: false,
    modelsError: '',
    modelsRequestId: 0,
  }
}

function addKey() {
  const names = new Set(channelKeys.value.map((key) => key.name.trim()))
  let index = channelKeys.value.length + 1
  while (names.has(`密钥 ${index}`)) index += 1
  const key = newKeyDraft({ name: `密钥 ${index}`, priority: 1 })
  channelKeys.value.push(key)
  selectedChannelKeyId.value = key.localId
  expandedChannelKeyId.value = key.localId
  focusChannelKeyTab(key.localId)
}

function removeKey(index: number): void {
  const [removed] = channelKeys.value.splice(index, 1)
  if (selectedChannelKeyId.value === removed.localId) {
    const nextKey = channelKeys.value[Math.min(index, channelKeys.value.length - 1)]
    selectedChannelKeyId.value = nextKey?.localId ?? null
    if (expandedChannelKeyId.value === removed.localId) expandedChannelKeyId.value = nextKey?.localId ?? null
    if (nextKey) focusChannelKeyTab(nextKey.localId)
  }
}

function channelKeyTabId(localId: string): string {
  return `channel-key-tab-${localId}`
}

function focusChannelKeyTab(localId: string): void {
  nextTick(() => document.getElementById(channelKeyTabId(localId))?.focus())
}

function handleChannelKeyTabKeydown(event: KeyboardEvent, index: number): void {
  let nextIndex = index
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') nextIndex = (index + 1) % channelKeys.value.length
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') nextIndex = (index - 1 + channelKeys.value.length) % channelKeys.value.length
  else if (event.key === 'Home') nextIndex = 0
  else if (event.key === 'End') nextIndex = channelKeys.value.length - 1
  else return

  event.preventDefault()
  const nextKey = channelKeys.value[nextIndex]
  selectedChannelKeyId.value = nextKey.localId
  focusChannelKeyTab(nextKey.localId)
}

function openRateLimitEditor(key: ChannelKeyEditorDraft): void {
  rateLimitTarget.value = {
    key,
    rule: normalizeChannelKeyRateLimit(key.rateLimit),
  }
}

function applyRateLimit(rule: unknown): void {
  if (!rateLimitTarget.value) return
  rateLimitTarget.value.key.rateLimit = normalizeChannelKeyRateLimit(rule)
  rateLimitTarget.value = null
}

async function loadKeyModels(key: ChannelKeyEditorDraft): Promise<void> {
  const baseUrl = form.baseUrl.trim()
  const credential = key.credential.trim()
  const authType = form.authType
  const protocolAuthTypes = { ...form.protocolAuthTypes }
  const protocols = [...form.protocols]
  if (!baseUrl) {
    notify('请先填写基础地址', 'warning')
    return
  }
  if (!key.id && !credential) {
    notify('新建密钥需要填写上游凭据才能加载模型', 'warning')
    return
  }
  const requestId = ++key.modelsRequestId
  key.modelsLoading = true
  key.modelsError = ''
  try {
    const response = await api.discoverChannelModels({
      baseUrl,
      credential: credential || null,
      authType,
      protocolAuthTypes,
      channelId: editingId.value,
      keyId: credential ? null : (key.id || null),
      protocols,
      requestOverrides: overrideEditor.value?.getValue() || form.requestOverrides,
    })
    if (
      requestId !== key.modelsRequestId ||
      form.baseUrl.trim() !== baseUrl ||
      form.authType !== authType ||
      JSON.stringify(form.protocolAuthTypes || {}) !== JSON.stringify(protocolAuthTypes) ||
      key.credential.trim() !== credential ||
      !sameProtocolSelection(form.protocols, protocols)
    ) return
    key.availableModels = response?.data || []
    if (key.models.length === 0) {
      const authorized = key.availableModels.filter((model) => model.authorized).map((model) => model.id)
      key.models = authorized.length ? authorized : (key.id ? [] : key.availableModels.map((model) => model.id))
    }
  } catch (cause) {
    if (
      requestId === key.modelsRequestId &&
      sameProtocolSelection(form.protocols, protocols)
    ) key.modelsError = errorMessage(cause)
  } finally {
    if (requestId === key.modelsRequestId) key.modelsLoading = false
  }
}

function savedEditingChannel(): Channel | null {
  return channels.value.find((channel) => channel.id === editingId.value) || null
}

function canTestModel(modelId: string): boolean {
  const channel = savedEditingChannel()
  if (!channel?.protocols?.length) return false
  return channel.keys?.length
    ? channel.keys.some((key) => isKeyRoutable(key) && key.models.includes(modelId))
    : channel.models.includes(modelId)
}

function openModelTest(modelId: string): void {
  const channel = savedEditingChannel()
  if (!channel) {
    notify('请先保存渠道后再测试模型', 'warning')
    return
  }
  const supported = channel.keys?.length
    ? channel.keys.some((key) => isKeyRoutable(key) && key.models.includes(modelId))
    : channel.models.includes(modelId)
  if (!supported) {
    notify('该模型尚未保存到渠道，请先保存渠道后再测试', 'warning')
    return
  }
  if (!channel.protocols?.length) {
    notify('渠道尚未启用任何协议，无法测试', 'warning')
    return
  }
  testTarget.value = { channel, model: modelId }
}

const priorityEditingId = ref<string | null>(null)
const priorityDraft = ref<number | string>(1)
let prioritySaving = false
const channelUpdateSavingIds = ref<string[]>([])

function startPriorityEdit(channel: Channel): void {
  if (prioritySaving || channelUpdateSavingIds.value.includes(channel.id)) {
    notify('该渠道正在保存修改，请稍候', 'warning')
    return
  }
  priorityEditingId.value = channel.id
  priorityDraft.value = channel.priority ?? 1
  nextTick(() => {
    const input = document.querySelector('.priority-input') as HTMLInputElement | null
    input?.focus()
    input?.select()
  })
}

function cancelPriorityEdit(): void {
  priorityEditingId.value = null
}

async function commitPriorityEdit(channel: Channel): Promise<void> {
  if (prioritySaving || channelUpdateSavingIds.value.includes(channel.id)) return
  if (priorityEditingId.value !== channel.id) return
  const next = Number(priorityDraft.value)
  priorityEditingId.value = null
  if (!Number.isInteger(next) || next < 1 || next > 1000) {
    notify('优先级必须是 1..1000 的整数', 'warning')
    return
  }
  if (next === (channel.priority ?? 1)) return
  prioritySaving = true
  channelUpdateSavingIds.value = [...channelUpdateSavingIds.value, channel.id]
  try {
    await api.updateChannel(channel.id, buildPriorityUpdatePayload(channel, next))
    listRevision++
    loading.value = false
    channel.priority = next
    notify('渠道优先级已更新', 'success')
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    await load()
  } finally {
    prioritySaving = false
    channelUpdateSavingIds.value = channelUpdateSavingIds.value.filter((id) => id !== channel.id)
  }
}

async function toggleChannelEnabled(channel: Channel): Promise<void> {
  if (channelUpdateSavingIds.value.includes(channel.id)) return
  channelUpdateSavingIds.value = [...channelUpdateSavingIds.value, channel.id]
  channelToggleSavingIds.value = [...channelToggleSavingIds.value, channel.id]
  const enabled = !channel.enabled
  try {
    const updated = await api.updateChannel(channel.id, {
      ...buildPriorityUpdatePayload(channel, channel.priority ?? 1),
      enabled,
    })
    listRevision++
    loading.value = false
    const index = channels.value.findIndex((item) => item.id === channel.id)
    if (index >= 0) channels.value[index] = updated
    clearUpstreamModelCache(channel.id)
    if (!listDisposed) void loadHealth()
    notify(`渠道「${channel.name}」已${enabled ? '启用' : '停用'}`, 'success')
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    await load()
  } finally {
    channelUpdateSavingIds.value = channelUpdateSavingIds.value.filter((id) => id !== channel.id)
    channelToggleSavingIds.value = channelToggleSavingIds.value.filter((id) => id !== channel.id)
  }
}

async function load(): Promise<boolean> {
  const request = ++listRevision
  loading.value = true
  error.value = ''
  try {
    const [channelsData, pricesData, defaultPricesData] = await Promise.all([
      api.listChannels(),
      api.listModelPrices(),
      api.listModelDefaultPrices(),
    ])
    if (request !== listRevision) return false
    channels.value = channelsData ?? []
    prices.value = pricesData ?? []
    defaultPrices.value = defaultPricesData ?? []
    void loadHealth()
    void api.listAuditLogs({ limit: 200, action: 'channel.probe_protocols' })
      .then((entries) => { if (request === listRevision) recentProbes.value = entries ?? [] })
      .catch(() => { if (request === listRevision) recentProbes.value = [] })
  } catch (cause) {
    if (request === listRevision) error.value = errorMessage(cause)
  } finally {
    if (request === listRevision) loading.value = false
  }
  return request === listRevision
}

function openPrice(channel: Channel, upstreamModel: string | null = null): void {
  const existingPrice = upstreamModel
    ? findModelPrice(prices.value, channel.id, upstreamModel)
    : null
  priceEditor.value = {
    channels: channels.value,
    price: existingPrice,
    initialTarget: {
      channelId: channel.id,
      modelId: upstreamModel || '',
    },
  }
}

function priceSummary(channel: Channel): { unpriced: string[]; count: number } {
  return priceSummaries.value.get(channel.id) || { unpriced: [], count: 0 }
}

function unpricedDisplayModels(channel: Channel, item: ChannelDisplayModel): string[] {
  const unpriced = priceSummary(channel).unpriced
  return (item.upstreamModels || []).filter((model) => unpriced.includes(String(model).trim()))
}

function priceSourceForDisplay(channel: Channel, item: ChannelDisplayModel): 'channel' | 'default' | 'mixed' | 'none' {
  return resolveDisplayPriceSource(channel, item.upstreamModels, prices.value, defaultPrices.value)
}

function priceSourceLabel(source: string): string {
  if (source === 'channel') return '渠道价'
  if (source === 'default') return '默认价'
  if (source === 'mixed') return '混合价'
  return '未定价'
}

function modelTitle(channel: Channel, item: ChannelDisplayModel): string {
  const base = item.title || item.displayName
  const unpriced = unpricedDisplayModels(channel, item)
  return unpriced.length ? `${base}；未配置单价: ${unpriced.join('、')}` : base
}

function configureChannelPrices(channel: Channel): void {
  openPrice(channel, priceSummary(channel).unpriced[0] || null)
}

function updatePricePrompt(channel: Channel | null): number {
  const count = channel ? priceSummary(channel).count : 0
  pricePromptChannelId.value = channel && count > 0 ? channel.id : null
  return count
}

function dismissPricePrompt(): void {
  pricePromptChannelId.value = null
}

function configurePromptPrices() {
  const channel = channels.value.find((item) => item.id === pricePromptChannelId.value)
  if (!channel) {
    pricePromptChannelId.value = null
    return
  }
  configureChannelPrices(channel)
}

async function onPriceSaved() {
  priceEditor.value = null
  notify('计费单价已保存')
  try {
    prices.value = await api.listModelPrices()
  } catch {
    notify('单价已保存，但刷新列表失败', 'warning')
  }
}

async function onPriceChanged() {
  try {
    prices.value = await api.listModelPrices()
  } catch {
    notify('单价已删除，但刷新列表失败', 'warning')
  }
}

async function onDefaultPricesSaved() {
  try {
    const [pricesData, defaultPricesData] = await Promise.all([
      api.listModelPrices(),
      api.listModelDefaultPrices(),
    ])
    prices.value = pricesData
    defaultPrices.value = defaultPricesData
  } catch {
    notify('默认价已变更，但刷新列表失败', 'warning')
  }
}

function openCreate() {
  editingId.value = null
  activeFormSection.value = 'basic'
  mappingSearch.value = ''
  showAllMappings.value = true
  probeRequests.invalidate()
  probing.value = false
  probeResults.value = []
  probeAddedProtocols = []
  modelRequests.invalidate()
  channelKeys.value = [newKeyDraft({ name: '默认密钥', priority: 1 })]
  selectedChannelKeyId.value = channelKeys.value[0].localId
  expandedChannelKeyId.value = channelKeys.value[0].localId
  clearMappings()
  Object.assign(form, {
    name: '',
    baseUrl: '',
    authType: 'bearer',
    protocolAuthTypes: {},
    protocols: ['chat_completions'],
    enabled: true,
    priority: 1,
    requestOverrides: normalizeRequestOverrides(),
  })
  showForm.value = true
  formSnapshot.value = channelFormSnapshot(false)
}

function openEdit(channel: Channel): void {
  editingId.value = channel.id
  activeFormSection.value = 'basic'
  mappingSearch.value = ''
  showAllMappings.value = false
  probeRequests.invalidate()
  probing.value = false
  probeResults.value = []
  probeAddedProtocols = []
  modelRequests.invalidate()
  const savedKeys = channel.keys?.length
    ? channel.keys
    : [{ name: '默认密钥', state: 'ACTIVE' as const, priority: 1, models: channel.models || [] }]
  channelKeys.value = savedKeys.map((key) => newKeyDraft(key))
  const initialKey = isMobile.value ? defaultChannelKey.value : channelKeys.value[0]
  selectedChannelKeyId.value = initialKey?.localId || null
  expandedChannelKeyId.value = isMobile.value ? initialKey?.localId || null : null
  clearMappings()
  ;(channel.modelMappings || []).forEach((mapping) => {
    originalMappings[mapping.upstreamModel] = {
      canonicalModelId: mapping.canonicalModelId || null,
      mappingSource: mapping.mappingSource || null,
    }
    if (mapping.canonicalModelId) {
      canonicalModels[mapping.upstreamModel] = mapping.canonicalModelId
    }
  })
  showAllMappings.value = mappingCount.value === 0
  Object.assign(form, {
    name: channel.name,
    baseUrl: channel.baseUrl,
    authType: channel.authType || 'bearer',
    protocolAuthTypes: { ...(channel.protocolAuthTypes || {}) },
    protocols: channel.protocols?.length ? [...channel.protocols] : ['chat_completions'],
    enabled: channel.enabled,
    priority: channel.priority ?? 1,
    requestOverrides: channel.requestOverrides || {},
  })
  showForm.value = true
  formSnapshot.value = channelFormSnapshot(false)
}

function closeChannelForm() {
  if (testTarget.value || showTestSettings.value || showRequestSettings.value || showAutoDisableSettings.value || rateLimitTarget.value) return
  if (isFormDirty.value && !window.confirm('当前渠道配置尚未保存，确定关闭吗？')) return
  showForm.value = false
  formSnapshot.value = ''
}

function onRequestSettingsSaved() {
  channels.value.forEach((channel) => clearUpstreamModelCache(channel.id))
  overrideRevision.value += 1
}

function clearMapping(modelId: string): void {
  canonicalModels[modelId] = ''
}

function copyUpstreamToCanonical(modelId: string): void {
  canonicalModels[modelId] = modelId
  nextTick(() => {
    const el = document.getElementById(`canonical-input-${modelId}`) as HTMLInputElement | null
    if (el) {
      el.focus()
      el.select()
    }
  })
}

function clearMappings() {
  Object.keys(canonicalModels).forEach((key) => delete canonicalModels[key])
  Object.keys(originalMappings).forEach((key) => delete originalMappings[key])
}

function probeState(result: ProtocolProbeResult): { label: string; className: string } {
  if (result.supported) return { label: '支持', className: 'success' }
  if (result.statusCode === 404 || result.statusCode === 405) return { label: '不支持', className: 'neutral' }
  return { label: '未知', className: 'warning' }
}

async function probeProtocols(): Promise<void> {
  const channelId = editingId.value
  if (!channelId) {
    notify('请先保存渠道后再探测协议', 'error')
    return
  }
  const probeBaseUrl = form.baseUrl.trim()
  const probeCredential = channelKeys.value.map((key) => key.credential).join('\u0000')
  const probeAuthType = form.authType
  const probeProtocolAuthTypes = { ...form.protocolAuthTypes }
  const savedChannel = channels.value.find((channel) => channel.id === channelId)
  if (!canProbeSavedChannel(savedChannel, {
    baseUrl: probeBaseUrl,
    credential: probeCredential,
    authType: probeAuthType,
    protocolAuthTypes: probeProtocolAuthTypes,
  })) {
    notify('请先保存基础地址、凭据或鉴权方式，再探测协议', 'error')
    return
  }
  const requestId = probeRequests.begin()
  probing.value = true
  probeResults.value = []
  try {
    const response = await api.probeChannelProtocols(channelId)
    if (
      !probeRequests.isCurrent(requestId) ||
      editingId.value !== channelId ||
      form.baseUrl.trim() !== probeBaseUrl ||
      channelKeys.value.map((key) => key.credential).join('\u0000') !== probeCredential ||
      form.authType !== probeAuthType ||
      JSON.stringify(form.protocolAuthTypes || {}) !== JSON.stringify(probeProtocolAuthTypes)
    ) {
      return
    }
    probeResults.value = response?.results || []
    const supported = probeResults.value.filter((item) => item.supported).map((item) => item.protocol)
    if (supported.length > 0) {
      const manuallySelected = [...form.protocols]
      form.protocols = mergeProbedProtocols(form.protocols, supported)
      probeAddedProtocols = supported.filter((protocol) => !manuallySelected.includes(protocol))
    }
    void api.listAuditLogs({ limit: 200, action: 'channel.probe_protocols' })
      .then((entries) => { recentProbes.value = entries ?? [] })
      .catch(() => { recentProbes.value = [] })
    notify(
      supported.length > 0 ? `探测到：${supported.join(' / ')}` : '未探测到可用协议',
      supported.length > 0 ? 'success' : 'error',
    )
  } catch (cause) {
    if (probeRequests.isCurrent(requestId) && editingId.value === channelId) {
      notify(errorMessage(cause), 'error')
    }
  } finally {
    if (probeRequests.isCurrent(requestId)) probing.value = false
  }
}

async function save(): Promise<void> {
  if (saving.value) return
  if (!form.name.trim() || !form.baseUrl.trim()) {
    focusFormSection('basic')
    notify('请填写渠道名称和基础地址', 'error')
    return
  }
  if (form.protocols.length === 0) {
    focusFormSection('protocols')
    notify('至少选择一个支持协议', 'error')
    return
  }
  const names = new Set()
  for (const key of channelKeys.value) {
    const name = key.name.trim()
    if (!name) {
      focusChannelKeyInForm(key)
      notify('密钥名称不能为空', 'error')
      return
    }
    if (names.has(name)) {
      focusChannelKeyInForm(key)
      notify(`密钥名称不能重复：${name}`, 'error')
      return
    }
    names.add(name)
    if (!Number.isInteger(Number(key.priority)) || Number(key.priority) < 1 || Number(key.priority) > 1000) {
      focusChannelKeyInForm(key)
      notify('密钥优先级必须是 1..1000 的整数', 'error')
      return
    }
    if (!key.id && !key.credential.trim()) {
      focusChannelKeyInForm(key)
      notify(`新建密钥「${name}」必须提供上游凭据`, 'error')
      return
    }
    if (!key.models.length) {
      focusChannelKeyInForm(key)
      notify(`密钥「${name}」至少需要授权一个模型`, 'error')
      return
    }
    try {
      validateChannelKeyRateLimit(key.rateLimit)
    } catch (cause) {
      focusChannelKeyInForm(key)
      notify(`密钥「${name}」限流规则无效：${errorMessage(cause)}`, 'error')
      return
    }
  }
  if (authorizedModels.value.length === 0) {
    focusFormSection('keys')
    notify('请至少授权一个上游模型', 'error')
    return
  }

  saving.value = true
  try {
    if (!overrideEditor.value) {
      notify('请求配置尚未加载完成，请稍后重试', 'error')
      return
    }
    form.requestOverrides = overrideEditor.value.getValue()
    const payload = buildChannelPayload(
      form,
      authorizedModels.value,
      canonicalModels,
      originalMappings,
      channelKeys.value,
    )
    const currentEditingId = editingId.value
    const wasEditing = currentEditingId !== null
    const saved = wasEditing
      ? await api.updateChannel(currentEditingId, payload)
      : await api.createChannel(payload)
    if (wasEditing) clearUpstreamModelCache(currentEditingId)
    showForm.value = false
    formSnapshot.value = ''
    probeRequests.invalidate()
    probeResults.value = []
    probeAddedProtocols = []
    await refreshUntilCurrent(load, () => listDisposed)
    if (listDisposed) return
    const savedChannel = resolveSavedChannel(channels.value, saved, currentEditingId, form.name)
    const unpricedCount = updatePricePrompt(savedChannel)
    if (unpricedCount > 0) {
      notify(`渠道已保存，还有 ${unpricedCount} 个上游模型未配置单价`, 'warning')
    } else {
      notify(wasEditing ? '渠道已更新' : '渠道已创建')
    }
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    if (cause instanceof ApiError && cause.code === 'channel_key_state_changed') await refreshUntilCurrent(load, () => listDisposed)
  } finally {
    saving.value = false
  }
}

async function remove(channel: Channel): Promise<void> {
  if (!window.confirm(`确定删除渠道「${channel.name}」？`)) return
  try {
    await api.deleteChannel(channel.id)
    clearUpstreamModelCache(channel.id)
    if (pricePromptChannelId.value === channel.id) pricePromptChannelId.value = null
    notify('渠道已删除')
    await load()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  }
}

const configActions = [
  { id: 'request', label: '上游请求配置', icon: 'bolt' },
  { id: 'disable', label: '密钥自动禁用', icon: 'shield' },
  { id: 'test', label: '全局测试配置', icon: 'bolt' },
  { id: 'prices', label: '模型默认价', icon: 'price' },
]
function openConfig(id: string) {
  if (id === 'request') showRequestSettings.value = true
  if (id === 'disable') showAutoDisableSettings.value = true
  if (id === 'test') showTestSettings.value = true
  if (id === 'prices') showDefaultPrices.value = true
}
function channelAction(id: string, channel: Channel) {
  if (id === 'prices') openPrice(channel)
  if (id === 'delete') remove(channel)
}

useAutoRefresh(healthRefreshMs, () => { void loadHealth() }, () => loading.value || healthLoading.value || document.hidden, { refreshOnVisible: true })
useAutoRefresh(occupancyRefreshMs, () => { void loadOccupancy() }, () => loading.value || document.hidden, { refreshOnVisible: true })
onMounted(() => {
  void load()
  void loadOccupancy()
})
</script>

<template>
  <section class="page-stack page-compact">
    <div class="channel-page-head">
      <dl class="channel-summary" aria-label="渠道概况">
        <div><dt>渠道总数</dt><dd>{{ channels.length }}</dd></div>
        <div><dt>启用中</dt><dd class="text-success">{{ enabledCount }}</dd></div>
      </dl>
      <div class="section-head-actions channel-page-actions">
        <ActionMenu label="配置" icon="settings" :items="configActions" @select="openConfig" />
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建渠道
        </button>
      </div>
    </div>

    <ErrorState v-if="error" :message="error" :busy="loading" @retry="load" />

    <div v-if="pricePrompt" class="callout warning channel-price-callout">
      <div>
        <strong>渠道「{{ pricePrompt.channelName }}」还有 {{ pricePrompt.count }} 个上游模型未配置单价</strong>
        <p>这些请求仍会正常转发，但不会计入费用。</p>
      </div>
      <div class="channel-price-callout-actions">
        <button type="button" class="button ghost mini" @click="dismissPricePrompt">稍后配置</button>
        <button type="button" class="button primary mini" @click="configurePromptPrices">立即配置</button>
      </div>
    </div>

    <div class="panel table-panel">
      <div v-if="channels.length" class="channel-list-toolbar">
        <label class="search-box channel-name-search">
          <Icon name="search" :size="15" />
          <input v-model="channelNameFilter" type="search" aria-label="按渠道名称筛选" placeholder="按名称筛选渠道" autocomplete="off" />
        </label>
        <div class="channel-list-filters" role="group" aria-label="渠道筛选">
          <span class="channel-sort-note">数字越小，路由优先级越高</span>
          <FilterSelect v-model="channelStatusFilter" label="状态" default-value="all" :options="channelStatusOptions.map(option => ({ value: option.id, label: option.label + '（' + option.count + '）' }))" />
          <FilterSelect v-model="channelModelFilter" label="模型" searchable :options="[{ value: '', label: '全部模型' }, ...channelModelOptions.map(model => ({ value: model, label: model }))]" />
          <button v-if="hasChannelFilters" type="button" class="filter-reset" @click="channelStatusFilter = 'all'; channelModelFilter = ''; channelNameFilter = ''">清除筛选</button>
        </div>
      </div>
      <ErrorState v-if="healthError" title="健康度读取失败" :message="healthError" :busy="healthLoading" @retry="loadHealth" />
      <p v-if="healthReport?.indexing" class="muted small health-indexing-note" role="status">正在分批补齐历史健康度；完成后会自动更新。</p>
      <TablePlaceholder
        v-if="loading || channels.length === 0"
        :loading="loading"
        loading-text="正在读取渠道…"
        :empty="channels.length === 0"
        icon="channels"
        title="还没有渠道"
        desc="先接入一个支持所需协议的上游，授权模型并对外提供服务。"
        action-text="新建渠道"
        @action="openCreate"
      />
      <div v-else v-scroll-hint class="table-scroll table-scroll-paged channel-table-view">
        <table class="data-table channels-table">
          <thead>
            <tr>
              <th class="channel-name-col">名称</th>
              <th :aria-sort="prioritySortDirection === 'asc' ? 'ascending' : 'descending'">
                <button
                  type="button"
                  class="channel-priority-sort"
                  :aria-label="prioritySortDirection === 'asc' ? '优先级升序，点击切换为降序' : '优先级降序，点击切换为升序'"
                  :title="prioritySortDirection === 'asc' ? '点击按优先级降序排列；双击数值可修改优先级' : '点击按优先级升序排列；双击数值可修改优先级'"
                  @click="prioritySortDirection = prioritySortDirection === 'asc' ? 'desc' : 'asc'"
                >
                  优先级 <span class="channel-sort-arrow" aria-hidden="true">{{ prioritySortDirection === 'asc' ? '↑' : '↓' }}</span>
                </button>
              </th>
              <th class="channel-protocols-col">协议</th>
              <th>授权模型</th>
              <th>状态 / 健康</th>
              <th>更新时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!displayedChannels.length">
              <td colspan="7" class="channel-filter-empty muted">当前筛选条件下没有渠道</td>
            </tr>
            <tr v-for="channel in displayedChannels" :key="channel.id">
              <td class="channel-name-col">
                <a
                  v-if="channelLandingUrl(channel.baseUrl)"
                  class="primary-cell channel-name-link"
                  :href="channelLandingUrl(channel.baseUrl) || undefined"
                  :title="`在新窗口打开上游地址：${channelLandingUrl(channel.baseUrl)}`"
                  target="_blank"
                  rel="noopener noreferrer"
                >{{ channel.name }}</a>
                <div v-else class="primary-cell" :title="channel.name">{{ channel.name }}</div>
                <div class="channel-id-cell">
                  <span class="mono muted small channel-id-text" :title="channel.id">{{ channel.id }}</span>
                  <CopyButton :text="channel.id" title="复制渠道 ID" variant="list" :size="12" />
                </div>
                <div class="muted small">
                  {{ channel.keys?.length || 0 }} 个密钥
                  <template v-if="channel.keys?.length">· {{ channel.keys.filter((key) => isKeyRoutable(key)).length }} 个启用</template>
                </div>
                <div v-if="channel.keys?.some((key) => key.state === 'AUTO_PERMANENT' || key.state === 'AUTO_TEMPORARY')" class="text-danger small">
                  {{ channel.keys.filter((key) => key.state === 'AUTO_PERMANENT' || key.state === 'AUTO_TEMPORARY').length }} 个自动禁用
                </div>
              </td>
              <td
                class="priority-cell"
                tabindex="0"
                role="button"
                aria-label="双击或按 Enter 编辑渠道优先级"
                title="双击编辑优先级"
                @dblclick="startPriorityEdit(channel)"
                @keyup.enter.self="startPriorityEdit(channel)"
              >
                <input
                  v-if="priorityEditingId === channel.id"
                  v-model.number="priorityDraft"
                  class="priority-input"
                  aria-label="渠道优先级，数值越小越优先"
                  title="数值越小越优先；Enter 保存，Esc 取消"
                  type="number"
                  min="1"
                  max="1000"
                  step="1"
                  @keyup.enter.stop="commitPriorityEdit(channel)"
                  @dblclick.stop
                  @keyup.esc="cancelPriorityEdit"
                  @blur="commitPriorityEdit(channel)"
                />
                <span v-else class="badge neutral">{{ channel.priority ?? 1 }}</span>
              </td>
              <td class="channel-protocols-col">
                <ChannelProtocols :protocols="channel.protocols" />
              </td>
              <td>
                <div v-if="channel.models?.length" class="channel-model-list">
                  <span
                    v-for="item in channelDisplayModels(channel)"
                    :key="item.displayName"
                    class="channel-model-entry"
                  >
                    <span class="badge info channel-model-name" :title="modelTitle(channel, item)">
                      {{ item.displayName }}
                    </span>
                    <span
                      v-if="unpricedDisplayModels(channel, item).length"
                      class="channel-model-price-flag"
                      title="该模型尚未配置计费单价"
                    >
                      未定价
                    </span>
                    <span
                      v-else
                      class="channel-model-price-source"
                      :class="`is-${priceSourceForDisplay(channel, item)}`"
                      :title="`当前价格来源：${priceSourceLabel(priceSourceForDisplay(channel, item))}`"
                    >
                      {{ priceSourceLabel(priceSourceForDisplay(channel, item)) }}
                    </span>
                  </span>
                </div>
                <span v-else class="badge warning">未授权模型</span>
                <button
                  v-if="priceSummary(channel).count > 0"
                  type="button"
                  class="channel-price-warning"
                  title="点击配置模型计费单价"
                  @click="configureChannelPrices(channel)"
                >
                  <Icon name="price" :size="12" />
                  {{ priceSummary(channel).count }} 个上游模型未定价
                </button>
              </td>
              <td class="channel-status-cell">
                <div class="channel-status-stack">
                  <button
                    type="button"
                    class="channel-status-toggle"
                    role="switch"
                    :class="channel.enabled ? 'is-enabled' : 'is-disabled'"
                    :aria-checked="channel.enabled"
                    :aria-label="channel.enabled ? '渠道已启用' : '渠道已停用'"
                    :title="channel.enabled ? '点击停用该渠道' : '点击启用该渠道'"
                    :disabled="channelUpdateSavingIds.includes(channel.id)"
                    @click="toggleChannelEnabled(channel)"
                  >
                    <span class="channel-status-track" aria-hidden="true"><span></span></span>
                    <span class="channel-status-label">{{ channel.enabled ? '启用' : '停用' }}</span>
                  </button>
                  <button type="button" class="channel-observation" :title="channelHealthTitle(channel)" :aria-label="channelHealthTitle(channel)" @click="healthTarget = channel">
                    <span class="health-status-icon" :data-state="channelHealthState(channel)" aria-hidden="true">{{ healthSymbol(channelHealthState(channel)) }}</span>
                    <span><strong>{{ channelSuccessRateText(channel) }}</strong><small>{{ healthByChannel.get(channel.id)?.metrics.attempts ?? 0 }} 次 · {{ HEALTH_LABELS[channelHealthState(channel)] }}</small></span>
                  </button>
                  <div v-if="healthByChannel.get(channel.id)?.metrics.p95FirstTokenMs != null" class="muted small health-list-latency">P95 首 token {{ formatDuration(healthByChannel.get(channel.id)?.metrics.p95FirstTokenMs) }}</div>
                  <div
                    class="channel-occupancy"
                    :class="{ 'is-active': channelConcurrent(channel.id) > 0 }"
                    :title="channelOccupancyTitle(channel)"
                  >
                    <span class="channel-occupancy-dot" aria-hidden="true"></span>
                    在途 {{ channelConcurrent(channel.id) }}
                  </div>
                  <button v-if="channelCooldownText(channel)" type="button" class="channel-cooldown muted small" :title="channelCooldownTitle(channel)" @click="healthTarget = channel">
                    {{ channelCooldownText(channel) }}
                  </button>
                  <div v-if="recentProbe(channel)" class="channel-health-probe muted small" :title="`最近探测：${formatTime(recentProbe(channel)!.createdAt)}`">
                    探测 {{ formatTime(recentProbe(channel)!.createdAt) }}
                  </div>
                </div>
              </td>
              <td class="muted">{{ formatTime(channel.updatedAt) }}</td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="button ghost mini" :title="'按密钥查询 ' + channel.name + ' 的账户额度'" @click="quotaTarget = channel">额度</button>
                  <button type="button" class="icon-button" title="编辑渠道" :aria-label="'编辑渠道 ' + channel.name" @click="openEdit(channel)">
                    <Icon name="edit" :size="16" />
                  </button>
                  <ActionMenu :label="channel.name + '的更多操作'" compact :items="[{ id: 'prices', label: '配置模型单价', icon: 'price' }, { id: 'delete', label: '删除渠道', icon: 'trash', danger: true }]" @select="channelAction($event, channel)" />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!loading && displayedChannels.length" class="channel-mobile-list">
        <article v-for="channel in displayedChannels" :key="channel.id" class="channel-mobile-card">
          <header class="channel-mobile-head">
            <div class="channel-mobile-identity">
              <a
                v-if="channelLandingUrl(channel.baseUrl)"
                class="channel-mobile-name"
                :href="channelLandingUrl(channel.baseUrl) || undefined"
                :title="`在新窗口打开上游地址：${channelLandingUrl(channel.baseUrl)}`"
                target="_blank"
                rel="noopener noreferrer"
              >{{ channel.name }}</a>
              <div v-else class="channel-mobile-name" :title="channel.name">{{ channel.name }}</div>
              <div class="channel-id-cell channel-mobile-id">
                <span class="mono muted small channel-id-text" :title="channel.id">{{ channel.id }}</span>
                <CopyButton :text="channel.id" title="复制渠道 ID" variant="list" :size="12" />
              </div>
              <div class="muted small channel-mobile-keys">
                {{ channel.keys?.length || 0 }} 个密钥
                <template v-if="channel.keys?.length">· {{ channel.keys.filter((key) => isKeyRoutable(key)).length }} 个启用</template>
                <template v-if="channelAutoDisabledCount(channel)">· {{ channelAutoDisabledCount(channel) }} 个自动禁用</template>
              </div>
            </div>
            <button
              type="button"
              class="channel-status-toggle"
              role="switch"
              :class="channel.enabled ? 'is-enabled' : 'is-disabled'"
              :aria-checked="channel.enabled"
              :aria-label="channel.enabled ? '渠道已启用' : '渠道已停用'"
              :title="channel.enabled ? '点击停用该渠道' : '点击启用该渠道'"
              :disabled="channelUpdateSavingIds.includes(channel.id)"
              @click="toggleChannelEnabled(channel)"
            >
              <span class="channel-status-track" aria-hidden="true"><span></span></span>
              <span class="channel-status-label">{{ channel.enabled ? '启用' : '停用' }}</span>
            </button>
          </header>

          <div class="channel-mobile-status">
            <div class="channel-mobile-status-main">
              <button type="button" class="channel-observation" :title="channelHealthTitle(channel)" :aria-label="channelHealthTitle(channel)" @click="healthTarget = channel">
                <span class="health-status-icon" :data-state="channelHealthState(channel)" aria-hidden="true">{{ healthSymbol(channelHealthState(channel)) }}</span>
                <span><strong>{{ channelSuccessRateText(channel) }}</strong><small>{{ channelHealthAttempts(channel) }} 次 · {{ HEALTH_LABELS[channelHealthState(channel)] }}</small></span>
              </button>
              <div
                class="channel-occupancy"
                :class="{ 'is-active': channelConcurrent(channel.id) > 0 }"
                :title="channelOccupancyTitle(channel)"
              >
                <span class="channel-occupancy-dot" aria-hidden="true"></span>
                在途 {{ channelConcurrent(channel.id) }}
              </div>
            </div>
            <button v-if="channelCooldownText(channel)" type="button" class="channel-cooldown muted small" :title="channelCooldownTitle(channel)" @click="healthTarget = channel">
              {{ channelCooldownText(channel) }}
            </button>
          </div>

          <div class="channel-mobile-meta">
            <span class="channel-mobile-priority" :title="`优先级 ${channel.priority ?? 1}`">P{{ channel.priority ?? 1 }}</span>
            <template v-if="channel.models?.length">
              <span v-for="item in channelDisplayModels(channel).slice(0, 1)" :key="item.displayName" class="badge info channel-model-name" :title="modelTitle(channel, item)">
                {{ item.displayName }}
              </span>
              <span v-if="channelDisplayModels(channel).length > 1" class="badge neutral">+{{ channelDisplayModels(channel).length - 1 }}</span>
              <span v-if="priceSummary(channel).count" class="channel-model-price-flag">{{ priceSummary(channel).count }} 个未定价</span>
            </template>
            <span v-else class="badge warning">未授权模型</span>
            <ChannelProtocols :protocols="channel.protocols" :size="20" />
          </div>

          <div class="channel-mobile-actions">
            <button type="button" class="button ghost mini" :title="'按密钥查询 ' + channel.name + ' 的账户额度'" @click="quotaTarget = channel">额度</button>
            <button type="button" class="icon-button" title="编辑渠道" :aria-label="'编辑渠道 ' + channel.name" @click="openEdit(channel)">
              <Icon name="edit" :size="16" />
            </button>
            <ActionMenu :label="channel.name + '的更多操作'" compact :items="[{ id: 'prices', label: '配置模型单价', icon: 'price' }, { id: 'delete', label: '删除渠道', icon: 'trash', danger: true }]" @select="channelAction($event, channel)" />
            <button
              type="button"
              class="button ghost mini channel-mobile-details-toggle"
              :aria-expanded="mobileDetailsOpen(channel.id)"
              :aria-controls="mobileDetailsOpen(channel.id) ? `channel-mobile-details-${channel.id}` : undefined"
              @click="toggleMobileDetails(channel.id)"
            >
              {{ mobileDetailsOpen(channel.id) ? '收起' : '详情' }}
              <Icon :name="mobileDetailsOpen(channel.id) ? 'up' : 'chevron'" :size="14" />
            </button>
          </div>

          <div v-if="mobileDetailsOpen(channel.id)" :id="`channel-mobile-details-${channel.id}`" class="channel-mobile-details">
            <div class="channel-mobile-detail-grid">
              <div class="channel-mobile-detail-item">
                <span>P95 首 token</span>
                <strong>{{ channelP95Text(channel) }}</strong>
              </div>
              <div class="channel-mobile-detail-item">
                <span>最近探测</span>
                <strong>{{ channelProbeText(channel) }}</strong>
              </div>
              <div class="channel-mobile-detail-item">
                <span>更新时间</span>
                <strong>{{ formatTime(channel.updatedAt) }}</strong>
              </div>
              <div class="channel-mobile-detail-item">
                <span>优先级</span>
                <strong>{{ channel.priority ?? 1 }}</strong>
              </div>
            </div>

            <div class="channel-mobile-detail-section">
              <span class="channel-mobile-detail-label">全部模型 · {{ channelDisplayModels(channel).length }}</span>
              <div class="channel-mobile-detail-models">
                <span v-for="item in channelDisplayModels(channel)" :key="item.displayName" class="channel-mobile-detail-model">
                  <span class="badge info channel-model-name" :title="modelTitle(channel, item)">{{ item.displayName }}</span>
                  <span v-if="unpricedDisplayModels(channel, item).length" class="channel-model-price-flag">未定价</span>
                  <span v-else class="channel-model-price-source" :class="`is-${priceSourceForDisplay(channel, item)}`">{{ priceSourceLabel(priceSourceForDisplay(channel, item)) }}</span>
                </span>
                <span v-if="!channelDisplayModels(channel).length" class="muted small">未授权模型</span>
              </div>
            </div>

            <div class="channel-mobile-detail-section">
              <span class="channel-mobile-detail-label">密钥 · {{ channel.keys?.length || 0 }}</span>
              <div class="channel-mobile-detail-keys">
                <div v-for="key in (channel.keys ?? [])" :key="key.id" class="channel-mobile-key">
                  <span class="channel-mobile-key-name" :title="key.name">{{ key.name || '未命名密钥' }}</span>
                  <span class="badge" :class="keyStateBadge(key.state).class">{{ keyStateBadge(key.state).label }}</span>
                  <span class="muted small channel-mobile-key-meta">{{ channelKeyRateLimitSummary(key.rateLimit) }}</span>
                </div>
                <span v-if="!channel.keys?.length" class="muted small">没有密钥</span>
              </div>
            </div>
          </div>
        </article>
      </div>
      <p v-else-if="!loading && channels.length" class="channel-mobile-empty muted">当前筛选条件下没有渠道</p>
    </div>

    <ChannelHealthModal v-if="healthTarget" :key="healthTarget.id" :channel="healthTarget" :initial-window="healthWindow" @close="onCloseHealthModal" />
    <ChannelQuotaModal v-if="quotaTarget" :key="quotaTarget.id" :channel="quotaTarget" @close="quotaTarget = null" @saved="onQuotaSaved" />

    <Modal v-if="showForm" :title="editingId ? '编辑渠道' : '新建渠道'" wide roomy mobile-fullscreen @close="closeChannelForm">
      <template #subhead>
        <nav class="channel-form-tabs" role="tablist" aria-label="渠道配置分区">
          <button
            v-for="(section, index) in formSectionTabs"
            :id="formSectionTabId(section.id)"
            :key="section.id"
            type="button"
            class="channel-form-tab"
            :class="{ 'is-active': activeFormSection === section.id }"
            role="tab"
            :aria-selected="activeFormSection === section.id"
            :aria-controls="'channel-form-panel-' + section.id"
            :tabindex="activeFormSection === section.id ? 0 : -1"
            @click="setActiveFormSection(section.id)"
            @keydown="handleFormSectionKeydown($event, index)"
          >
            <span>{{ section.label }}</span>
            <span v-if="section.count" class="channel-form-tab-count">{{ section.count }}</span>
          </button>
        </nav>
      </template>
      <form class="channel-form" @submit.prevent="save">
        <div class="channel-form-sections">
          <section
            v-show="activeFormSection === 'basic'"
            id="channel-form-panel-basic"
            class="channel-form-section"
            role="tabpanel"
            aria-labelledby="channel-form-tab-basic"
          >
            <div class="channel-form-section-head">
              <div><h3>基础连接</h3><p>名称、地址和默认鉴权方式。</p></div>
            </div>
            <div class="channel-form-row channel-form-row-two">
              <label class="field">
                <span>渠道名称</span>
                <input v-model="form.name" placeholder="例如 openai-primary" autocomplete="off" />
                <small>网关内唯一名称。</small>
              </label>
              <label class="field">
                <span>上游鉴权</span>
                <select v-model="form.authType">
                  <option value="bearer">Bearer token</option>
                  <option value="anthropic">Anthropic API key</option>
                  <option value="google">Google API key</option>
                </select>
                <small>未单独配置协议时使用；Anthropic/Google 使用专用请求头。</small>
              </label>
            </div>
            <label class="field">
              <span>基础地址</span>
              <input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" autocomplete="off" />
              <small>按协议自动追加端点；模型可读取或手动添加。</small>
            </label>
          </section>

          <section
            v-show="activeFormSection === 'protocols'"
            id="channel-form-panel-protocols"
            class="channel-form-section"
            role="tabpanel"
            aria-labelledby="channel-form-tab-protocols"
          >
            <div class="channel-form-section-head">
              <div><h3>协议与请求</h3><p>选择协议、鉴权方式和转换顺序。</p></div>
              <div class="channel-form-section-actions">
                <span class="badge neutral">{{ form.protocols.length }} 个启用</span>
              </div>
            </div>

            <div class="protocol-table">
              <div class="protocol-table-head" aria-hidden="true">
                <span>启用</span>
                <span>协议</span>
                <span>鉴权方式</span>
                <span>顺序</span>
                <span>探测</span>
              </div>
              <div
                v-for="row in protocolRows"
                :key="row.value"
                class="protocol-row"
                :class="{ 'is-disabled': !row.enabled }"
              >
                <label class="protocol-check">
                  <input
                    v-model="form.protocols"
                    type="checkbox"
                    :value="row.value"
                    :aria-label="'启用 ' + row.label"
                    @change="keepProtocolAsManual(row.value)"
                  />
                </label>
                <div class="protocol-name">
                  <strong>{{ row.label }}</strong>
                </div>
                <select
                  :value="form.protocolAuthTypes[row.value] || ''"
                  :disabled="!row.enabled"
                  :aria-label="row.label + '鉴权方式'"
                  @change="setProtocolAuthTypeFromEvent(row.value, $event)"
                >
                  <option value="">继承默认 · {{ authTypeLabel(form.authType) }}</option>
                  <option value="bearer">Bearer</option>
                  <option value="anthropic">Anthropic</option>
                  <option value="google">Google</option>
                </select>
                <div class="protocol-order-actions" aria-label="协议顺序">
                  <button type="button" class="icon-button" :disabled="row.index <= 0" title="上移" :aria-label="'上移 ' + row.label" @click="moveProtocol(row.index, -1)">↑</button>
                  <button type="button" class="icon-button" :disabled="row.index < 0 || row.index >= form.protocols.length - 1" title="下移" :aria-label="'下移 ' + row.label" @click="moveProtocol(row.index, 1)">↓</button>
                </div>
                <div class="protocol-probe-cell">
                  <span v-if="row.probe" class="badge" :class="row.probe.className" :title="row.probe.title">{{ row.probe.detail }}</span>
                  <span v-else class="muted small">未探测</span>
                </div>
              </div>
            </div>

            <div class="protocol-probe-row">
              <button
                type="button"
                class="button subtle mini"
                :disabled="!canProbeProtocols || probing"
                @click="probeProtocols"
              >
                {{ probing ? '探测中…' : '探测全部协议' }}
              </button>
              <span v-if="!editingId" class="muted small">保存渠道后可探测。</span>
              <span v-else-if="!canProbeProtocols" class="muted small">保存地址、凭据或鉴权后可探测。</span>
              <span v-else class="muted small">使用已保存密钥探测四种协议，每种最多 1 token。</span>
            </div>
            <p class="channel-form-note">同协议优先；需要转换时按上方顺序选择目标协议。</p>

            <details class="request-overrides-details">
              <summary>高级：上游请求覆盖（UA / 请求头 / 请求体）</summary>
              <div class="request-overrides-fields">
                <RequestOverridesEditor ref="overrideEditor" :value="form.requestOverrides" :disabled="saving" @changed="overrideRevision += 1" />
              </div>
            </details>
          </section>

          <section
            v-show="activeFormSection === 'keys'"
            id="channel-form-panel-keys"
            class="channel-form-section"
            role="tabpanel"
            aria-labelledby="channel-form-tab-keys"
          >
            <div class="channel-form-section-head">
              <div><h3>上游密钥</h3><p>共享地址与协议，可分别授权模型和限流。</p></div>
              <div class="channel-form-section-actions">
                <span class="badge neutral">{{ channelKeys.length }} 个</span>
                <button type="button" class="button ghost mini" @click="showAutoDisableSettings = true">自动禁用规则</button>
              </div>
            </div>

            <p v-if="channelKeys.length === 0" class="muted small">
              当前渠道没有静态密钥。可保存后在「订阅账号」页关联 OAuth 账号，账号会作为该渠道路由凭据。
            </p>

            <div v-if="!isMobile" class="channel-key-workspace">
              <div class="channel-key-nav">
                <div class="channel-key-tabs" role="tablist" aria-label="渠道密钥" aria-orientation="vertical">
                  <button
                    v-for="(key, index) in channelKeys"
                    :id="channelKeyTabId(key.localId)"
                    :key="key.localId"
                    type="button"
                    class="channel-key-nav-item"
                    :class="{ 'is-active': selectedChannelKey?.localId === key.localId }"
                    role="tab"
                    :aria-selected="selectedChannelKey?.localId === key.localId"
                    :aria-controls="selectedChannelKey?.localId === key.localId ? 'channel-key-panel-' + key.localId : undefined"
                    :tabindex="selectedChannelKey?.localId === key.localId ? 0 : -1"
                    :title="keyStateBadge(key.state).label + ' · ' + key.models.length + ' 个模型 · 优先级 ' + key.priority"
                    @click="selectedChannelKeyId = key.localId"
                    @keydown="handleChannelKeyTabKeydown($event, index)"
                  >
                    <span class="channel-key-nav-dot" :class="'is-' + keyStateTone(key.state)" aria-hidden="true"></span>
                    <span class="channel-key-nav-body">
                      <span class="channel-key-nav-name">{{ key.name.trim() || `密钥 ${index + 1}` }}</span>
                      <span class="channel-key-nav-meta">{{ keyStateBadge(key.state).label }} · {{ key.models.length }} 个模型 · 优先级 {{ key.priority }}</span>
                    </span>
                    <span
                      v-if="channelKeyConcurrent(editingId, key.id) > 0"
                      class="channel-key-nav-live"
                      :title="`当前在途 ${channelKeyConcurrent(editingId, key.id)} 个请求`"
                    >{{ channelKeyConcurrent(editingId, key.id) }}</span>
                  </button>
                </div>
                <button type="button" class="button ghost mini channel-key-add" @click="addKey">＋ 添加密钥</button>
              </div>
              <div
                v-if="selectedChannelKey"
                :id="'channel-key-panel-' + selectedChannelKey.localId"
                class="channel-key-detail"
                role="tabpanel"
                :aria-labelledby="channelKeyTabId(selectedChannelKey.localId)"
              >
                <ChannelKeyEditor
                  :key="selectedChannelKey.localId"
                  :channel-key="selectedChannelKey"
                  :concurrent="channelKeyConcurrent(editingId, selectedChannelKey.id)"
                  @remove="removeSelectedKey"
                  @edit-rate-limit="openRateLimitEditor(selectedChannelKey)"
                  @load-models="loadKeyModels(selectedChannelKey)"
                />
              </div>
            </div>

            <div v-else class="channel-key-accordion">
              <div
                v-for="(key, index) in channelKeys"
                :key="key.localId"
                class="channel-key-accordion-item"
                :class="{ 'is-open': expandedChannelKeyId === key.localId }"
              >
                <button
                  :id="channelKeyTabId(key.localId)"
                  type="button"
                  class="channel-key-accordion-head"
                  :aria-expanded="expandedChannelKeyId === key.localId"
                  :aria-controls="expandedChannelKeyId === key.localId ? 'channel-key-panel-' + key.localId : undefined"
                  :title="keyStateBadge(key.state).label + ' · ' + key.models.length + ' 个模型 · 优先级 ' + key.priority"
                  @click="toggleKeyAccordion(key)"
                >
                  <span class="channel-key-nav-dot" :class="'is-' + keyStateTone(key.state)" aria-hidden="true"></span>
                  <span class="channel-key-nav-body">
                    <span class="channel-key-nav-name">{{ key.name.trim() || `密钥 ${index + 1}` }}</span>
                    <span class="channel-key-nav-meta">{{ keyStateBadge(key.state).label }} · {{ key.models.length }} 个模型 · 优先级 {{ key.priority }}</span>
                  </span>
                  <span
                    v-if="channelKeyConcurrent(editingId, key.id) > 0"
                    class="channel-key-nav-live"
                  >{{ channelKeyConcurrent(editingId, key.id) }}</span>
                  <Icon name="chevron" :size="14" class="channel-key-accordion-chevron" />
                </button>
                <div
                  v-if="expandedChannelKeyId === key.localId"
                  :id="'channel-key-panel-' + key.localId"
                  class="channel-key-accordion-panel"
                >
                  <ChannelKeyEditor
                    :key="key.localId"
                    :channel-key="key"
                    :concurrent="channelKeyConcurrent(editingId, key.id)"
                    @remove="removeKey(index)"
                    @edit-rate-limit="openRateLimitEditor(key)"
                    @load-models="loadKeyModels(key)"
                  />
                </div>
              </div>
              <button type="button" class="button ghost channel-key-add" @click="addKey">＋ 添加密钥</button>
            </div>

            <p class="channel-form-note">同一模型可以配置在多个密钥上；低优先级密钥只在高优先级密钥失败、冷却或不可用时启用。</p>
          </section>

          <section
            v-show="activeFormSection === 'models'"
            id="channel-form-panel-models"
            class="channel-form-section"
            role="tabpanel"
            aria-labelledby="channel-form-tab-models"
          >
            <div class="channel-form-section-head">
              <div><h3>模型映射</h3><p>设置对外模型 ID；留空沿用上游名。</p></div>
              <div class="channel-form-section-actions">
                <span class="badge neutral">{{ authorizedModels.length }} 个模型</span>
                <span v-if="mappingCount" class="badge info">{{ mappingCount }} 个自定义</span>
              </div>
            </div>

            <div v-if="!authorizedModels.length" class="channel-form-empty">
              <Icon name="models" :size="24" />
              <strong>还没有可映射的模型</strong>
              <p>先在“密钥”里读取或手动添加模型。</p>
              <button type="button" class="button subtle" @click="setActiveFormSection('keys')">去配置密钥</button>
            </div>

            <template v-else>
              <div class="channel-mapping-toolbar">
                <label class="search-box channel-mapping-search">
                  <Icon name="search" :size="15" />
                  <input v-model="mappingSearch" type="search" placeholder="搜索上游模型" />
                </label>
                <button
                  v-if="mappingCount"
                  type="button"
                  class="button ghost mini"
                  @click="showAllMappings = !showAllMappings"
                >
                  {{ showAllMappings ? '只看自定义' : `显示全部 ${authorizedModels.length} 个` }}
                </button>
              </div>

              <div class="channel-canonical-section">
                <div class="channel-canonical-header">
                  <span>上游模型</span>
                  <span></span>
                  <span>对外模型 ID（留空沿用上游名；多个上游可映射为同一个 ID）</span>
                </div>
                <div v-for="modelId in visibleMappingModels" :key="modelId" class="channel-canonical-row">
                  <div class="channel-canonical-model">
                    <button
                      type="button"
                      class="channel-canonical-upstream"
                      :title="'点击将「' + modelId + '」填入右侧'"
                      @click="copyUpstreamToCanonical(modelId)"
                    >
                      <code>{{ modelId }}</code>
                    </button>
                    <button
                      type="button"
                      class="button ghost mini channel-model-test-btn"
                      :disabled="!canTestModel(modelId)"
                      :title="canTestModel(modelId) ? '测试模型连通性' : '保存渠道和模型后可测试'"
                      @click="openModelTest(modelId)"
                    >
                      测试
                    </button>
                    <button
                      type="button"
                      class="button ghost mini channel-mapping-fill-btn"
                      @click="copyUpstreamToCanonical(modelId)"
                    >填入</button>
                  </div>
                  <button
                    type="button"
                    class="channel-canonical-arrow-btn"
                    :title="'点击将「' + modelId + '」填入右侧输入框'"
                    :aria-label="'填入 ' + modelId"
                    @click="copyUpstreamToCanonical(modelId)"
                  >
                    <Icon name="arrow" :size="13" />
                  </button>
                  <div class="channel-canonical-input-wrap">
                    <span class="channel-canonical-input-prefix">
                      <Icon name="models" :size="13" />
                    </span>
                    <input
                      :id="`canonical-input-${modelId}`"
                      v-model="canonicalModels[modelId]"
                      placeholder="留空沿用上游名"
                      autocomplete="off"
                      spellcheck="false"
                    />
                    <button
                      v-if="canonicalModels[modelId]"
                      type="button"
                      class="channel-canonical-clear"
                      title="恢复默认（沿用上游名）"
                      @click="clearMapping(modelId)"
                    >
                      <Icon name="close" :size="12" />
                    </button>
                  </div>
                </div>
                <p v-if="!visibleMappingModels.length" class="channel-form-empty-inline">没有匹配的模型。</p>
              </div>
              <p class="channel-form-note">测试会使用已保存的渠道配置发送一次真实上游请求。</p>
            </template>
          </section>

          <section
            v-show="activeFormSection === 'routing'"
            id="channel-form-panel-routing"
            class="channel-form-section"
            role="tabpanel"
            aria-labelledby="channel-form-tab-routing"
          >
            <div class="channel-form-section-head">
              <div><h3>路由状态</h3><p>优先级越小越优先；停用后不参与路由。</p></div>
            </div>
            <div class="channel-form-row channel-form-row-two">
              <label class="field">
                <span>渠道优先级</span>
                <input v-model.number="form.priority" type="number" min="1" max="1000" step="1" />
                <small>1..1000，越小越优先；同级轮询。</small>
              </label>
              <div class="field channel-enabled-field">
                <span>渠道状态</span>
                <button
                  type="button"
                  class="channel-status-toggle"
                  role="switch"
                  :class="form.enabled ? 'is-enabled' : 'is-disabled'"
                  :aria-checked="form.enabled"
                  :aria-label="form.enabled ? '渠道已启用' : '渠道已停用'"
                  :title="form.enabled ? '点击停用该渠道' : '点击启用该渠道'"
                  @click="form.enabled = !form.enabled"
                >
                  <span class="channel-status-track" aria-hidden="true"><span></span></span>
                  <span class="channel-status-label">{{ form.enabled ? '启用' : '停用' }}</span>
                </button>
                <small>停用后不会参与模型路由。</small>
              </div>
            </div>
          </section>
        </div>
      </form>
      <template #footer>
        <span v-if="isFormDirty" class="form-dirty-indicator"><span class="form-dirty-dot"></span>有未保存修改</span>
        <button type="button" class="button ghost" @click="closeChannelForm">取消</button>
        <button type="button" class="button primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存渠道' }}
        </button>
      </template>

      <ModelTestModal
        v-if="testTarget"
        :channel="testTarget.channel"
        :model="testTarget.model"
        @close="testTarget = null"
      />

      <ChannelKeyRateLimitModal
        v-if="rateLimitTarget"
        :key-name="rateLimitTarget.key.name || '未命名密钥'"
        :rule="rateLimitTarget.rule"
        @close="rateLimitTarget = null"
        @save="applyRateLimit"
      />

    </Modal>

    <ModelTestSettingsModal
      v-if="showTestSettings"
      @close="showTestSettings = false"
    />

    <UpstreamRequestSettingsModal v-if="showRequestSettings" @close="showRequestSettings = false" @saved="onRequestSettingsSaved" />

    <PriceEditorModal
      v-if="priceEditor"
      :channels="priceEditor.channels"
      :price="priceEditor.price"
      :prices="prices"
      :default-prices="defaultPrices"
      :initial-target="priceEditor.initialTarget"
      @close="priceEditor = null"
      @saved="onPriceSaved"
      @changed="onPriceChanged"
    />

    <DefaultPriceModal
      v-if="showDefaultPrices"
      :prices="defaultPrices"
      :channels="channels"
      @close="showDefaultPrices = false"
      @saved="onDefaultPricesSaved"
    />

    <KeyAutoDisableSettingsModal
      v-if="showAutoDisableSettings"
      @close="showAutoDisableSettings = false"
    />

  </section>
</template>

<style scoped>
.priority-cell {
  cursor: default;
}

.priority-input {
  width: 72px;
  padding: 4px 6px;
  font: inherit;
}

.channel-enabled-field { align-items: flex-start; }
/* 状态列信息较多：纵向堆叠让在途、冷却等原因各占一行，窄列下也不会串成一行。 */
.channel-status-stack { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; min-width: 0; max-width: 100%; }
.channel-status-stack > .channel-status-toggle,
.channel-status-stack > .channel-observation,
.channel-status-stack > .channel-occupancy,
.channel-status-stack > .channel-cooldown,
.channel-status-stack > .health-list-latency,
.channel-status-stack > .channel-health-probe { margin-top: 0; }
.channel-status-stack > .channel-observation { padding: 0; }
.channel-status-toggle { display: inline-flex; align-items: center; flex-wrap: nowrap; gap: 7px; min-width: max-content; padding: 2px 0; border: 0; background: transparent; color: var(--ink); font: inherit; font-size: 12px; font-weight: 650; white-space: nowrap; cursor: pointer; }
.channel-status-label { flex: 0 0 auto; white-space: nowrap; }
.channel-status-track { position: relative; display: inline-flex; align-items: center; width: 34px; height: 20px; flex: 0 0 auto; border: 1px solid color-mix(in srgb, var(--danger-fill) 58%, var(--line-strong)); border-radius: 999px; background: var(--danger-soft); transition: background .16s ease, border-color .16s ease; }
.channel-status-track > span { position: absolute; left: 3px; width: 14px; height: 14px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(16, 24, 40, .2); transition: transform .16s ease; }
.channel-status-toggle.is-enabled .channel-status-track { border-color: var(--success); background: var(--success); }
.channel-status-toggle.is-enabled .channel-status-track > span { transform: translateX(14px); }
.channel-status-toggle.is-enabled .channel-status-label { color: var(--success); }
.channel-status-toggle.is-disabled .channel-status-track { border-color: var(--danger); background: var(--danger); }
.channel-status-toggle.is-disabled .channel-status-label { color: var(--danger); }
.channel-status-toggle:disabled { opacity: .55; cursor: wait; }
.channel-status-toggle:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; border-radius: 4px; }
.channel-health-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.channel-health-summary .badge { padding: 3px 6px; font-size: 10px; }
.channel-health-failure { max-width: 190px; margin-top: 4px; overflow: hidden; color: var(--danger); text-overflow: ellipsis; white-space: nowrap; }
.channel-health-probe { margin-top: 3px; }
.channel-cooldown { display: flex; align-items: flex-start; gap: 4px; max-width: 100%; padding: 0; border: 0; background: transparent; color: var(--warning); font: inherit; font-size: 12px; line-height: 1.4; text-align: left; white-space: normal; overflow-wrap: anywhere; cursor: pointer; }
.channel-cooldown:hover { text-decoration: underline; }
.channel-form { display: block; --form-muted: color-mix(in srgb, var(--muted) 80%, var(--ink)); }
.channel-form-tabs { display: flex; align-items: stretch; gap: 2px; overflow-x: auto; scrollbar-width: none; }
.channel-form-tabs::-webkit-scrollbar { display: none; }
.channel-form-tab { position: relative; display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 46px; padding: 0 13px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: color-mix(in srgb, var(--muted) 80%, var(--ink)); font: inherit; font-size: 13px; font-weight: 650; white-space: nowrap; cursor: pointer; transition: color .14s ease, border-color .14s ease; }
.channel-form-tab:hover { color: var(--ink); }
.channel-form-tab.is-active { color: var(--brand-deep); border-bottom-color: var(--brand); }
.channel-form-tab:focus-visible { outline: 2px solid var(--brand); outline-offset: -3px; border-radius: 8px 8px 0 0; }
.channel-form-tab-count { min-width: 18px; padding: 0 5px; border-radius: 999px; background: var(--bg); color: color-mix(in srgb, var(--muted) 80%, var(--ink)); font-size: 10px; font-variant-numeric: tabular-nums; line-height: 18px; text-align: center; }
.channel-form-tab.is-active .channel-form-tab-count { background: var(--info-soft); color: var(--brand-deep); }
.channel-form-sections { padding-top: 20px; }
.channel-form-section { display: grid; gap: 16px; padding: 0; border: 0; background: transparent; }
.channel-form-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.channel-form-section-head h3 { margin: 0; font-size: 15px; letter-spacing: -0.01em; }
.channel-form-section-head p { margin: 4px 0 0; color: var(--form-muted); font-size: 12px; line-height: 1.5; }
.channel-form-section-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.channel-form-row { display: grid; gap: 12px; }
.channel-form-row-two { grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); }
.channel-form .field small { color: var(--form-muted); }
.channel-form-note { margin: 0; color: var(--form-muted); font-size: 11px; line-height: 1.6; }
.channel-form-empty { display: grid; justify-items: start; gap: 8px; padding: 20px; border: 1px dashed var(--line-strong); border-radius: 12px; background: var(--bg); }
.channel-form-empty > svg { color: var(--muted); }
.channel-form-empty strong { font-size: 13px; }
.channel-form-empty p { margin: 0; color: var(--form-muted); font-size: 12px; line-height: 1.5; }
.channel-form-empty .button.subtle { color: var(--brand-deep); }
.channel-form-empty-inline { margin: 0; padding: 16px; color: var(--form-muted); font-size: 12px; text-align: center; }

.protocol-table { display: grid; gap: 6px; }
.protocol-table-head { display: grid; grid-template-columns: 30px minmax(0, 1.1fr) minmax(0, 1.5fr) 76px 110px; align-items: center; gap: 10px; padding: 0 10px; color: var(--muted); font-size: 10px; font-weight: 650; letter-spacing: .02em; }
.protocol-row { display: grid; grid-template-columns: 30px minmax(0, 1.1fr) minmax(0, 1.5fr) 76px 110px; align-items: center; gap: 10px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); transition: border-color .14s ease, opacity .14s ease; }
.protocol-row:not(.is-disabled):hover { border-color: var(--line-strong); }
.protocol-row.is-disabled { opacity: .58; background: var(--bg); }
.protocol-check { display: grid; place-items: center; }
.protocol-check input { width: 16px; min-height: auto; height: 16px; margin: 0; }
.protocol-name { min-width: 0; }
.protocol-name strong { display: block; overflow: hidden; color: var(--ink); font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.protocol-row select { min-height: 34px; padding: 6px 30px 6px 9px; font-size: 12px; }
.protocol-order-actions { display: flex; gap: 4px; }
.protocol-order-actions .icon-button { width: 32px; height: 32px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: var(--muted); font-size: 14px; }
.protocol-order-actions .icon-button:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
.protocol-probe-cell { min-width: 0; }
.protocol-probe-cell .badge { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.protocol-probe-row { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }
.protocol-probe-row .button { flex: 0 0 auto; }
.form-dirty-indicator { display: inline-flex; align-items: center; gap: 7px; margin-right: auto; color: var(--warning); font-size: 12px; font-weight: 650; }
.form-dirty-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--warning-fill); box-shadow: 0 0 0 3px var(--warning-soft); }

.channel-key-workspace { display: grid; grid-template-columns: 210px minmax(0, 1fr); align-items: start; gap: 18px; min-width: 0; }
.channel-key-nav { display: grid; align-content: start; gap: 3px; min-width: 0; }
.channel-key-tabs { display: grid; gap: 3px; min-width: 0; }
.channel-key-nav-item { display: flex; align-items: flex-start; gap: 9px; width: 100%; padding: 10px 11px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: var(--ink); text-align: left; cursor: pointer; font: inherit; transition: background .14s ease, border-color .14s ease; }
.channel-key-nav-item:hover { background: var(--bg); }
.channel-key-nav-item.is-active { border-color: var(--line); background: var(--bg); }
.channel-key-nav-item:focus-visible { outline: 2px solid var(--brand); outline-offset: 1px; }
.channel-key-nav-dot { width: 8px; height: 8px; flex: 0 0 auto; margin-top: 5px; border-radius: 50%; background: var(--line-strong); }
.channel-key-nav-dot.is-success { background: var(--success-fill); box-shadow: 0 0 0 3px var(--success-soft); }
.channel-key-nav-dot.is-warning { background: var(--warning-fill); box-shadow: 0 0 0 3px var(--warning-soft); }
.channel-key-nav-dot.is-danger { background: var(--danger-fill); box-shadow: 0 0 0 3px var(--danger-soft); }
.channel-key-nav-body { display: grid; gap: 3px; min-width: 0; flex: 1 1 auto; }
.channel-key-nav-name { overflow: hidden; color: var(--ink); font-size: 12.5px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.channel-key-nav-meta { overflow: hidden; color: var(--form-muted); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.channel-key-nav-live { flex: 0 0 auto; min-width: 18px; padding: 0 5px; border-radius: 999px; background: var(--success-soft); color: var(--success); font-size: 10px; font-variant-numeric: tabular-nums; line-height: 18px; text-align: center; }
.channel-key-nav .channel-key-add { justify-content: flex-start; margin-top: 6px; }
.channel-key-detail { min-width: 0; padding: 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
.channel-key-accordion { display: grid; gap: 8px; }
.channel-key-accordion-item { overflow: hidden; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
.channel-key-accordion-item.is-open { border-color: var(--line-strong); }
.channel-key-accordion-head { display: flex; align-items: center; gap: 10px; width: 100%; padding: 12px; border: 0; background: transparent; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
.channel-key-accordion-head:focus-visible { outline: 2px solid var(--brand); outline-offset: -3px; }
.channel-key-accordion-chevron { flex: 0 0 auto; color: var(--muted); transition: transform .16s ease; }
.channel-key-accordion-item.is-open .channel-key-accordion-chevron { transform: rotate(180deg); }
.channel-key-accordion-panel { padding: 0 12px 14px; border-top: 1px solid var(--line); }
.channel-key-accordion-panel .channel-key-editor { padding-top: 14px; }
.channel-key-accordion > .channel-key-add { justify-self: start; }

.channel-mapping-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.channel-mapping-search { flex: 1 1 220px; max-width: 320px; }
.channel-canonical-section { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
.channel-canonical-header { display: grid; grid-template-columns: minmax(200px, 1.2fr) 24px minmax(220px, 1.8fr); align-items: center; gap: 10px; padding: 0 10px; color: var(--form-muted); font-size: 10px; font-weight: 650; letter-spacing: .02em; }
.channel-canonical-row { display: grid; grid-template-columns: minmax(200px, 1.2fr) 28px minmax(220px, 1.8fr); align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 9px; background: var(--bg); transition: border-color .14s ease; }
.channel-canonical-row:hover { border-color: var(--line-strong); }
.channel-canonical-model { display: flex; align-items: center; gap: 7px; min-width: 0; }
.channel-canonical-model .channel-canonical-upstream { min-width: 0; flex: 1 1 auto; }
.channel-canonical-input-wrap input { min-height: 34px; height: 34px; background: var(--panel); font-size: 12px; }
.channel-canonical-input-wrap input::placeholder { color: var(--form-muted); opacity: 1; font-size: 10.5px; }
.channel-mapping-search.search-box input::placeholder { color: var(--form-muted); opacity: 1; }
.channel-mapping-fill-btn { display: none; }

.channel-occupancy {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.channel-occupancy-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--line-strong);
}

.channel-occupancy.is-active { color: var(--success); font-weight: 600; }
.channel-occupancy.is-active .channel-occupancy-dot {
  background: var(--success-fill);
  box-shadow: 0 0 0 3px var(--success-soft);
}

.channel-mobile-list,
.channel-mobile-empty { display: none; }

/* 640px 以下使用摘要卡片；完整模型、密钥和延迟放进展开详情。 */
@media (max-width: 640px) {
  .channel-table-view { display: none; }
  :deep(.table-scroll-hint) { display: none !important; }
  .channel-mobile-list {
    display: grid;
    flex: 1 1 auto;
    align-content: start;
    gap: 12px;
    min-height: 0;
    padding: 12px;
    overflow: auto;
  }
  .channel-mobile-empty { display: block; padding: 28px 12px; text-align: center; }
  .channel-mobile-card { display: grid; gap: 10px; padding: 13px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
  .channel-mobile-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
  .channel-mobile-identity { display: grid; flex: 1 1 auto; gap: 3px; min-width: 0; }
  .channel-mobile-name { min-width: 0; color: var(--ink); font-size: 14px; font-weight: 650; line-height: 1.35; overflow-wrap: anywhere; text-decoration: none; }
  a.channel-mobile-name:hover { color: var(--brand); text-decoration: underline; }
  .channel-mobile-id { display: flex; align-items: center; gap: 4px; width: 100%; min-width: 0; }
  .channel-mobile-id .channel-id-text { min-width: 0; }
  .channel-mobile-keys { line-height: 1.5; }
  .channel-mobile-priority { flex: 0 0 auto; padding: 1px 5px; border-radius: 4px; background: var(--bg); color: var(--muted); font-size: 10px; font-weight: 650; font-variant-numeric: tabular-nums; }
  .channel-mobile-head .channel-status-toggle { flex: 0 0 auto; }
  .channel-mobile-status { display: grid; gap: 6px; }
  .channel-mobile-status-main { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; }
  .channel-mobile-status-main .channel-observation { margin: 0; padding: 0; min-width: 0; }
  .channel-mobile-status-main .channel-occupancy { flex: 0 0 auto; margin: 0; }
  .channel-mobile-card .channel-cooldown { margin: 0; }
  .channel-mobile-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
  .channel-mobile-meta .channel-protocol-matrix { gap: 4px; }
  .channel-mobile-actions { display: flex; align-items: center; gap: 4px; }
  .channel-mobile-actions .button,
  .channel-mobile-actions .icon-button { min-height: 40px; }
  .channel-mobile-actions .icon-button { width: 40px; height: 40px; }
  .channel-mobile-details-toggle { margin-left: auto; }
  .channel-mobile-details { display: grid; gap: 10px; padding-top: 10px; border-top: 1px solid var(--line); }
  .channel-mobile-detail-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px 12px; }
  .channel-mobile-detail-item { display: grid; gap: 2px; min-width: 0; }
  .channel-mobile-detail-item span { color: var(--muted); font-size: 10px; }
  .channel-mobile-detail-item strong { font-size: 12px; font-weight: 600; overflow-wrap: anywhere; }
  .channel-mobile-detail-section { display: grid; gap: 5px; min-width: 0; }
  .channel-mobile-detail-label { color: var(--muted); font-size: 10px; font-weight: 600; }
  .channel-mobile-detail-models { display: flex; flex-wrap: wrap; gap: 6px; }
  .channel-mobile-detail-model { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; }
  .channel-mobile-detail-keys { display: grid; gap: 6px; }
  .channel-mobile-key { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 2px 6px; min-width: 0; font-size: 12px; }
  .channel-mobile-key-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .channel-mobile-key-meta { grid-column: 1 / -1; }
}

@media (max-width: 360px) {
  .channel-mobile-detail-grid { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .channel-form-tabs { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0; overflow: visible; }
  .channel-form-tab { min-height: 44px; padding: 0 2px; font-size: 12px; }
  .channel-form-tab-count { display: none; }
  .channel-form-sections { padding-top: 16px; }
  .channel-form-section { gap: 14px; }
  .channel-form-section-head { align-items: flex-start; }
  .channel-form-section-head h3 { font-size: 14px; }
  .channel-form-section-actions { justify-content: flex-start; }
  .channel-form-row-two { grid-template-columns: 1fr; }
  .channel-form-note { font-size: 12px; }
  .channel-form .field input,
  .channel-form .field select,
  .channel-form .field textarea { min-height: 44px; font-size: 16px; }
  .channel-form .field small { font-size: 12px; }
  .channel-form .button.mini { min-height: 40px; }

  .protocol-table-head { display: none; }
  .protocol-row {
    grid-template-columns: 32px minmax(0, 1fr) auto;
    grid-template-areas:
      "check name order"
      "auth auth auth"
      "probe probe probe";
    gap: 8px;
    padding: 10px;
  }
  .protocol-check { grid-area: check; min-height: 44px; }
  .protocol-check input { width: 18px; height: 18px; }
  .protocol-name { grid-area: name; }
  .protocol-row select { grid-area: auth; width: 100%; min-height: 44px; font-size: 16px; }
  .protocol-order-actions { grid-area: order; }
  .protocol-order-actions .icon-button { width: 40px; height: 40px; }
  .protocol-probe-cell { grid-area: probe; }
  .protocol-probe-row { align-items: flex-start; flex-direction: column; gap: 7px; }

  .channel-key-accordion { gap: 7px; }
  .channel-key-accordion-head { min-height: 56px; padding: 11px 12px; }
  .channel-key-nav-meta { font-size: 11.5px; }
  .channel-key-accordion-panel { padding: 0 11px 12px; }
  .channel-key-accordion-panel .channel-key-editor { padding-top: 12px; }
  .channel-key-accordion > .channel-key-add { width: 100%; min-height: 42px; }

  .channel-mapping-toolbar { align-items: stretch; flex-direction: column; }
  .channel-mapping-toolbar > .button { align-self: flex-start; }
  .channel-mapping-search { width: 100%; max-width: none; min-height: 44px; }
  .channel-mapping-search input { height: 44px; min-height: 44px; font-size: 16px; }
  .channel-canonical-section { padding: 10px; }
  .channel-canonical-header { display: none; }
  .channel-canonical-row { grid-template-columns: 1fr; gap: 7px; padding: 9px 10px; }
  .channel-canonical-model { flex-wrap: wrap; }
  .channel-canonical-upstream { min-height: 40px; }
  .channel-canonical-arrow-btn { display: none; }
  .channel-mapping-fill-btn { display: inline-flex; }
  .channel-canonical-input-wrap input { min-height: 44px; height: 44px; padding-right: 46px !important; font-size: 16px; }
  .channel-canonical-input-wrap input::placeholder { font-size: 12px; }
  .channel-canonical-clear { width: 34px; height: 34px; }
  .channel-model-test-btn { min-height: 34px; }
}
</style>

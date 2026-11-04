<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { clearUpstreamModelCache } from '../upstreamModelCache'
import { ApiError } from '../types/api'
import type { AdminAuditLog, Channel, ChannelHealthReport, ChannelKeyState, HealthState, HealthWindow, ModelDefaultPrice, ModelPrice, ProtocolProbeResult, UpstreamRequestOverrides } from '../types/api'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import ActionMenu from './ActionMenu.vue'
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
import { HEALTH_WINDOWS, HEALTH_LABELS, healthPercent, healthSymbol } from '../utils/channelHealth'
import { cooldownSummaryText } from '../utils/cooldown'
import SecretInput from './SecretInput.vue'
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
import { buildChannelPayload, buildPriorityUpdatePayload, canonicalModelSnapshot, isKeyRoutable, mergeProbedProtocols, parseManualModelIds, removeProbeAddedProtocols } from '../utils/channelForm'
import {
  channelKeyRateLimitSummary,
  normalizeChannelKeyRateLimit,
  validateChannelKeyRateLimit,
} from '../utils/channelKeyRateLimit'
import {
  canProbeSavedChannel,
  createRequestTracker,
  sameProtocolSelection,
  watchChannelRequestInputs,
} from '../utils/channelRequestState'
import { createRequestOverrideDrafts, normalizeRequestOverrides, requestOverrideDraftSnapshot } from '../utils/requestOverrides'
import type { ChannelDisplayModel } from '../utils/channelDisplay'
import type { ChannelKeyRateLimitDraft } from '../utils/channelKeyRateLimit'
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

interface KeyModelOption {
  id: string
  ownedBy?: string | null
  authorized?: boolean
}

interface ChannelKeyEditorDraft {
  localId: string
  id: string | null
  name: string
  credential: string
  state: ChannelKeyState
  originalState: ChannelKeyState
  priority: number | string | null
  stateVersion: number | null
  autoDisableReason: string | null
  autoDisableUntil: number | null
  rateLimit: ChannelKeyRateLimitDraft & Record<string, unknown>
  models: string[]
  availableModels: KeyModelOption[]
  manualModels: string[]
  showManualModels: boolean
  manualModelsText: string
  manualModelsError: string
  modelsLoading: boolean
  modelsError: string
  modelsRequestId: number
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
const healthWindow = ref<HealthWindow>('24h')
const healthReport = ref<ChannelHealthReport | null>(null)
const healthTarget = ref<Channel | null>(null)
const healthLoading = ref(false)
const healthError = ref('')
let healthRevision = 0
const healthByChannel = computed(() => new Map(healthReport.value?.window === healthWindow.value ? healthReport.value.channels.map(row => [row.channelId, row]) : []))
const cooldownNow = ref(Date.now())
let cooldownTimer: ReturnType<typeof setInterval> | null = null
function syncCooldownTimer() {
  const cooling = healthReport.value?.channels.some(row => (row.coolingCandidates ?? 0) > 0) ?? false
  if (cooling && cooldownTimer == null) {
    cooldownTimer = setInterval(() => { cooldownNow.value = Date.now() }, 1000)
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
async function loadHealth() {
  const request = ++healthRevision
  healthLoading.value = true
  healthError.value = ''
  try {
    const result = await api.getChannelHealth(healthWindow.value)
    if (request === healthRevision) { healthReport.value = result; syncCooldownTimer() }
  } catch (cause) {
    if (request === healthRevision) { healthReport.value = null; syncCooldownTimer(); healthError.value = errorMessage(cause) }
  } finally { if (request === healthRevision) healthLoading.value = false }
}
watch(healthWindow, loadHealth)
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
        : requestOverrideDraftSnapshot(createRequestOverrideDrafts(form.requestOverrides), form.requestOverrides.inheritGlobal !== false),
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
const prioritySortDirection = ref<'asc' | 'desc'>('asc')
const channelToggleSavingIds = ref<string[]>([])
const showForm = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const probing = ref(false)
const probeResults = ref<ProtocolProbeResult[]>([])
const channelKeys = ref<ChannelKeyEditorDraft[]>([])
const selectedChannelKeyId = ref<string | null>(null)
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

function protocolLabel(value: string): string {
  return protocolOptions.find((item) => item.value === value)?.label || value
}

function authTypeLabel(value: string): string {
  if (value === 'anthropic') return 'Anthropic API key（x-api-key）'
  if (value === 'google') return 'Google API key（x-goog-api-key）'
  return 'Bearer token'
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
  .filter((channel) => channelMatchesFilters(channel, channelStatusFilter.value, channelModelFilter.value))
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

function keyStateBadge(state: ChannelKeyState): { label: string; class: string } {
  switch (state) {
    case 'ACTIVE':
      return { label: '正常', class: 'success' }
    case 'MANUAL_DISABLED':
      return { label: '人工停用', class: 'neutral' }
    case 'AUTO_TEMPORARY':
      return { label: '临时停用', class: 'warning' }
    case 'AUTO_PERMANENT':
      return { label: '永久停用', class: 'danger' }
    default:
      return { label: state || '正常', class: 'neutral' }
  }
}

function toggleKeyState(key: ChannelKeyEditorDraft): void {
  key.state = key.state === 'ACTIVE' ? 'MANUAL_DISABLED' : 'ACTIVE'
}

function addKey() {
  const names = new Set(channelKeys.value.map((key) => key.name.trim()))
  let index = channelKeys.value.length + 1
  while (names.has(`密钥 ${index}`)) index += 1
  const key = newKeyDraft({ name: `密钥 ${index}`, priority: 1 })
  channelKeys.value.push(key)
  selectedChannelKeyId.value = key.localId
  focusChannelKeyTab(key.localId)
}

function removeKey(index: number): void {
  if (channelKeys.value.length <= 1) {
    notify('至少保留一个密钥', 'warning')
    return
  }
  const [removed] = channelKeys.value.splice(index, 1)
  if (selectedChannelKeyId.value === removed.localId) {
    const nextKey = channelKeys.value[Math.min(index, channelKeys.value.length - 1)]
    selectedChannelKeyId.value = nextKey.localId
    focusChannelKeyTab(nextKey.localId)
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
  if (event.key === 'ArrowRight') nextIndex = (index + 1) % channelKeys.value.length
  else if (event.key === 'ArrowLeft') nextIndex = (index - 1 + channelKeys.value.length) % channelKeys.value.length
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

function keyModelOptions(key: ChannelKeyEditorDraft): KeyModelOption[] {
  const options = new Map<string, KeyModelOption>()
  ;(key.availableModels || []).forEach((model) => options.set(model.id, model))
  ;[...(key.manualModels || []), ...(key.models || [])].forEach((modelId) => {
    if (!options.has(modelId)) options.set(modelId, { id: modelId })
  })
  return [...options.values()].sort((left, right) => left.id.localeCompare(right.id))
}

function openManualKeyModels(key: ChannelKeyEditorDraft): void {
  key.showManualModels = true
  nextTick(() => document.getElementById(`manual-models-${key.localId}`)?.focus())
}

function closeManualKeyModels(key: ChannelKeyEditorDraft): void {
  key.showManualModels = false
  key.manualModelsText = ''
  key.manualModelsError = ''
  nextTick(() => document.getElementById(`manual-models-button-${key.localId}`)?.focus())
}

function addManualKeyModels(key: ChannelKeyEditorDraft): void {
  try {
    const models = parseManualModelIds(key.manualModelsText)
    key.manualModels = [...new Set([...key.manualModels, ...models])]
    key.models = [...new Set([...key.models, ...models])].sort()
    closeManualKeyModels(key)
  } catch (cause) {
    key.manualModelsError = errorMessage(cause)
  }
}

function toggleKeyModel(key: ChannelKeyEditorDraft, modelId: string): void {
  const selected = new Set(key.models)
  if (selected.has(modelId)) selected.delete(modelId)
  else selected.add(modelId)
  key.models = [...selected].sort()
}

function selectAllKeyModels(key: ChannelKeyEditorDraft): void {
  key.models = keyModelOptions(key).map((model) => model.id)
}

function clearKeyModels(key: ChannelKeyEditorDraft): void {
  key.models = []
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
  probeRequests.invalidate()
  probing.value = false
  probeResults.value = []
  probeAddedProtocols = []
  modelRequests.invalidate()
  channelKeys.value = [newKeyDraft({ name: '默认密钥', priority: 1 })]
  selectedChannelKeyId.value = channelKeys.value[0].localId
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
  probeRequests.invalidate()
  probing.value = false
  probeResults.value = []
  probeAddedProtocols = []
  modelRequests.invalidate()
  const savedKeys = channel.keys?.length
    ? channel.keys
    : [{ name: '默认密钥', state: 'ACTIVE' as const, priority: 1, models: channel.models || [] }]
  channelKeys.value = savedKeys.map((key) => newKeyDraft(key))
  selectedChannelKeyId.value = channelKeys.value[0]?.localId || null
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
    notify('请填写渠道名称和基础地址', 'error')
    return
  }
  if (form.protocols.length === 0) {
    notify('至少选择一个支持协议', 'error')
    return
  }
  if (channelKeys.value.length === 0) {
    notify('渠道至少需要一个密钥', 'error')
    return
  }
  const names = new Set()
  for (const key of channelKeys.value) {
    const name = key.name.trim()
    if (!name) {
      notify('密钥名称不能为空', 'error')
      return
    }
    if (names.has(name)) {
      notify(`密钥名称不能重复：${name}`, 'error')
      return
    }
    names.add(name)
    if (!Number.isInteger(Number(key.priority)) || Number(key.priority) < 1 || Number(key.priority) > 1000) {
      notify('密钥优先级必须是 1..1000 的整数', 'error')
      return
    }
    if (!key.id && !key.credential.trim()) {
      notify(`新建密钥「${name}」必须提供上游凭据`, 'error')
      return
    }
    if (!key.models.length) {
      notify(`密钥「${name}」至少需要授权一个模型`, 'error')
      return
    }
    try {
      validateChannelKeyRateLimit(key.rateLimit)
    } catch (cause) {
      notify(`密钥「${name}」限流规则无效：${errorMessage(cause)}`, 'error')
      return
    }
  }
  if (authorizedModels.value.length === 0) {
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

onMounted(load)
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
        <span class="channel-sort-note">数字越小，路由优先级越高</span>
        <div class="channel-list-filters" role="group" aria-label="渠道筛选">
          <FilterSelect v-model="healthWindow" label="健康观察" :clearable="false" :options="HEALTH_WINDOWS.map(option => ({ value: option.id, label: option.label }))" />
          <FilterSelect v-model="channelStatusFilter" label="状态" default-value="all" :options="channelStatusOptions.map(option => ({ value: option.id, label: option.label + '（' + option.count + '）' }))" />
          <FilterSelect v-model="channelModelFilter" label="模型" searchable :options="[{ value: '', label: '全部模型' }, ...channelModelOptions.map(model => ({ value: model, label: model }))]" />
          <button v-if="channelStatusFilter !== 'all' || channelModelFilter" type="button" class="filter-reset" @click="channelStatusFilter = 'all'; channelModelFilter = ''">清除筛选</button>
        </div>
      </div>
      <ErrorState v-if="healthError" title="健康度读取失败" :message="healthError" :busy="healthLoading" @retry="loadHealth" />
      <p v-if="healthReport?.indexing" class="muted small health-indexing-note" role="status">正在分批补齐历史健康度；完成后点击刷新查看完整统计。</p>
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
      <div v-else v-scroll-hint class="table-scroll">
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
              <th>协议</th>
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
              <td>
                <div class="protocol-badges channel-protocol-list">
                  <span v-for="protocol in channel.protocols" :key="protocol" class="badge info">
                    {{ protocol }}
                  </span>
                </div>
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
              <td>
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
                  <span><strong>{{ healthLoading ? '汇总中…' : healthError ? '读取失败' : healthPercent(healthByChannel.get(channel.id)?.metrics.successRate) }}</strong><small>{{ healthByChannel.get(channel.id)?.metrics.attempts ?? 0 }} 次 · {{ HEALTH_LABELS[channelHealthState(channel)] }}</small></span>
                </button>
                <div v-if="healthByChannel.get(channel.id)?.metrics.p95FirstTokenMs != null" class="muted small health-list-latency">P95 首 token {{ formatDuration(healthByChannel.get(channel.id)?.metrics.p95FirstTokenMs) }}</div>
                <button v-if="channelCooldownText(channel)" type="button" class="channel-cooldown muted small" :title="channelCooldownTitle(channel)" @click="healthTarget = channel">
                  {{ channelCooldownText(channel) }}
                </button>
                <div v-if="recentProbe(channel)" class="channel-health-probe muted small" :title="`最近探测：${formatTime(recentProbe(channel)!.createdAt)}`">
                  探测 {{ formatTime(recentProbe(channel)!.createdAt) }}
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
    </div>

    <ChannelHealthModal v-if="healthTarget" :key="healthTarget.id" :channel="healthTarget" :initial-window="healthWindow" @close="healthTarget = null; loadHealth()" />
    <ChannelQuotaModal v-if="quotaTarget" :key="quotaTarget.id" :channel="quotaTarget" @close="quotaTarget = null" @saved="onQuotaSaved" />

    <Modal v-if="showForm" :title="editingId ? '编辑渠道' : '新建渠道'" wide @close="closeChannelForm">
      <form class="form-grid" @submit.prevent="save">
        <section class="channel-form-section">
          <div class="channel-form-section-head">
            <div><h3>基础连接</h3><p>配置渠道名称、地址和默认鉴权方式。</p></div>
            <span class="badge info">步骤 1</span>
          </div>
        <label class="field">
          <span>渠道名称</span>
          <input v-model="form.name" placeholder="例如 openai-primary" autocomplete="off" />
          <small>渠道在网关中的唯一标识名称。</small>
        </label>
        <label class="field">
          <span>基础地址</span>
          <input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" autocomplete="off" />
          <small>网关会按请求协议在此地址后追加对应端点。模型列表可通过 <code>/models</code> 读取，也可手动添加。</small>
        </label>
        <label class="field">
          <span>上游鉴权</span>
          <select v-model="form.authType">
            <option value="bearer">Bearer token</option>
            <option value="anthropic">Anthropic API key（x-api-key）</option>
            <option value="google">Google API key（x-goog-api-key）</option>
          </select>
          <small>这是未单独配置协议时的默认鉴权方式；各协议可在下方单独覆盖。</small>
        </label>
        </section>

        <section class="channel-form-section">
          <div class="channel-form-section-head">
            <div><h3>协议与请求</h3><p>选择支持的协议，分别设置鉴权并调整上游请求覆盖。</p></div>
            <span class="badge info">步骤 2</span>
          </div>
        <div class="field">
          <span>支持协议与转换优先级</span>
          <div class="protocol-options">
            <label v-for="item in protocolOptions" :key="item.value">
              <input v-model="form.protocols" type="checkbox" :value="item.value" @change="keepProtocolAsManual(item.value)" /> {{ item.label }}
            </label>
          </div>
          <div v-if="form.protocols.length" class="protocol-order-list">
            <small>同协议优先；需要转换时按以下顺序选择目标协议。</small>
            <div v-for="(protocol, index) in form.protocols" :key="protocol" class="protocol-order-item">
              <span class="badge info">{{ protocolLabel(protocol) }}</span>
              <button type="button" class="icon-button" :disabled="index === 0" title="上移" @click="moveProtocol(index, -1)">↑</button>
              <button type="button" class="icon-button" :disabled="index === form.protocols.length - 1" title="下移" @click="moveProtocol(index, 1)">↓</button>
            </div>
          </div>
          <div v-if="form.protocols.length" class="protocol-auth-settings">
            <small>协议级鉴权（可选）：选择“继承渠道默认”即可继续使用上方设置。</small>
            <div v-for="protocol in form.protocols" :key="'auth-' + protocol" class="protocol-auth-row">
              <span class="badge info">{{ protocolLabel(protocol) }}</span>
              <select
                :value="form.protocolAuthTypes[protocol] || ''"
                :aria-label="protocolLabel(protocol) + '鉴权方式'"
                @change="setProtocolAuthTypeFromEvent(protocol, $event)"
              >
                <option value="">继承渠道默认（{{ authTypeLabel(form.authType) }}）</option>
                <option value="bearer">Bearer token</option>
                <option value="anthropic">Anthropic API key（x-api-key）</option>
                <option value="google">Google API key（x-goog-api-key）</option>
              </select>
            </div>
          </div>
          <div class="protocol-probe-row">
            <button
              type="button"
              class="button subtle mini"
              :disabled="!canProbeProtocols || probing"
              @click="probeProtocols"
            >
              {{ probing ? '探测中…' : '探测协议' }}
            </button>
            <span v-if="!editingId" class="muted small">保存渠道后可探测</span>
            <span v-else-if="!canProbeProtocols" class="muted small">保存地址、凭据、鉴权方式或请求配置后可探测</span>
            <span v-else class="muted small">使用已保存密钥探测全部四种协议（含未勾选项），每种最多 1 token</span>
          </div>
          <div v-if="probeResults.length" class="probe-results">
            <span
              v-for="result in probeResults"
              :key="result.protocol"
              class="badge"
              :class="probeState(result).className"
              :title="result.message"
            >
              {{ result.protocol }} · {{ probeState(result).label }}{{ result.statusCode ? ` (${result.statusCode})` : '' }}
            </span>
          </div>
          <small>请求只会路由到支持对应协议的渠道。</small>
        </div>
        <details class="request-overrides-details">
          <summary>上游请求配置 · 自定义 UA、请求头与请求体</summary>
          <div class="request-overrides-fields">
            <RequestOverridesEditor ref="overrideEditor" :value="form.requestOverrides" :disabled="saving" @changed="overrideRevision += 1" />
          </div>
        </details>
        </section>

        <section class="channel-form-section">
          <div class="channel-form-section-head">
            <div><h3>上游密钥</h3><p>密钥共享渠道地址和协议配置，可分别授权模型与限流。</p></div>
            <span class="badge info">步骤 3</span>
          </div>
        <div class="field channel-key-pool">
          <div class="channel-model-auth-head">
            <div>
              <span>上游密钥</span>
              <small>同一渠道的密钥共用基础地址、协议和协议级鉴权配置；未覆盖的协议继承渠道默认鉴权方式。</small>
            </div>
            <div class="section-head-actions">
              <span class="badge info">{{ channelKeys.length }} 个密钥</span>
              <button type="button" class="button ghost mini" @click="showAutoDisableSettings = true">自动禁用规则</button>
            </div>
          </div>
          <div class="channel-key-tabs" role="tablist" aria-label="渠道密钥">
            <button
              v-for="(key, index) in channelKeys"
              :id="channelKeyTabId(key.localId)"
              :key="key.localId"
              type="button"
              class="channel-key-tab"
              role="tab"
              :aria-selected="selectedChannelKeyId === key.localId"
              :aria-controls="`channel-key-panel-${key.localId}`"
              :tabindex="selectedChannelKeyId === key.localId ? 0 : -1"
              @click="selectedChannelKeyId = key.localId"
              @keydown="handleChannelKeyTabKeydown($event, index)"
            >{{ key.name.trim() || `密钥 ${index + 1}` }}</button>
          </div>
          <div class="channel-key-list">
            <div
              v-for="(key, index) in channelKeys"
              :id="`channel-key-panel-${key.localId}`"
              :key="key.localId"
              v-show="selectedChannelKeyId === key.localId"
              class="channel-key-card"
              role="tabpanel"
              tabindex="0"
              :aria-labelledby="channelKeyTabId(key.localId)"
            >
              <div class="channel-key-card-head">
                <label class="field">
                  <span>密钥名称</span>
                  <input v-model="key.name" placeholder="例如 primary" autocomplete="off" />
                </label>
                <label class="field">
                  <span>密钥优先级</span>
                  <input
                    v-model.number="key.priority"
                    type="number"
                    min="1"
                    max="1000"
                    step="1"
                    title="数值越小越优先；同优先级密钥轮询"
                  />
                  <small>数值越小越优先（1..1000）；同优先级密钥轮询分摊。</small>
                </label>
                <div class="channel-key-toggle-field">
                  <span>状态</span>
                  <div class="channel-key-status-row">
                    <span class="badge" :class="keyStateBadge(key.state).class">{{ keyStateBadge(key.state).label }}</span>
                    <button
                      type="button"
                      class="button subtle mini channel-key-state-btn"
                      @click="toggleKeyState(key)"
                    >
                      {{ key.state === 'ACTIVE' ? '停用' : '启用' }}
                    </button>
                  </div>
                </div>
                <div class="channel-key-action-field">
                  <span>操作</span>
                  <button type="button" class="button ghost mini channel-key-delete" @click="removeKey(index)">删除</button>
                </div>
              </div>
              <div v-if="key.originalState === 'AUTO_PERMANENT'" class="callout" :class="key.state === 'ACTIVE' ? 'info' : 'danger'">
                <strong>{{ key.state === 'ACTIVE' ? '保存后重新启用' : '已自动永久停用' }}<template v-if="key.autoDisableReason">：{{ key.autoDisableReason }}</template></strong>
                <p>{{ key.state === 'ACTIVE' ? '保存后将人工恢复为正常状态，可保留当前凭据或在下方替换凭据；失败计数会重置，但上游问题未解决时可能再次触发禁用。' : '上游鉴权、账号或余额问题触发了永久禁用，不会自动恢复。修复上游问题后可点击「启用」并保存，当前凭据无需更换。' }}</p>
              </div>
              <div v-else-if="key.originalState === 'AUTO_TEMPORARY'" class="callout" :class="key.state === 'ACTIVE' ? 'info' : 'warning'">
                <strong>{{ key.state === 'ACTIVE' ? '保存后提前启用' : '已自动临时停用' }}<template v-if="key.autoDisableReason">：{{ key.autoDisableReason }}</template></strong>
                <p>
                  <template v-if="key.autoDisableUntil">恢复时间：{{ formatTime(key.autoDisableUntil) }}（到期后自动可用）。</template>
                  {{ key.state === 'ACTIVE' ? '提前启用无需更换凭据，保存后立即恢复为正常状态。' : '可等待到期自动恢复，或点击「启用」并在保存后提前恢复。' }}
                </p>
              </div>
              <label class="field">
                <span>上游凭据</span>
                <SecretInput
                  v-model="key.credential"
                  :placeholder="key.id ? '留空保持当前凭据' : '输入上游凭据（Bearer Token 或 Anthropic API key）'"
                  autocomplete="new-password"
                />
              </label>
              <div class="channel-key-rate-limit-row">
                <div>
                  <strong>上游分组限流</strong>
                  <small>{{ channelKeyRateLimitSummary(key.rateLimit) }}。按当前渠道与密钥共享计数，覆盖全部模型。</small>
                </div>
                <button type="button" class="button ghost mini" @click="openRateLimitEditor(key)">
                  {{ key.rateLimit?.enabled ? '编辑限流' : '配置限流' }}
                </button>
              </div>
              <div class="channel-key-models">
                <div class="channel-model-auth-toolbar">
                  <span class="muted small">可用模型（{{ key.models.length }}）</span>
                  <button type="button" class="button subtle mini" :disabled="key.modelsLoading" @click="loadKeyModels(key)">
                    {{ key.modelsLoading ? '读取中…' : '读取模型' }}
                  </button>
                  <button
                    :id="`manual-models-button-${key.localId}`"
                    type="button"
                    class="button subtle mini"
                    :aria-expanded="key.showManualModels"
                    :aria-controls="`manual-models-panel-${key.localId}`"
                    @click="openManualKeyModels(key)"
                  >手动添加</button>
                  <button type="button" class="button ghost mini" @click="selectAllKeyModels(key)">全选</button>
                  <button type="button" class="button ghost mini" @click="clearKeyModels(key)">清空</button>
                </div>
                <div v-if="key.showManualModels" :id="`manual-models-panel-${key.localId}`" class="channel-key-manual-models">
                  <label class="field" :for="`manual-models-${key.localId}`">
                    <span>上游模型 ID</span>
                    <textarea
                      :id="`manual-models-${key.localId}`"
                      v-model="key.manualModelsText"
                      rows="3"
                      placeholder="填写上游实际使用的模型 ID，可批量粘贴"
                      spellcheck="false"
                      autocapitalize="off"
                      :aria-invalid="!!key.manualModelsError"
                      :aria-describedby="`manual-models-hint-${key.localId}`"
                      @input="key.manualModelsError = ''"
                    ></textarea>
                    <small :id="`manual-models-hint-${key.localId}`">支持换行、逗号、分号或空格分隔；添加后自动勾选，重复 ID 会合并。</small>
                  </label>
                  <div v-if="key.manualModelsError" class="callout danger" role="alert">{{ key.manualModelsError }}</div>
                  <div class="channel-key-manual-model-actions">
                    <button type="button" class="button subtle mini" :disabled="!key.manualModelsText.trim()" @click="addManualKeyModels(key)">添加并选中</button>
                    <button type="button" class="button ghost mini" @click="closeManualKeyModels(key)">取消</button>
                  </div>
                </div>
                <div v-if="key.modelsError" class="callout danger">
                  {{ key.modelsError }}
                  <p>可点击“手动添加”填写上游模型 ID，然后保存渠道。</p>
                </div>
                <div v-if="keyModelOptions(key).length" class="channel-model-auth-body">
                  <div class="channel-model-auth-list">
                    <label v-for="model in keyModelOptions(key)" :key="model.id" class="channel-model-option">
                      <input
                        type="checkbox"
                        :checked="key.models.includes(model.id)"
                        @change="toggleKeyModel(key, model.id)"
                      />
                      <span>
                        <code>{{ model.id }}</code>
                        <small v-if="model.ownedBy">上游 owned_by: {{ model.ownedBy }}</small>
                      </span>
                    </label>
                  </div>
                </div>
                <div v-else class="channel-model-auth-placeholder">
                  点击“读取模型”获取列表，或点击“手动添加”填写该密钥可用的上游模型 ID。
                </div>
              </div>
            </div>
          </div>
          <button type="button" class="button ghost mini channel-key-add" @click="addKey">添加密钥</button>
          <small>同一模型可以配置在多个密钥上；低优先级密钥只在高优先级密钥失败、冷却或不可用时启用。</small>
        </div>
        </section>

        <section v-if="authorizedModels.length" class="channel-form-section">
          <div class="channel-form-section-head">
            <div><h3>模型映射</h3><p>为调用方设置对外模型 ID，并保留上游实际模型名。</p></div>
            <span class="badge info">步骤 4</span>
          </div>
        <div v-if="authorizedModels.length" class="channel-canonical-section">
          <div class="channel-model-auth-head">
            <div>
              <span>对外模型 ID</span>
              <small>调用方在请求体里使用的模型名；留空则直接使用上游模型名。</small>
            </div>
            <span v-if="mappingCount" class="badge info">{{ mappingCount }} 个已自定义</span>
            <span v-else class="badge neutral">全部沿用上游名</span>
          </div>
          <div class="channel-canonical-list">
            <div class="channel-model-test-toolbar">
              <span class="muted small">测试会使用已保存的渠道配置发送一次真实上游请求。</span>
            </div>
            <div class="channel-canonical-header">
              <span>上游模型</span>
              <span></span>
              <span>
                对外模型 ID（留空沿用上游名；多个上游可映射为同一个 ID）
              </span>
            </div>
            <div v-for="modelId in authorizedModels" :key="modelId" class="channel-canonical-row">
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
                  :placeholder="'留空沿用上游模型名（点击箭头填入）'"
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
          </div>
        </div>
        </section>

        <section class="channel-form-section">
          <div class="channel-form-section-head">
            <div><h3>路由状态</h3><p>优先级越小越优先；停用后渠道不会参与路由。</p></div>
            <span class="badge info">步骤 5</span>
          </div>
        <label class="field">
          <span>渠道优先级</span>
          <input v-model.number="form.priority" type="number" min="1" max="1000" step="1" />
          <small>数值越小越优先（1..1000）：同优先级渠道轮询分摊，低优先级只在高优先级全部失败或冷却时启用。</small>
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
        </section>
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
.channel-status-toggle { display: inline-flex; align-items: center; flex-wrap: nowrap; gap: 7px; min-width: max-content; padding: 2px 0; border: 0; background: transparent; color: var(--ink); font: inherit; font-size: 12px; font-weight: 650; white-space: nowrap; cursor: pointer; }
.channel-status-label { flex: 0 0 auto; white-space: nowrap; }
.channel-status-track { position: relative; display: inline-flex; align-items: center; width: 34px; height: 20px; flex: 0 0 auto; border: 1px solid color-mix(in srgb, var(--danger-fill) 58%, var(--line-strong)); border-radius: 999px; background: var(--danger-soft); transition: background .16s ease, border-color .16s ease; }
.channel-status-track > span { position: absolute; left: 3px; width: 14px; height: 14px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(16, 24, 40, .2); transition: transform .16s ease; }
.channel-status-toggle.is-enabled .channel-status-track { border-color: var(--success-fill); background: var(--success-fill); }
.channel-status-toggle.is-enabled .channel-status-track > span { transform: translateX(14px); }
.channel-status-toggle.is-enabled .channel-status-label { color: var(--success); }
.channel-status-toggle.is-disabled .channel-status-track { border-color: var(--danger-fill); background: var(--danger-fill); }
.channel-status-toggle.is-disabled .channel-status-label { color: var(--danger); }
.channel-status-toggle:disabled { opacity: .55; cursor: wait; }
.channel-status-toggle:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; border-radius: 4px; }
.channel-health-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.channel-health-summary .badge { padding: 3px 6px; font-size: 10px; }
.channel-health-failure { max-width: 190px; margin-top: 4px; overflow: hidden; color: var(--danger); text-overflow: ellipsis; white-space: nowrap; }
.channel-health-probe { margin-top: 3px; }
.channel-cooldown { display: inline-flex; align-items: center; gap: 4px; margin-top: 3px; padding: 0; border: 0; background: transparent; color: var(--warning); font: inherit; text-align: left; cursor: pointer; }
.channel-cooldown:hover { text-decoration: underline; }
.channel-form-section { display: grid; gap: 16px; padding: 16px; border: 1px solid var(--line); border-radius: 14px; background: color-mix(in srgb, var(--panel) 94%, var(--bg)); }
.channel-form-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 4px; border-bottom: 1px solid var(--line); }
.channel-form-section-head h3 { margin: 0; font-size: 14px; }
.channel-form-section-head p { margin: 4px 0 0; color: var(--muted); font-size: 11px; line-height: 1.5; }
.channel-form-section > .field, .channel-form-section > .request-overrides-details { margin: 0; }
.form-dirty-indicator { display: inline-flex; align-items: center; gap: 7px; margin-right: auto; color: var(--warning); font-size: 12px; font-weight: 650; }
.form-dirty-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--warning-fill); box-shadow: 0 0 0 3px var(--warning-soft); }

.channel-key-manual-models {
  display: grid;
  gap: 10px;
  min-width: 0;
  margin-top: 8px;
}

.channel-key-manual-model-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.channel-key-rate-limit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.channel-key-rate-limit-row > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.channel-key-rate-limit-row strong { font-size: 13px; }
.channel-key-rate-limit-row small { color: var(--muted); line-height: 1.5; }
.channel-key-rate-limit-row .button { flex: 0 0 auto; min-height: 36px; }

@media (max-width: 640px) {
  .channel-key-rate-limit-row { align-items: stretch; flex-direction: column; }
  .channel-key-rate-limit-row .button { align-self: flex-start; }
  .channel-form-section { padding: 13px; border-radius: 12px; }
  .channel-form-section-head { align-items: flex-start; }
}
</style>

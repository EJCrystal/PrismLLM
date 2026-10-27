<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { clearUpstreamModelCache } from '../upstreamModelCache'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PriceEditorModal from './PriceEditorModal.vue'
import DefaultPriceModal from './DefaultPriceModal.vue'
import ModelTestModal from './ModelTestModal.vue'
import ModelTestSettingsModal from './ModelTestSettingsModal.vue'
import SecretInput from './SecretInput.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatTime } from '../utils/format'
import {
  VISIBLE_MODEL_LIMIT,
  channelDisplayModels,
  visibleModels,
  hiddenModelsTitle,
} from '../utils/channelDisplay'
import { buildChannelPayload, buildPriorityUpdatePayload } from '../utils/channelForm'
import { canProbeSavedChannel, createRequestTracker, watchChannelRequestInputs } from '../utils/channelRequestState'
import {
  buildPricePrompt,
  channelPriceSummaries,
  findModelPrice,
  resolveDisplayPriceSource,
  resolveSavedChannel,
} from '../utils/modelPrice'

const channels = ref([])
const prices = ref([])
const defaultPrices = ref([])
const priceEditor = ref(null)
const showDefaultPrices = ref(false)
const testTarget = ref(null)
const showTestSettings = ref(false)
const pricePromptChannelId = ref(null)
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingId = ref(null)
const probing = ref(false)
const probeResults = ref([])
const channelKeys = ref([])
let keyDraftSeq = 0
const canonicalModels = reactive({})
const originalMappings = reactive({})
const probeRequests = createRequestTracker(() => {
  probing.value = false
})
const modelRequests = createRequestTracker()
let probeDerivedProtocols = false

const form = reactive({
  name: '',
  baseUrl: '',
  authType: 'bearer',
  protocols: ['chat_completions'],
  enabled: true,
  priority: 1,
})

watchChannelRequestInputs(form, {
  modelRequests,
  probeRequests,
  credentialSource: () => channelKeys.value.map((key) => key.credential).join('\u0000'),
  onInputsChanged: () => {
    channelKeys.value.forEach((key) => {
      key.availableModels = []
      key.modelsLoading = false
      key.modelsError = ''
      key.modelsRequestId = (key.modelsRequestId || 0) + 1
    })
    if (!probeResults.value.length && !probeDerivedProtocols) return
    probeResults.value = []
    if (probeDerivedProtocols) form.protocols = []
    probeDerivedProtocols = false
  },
})

const enabledCount = computed(() => channels.value.filter((channel) => channel.enabled).length)
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
  const models = new Set()
  channelKeys.value.forEach((key) => key.models.forEach((model) => models.add(model)))
  return [...models].sort()
})
const mappingCount = computed(() => authorizedModels.value.filter((modelId) => canonicalModels[modelId]?.trim()).length)
const canProbeProtocols = computed(() => {
  const savedChannel = channels.value.find((channel) => channel.id === editingId.value)
  if (!savedChannel?.keys?.length) return false
  return canProbeSavedChannel(savedChannel, {
    baseUrl: form.baseUrl,
    credential: channelKeys.value.some((key) => key.credential.trim()) ? 'draft' : '',
    authType: form.authType,
  })
})

function newKeyDraft(overrides = {}) {
  return {
    localId: `key-${++keyDraftSeq}`,
    id: overrides.id || null,
    name: overrides.name || '',
    credential: '',
    enabled: overrides.enabled ?? true,
    priority: overrides.priority ?? 1,
    models: [...(overrides.models || [])],
    availableModels: [],
    modelsLoading: false,
    modelsError: '',
    modelsRequestId: 0,
  }
}

function addKey() {
  const names = new Set(channelKeys.value.map((key) => key.name.trim()))
  let index = channelKeys.value.length + 1
  while (names.has(`密钥 ${index}`)) index += 1
  channelKeys.value.push(newKeyDraft({ name: `密钥 ${index}`, priority: 1 }))
}

function removeKey(index) {
  if (channelKeys.value.length <= 1) {
    notify('至少保留一个密钥', 'warning')
    return
  }
  channelKeys.value.splice(index, 1)
}

function keyModelOptions(key) {
  const options = new Map()
  ;(key.availableModels || []).forEach((model) => options.set(model.id, model))
  ;(key.models || []).forEach((modelId) => {
    if (!options.has(modelId)) options.set(modelId, { id: modelId })
  })
  return [...options.values()].sort((left, right) => left.id.localeCompare(right.id))
}

function toggleKeyModel(key, modelId) {
  const selected = new Set(key.models)
  if (selected.has(modelId)) selected.delete(modelId)
  else selected.add(modelId)
  key.models = [...selected].sort()
}

function selectAllKeyModels(key) {
  key.models = keyModelOptions(key).map((model) => model.id)
}

function clearKeyModels(key) {
  key.models = []
}

async function loadKeyModels(key) {
  const baseUrl = form.baseUrl.trim()
  const credential = key.credential.trim()
  const authType = form.authType
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
      channelId: editingId.value,
      keyId: key.id || null,
    })
    if (
      requestId !== key.modelsRequestId ||
      form.baseUrl.trim() !== baseUrl ||
      form.authType !== authType ||
      key.credential.trim() !== credential
    ) return
    key.availableModels = response?.data || []
    if (key.models.length === 0) {
      const authorized = key.availableModels.filter((model) => model.authorized).map((model) => model.id)
      key.models = authorized.length ? authorized : (key.id ? [] : key.availableModels.map((model) => model.id))
    }
  } catch (cause) {
    if (requestId === key.modelsRequestId) key.modelsError = cause.message
  } finally {
    if (requestId === key.modelsRequestId) key.modelsLoading = false
  }
}

function savedEditingChannel() {
  return channels.value.find((channel) => channel.id === editingId.value) || null
}

function canTestModel(modelId) {
  const channel = savedEditingChannel()
  if (!channel?.protocols?.length) return false
  return channel.keys?.length
    ? channel.keys.some((key) => key.enabled && key.models.includes(modelId))
    : channel.models.includes(modelId)
}

function openModelTest(modelId) {
  const channel = savedEditingChannel()
  if (!channel) {
    notify('请先保存渠道后再测试模型', 'warning')
    return
  }
  const supported = channel.keys?.length
    ? channel.keys.some((key) => key.enabled && key.models.includes(modelId))
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

const priorityEditingId = ref(null)
const priorityDraft = ref(1)
let prioritySaving = false

function startPriorityEdit(channel) {
  if (prioritySaving) {
    notify('正在保存上一处优先级修改，请稍候', 'warning')
    return
  }
  priorityEditingId.value = channel.id
  priorityDraft.value = channel.priority ?? 1
  nextTick(() => {
    const input = document.querySelector('.priority-input')
    input?.focus()
    input?.select()
  })
}

function cancelPriorityEdit() {
  priorityEditingId.value = null
}

async function commitPriorityEdit(channel) {
  if (prioritySaving) return
  if (priorityEditingId.value !== channel.id) return
  const next = Number(priorityDraft.value)
  priorityEditingId.value = null
  if (!Number.isInteger(next) || next < 1 || next > 1000) {
    notify('优先级必须是 1..1000 的整数', 'warning')
    return
  }
  if (next === (channel.priority ?? 1)) return
  prioritySaving = true
  try {
    await api.updateChannel(channel.id, buildPriorityUpdatePayload(channel, next))
    channel.priority = next
    notify('渠道优先级已更新', 'success')
  } catch (cause) {
    notify(cause.message, 'error')
    await load()
  } finally {
    prioritySaving = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [channelsData, pricesData, defaultPricesData] = await Promise.all([
      api.listChannels(),
      api.listModelPrices(),
      api.listModelDefaultPrices(),
    ])
    channels.value = channelsData
    prices.value = pricesData
    defaultPrices.value = defaultPricesData
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

function openPrice(channel, upstreamModel = null) {
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

function priceSummary(channel) {
  return priceSummaries.value.get(channel.id) || { unpriced: [], count: 0 }
}

function unpricedDisplayModels(channel, item) {
  const unpriced = priceSummary(channel).unpriced
  return (item.upstreamModels || []).filter((model) => unpriced.includes(String(model).trim()))
}

function priceSourceForDisplay(channel, item) {
  return resolveDisplayPriceSource(channel, item.upstreamModels, prices.value, defaultPrices.value)
}

function priceSourceLabel(source) {
  if (source === 'channel') return '渠道价'
  if (source === 'default') return '默认价'
  if (source === 'mixed') return '混合价'
  return '未定价'
}

function modelTitle(channel, item) {
  const base = item.title || item.displayName
  const unpriced = unpricedDisplayModels(channel, item)
  return unpriced.length ? `${base}；未配置单价: ${unpriced.join('、')}` : base
}

function configureChannelPrices(channel) {
  openPrice(channel, priceSummary(channel).unpriced[0] || null)
}

function updatePricePrompt(channel) {
  const count = channel ? priceSummary(channel).count : 0
  pricePromptChannelId.value = channel && count > 0 ? channel.id : null
  return count
}

function dismissPricePrompt() {
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
  probeDerivedProtocols = false
  modelRequests.invalidate()
  channelKeys.value = [newKeyDraft({ name: '默认密钥', priority: 1 })]
  clearMappings()
  Object.assign(form, {
    name: '',
    baseUrl: '',
    authType: 'bearer',
    protocols: ['chat_completions'],
    enabled: true,
    priority: 1,
  })
  showForm.value = true
}

function openEdit(channel) {
  editingId.value = channel.id
  probeRequests.invalidate()
  probing.value = false
  probeResults.value = []
  probeDerivedProtocols = false
  modelRequests.invalidate()
  const savedKeys = channel.keys?.length
    ? channel.keys
    : [{ name: '默认密钥', enabled: true, priority: 1, models: channel.models || [] }]
  channelKeys.value = savedKeys.map((key) => newKeyDraft({
    id: key.id,
    name: key.name,
    enabled: key.enabled,
    priority: key.priority,
    models: key.models,
  }))
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
    protocols: channel.protocols?.length ? [...channel.protocols] : ['chat_completions'],
    enabled: channel.enabled,
    priority: channel.priority ?? 1,
  })
  showForm.value = true
}

function closeChannelForm() {
  if (testTarget.value || showTestSettings.value) return
  showForm.value = false
}

function clearMapping(modelId) {
  canonicalModels[modelId] = ''
}

function copyUpstreamToCanonical(modelId) {
  canonicalModels[modelId] = modelId
  nextTick(() => {
    const el = document.getElementById(`canonical-input-${modelId}`)
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

function channelModelCount(channel) {
  return channelDisplayModels(channel).length
}

function probeState(result) {
  if (result.supported) return { label: '支持', className: 'success' }
  if (result.statusCode === 404 || result.statusCode === 405) return { label: '不支持', className: 'neutral' }
  return { label: '未知', className: 'warning' }
}

async function probeProtocols() {
  const channelId = editingId.value
  const probeBaseUrl = form.baseUrl.trim()
  const probeCredential = channelKeys.value.map((key) => key.credential).join('\u0000')
  const probeAuthType = form.authType
  const savedChannel = channels.value.find((channel) => channel.id === channelId)
  if (!canProbeSavedChannel(savedChannel, {
    baseUrl: probeBaseUrl,
    credential: probeCredential,
    authType: probeAuthType,
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
      form.authType !== probeAuthType
    ) {
      return
    }
    probeResults.value = response?.results || []
    const supported = probeResults.value.filter((item) => item.supported).map((item) => item.protocol)
    if (supported.length > 0) {
      form.protocols = supported
      probeDerivedProtocols = true
    }
    notify(
      supported.length > 0 ? `探测到：${supported.join(' / ')}` : '未探测到可用协议',
      supported.length > 0 ? 'success' : 'error',
    )
  } catch (cause) {
    if (probeRequests.isCurrent(requestId) && editingId.value === channelId) {
      notify(cause.message, 'error')
    }
  } finally {
    if (probeRequests.isCurrent(requestId)) probing.value = false
  }
}

async function save() {
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
  }
  if (authorizedModels.value.length === 0) {
    notify('请至少授权一个上游模型', 'error')
    return
  }

  saving.value = true
  try {
    const payload = buildChannelPayload(
      form,
      authorizedModels.value,
      canonicalModels,
      originalMappings,
      channelKeys.value,
    )
    const wasEditing = !!editingId.value
    const saved = wasEditing
      ? await api.updateChannel(editingId.value, payload)
      : await api.createChannel(payload)
    if (wasEditing) clearUpstreamModelCache(editingId.value)
    showForm.value = false
    probeRequests.invalidate()
    probeResults.value = []
    probeDerivedProtocols = false
    await load()
    const savedChannel = resolveSavedChannel(channels.value, saved, editingId.value, form.name)
    const unpricedCount = updatePricePrompt(savedChannel)
    if (unpricedCount > 0) {
      notify(`渠道已保存，还有 ${unpricedCount} 个上游模型未配置单价`, 'warning')
    } else {
      notify(wasEditing ? '渠道已更新' : '渠道已创建')
    }
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(channel) {
  if (!window.confirm(`确定删除渠道「${channel.name}」？`)) return
  try {
    await api.deleteChannel(channel.id)
    clearUpstreamModelCache(channel.id)
    if (pricePromptChannelId.value === channel.id) pricePromptChannelId.value = null
    notify('渠道已删除')
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
        <h2>上游接入点</h2>
        <p>一个渠道对应一个上游接入点，可选择支持 Chat Completions、Responses、Anthropic Messages 或其组合。</p>
      </div>
      <div class="section-head-actions">
        <button type="button" class="button subtle" @click="showDefaultPrices = true">
          <Icon name="price" :size="16" />
          模型默认价
        </button>
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建渠道
        </button>
      </div>
    </div>

    <div class="stat-row two">
      <div class="stat-card">
        <span>渠道总数</span>
        <strong>{{ channels.length }}</strong>
        <small>所有已配置接入点</small>
      </div>
      <div class="stat-card">
        <span>启用中</span>
        <strong class="text-success">{{ enabledCount }}</strong>
        <small>已启用并提供服务</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

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
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>基础地址</th>
              <th title="数值越小越优先；双击单元格可直接修改">优先级</th>
              <th>协议</th>
              <th>授权模型</th>
              <th>状态</th>
              <th>更新时间</th>
              <th class="actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="channel in channels" :key="channel.id">
              <td>
                <div class="primary-cell">{{ channel.name }}</div>
                <div class="channel-id-cell">
                  <span class="mono muted small">{{ channel.id }}</span>
                  <CopyButton :text="channel.id" title="复制渠道 ID" variant="list" :size="12" />
                </div>
                <div class="muted small">
                  {{ channel.keys?.length || 0 }} 个密钥
                  <template v-if="channel.keys?.length">· {{ channel.keys.filter((key) => key.enabled).length }} 个启用</template>
                </div>
              </td>
              <td><span class="mono address">{{ channel.baseUrl }}</span></td>
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
                  aria-label="渠道优先级"
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
                <div class="protocol-badges">
                  <span v-for="protocol in channel.protocols" :key="protocol" class="badge info">
                    {{ protocol }}
                  </span>
                </div>
              </td>
              <td>
                <div v-if="channel.models?.length" class="channel-model-list">
                  <span
                    v-for="item in visibleModels(channel)"
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
                  <span
                    v-if="channelModelCount(channel) > VISIBLE_MODEL_LIMIT"
                    class="badge neutral channel-model-name"
                    :title="hiddenModelsTitle(channel)"
                  >
                    +{{ channelModelCount(channel) - VISIBLE_MODEL_LIMIT }}
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
                <span class="badge" :class="channel.enabled ? 'success' : 'neutral'">
                  {{ channel.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="muted">{{ formatTime(channel.updatedAt) }}</td>
              <td class="actions-col">
                <div class="row-actions">
                  <button type="button" class="icon-button" title="配置模型单价" @click="openPrice(channel)">
                    <Icon name="price" :size="16" />
                  </button>
                  <button type="button" class="icon-button" title="编辑" @click="openEdit(channel)">
                    <Icon name="edit" :size="16" />
                  </button>
                  <button type="button" class="icon-button danger" title="删除" @click="remove(channel)">
                    <Icon name="trash" :size="16" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <Modal v-if="showForm" :title="editingId ? '编辑渠道' : '新建渠道'" wide @close="closeChannelForm">
      <form class="form-grid" @submit.prevent="save">
        <label class="field">
          <span>渠道名称</span>
          <input v-model="form.name" placeholder="例如 openai-primary" autocomplete="off" />
          <small>渠道在网关中的唯一标识名称。</small>
        </label>
        <label class="field">
          <span>基础地址</span>
          <input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" autocomplete="off" />
          <small>网关会按请求协议在此地址后追加对应端点，并用 <code>/models</code> 拉取模型列表。</small>
        </label>
        <label class="field">
          <span>上游鉴权</span>
          <select v-model="form.authType">
            <option value="bearer">Bearer token</option>
            <option value="anthropic">Anthropic API key（x-api-key）</option>
          </select>
          <small>Anthropic 原生渠道选 Anthropic API key；调用方密钥仍由网关单独鉴权。</small>
        </label>
        <div class="field">
          <span>支持协议</span>
          <div class="protocol-options">
            <label><input v-model="form.protocols" type="checkbox" value="chat_completions" /> Chat Completions</label>
            <label><input v-model="form.protocols" type="checkbox" value="responses" /> Responses</label>
            <label><input v-model="form.protocols" type="checkbox" value="messages" /> Anthropic Messages</label>
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
            <span v-else-if="!canProbeProtocols" class="muted small">保存地址、密钥凭据或鉴权方式后可探测</span>
            <span v-else class="muted small">使用已保存密钥发送最小请求（1 token）</span>
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
        <div class="field channel-key-pool">
          <div class="channel-model-auth-head">
            <div>
              <span>上游密钥</span>
              <small>同一渠道的密钥共用基础地址、鉴权方式和协议；密钥优先级只在当前渠道内生效。</small>
            </div>
            <span class="badge info">{{ channelKeys.length }} 个密钥</span>
          </div>
          <div class="channel-key-list">
            <div v-for="(key, index) in channelKeys" :key="key.localId" class="channel-key-card">
              <div class="channel-key-card-head">
                <label class="field">
                  <span>密钥名称</span>
                  <input v-model="key.name" placeholder="例如 primary" autocomplete="off" />
                </label>
                <label class="field">
                  <span>密钥优先级</span>
                  <input v-model.number="key.priority" type="number" min="1" max="1000" step="1" />
                </label>
                <label class="switch-row channel-key-enabled">
                  <input v-model="key.enabled" type="checkbox" />
                  <span class="switch"></span>
                  <span><strong>启用</strong></span>
                </label>
                <button type="button" class="button ghost mini" @click="removeKey(index)">删除</button>
              </div>
              <label class="field">
                <span>上游凭据</span>
                <SecretInput
                  v-model="key.credential"
                  :placeholder="key.id ? '留空保持当前凭据' : '输入上游凭据（Bearer Token 或 Anthropic API key）'"
                  autocomplete="new-password"
                />
              </label>
              <div class="channel-key-models">
                <div class="channel-model-auth-toolbar">
                  <span class="muted small">可用模型（{{ key.models.length }}）</span>
                  <button type="button" class="button subtle mini" :disabled="key.modelsLoading" @click="loadKeyModels(key)">
                    {{ key.modelsLoading ? '读取中…' : '读取模型' }}
                  </button>
                  <button type="button" class="button ghost mini" @click="selectAllKeyModels(key)">全选</button>
                  <button type="button" class="button ghost mini" @click="clearKeyModels(key)">清空</button>
                </div>
                <div v-if="key.modelsError" class="callout danger">{{ key.modelsError }}</div>
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
                  填写凭据后点击“读取模型”，再选择该密钥可用的模型。
                </div>
              </div>
            </div>
          </div>
          <button type="button" class="button ghost mini channel-key-add" @click="addKey">添加密钥</button>
          <small>同一模型可以配置在多个密钥上；同优先级密钥轮询，低优先级密钥只在高优先级密钥失败或冷却时启用。</small>
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
              <button type="button" class="button ghost mini" @click="showTestSettings = true">全局测试设置</button>
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
        <label class="field">
          <span>渠道优先级</span>
          <input v-model.number="form.priority" type="number" min="1" max="1000" step="1" />
          <small>数值越小越优先（1..1000），仅影响直连路由：同优先级渠道轮询分摊，低优先级只在高优先级全部失败或冷却时启用；别名候选档位由别名自行声明。</small>
        </label>
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>启用渠道</strong>
            <small>停用后不会参与模型路由。</small>
          </span>
        </label>
      </form>
      <template #footer>
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

      <ModelTestSettingsModal
        v-if="showTestSettings"
        @close="showTestSettings = false"
      />
    </Modal>

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
</style>

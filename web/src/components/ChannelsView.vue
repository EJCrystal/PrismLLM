<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { clearUpstreamModelCache } from '../upstreamModelCache'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PriceEditorModal from './PriceEditorModal.vue'
import SecretInput from './SecretInput.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatTime } from '../utils/format'
import {
  VISIBLE_MODEL_LIMIT,
  visibleModels,
  hiddenModelsTitle,
} from '../utils/channelDisplay'
import { buildChannelPayload } from '../utils/channelForm'
import { findModelPrice } from '../utils/modelPrice'

const channels = ref([])
const prices = ref([])
const priceEditor = ref(null)
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const editingId = ref(null)
const probing = ref(false)
const probeResults = ref([])
const availableModels = ref([])
const authorizedModels = ref([])
const canonicalModels = reactive({})
const originalMappings = reactive({})
const modelsLoading = ref(false)
const modelsError = ref('')
const modelSearch = ref('')
const modelsLoaded = ref(false)
let probeRequestId = 0
let modelsRequestId = 0
let probeDerivedProtocols = false

const form = reactive({
  name: '',
  baseUrl: '',
  credential: '',
  protocols: ['chat_completions'],
  enabled: true,
  exposeUpstreamModels: true,
  priority: 1,
})

watch(
  () => [form.baseUrl, form.credential],
  () => {
    modelsRequestId += 1
    availableModels.value = []
    modelsLoaded.value = false
    modelSearch.value = ''
    if (!probeResults.value.length && !probeDerivedProtocols) return
    probeResults.value = []
    if (probeDerivedProtocols) form.protocols = []
    probeDerivedProtocols = false
  },
)

const enabledCount = computed(() => channels.value.filter((channel) => channel.enabled).length)
const mappingCount = computed(() => authorizedModels.value.filter((modelId) => canonicalModels[modelId]?.trim()).length)
const filteredModels = computed(() => {
  const query = modelSearch.value.trim().toLowerCase()
  if (!query) return availableModels.value
  return availableModels.value.filter((model) => [model.id, model.ownedBy || '']
    .some((value) => value.toLowerCase().includes(query)))
})

const priorityEditingId = ref(null)
const priorityDraft = ref(1)
let prioritySaving = false

function startPriorityEdit(channel) {
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
    await api.updateChannel(channel.id, {
      name: channel.name,
      baseUrl: channel.baseUrl,
      protocols: [...(channel.protocols || [])],
      enabled: channel.enabled,
      priority: next,
    })
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
    const [channelsData, pricesData] = await Promise.all([
      api.listChannels(),
      api.listModelPrices(),
    ])
    channels.value = channelsData
    prices.value = pricesData
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

async function onPriceSaved() {
  priceEditor.value = null
  notify('计费单价已保存')
  try {
    prices.value = await api.listModelPrices()
  } catch {
    notify('单价已保存，但刷新列表失败', 'warning')
  }
}

function openCreate() {
  editingId.value = null
  probeRequestId += 1
  probing.value = false
  probeResults.value = []
  probeDerivedProtocols = false
  modelsRequestId += 1
  availableModels.value = []
  authorizedModels.value = []
  clearMappings()
  modelsLoading.value = false
  modelsError.value = ''
  modelsLoaded.value = false
  modelSearch.value = ''
  Object.assign(form, {
    name: '',
    baseUrl: '',
    credential: '',
    protocols: ['chat_completions'],
    enabled: true,
    exposeUpstreamModels: true,
    priority: 1,
  })
  showForm.value = true
}

function openEdit(channel) {
  editingId.value = channel.id
  probeRequestId += 1
  probing.value = false
  probeResults.value = []
  probeDerivedProtocols = false
  modelsRequestId += 1
  availableModels.value = []
  authorizedModels.value = [...(channel.models || [])]
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
  modelsLoading.value = false
  modelsError.value = ''
  modelsLoaded.value = false
  modelSearch.value = ''
  Object.assign(form, {
    name: channel.name,
    baseUrl: channel.baseUrl,
    credential: '',
    protocols: channel.protocols?.length ? [...channel.protocols] : ['chat_completions'],
    enabled: channel.enabled,
    exposeUpstreamModels: channel.exposeUpstreamModels !== false,
    priority: channel.priority ?? 1,
  })
  showForm.value = true
}

async function loadAvailableModels() {
  const baseUrl = form.baseUrl.trim()
  const credential = form.credential.trim()
  if (!baseUrl) {
    notify('请先填写基础地址', 'warning')
    return
  }
  if (!editingId.value && !credential) {
    notify('新建渠道需要填写上游凭据才能加载模型', 'warning')
    return
  }
  const requestId = ++modelsRequestId
  modelsLoading.value = true
  modelsError.value = ''
  try {
    const response = await api.discoverChannelModels({
      baseUrl,
      credential: credential || null,
      channelId: editingId.value,
    })
    if (requestId !== modelsRequestId) return
    availableModels.value = response?.data || []
    availableModels.value.forEach((model) => {
      if (model.authorized && model.canonicalModelId && !canonicalModels[model.id]?.trim()) {
        canonicalModels[model.id] = model.canonicalModelId
      }
    })
    if (authorizedModels.value.length === 0) {
      authorizedModels.value = editingId.value
        ? availableModels.value.filter((model) => model.authorized).map((model) => model.id)
        : availableModels.value.map((model) => model.id)
    }
    modelsLoaded.value = true
  } catch (cause) {
    if (requestId === modelsRequestId) modelsError.value = cause.message
  } finally {
    if (requestId === modelsRequestId) modelsLoading.value = false
  }
}

function toggleModel(modelId) {
  const selected = new Set(authorizedModels.value)
  if (selected.has(modelId)) selected.delete(modelId)
  else selected.add(modelId)
  authorizedModels.value = [...selected]
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

function selectAllModels() {
  authorizedModels.value = availableModels.value.map((model) => model.id)
}

function clearAllModels() {
  authorizedModels.value = []
}

function isAuthorized(modelId) {
  return authorizedModels.value.includes(modelId)
}

function probeState(result) {
  if (result.supported) return { label: '支持', className: 'success' }
  if (result.statusCode === 404 || result.statusCode === 405) return { label: '不支持', className: 'neutral' }
  return { label: '未知', className: 'warning' }
}

async function probeProtocols() {
  if (!editingId.value) {
    notify('请先保存渠道，再探测协议', 'error')
    return
  }
  const savedChannel = channels.value.find((channel) => channel.id === editingId.value)
  const probeBaseUrl = form.baseUrl.trim()
  const probeCredential = form.credential.trim()
  if (!savedChannel || probeBaseUrl !== savedChannel.baseUrl.trim() || probeCredential) {
    notify('请先保存基础地址或凭据，再探测协议', 'error')
    return
  }
  const channelId = editingId.value
  const requestId = ++probeRequestId
  probing.value = true
  probeResults.value = []
  try {
    const response = await api.probeChannelProtocols(channelId)
    if (
      requestId !== probeRequestId ||
      editingId.value !== channelId ||
      form.baseUrl.trim() !== probeBaseUrl ||
      form.credential.trim() !== probeCredential
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
    if (requestId === probeRequestId && editingId.value === channelId) {
      notify(cause.message, 'error')
    }
  } finally {
    if (requestId === probeRequestId) probing.value = false
  }
}

async function save() {
  if (!form.name.trim() || !form.baseUrl.trim()) {
    notify('请填写渠道名称和基础地址', 'error')
    return
  }
  if (!editingId.value && !form.credential.trim()) {
    notify('新建渠道必须提供上游凭据', 'error')
    return
  }
  if (form.protocols.length === 0) {
    notify('至少选择一个支持协议', 'error')
    return
  }
  if (authorizedModels.value.length === 0) {
    notify('请至少授权一个上游模型', 'error')
    return
  }

  saving.value = true
  try {
    const payload = buildChannelPayload(form, authorizedModels.value, canonicalModels, originalMappings)
    if (editingId.value) {
      await api.updateChannel(editingId.value, payload)
      clearUpstreamModelCache(editingId.value)
      notify('渠道已更新')
    } else {
      await api.createChannel(payload)
      notify('渠道已创建')
    }
    showForm.value = false
    probeRequestId += 1
    probing.value = false
    probeResults.value = []
    probeDerivedProtocols = false
    await load()
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
        <p>一个渠道对应一个 OpenAI 兼容接入点，可选择支持 Chat Completions、Responses 或二者皆可。</p>
      </div>
      <button type="button" class="button primary" @click="openCreate">
        <Icon name="plus" :size="17" />
        新建渠道
      </button>
    </div>

    <div class="stat-row">
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
      <div class="stat-card accent">
        <span>凭据策略</span>
        <strong>AES-256-GCM</strong>
        <small>接口不回显上游凭据</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || channels.length === 0"
        :loading="loading"
        loading-text="正在读取渠道…"
        :empty="channels.length === 0"
        icon="channels"
        title="还没有渠道"
        desc="先接入一个 OpenAI 兼容上游，授权模型并对外提供服务。"
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
              <th>凭据</th>
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
              </td>
              <td><span class="mono address">{{ channel.baseUrl }}</span></td>
              <td
                class="priority-cell"
                title="双击编辑优先级"
                @dblclick="startPriorityEdit(channel)"
              >
                <input
                  v-if="priorityEditingId === channel.id"
                  v-model.number="priorityDraft"
                  class="priority-input"
                  type="number"
                  min="1"
                  max="1000"
                  step="1"
                  @keyup.enter="commitPriorityEdit(channel)"
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
                    :key="item.upstreamModel"
                    class="badge info channel-model-name"
                    :title="item.title"
                  >
                    {{ item.displayName }}
                  </span>
                  <span
                    v-if="channel.models.length > VISIBLE_MODEL_LIMIT"
                    class="badge neutral channel-model-name"
                    :title="hiddenModelsTitle(channel)"
                  >
                    +{{ channel.models.length - VISIBLE_MODEL_LIMIT }}
                  </span>
                </div>
                <span v-else class="badge warning">未授权模型</span>
              </td>
              <td>
                <span class="badge" :class="channel.enabled ? 'success' : 'neutral'">
                  {{ channel.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td>
                <span class="badge" :class="channel.credentialSet ? 'info' : 'warning'">
                  {{ channel.credentialSet ? '已配置' : '未配置' }}
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

    <Modal v-if="showForm" :title="editingId ? '编辑渠道' : '新建渠道'" wide @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <label class="field">
          <span>渠道名称</span>
          <input v-model="form.name" placeholder="例如 openai-primary" autocomplete="off" />
          <small>渠道在网关中的唯一标识名称。</small>
        </label>
        <label class="field">
          <span>基础地址</span>
          <input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" autocomplete="off" />
          <small>网关会按请求协议在此地址后追加 <code>/chat/completions</code> 或 <code>/responses</code>，并用 <code>/models</code> 拉取模型列表。</small>
        </label>
        <div class="field">
          <span>支持协议</span>
          <div class="protocol-options">
            <label><input v-model="form.protocols" type="checkbox" value="chat_completions" /> Chat Completions</label>
            <label><input v-model="form.protocols" type="checkbox" value="responses" /> Responses</label>
          </div>
          <div class="protocol-probe-row">
            <button
              type="button"
              class="button subtle mini"
              :disabled="!editingId || probing"
              @click="probeProtocols"
            >
              {{ probing ? '探测中…' : '探测协议' }}
            </button>
            <span v-if="!editingId" class="muted small">保存渠道后可探测</span>
            <span v-else class="muted small">使用已保存凭据发送最小请求（1 token）</span>
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
        <label class="field">
          <span>上游凭据</span>
          <SecretInput
            v-model="form.credential"
            :placeholder="editingId ? '留空保持当前凭据' : '输入上游 Bearer Token'"
            autocomplete="new-password"
          />
          <small>v1 只支持 Bearer。凭据会加密存储，保存后不再回显。</small>
        </label>
        <div class="field channel-model-auth">
          <div class="channel-model-auth-head">
            <div>
              <span>授权上游模型</span>
              <small>选中的模型会进入模型目录并对外提供服务。</small>
            </div>
            <span class="badge info">{{ authorizedModels.length }} 个已选</span>
          </div>
          <div class="channel-model-auth-toolbar">
            <button type="button" class="button subtle mini" :disabled="modelsLoading" @click="loadAvailableModels">
              {{ modelsLoading ? '读取中…' : (modelsLoaded ? '刷新上游模型' : '加载上游模型') }}
            </button>
            <button v-if="availableModels.length" type="button" class="button ghost mini" @click="selectAllModels">全选</button>
            <button v-if="availableModels.length" type="button" class="button ghost mini" @click="clearAllModels">清空</button>
            <span v-if="!editingId && !modelsLoaded" class="muted small">填写基础地址和凭据后加载</span>
          </div>
          <div v-if="modelsError" class="callout danger">{{ modelsError }}</div>
          <div v-if="availableModels.length" class="channel-model-auth-body">
            <div class="search-box channel-model-auth-search">
              <Icon name="search" :size="15" />
              <input v-model="modelSearch" placeholder="搜索模型" />
            </div>
            <div class="channel-model-auth-list">
              <label v-for="model in filteredModels" :key="model.id" class="channel-model-option">
                <input type="checkbox" :checked="isAuthorized(model.id)" @change="toggleModel(model.id)" />
                <span><code>{{ model.id }}</code><small v-if="model.ownedBy">上游 owned_by: {{ model.ownedBy }}</small></span>
              </label>
              <p v-if="filteredModels.length === 0" class="muted small channel-model-auth-empty">没有匹配的模型。</p>
            </div>
          </div>
          <div v-else-if="authorizedModels.length" class="channel-model-auth-selected">
            <span v-for="modelId in authorizedModels" :key="modelId" class="badge neutral">{{ modelId }}</span>
          </div>
          <div v-else class="channel-model-auth-placeholder">填写基础地址和凭据，点击“加载上游模型”选择允许使用的模型。</div>
        </div>
        <div v-if="authorizedModels.length" class="channel-canonical-section">
          <div class="channel-model-auth-head">
            <div>
              <span>对外模型 ID</span>
              <small v-if="form.exposeUpstreamModels !== false">
                调用方在请求体里使用的模型名；留空则直接使用上游模型名。
              </small>
              <small v-else>
                当前渠道仅通过别名访问；留空不会公开上游模型名，只能由别名引用。
              </small>
            </div>
            <span v-if="mappingCount" class="badge info">{{ mappingCount }} 个已自定义</span>
            <span v-else class="badge neutral">全部沿用上游名</span>
          </div>
          <div class="channel-canonical-list">
            <div class="channel-canonical-header">
              <span>上游模型</span>
              <span></span>
              <span>
                对外模型 ID（{{ form.exposeUpstreamModels === false ? '可选；留空不公开上游模型' : '留空沿用上游模型名' }}）
              </span>
            </div>
            <div v-for="modelId in authorizedModels" :key="modelId" class="channel-canonical-row">
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
                  :placeholder="form.exposeUpstreamModels === false
                    ? '可选；留空仅通过别名调用'
                    : '留空沿用上游模型名（点击箭头填入）'"
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
          <input v-model="form.exposeUpstreamModels" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>允许上游模型名直连</strong>
            <small>开启后可直接使用上游模型名；关闭后只能通过配置的别名访问。</small>
          </span>
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
        <button type="button" class="button ghost" @click="showForm = false">取消</button>
        <button type="button" class="button primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存渠道' }}
        </button>
      </template>
    </Modal>

    <PriceEditorModal
      v-if="priceEditor"
      :channels="priceEditor.channels"
      :price="priceEditor.price"
      :prices="prices"
      :initial-target="priceEditor.initialTarget"
      @close="priceEditor = null"
      @saved="onPriceSaved"
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

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { copyText } from '../clipboard'
import { notify } from '../toast'
import { clearUpstreamModelCache } from '../upstreamModelCache'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const channels = ref([])
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
const mappingSources = reactive({})
const modelsLoading = ref(false)
const modelsError = ref('')
const suggestionsLoading = ref(false)
const suggestionsError = ref('')
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
})

const aliasOnly = computed({
  get: () => !form.exposeUpstreamModels,
  set: (value) => {
    form.exposeUpstreamModels = !value
  },
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

async function copyChannelId(id) {
  try {
    await copyText(id)
    notify('渠道 ID 已复制')
  } catch {
    window.prompt('复制渠道 ID', id)
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    channels.value = await api.listChannels()
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
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
  suggestionsLoading.value = false
  suggestionsError.value = ''
  modelsLoaded.value = false
  modelSearch.value = ''
  Object.assign(form, {
    name: '',
    baseUrl: '',
    credential: '',
    protocols: ['chat_completions'],
    enabled: true,
    exposeUpstreamModels: true,
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
    if (mapping.canonicalModelId) {
      canonicalModels[mapping.upstreamModel] = mapping.canonicalModelId
      mappingSources[mapping.upstreamModel] = mapping.mappingSource || 'manual'
    }
  })
  modelsLoading.value = false
  modelsError.value = ''
  suggestionsLoading.value = false
  suggestionsError.value = ''
  modelsLoaded.value = false
  modelSearch.value = ''
  Object.assign(form, {
    name: channel.name,
    baseUrl: channel.baseUrl,
    credential: '',
    protocols: channel.protocols?.length ? [...channel.protocols] : ['chat_completions'],
    enabled: channel.enabled,
    exposeUpstreamModels: channel.exposeUpstreamModels !== false,
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
        mappingSources[model.id] = model.mappingSource || 'manual'
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

async function autoMatchModels() {
  if (authorizedModels.value.length === 0) {
    notify('请先选择授权模型', 'warning')
    return
  }
  suggestionsLoading.value = true
  suggestionsError.value = ''
  try {
    const response = await api.getModelSuggestions(authorizedModels.value)
    if (!response?.loaded) {
      suggestionsError.value = response?.error || 'models.dev 目录暂不可用'
      return
    }
    let matched = 0
    ;(response.data || []).forEach((item) => {
      if (canonicalModels[item.model]?.trim()) return
      const candidate = item.candidates?.[0]
      if (candidate) {
        // id 是某个具体服务商的模型名；规范映射必须保存 canonical_model_id，
        // 否则会把渠道私有前缀重新暴露给调用方。
        canonicalModels[item.model] = candidate.canonicalId || candidate.id
        mappingSources[item.model] = 'models_dev'
        matched += 1
      }
    })
    notify(
      matched > 0 ? `已匹配 ${matched} 个规范 ID` : '没有找到可用的规范 ID',
      matched > 0 ? 'success' : 'warning',
    )
  } catch (cause) {
    suggestionsError.value = cause.message
  } finally {
    suggestionsLoading.value = false
  }
}

function clearMappings() {
  Object.keys(canonicalModels).forEach((key) => delete canonicalModels[key])
  Object.keys(mappingSources).forEach((key) => delete mappingSources[key])
}

function mappingSourceLabel(source) {
  if (source === 'models_dev') return 'models.dev'
  if (source === 'manual') return '手动'
  return ''
}

function mappedCount(channel) {
  return (channel.modelMappings || []).filter((mapping) => mapping.canonicalModelId).length
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
    const payload = {
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      credential: form.credential.trim() || null,
      authType: 'bearer',
      protocols: form.protocols,
      enabled: form.enabled,
      modelMappings: authorizedModels.value.map((upstreamModel) => ({
        upstreamModel,
        canonicalModelId: canonicalModels[upstreamModel]?.trim() || null,
        mappingSource: canonicalModels[upstreamModel]?.trim()
          ? (mappingSources[upstreamModel] || 'manual')
          : null,
      })),
      exposeUpstreamModels: form.exposeUpstreamModels,
    }
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

function formatTime(value) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(value)
    : '—'
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
        <small>可参与别名路由</small>
      </div>
      <div class="stat-card accent">
        <span>凭据策略</span>
        <strong>AES-256-GCM</strong>
        <small>接口不回显上游凭据</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>

    <div class="panel table-panel">
      <div v-if="loading" class="loading-state">
        <span class="spinner"></span>
        正在读取渠道…
      </div>
      <div v-else-if="channels.length === 0" class="empty-state">
        <div class="empty-icon"><Icon name="channels" :size="26" /></div>
        <h3>还没有渠道</h3>
        <p>先接入一个 OpenAI 兼容上游，再创建模型别名。</p>
        <button type="button" class="button primary" @click="openCreate">
          <Icon name="plus" :size="17" />
          新建渠道
        </button>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>基础地址</th>
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
                  <button
                    type="button"
                    class="icon-button list-copy"
                    title="复制渠道 ID"
                    @click="copyChannelId(channel.id)"
                  >
                    <Icon name="copy" :size="12" />
                  </button>
                </div>
              </td>
              <td><span class="mono address">{{ channel.baseUrl }}</span></td>
              <td>
                <div class="protocol-badges">
                  <span v-for="protocol in channel.protocols" :key="protocol" class="badge info">
                    {{ protocol }}
                  </span>
                </div>
              </td>
              <td>
                <div class="channel-exposure-badges">
                  <span class="badge" :class="channel.models?.length ? 'info' : 'warning'">
                    {{ channel.models?.length || 0 }} 个
                  </span>
                  <span class="badge" :class="channel.exposeUpstreamModels === false ? 'neutral' : 'info'">
                    {{ channel.exposeUpstreamModels === false ? '仅别名' : '可直连' }}
                  </span>
                  <span v-if="mappedCount(channel)" class="badge success">
                    规范 {{ mappedCount(channel) }}
                  </span>
                </div>
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

    <Modal v-if="showForm" :title="editingId ? '编辑渠道' : '新建渠道'" @close="showForm = false">
      <form class="form-grid" @submit.prevent="save">
        <label class="field">
          <span>渠道名称</span>
          <input v-model="form.name" placeholder="例如 openai-primary" autocomplete="off" />
          <small>用于在别名候选中识别这个接入点。</small>
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
          <input
            v-model="form.credential"
            type="password"
            :placeholder="editingId ? '留空保持当前凭据' : '输入上游 Bearer Token'"
            autocomplete="new-password"
          />
          <small>v1 只支持 Bearer。凭据会加密存储，保存后不再回显。</small>
        </label>
        <div class="field channel-model-auth">
          <div class="channel-model-auth-head">
            <div>
              <span>授权上游模型</span>
              <small>只有选中的模型会进入模型目录、别名路由和直连路由。</small>
            </div>
            <span class="badge info">{{ authorizedModels.length }} 个已选</span>
          </div>
          <div class="channel-model-auth-toolbar">
            <button type="button" class="button subtle mini" :disabled="modelsLoading" @click="loadAvailableModels">
              {{ modelsLoading ? '读取中…' : (modelsLoaded ? '刷新上游模型' : '加载上游模型') }}
            </button>
            <button v-if="authorizedModels.length" type="button" class="button ghost mini" :disabled="suggestionsLoading" @click="autoMatchModels">
              {{ suggestionsLoading ? '匹配中…' : '自动匹配规范 ID' }}
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
              <span>规范 ID 映射</span>
              <small>对外模型 ID 优先使用规范 ID；留空则使用上游模型 ID。建议采用 models.dev 的 canonical ID。</small>
            </div>
            <span class="badge info">{{ mappingCount }} 个已映射</span>
          </div>
          <div v-if="suggestionsError" class="callout danger">{{ suggestionsError }}</div>
          <div class="channel-canonical-list">
            <label v-for="modelId in authorizedModels" :key="modelId" class="channel-canonical-row">
              <code>{{ modelId }}</code>
              <input
                v-model="canonicalModels[modelId]"
                placeholder="留空则使用上游 ID"
                autocomplete="off"
                @input="mappingSources[modelId] = 'manual'"
              />
              <span v-if="canonicalModels[modelId]?.trim() && mappingSources[modelId]" class="badge info">
                {{ mappingSourceLabel(mappingSources[modelId]) }}
              </span>
            </label>
          </div>
        </div>
        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>启用渠道</strong>
            <small>停用后不会参与新的别名路由。</small>
          </span>
        </label>
        <label class="switch-row">
          <input v-model="aliasOnly" type="checkbox" />
          <span class="switch"></span>
          <span>
            <strong>仅通过别名暴露</strong>
            <small>开启后，该渠道模型不进入 /v1/models 直连列表，也不能用上游模型名直接调用；别名路由仍然可用。</small>
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

  </section>
</template>

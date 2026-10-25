<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { copyText } from '../clipboard'
import { notify } from '../toast'
import Icon from './Icon.vue'
import TablePlaceholder from './TablePlaceholder.vue'
import { formatTime } from '../utils/format'

const models = ref([])
const loading = ref(true)
const error = ref('')
const loadedAt = ref('')
const catalog = ref([])
const catalogMatches = ref({})
const catalogLoading = ref(true)
const catalogLoaded = ref(false)
const catalogError = ref('')
const catalogSource = ref('https://models.dev/catalog.json')
const catalogSearch = ref('')
const catalogLimit = ref(150)

const aliasCount = computed(() => models.value.filter((model) => model.source === 'alias').length)
const canonicalCount = computed(() => models.value.filter((model) => model.source === 'canonical').length)
const upstreamCount = computed(() => models.value.filter((model) => model.source === 'upstream').length)
const matchedCount = computed(() => models.value.filter((model) => matchesFor(model.id).length > 0).length)
const filteredCatalog = computed(() => {
  const query = catalogSearch.value.trim().toLowerCase()
  if (!query) return catalog.value
  return catalog.value.filter((model) => [model.id, model.canonicalId, model.provider, model.providerId, model.developer, model.developerId]
    .some((value) => (value || '').toLowerCase().includes(query)))
})
const visibleCatalog = computed(() => filteredCatalog.value.slice(0, catalogLimit.value))
const rawJson = computed(() => JSON.stringify({
  object: 'list',
  data: models.value.map((model) => ({
    id: model.id,
    object: 'model',
    created: 0,
    owned_by: model.ownedBy || 'llm-gateway',
  })),
}, null, 2))

async function loadExposedModels() {
  loading.value = true
  error.value = ''
  try {
    const response = await api.getExposedModels()
    models.value = response?.data || []
    loadedAt.value = formatTime(new Date(), { style: 'timeOnly' })
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function loadCatalog() {
  catalogLoading.value = true
  catalogError.value = ''
  try {
    const response = await api.getModelsDevCatalog()
    catalog.value = response?.data || []
    catalogMatches.value = response?.matches || {}
    catalogLoaded.value = Boolean(response?.loaded)
    catalogError.value = response?.error || ''
    catalogSource.value = response?.source || catalogSource.value
  } catch (cause) {
    catalogError.value = cause.message
  } finally {
    catalogLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadExposedModels(), loadCatalog()])
}

function matchesFor(modelId) {
  return catalogMatches.value[modelId] || []
}

function uniqueMatchesFor(modelId) {
  const seen = new Set()
  return matchesFor(modelId).filter((match) => {
    const key = match.id.toLowerCase()
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function matchProviders(modelId, modelName) {
  return matchesFor(modelId)
    .filter((match) => match.id.toLowerCase() === modelName.toLowerCase())
    .map((match) => match.provider)
    .filter((provider, index, providers) => providers.indexOf(provider) === index)
}

watch(catalogSearch, () => {
  catalogLimit.value = 150
})

async function copyJson() {
  try {
    await copyText(rawJson.value)
    notify('原始响应已复制')
  } catch {
    notify('复制失败，请手动选择 JSON', 'error')
  }
}

function sourceLabel(source) {
  if (source === 'alias') return '别名'
  if (source === 'canonical') return '规范映射'
  return '上游直连'
}

function sourceClass(source) {
  if (source === 'alias') return 'info'
  if (source === 'canonical') return 'success'
  return 'warning'
}

onMounted(() => {
  loadExposedModels()
  loadCatalog()
})
</script>

<template>
  <section class="page-stack">
    <div class="section-head">
      <div>
        <h2>对外模型列表 <span class="badge info">只读</span></h2>
        <p>调用方通过 <code>GET /v1/models</code> 获取到的模型结果，models.dev 已匹配 {{ matchedCount }}/{{ models.length }}。</p>
      </div>
      <button type="button" class="button subtle" :disabled="loading || catalogLoading" @click="refreshAll">
        <Icon name="refresh" :size="16" />
        {{ loading || catalogLoading ? '读取中…' : '刷新' }}
      </button>
    </div>

    <div class="stat-row four">
      <div class="stat-card accent">
        <span>对外模型总数</span>
        <strong>{{ models.length }}</strong>
        <small>任意有效调用方密钥都能看到</small>
      </div>
      <div class="stat-card">
        <span>别名模型</span>
        <strong>{{ aliasCount }}</strong>
        <small>来自启用的模型别名</small>
      </div>
      <div class="stat-card">
        <span>规范映射</span>
        <strong>{{ canonicalCount }}</strong>
        <small>来自渠道模型规范 ID</small>
      </div>
      <div class="stat-card">
        <span>上游直连</span>
        <strong>{{ upstreamCount }}</strong>
        <small>来自渠道已授权模型</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">
      {{ error }}
      <button type="button" class="button ghost mini" @click="refreshAll">重试</button>
    </div>

    <div class="panel table-panel">
      <TablePlaceholder
        v-if="loading || models.length === 0"
        :loading="loading"
        loading-text="正在读取模型…"
        :empty="models.length === 0"
        icon="models"
        title="当前没有对外暴露的模型"
        desc="授权模型并允许公开直连，或创建别名后，这里会显示对应模型。若预期出现上游模型名，请检查渠道的“仅通过别名暴露”设置。"
      />
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>模型 ID</th>
              <th>来源</th>
              <th>上游模型</th>
              <th>渠道</th>
              <th>models.dev 匹配</th>
              <th>owned_by</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="model in models" :key="model.id">
              <td><code>{{ model.id }}</code></td>
              <td>
                <span class="badge" :class="sourceClass(model.source)">
                  {{ sourceLabel(model.source) }}
                </span>
              </td>
              <td>
                <span v-if="model.upstreamModels?.length" class="mono small">{{ model.upstreamModels.join('、') }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td>
                <span v-if="model.channelNames?.length" class="small">{{ model.channelNames.join('、') }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td>
                <div v-if="catalogLoading" class="muted small">读取中…</div>
                <div v-else-if="catalogError" class="muted small">暂不可用</div>
                <div v-else-if="uniqueMatchesFor(model.id).length" class="models-dev-matches">
                  <span
                    v-for="match in uniqueMatchesFor(model.id).slice(0, 4)"
                    :key="`${match.providerId}-${match.id}`"
                    class="models-dev-match"
                    :title="matchProviders(model.id, match.id).join('、')"
                  >
                    <code>{{ match.id }}</code>
                    <small>{{ match.developer || match.provider }} · 服务：{{ match.provider }}</small>
                  </span>
                  <span v-if="uniqueMatchesFor(model.id).length > 4" class="badge neutral">
                    +{{ uniqueMatchesFor(model.id).length - 4 }}
                  </span>
                </div>
                <span v-else class="muted">—</span>
              </td>
              <td class="muted">{{ model.ownedBy }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel table-panel">
      <div class="exposed-models-head">
        <div>
          <strong>models.dev 模型 ID</strong>
          <small>{{ catalogSource }} · {{ catalog.length }} 个模型</small>
        </div>
        <span class="badge" :class="catalogError ? 'warning' : 'info'">
          {{ catalogError ? '暂不可用' : `${catalog.length} 个` }}
        </span>
      </div>
      <div v-if="catalogError" class="callout warning models-dev-callout">{{ catalogError }}</div>
      <div class="table-toolbar models-dev-toolbar">
        <div class="search-box">
          <Icon name="search" :size="15" />
          <input v-model="catalogSearch" placeholder="搜索模型 ID、canonical ID、开发者或服务提供方" />
        </div>
        <span class="muted small">{{ filteredCatalog.length }} / {{ catalog.length }}</span>
      </div>
      <div v-if="catalogLoading" class="loading-state">
        <span class="spinner"></span>
        正在读取 models.dev 目录…
      </div>
      <div v-else-if="filteredCatalog.length === 0" class="empty-state compact">
        <h3>没有匹配的 models.dev 模型</h3>
        <p v-if="!catalogLoaded">models.dev 目录尚未加载或暂不可用。</p>
        <p v-else-if="catalog.length === 0">models.dev 没有返回模型 ID。</p>
        <p v-else>调整搜索关键词。</p>
      </div>
      <div v-else class="table-scroll models-dev-table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>模型 ID</th>
              <th>canonical_model_id</th>
              <th>模型开发者</th>
              <th>服务提供方</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="model in visibleCatalog" :key="`${model.providerId}-${model.id}`">
              <td><code>{{ model.id }}</code></td>
              <td><code class="muted">{{ model.canonicalId }}</code></td>
              <td>{{ model.developer }} <span class="muted small">{{ model.developerId }}</span></td>
              <td>{{ model.provider }} <span class="muted small">{{ model.providerId }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!catalogLoading && visibleCatalog.length < filteredCatalog.length" class="models-dev-more">
        <button type="button" class="button subtle mini" @click="catalogLimit += 150">
          加载更多（已显示 {{ visibleCatalog.length }} / {{ filteredCatalog.length }}）
        </button>
      </div>
    </div>

    <div class="panel exposed-models-panel">
      <div class="exposed-models-head">
        <div>
          <strong>原始响应</strong>
          <small>GET /v1/models · 200 OK{{ loadedAt ? ` · 读取于 ${loadedAt}` : '' }}</small>
        </div>
        <button type="button" class="button subtle mini" :disabled="loading" @click="copyJson">
          <Icon name="copy" :size="14" /> 复制 JSON
        </button>
      </div>
      <div v-if="loading" class="loading-state">
        <span class="spinner"></span>
        正在计算对外模型列表…
      </div>
      <pre v-else class="exposed-models-json"><code>{{ rawJson }}</code></pre>
    </div>

    <p class="muted small exposed-models-note">
      别名优先于同名上游模型。渠道模型配置了规范 ID 时，对外展示规范 ID 并路由到对应上游模型；未配置时回退上游 ID。建议以 models.dev 的 canonical ID 作为规范 ID。页面结果不区分调用方密钥，只要密钥有效，<code>/v1/models</code> 返回的内容一致。
    </p>
  </section>
</template>

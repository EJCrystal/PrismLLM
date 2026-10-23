<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { getAuthorizedUpstreamModelCache, hasUpstreamModelCache, setUpstreamModelCache } from '../upstreamModelCache'
import { notify } from '../toast'
import AliasEditorModal from './AliasEditorModal.vue'
import Icon from './Icon.vue'
import PriceEditorModal from './PriceEditorModal.vue'

const channels = ref([])
const aliases = ref([])
const prices = ref([])
const modelCatalog = ref([])
const modelCatalogLoading = ref(true)
const modelCatalogError = ref('')
const channelModels = reactive({})
const modelLoading = reactive({})
const modelErrors = reactive({})
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const search = ref('')
const providerFilter = ref('')
const channelFilter = ref('')
const statusFilter = ref('')
const expanded = ref(new Set())
const aliasEditor = ref(null)
const priceEditor = ref(null)
let editorKey = 0

const priceByKey = computed(() => new Map(
  prices.value.map((price) => [priceKey(price.channelId, price.upstreamModel), price]),
))
const channelById = computed(() => new Map(channels.value.map((channel) => [channel.id, channel])))
const aliasesByModel = computed(() => {
  const result = new Map()
  aliases.value.forEach((alias) => {
    const seen = new Set()
    alias.candidates.forEach((candidate) => {
      if (seen.has(candidate.upstreamModel)) return
      seen.add(candidate.upstreamModel)
      const list = result.get(candidate.upstreamModel) || []
      list.push(alias)
      result.set(candidate.upstreamModel, list)
    })
  })
  return result
})

const catalogByKey = computed(() => {
  const result = new Map()
  modelCatalog.value.forEach((model) => {
    modelKeys(model.id).concat(modelKeys(model.canonicalId)).forEach((key) => {
      const list = result.get(key) || []
      list.push(model)
      result.set(key, list)
    })
  })
  return result
})

function modelKeys(value) {
  if (!value) return []
  const normalized = value.trim().toLowerCase()
  if (!normalized) return []
  const unqualified = normalized.includes('/') ? normalized.slice(normalized.lastIndexOf('/') + 1) : normalized
  return [...new Set([normalized, unqualified])]
}

function catalogMatches(modelId, canonicalId = null) {
  const matches = []
  const seen = new Set()
  modelKeys(modelId).concat(modelKeys(canonicalId)).forEach((key) => {
    ;(catalogByKey.value.get(key) || []).forEach((model) => {
      const uniqueKey = model.providerId + '|' + model.id
      if (seen.has(uniqueKey)) return
      seen.add(uniqueKey)
      matches.push(model)
    })
  })
  return matches
}

function modelDeveloper(modelId, canonicalId, fallback) {
  const match = catalogMatches(modelId, canonicalId).find((model) => model.developer)
  return match?.developer || fallback || '未标注开发者'
}

const modelGroups = computed(() => {
  const models = new Map()
  const addOffer = (modelId, ownedBy, channel, price, flags = {}) => {
    let group = models.get(modelId)
    if (!group) {
      group = {
        id: modelId,
        ownedBy,
        developer: modelDeveloper(modelId, flags.canonicalModelId, ownedBy),
        offers: new Map(),
      }
      models.set(modelId, group)
    }
    if (!group.ownedBy && ownedBy) group.ownedBy = ownedBy
    if (group.developer === '未标注开发者' || group.developer === group.ownedBy) {
      group.developer = modelDeveloper(modelId, flags.canonicalModelId, ownedBy)
    }
    let offer = group.offers.get(channel.id)
    if (!offer) {
      offer = {
        channel,
        price: price || null,
        priceOnly: Boolean(flags.priceOnly),
        aliasOnly: Boolean(flags.aliasOnly),
      }
      group.offers.set(channel.id, offer)
    } else {
      if (price) offer.price = price
      if (flags.priceOnly) offer.priceOnly = true
    }
  }

  channels.value.forEach((channel) => {
    ;(channelModels[channel.id] || []).forEach((model) => {
      addOffer(
        model.id,
        model.ownedBy,
        channel,
        priceByKey.value.get(priceKey(channel.id, model.id)),
        { canonicalModelId: model.canonicalModelId },
      )
    })
    prices.value
      .filter((price) => price.channelId === channel.id)
      .forEach((price) => {
        const known = (channelModels[channel.id] || []).some((model) => model.id === price.upstreamModel)
        if (!known) addOffer(price.upstreamModel, null, channel, price, { priceOnly: true })
      })
  })

  aliases.value.forEach((alias) => {
    alias.candidates.forEach((candidate) => {
      const channel = channelById.value.get(candidate.channelId) || {
        id: candidate.channelId,
        name: candidate.channelName || candidate.channelId.slice(0, 8),
        baseUrl: '',
        enabled: false,
      }
      addOffer(candidate.upstreamModel, null, channel, priceByKey.value.get(priceKey(channel.id, candidate.upstreamModel)), { aliasOnly: true })
    })
  })

  return [...models.values()]
    .map((group) => {
      const offers = [...group.offers.values()]
      const configured = offers.filter((offer) => offer.price).length
      return {
        ...group,
        offers,
        aliases: aliasesByModel.value.get(group.id) || [],
        enabled: offers.some((offer) => offer.channel.enabled),
        configured,
      }
    })
    .sort((left, right) => left.developer.localeCompare(right.developer) || left.id.localeCompare(right.id))
})

const providerOptions = computed(() => [...new Set(modelGroups.value.map((group) => group.developer))].sort())
const totalOffers = computed(() => modelGroups.value.reduce((total, group) => total + group.offers.length, 0))
const configuredOffers = computed(() => modelGroups.value.reduce((total, group) => total + group.configured, 0))
const unpricedOffers = computed(() => totalOffers.value - configuredOffers.value)

const visibleGroups = computed(() => {
  const query = search.value.trim().toLowerCase()
  return modelGroups.value
    .map((group) => {
      const offers = channelFilter.value
        ? group.offers.filter((offer) => offer.channel.id === channelFilter.value)
        : group.offers
      if (offers.length === 0) return null
      if (providerFilter.value && group.developer !== providerFilter.value) return null
      const enabled = offers.some((offer) => offer.channel.enabled)
      const configured = offers.filter((offer) => offer.price).length
      if (statusFilter.value === 'enabled' && !enabled) return null
      if (statusFilter.value === 'disabled' && enabled) return null
      if (statusFilter.value === 'unpriced' && configured === offers.length) return null
      if (statusFilter.value === 'unaliased' && group.aliases.length > 0) return null
      const matches = !query || [group.id, group.developer, group.ownedBy, ...group.aliases.map((alias) => alias.alias)]
        .some((value) => (value || '').toLowerCase().includes(query)) || offers.some((offer) => [offer.channel.name, offer.channel.baseUrl]
          .some((value) => value.toLowerCase().includes(query)))
      return matches ? { ...group, offers, enabled, configured } : null
    })
    .filter(Boolean)
})

const providerGroups = computed(() => {
  const groups = new Map()
  visibleGroups.value.forEach((group) => {
    const list = groups.get(group.developer) || []
    list.push(group)
    groups.set(group.developer, list)
  })
  return [...groups.entries()].map(([provider, models]) => ({ provider, models }))
})

const channelErrors = computed(() => channels.value
  .filter((channel) => modelErrors[channel.id])
  .map((channel) => ({ channel, message: modelErrors[channel.id] })))

function priceKey(channelId, modelId) {
  return `${channelId}\u0000${modelId}`
}

async function loadChannelModels(channelId, force = false) {
  const channel = channels.value.find((item) => item.id === channelId)
  if (channel && (channel.models || []).length === 0) {
    channelModels[channelId] = []
    modelErrors[channelId] = ''
    return
  }
  if (!force && hasUpstreamModelCache(channelId)) {
    channelModels[channelId] = getAuthorizedUpstreamModelCache(channelId)
    modelErrors[channelId] = ''
    return
  }
  if (modelLoading[channelId]) return
  modelLoading[channelId] = true
  modelErrors[channelId] = ''
  try {
    const response = await api.listChannelModels(channelId)
    const models = (response?.data || []).filter((model) => model.authorized)
    setUpstreamModelCache(channelId, models)
    channelModels[channelId] = models
  } catch (cause) {
    channelModels[channelId] = getAuthorizedUpstreamModelCache(channelId)
    modelErrors[channelId] = cause.message
  } finally {
    modelLoading[channelId] = false
  }
}

async function loadAllModels(force = false) {
  await Promise.all(channels.value.map((channel) => loadChannelModels(channel.id, force)))
}

async function loadModelCatalog() {
  modelCatalogLoading.value = true
  modelCatalogError.value = ''
  try {
    const response = await api.getModelsDevCatalog()
    modelCatalog.value = response?.data || []
    modelCatalogError.value = response?.error || ''
  } catch (cause) {
    modelCatalog.value = []
    modelCatalogError.value = cause.message
  } finally {
    modelCatalogLoading.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [channelList, aliasList, priceList] = await Promise.all([
      api.listChannels(),
      api.listAliases(),
      api.listModelPrices(),
    ])
    channels.value = channelList
    aliases.value = aliasList
    prices.value = priceList
    void loadModelCatalog()
    await loadAllModels()
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function refreshAll() {
  refreshing.value = true
  try {
    await Promise.all([loadAllModels(true), loadModelCatalog()])
    notify('模型目录已刷新')
  } finally {
    refreshing.value = false
  }
}

function toggleExpanded(modelId) {
  const next = new Set(expanded.value)
  if (next.has(modelId)) next.delete(modelId)
  else next.add(modelId)
  expanded.value = next
}

function isExpanded(modelId) {
  return expanded.value.has(modelId)
}

function openAliasCreate(group = null, offer = null) {
  if (channels.value.length === 0) {
    notify('请先创建至少一个渠道', 'error')
    return
  }
  const firstOffer = offer || group?.offers[0]
  aliasEditor.value = {
    key: ++editorKey,
    alias: null,
    initialChannelId: firstOffer?.channel.id || channels.value[0].id,
    initialModel: group?.id || '',
  }
}

function openAliasEdit(alias) {
  aliasEditor.value = { key: ++editorKey, alias }
}

async function deleteAlias(alias) {
  if (!window.confirm(`确定删除模型别名「${alias.alias}」？`)) return
  try {
    await api.deleteAlias(alias.id)
    notify('模型别名已删除')
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  }
}

function openPrice(group = null, offer = null) {
  if (channels.value.length === 0) {
    notify('请先创建至少一个渠道', 'error')
    return
  }
  const targetOffer = offer || group?.offers.find((item) => !item.price) || group?.offers[0]
  priceEditor.value = {
    key: ++editorKey,
    price: targetOffer?.price || null,
    initialTarget: targetOffer ? { channelId: targetOffer.channel.id, modelId: group.id } : null,
  }
}

async function deletePrice(price) {
  if (!window.confirm(`确定删除「${price.channelName} / ${price.upstreamModel}」的计费单价？`)) return
  try {
    await api.deleteModelPrice(price.id)
    notify('计费单价已删除')
    await load()
  } catch (cause) {
    notify(cause.message, 'error')
  }
}

async function handleAliasSaved() {
  aliasEditor.value = null
  await load()
}

async function handlePriceSaved() {
  priceEditor.value = null
  await load()
}

function formatPrice(value) {
  if (value == null) return '—'
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 8 })
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="section-head model-manage-head">
      <div>
        <h2>模型清单</h2>
        <p>模型、路由规则与渠道报价在同一页维护；底层仍分别保存。</p>
      </div>
      <div class="model-manage-actions">
        <button type="button" class="button subtle" @click="openAliasCreate()">
          <Icon name="aliases" :size="16" /> 新增别名
        </button>
        <button type="button" class="button subtle" @click="openPrice()">
          <Icon name="price" :size="16" /> 新增价格
        </button>
        <button type="button" class="button subtle" :disabled="refreshing || loading" @click="refreshAll">
          <Icon name="refresh" :size="16" /> {{ refreshing ? '刷新中…' : '刷新目录' }}
        </button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span>模型总数</span>
        <strong>{{ modelGroups.length }}</strong>
        <small>按模型开发者聚合，优先使用 models.dev 目录</small>
      </div>
      <div class="stat-card">
        <span>路由规则</span>
        <strong>{{ aliases.length }}</strong>
        <small>调用方可见的模型别名</small>
      </div>
      <div class="stat-card accent">
        <span>价格覆盖</span>
        <strong>{{ totalOffers ? `${configuredOffers}/${totalOffers}` : '—' }}</strong>
        <small>{{ unpricedOffers }} 个渠道报价尚未配置</small>
      </div>
    </div>

    <div v-if="error" class="callout danger">{{ error }}</div>
    <div v-if="modelCatalogError" class="callout warning">
      models.dev 目录暂不可用，开发者信息将回退到上游 <code>owned_by</code>：{{ modelCatalogError }}
    </div>
    <div v-if="channelErrors.length" class="callout warning model-manage-errors">
      <span v-for="item in channelErrors" :key="item.channel.id">
        <strong>{{ item.channel.name }}</strong>：{{ item.message }}
        <button type="button" class="button ghost mini" @click="loadChannelModels(item.channel.id, true)">重试</button>
      </span>
    </div>

    <div class="panel model-manage-panel">
      <div class="model-manage-toolbar">
        <div class="search-box model-manage-search">
          <Icon name="search" :size="16" />
          <input v-model="search" placeholder="搜索模型、别名、开发者或渠道" />
        </div>
        <label class="model-manage-filter"><span>开发者</span><select v-model="providerFilter"><option value="">全部</option><option v-for="provider in providerOptions" :key="provider" :value="provider">{{ provider }}</option></select></label>
        <label class="model-manage-filter"><span>渠道</span><select v-model="channelFilter"><option value="">全部</option><option v-for="channel in channels" :key="channel.id" :value="channel.id">{{ channel.name }}</option></select></label>
        <label class="model-manage-filter"><span>状态</span><select v-model="statusFilter"><option value="">全部</option><option value="enabled">已启用</option><option value="disabled">已停用</option><option value="unpriced">未配价</option><option value="unaliased">未关联别名</option></select></label>
      </div>

      <div v-if="loading" class="loading-state"><span class="spinner"></span>正在读取模型、别名和价格…</div>
      <div v-else-if="modelGroups.length === 0" class="empty-state"><div class="empty-icon"><Icon name="models" :size="26" /></div><h3>还没有模型</h3><p>刷新渠道模型或先创建别名。</p></div>
      <div v-else-if="visibleGroups.length === 0" class="empty-state compact"><h3>没有匹配的模型</h3><p>调整搜索或筛选条件后再试。</p></div>
      <template v-else>
        <div class="model-manage-columns"><span></span><span>模型</span><span>模型开发者</span><span>渠道</span><span>别名</span><span>价格覆盖</span><span>状态</span><span>操作</span></div>
        <div class="model-manage-groups">
          <section v-for="providerGroup in providerGroups" :key="providerGroup.provider" class="model-provider-group">
            <header class="model-provider-head"><strong>{{ providerGroup.provider }}</strong><span>{{ providerGroup.models.length }} 个模型</span></header>
            <article v-for="group in providerGroup.models" :key="group.id" class="model-manage-item">
              <div class="model-manage-row">
                <button type="button" class="model-expand" :aria-expanded="isExpanded(group.id)" :title="isExpanded(group.id) ? '收起' : '展开'" @click="toggleExpanded(group.id)"><Icon name="chevron" :size="16" /></button>
                <div class="model-manage-name"><code>{{ group.id }}</code><div><span v-if="group.offers.some((offer) => offer.aliasOnly)" class="badge warning">仅别名</span><span v-if="group.offers.some((offer) => offer.priceOnly)" class="badge warning">仅价格</span></div></div>
                <div class="model-manage-meta">
                  <div class="model-manage-cell"><span class="model-manage-label">模型开发者</span><span>{{ group.developer }}</span></div>
                  <div class="model-manage-cell"><span class="model-manage-label">渠道</span><strong>{{ group.offers.length }}</strong></div>
                  <div class="model-manage-cell"><span class="model-manage-label">别名</span><strong>{{ group.aliases.length }}</strong></div>
                  <div class="model-manage-cell"><span class="model-manage-label">价格覆盖</span><span class="badge" :class="group.configured === group.offers.length ? 'success' : 'warning'">{{ group.configured }}/{{ group.offers.length }}</span></div>
                  <div class="model-manage-cell"><span class="model-manage-label">状态</span><span class="badge" :class="group.enabled ? 'success' : 'neutral'">{{ group.enabled ? '启用' : '停用' }}</span></div>
                </div>
                <div class="model-manage-row-actions"><button type="button" class="button ghost mini" @click="openAliasCreate(group)">加路由</button><button type="button" class="button ghost mini" @click="toggleExpanded(group.id)">{{ isExpanded(group.id) ? '收起' : '管理' }}</button></div>
              </div>

              <div v-if="isExpanded(group.id)" class="model-manage-detail">
                <section class="model-detail-section">
                  <header class="model-detail-head"><div><strong>路由规则</strong><small>别名与候选顺序</small></div><button type="button" class="button subtle mini" @click="openAliasCreate(group)"><Icon name="plus" :size="14" /> 新增</button></header>
                  <div v-if="group.aliases.length === 0" class="model-detail-empty">尚未关联别名。</div>
                  <div v-else class="model-alias-list">
                    <div v-for="alias in group.aliases" :key="alias.id" class="model-alias-card">
                      <div class="model-alias-title"><code>{{ alias.alias }}</code><span class="badge" :class="alias.enabled ? 'success' : 'neutral'">{{ alias.enabled ? '启用' : '停用' }}</span><div class="row-actions"><button type="button" class="icon-button" title="编辑别名" @click="openAliasEdit(alias)"><Icon name="edit" :size="14" /></button><button type="button" class="icon-button danger" title="删除别名" @click="deleteAlias(alias)"><Icon name="trash" :size="14" /></button></div></div>
                      <div class="candidate-list"><span v-for="(candidate, index) in alias.candidates" :key="candidate.id || index" class="candidate-chip"><b>{{ index + 1 }}</b><span>{{ candidate.channelName }} / <code>{{ candidate.upstreamModel }}</code></span></span></div>
                    </div>
                  </div>
                </section>
                <section class="model-detail-section model-detail-prices">
                  <header class="model-detail-head"><div><strong>渠道报价</strong><small>输入 / 输出 / 缓存，每百万 token</small></div><button type="button" class="button subtle mini" @click="openPrice(group)"><Icon name="plus" :size="14" /> 新增</button></header>
                  <div class="model-offer-list">
                    <div v-for="offer in group.offers" :key="offer.channel.id" class="model-offer-row">
                      <div class="model-offer-channel"><strong>{{ offer.channel.name }}</strong><span class="badge" :class="offer.channel.enabled ? 'success' : 'neutral'">{{ offer.channel.enabled ? '启用' : '停用' }}</span><span v-if="offer.aliasOnly" class="badge warning">仅别名</span><span v-if="offer.priceOnly" class="badge warning">仅价格</span><small class="mono muted">{{ offer.channel.baseUrl }}</small></div>
                      <div class="model-offer-price"><span>输入 / 1M</span><strong>{{ offer.price ? formatPrice(offer.price.inputPricePerMillion) : '—' }}</strong></div>
                      <div class="model-offer-price"><span>输出 / 1M</span><strong>{{ offer.price ? formatPrice(offer.price.outputPricePerMillion) : '—' }}</strong></div>
                      <div class="model-offer-price"><span>缓存 / 1M</span><strong>{{ offer.price?.cachedInputPricePerMillion != null ? formatPrice(offer.price.cachedInputPricePerMillion) : '—' }}</strong></div>
                      <div class="model-offer-actions"><span class="badge" :class="offer.price ? 'success' : 'warning'">{{ offer.price ? offer.price.currency : '未配置' }}</span><button type="button" class="button subtle mini" @click="openPrice(group, offer)">{{ offer.price ? '编辑价格' : '配置价格' }}</button><button v-if="offer.price" type="button" class="icon-button danger" title="删除价格" @click="deletePrice(offer.price)"><Icon name="trash" :size="14" /></button></div>
                    </div>
                  </div>
                </section>
              </div>
            </article>
          </section>
        </div>
      </template>
    </div>

    <AliasEditorModal v-if="aliasEditor" :key="aliasEditor.key" :channels="channels" :alias="aliasEditor.alias" :initial-channel-id="aliasEditor.initialChannelId" :initial-model="aliasEditor.initialModel" @close="aliasEditor = null" @saved="handleAliasSaved" />
    <PriceEditorModal v-if="priceEditor" :key="priceEditor.key" :channels="channels" :price="priceEditor.price" :initial-target="priceEditor.initialTarget" @close="priceEditor = null" @saved="handlePriceSaved" />
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { getAuthorizedUpstreamModelCache, hasUpstreamModelCache, setUpstreamModelCache } from '../upstreamModelCache'
import { channelDisplayName, channelModelPriceOptions, findModelPrice, isSamePriceTarget, pricesForChannel } from '../utils/modelPrice'
import {
  applyPriceToForm,
  buildBatchPricePayload,
  buildPricePayload,
  existingPricePayload,
  emptyPriceDraft,
  hasPriceDraft,
  priceFormError,
  priceFormState,
  resetPriceForm,
} from '../utils/priceForm'
import { formatPriceSummary, formatTime, formatUnitPrice } from '../utils/format'
import Modal from './Modal.vue'
import PriceFields from './PriceFields.vue'

const props = defineProps({
  channels: { type: Array, required: true },
  price: { type: Object, default: null },
  prices: { type: Array, default: () => [] },
  defaultPrices: { type: Array, default: () => [] },
  initialTarget: { type: Object, default: null },
})
const emit = defineEmits(['close', 'saved'])
const channelModels = ref([])
const modelsLoading = ref(false)
const modelsError = ref('')
const fetchingReference = ref(false)
const referenceHint = ref('')
const modelPickerOpen = ref(false)
const saving = ref(false)
const activePrice = ref(props.price)
const batchOpen = ref(false)
const batchSelected = ref([])
const showVersions = ref(false)
const loadingVersions = ref(false)
const versionHistory = ref([])
const cancellingVersion = ref(false)
let modelsRequestId = 0
let referenceRequestId = 0
let versionRequestId = 0
let cancellationRequestId = 0

const initialPrice = priceFormState(props.price)
const form = reactive({
  channelId: props.price?.channelId || props.initialTarget?.channelId || props.channels[0]?.id || '',
  upstreamModel: props.price?.upstreamModel || props.initialTarget?.modelId || '',
  ...initialPrice,
})

const currentChannelName = computed(() => channelDisplayName(props.channels, form.channelId))

function referenceDraftKey() {
  return [
    form.channelId,
    form.upstreamModel.trim(),
    activePrice.value?.id || '',
    form.currency,
    form.inputPricePerMillion,
    form.outputPricePerMillion,
    form.cachedInputPricePerMillion,
    form.cachedInputWritePricePerMillion,
    form.pricingMode,
    form.perRequestPrice,
    JSON.stringify(form.priceTiers),
  ].join('\u0000')
}

function cancelReferenceLookup() {
  referenceRequestId += 1
  fetchingReference.value = false
}

const channelPrices = computed(() => pricesForChannel(props.prices, form.channelId))
const channelModelOptions = computed(() => channelModelPriceOptions(
  channelModels.value,
  props.prices,
  form.channelId,
  props.defaultPrices,
))
const filteredChannelModels = computed(() => {
  const query = form.upstreamModel.trim().toLowerCase()
  if (!query) return channelModelOptions.value
  return channelModelOptions.value.filter((model) => model.id.toLowerCase().includes(query))
})
const selectedModelOption = computed(() => channelModelOptions.value.find(
  (model) => String(model.id ?? '').trim() === form.upstreamModel.trim(),
))
const inheritedPrice = computed(() => selectedModelOption.value?.defaultPrice || null)
const selectedPriceSource = computed(() => {
  if (activePrice.value?.id) return 'channel'
  if (selectedModelOption.value?.price) return 'channel'
  if (inheritedPrice.value) return 'default'
  return 'none'
})
const allBatchSelected = computed(() => channelModelOptions.value.length > 0 &&
  channelModelOptions.value.every((model) => batchSelected.value.includes(model.id)))

watch(
  () => form.channelId,
  async (channelId) => {
    const requestId = ++modelsRequestId
    cancelReferenceLookup()
    modelPickerOpen.value = false
    batchOpen.value = false
    batchSelected.value = []
    channelModels.value = []
    modelsError.value = ''
    if (!channelId) return
    const channel = props.channels.find((item) => item.id === channelId)
    if (channel && (channel.models || []).length === 0) {
      modelsLoading.value = false
      return
    }
    if (hasUpstreamModelCache(channelId)) {
      channelModels.value = getAuthorizedUpstreamModelCache(channelId)
      modelsLoading.value = false
      return
    }
    modelsLoading.value = true
    try {
      const response = await api.listChannelModels(channelId)
      const models = (response?.data || []).filter((model) => model.authorized)
      setUpstreamModelCache(channelId, models)
      if (requestId === modelsRequestId) channelModels.value = models
    } catch (cause) {
      if (requestId === modelsRequestId) modelsError.value = cause.message
    } finally {
      if (requestId === modelsRequestId) modelsLoading.value = false
    }
  },
  { immediate: true },
)

watch(
  () => form.upstreamModel,
  () => {
    referenceHint.value = ''
  },
)

function applyExistingPrice(price) {
  cancelReferenceLookup()
  versionRequestId += 1
  activePrice.value = price
  Object.assign(form, priceFormState(price))
}

watch(
  () => [form.channelId, form.upstreamModel, props.prices],
  ([channelId, upstreamModel]) => {
    const existingPrice = findModelPrice(props.prices, channelId, upstreamModel.trim())
    if (existingPrice) {
      if (
        activePrice.value?.id !== existingPrice.id &&
        !isSamePriceTarget(activePrice.value, channelId, upstreamModel)
      ) {
        applyExistingPrice(existingPrice)
      }
      return
    }
    if (activePrice.value) {
      activePrice.value = null
      Object.assign(form, emptyPriceDraft())
    }
  },
  { immediate: true },
)

function openModelPicker() {
  modelPickerOpen.value = true
}

function handleModelPickerFocusOut(event) {
  const next = event.relatedTarget
  if (next && event.currentTarget.contains(next)) return
  window.setTimeout(() => {
    modelPickerOpen.value = false
  }, 0)
}

function selectChannelModel(model) {
  cancelReferenceLookup()
  form.upstreamModel = model.id
  modelPickerOpen.value = false
}

function editPrice(price) {
  cancelReferenceLookup()
  versionRequestId += 1
  activePrice.value = price
  showVersions.value = false
  versionHistory.value = []
  applyPriceToForm(form, price)
  modelPickerOpen.value = false
}

function startNewPrice() {
  cancelReferenceLookup()
  versionRequestId += 1
  activePrice.value = null
  showVersions.value = false
  versionHistory.value = []
  resetPriceForm(form)
  modelPickerOpen.value = false
}

async function toggleVersionHistory() {
  showVersions.value = !showVersions.value
  if (showVersions.value && form.channelId && form.upstreamModel.trim()) {
    const requestId = ++versionRequestId
    const channelId = form.channelId
    const upstreamModel = form.upstreamModel.trim()
    loadingVersions.value = true
    try {
      const versions = await api.listModelPriceVersions(channelId, upstreamModel)
      if (requestId === versionRequestId) versionHistory.value = versions
    } catch (e) {
      if (requestId === versionRequestId) notify(e.message, 'error')
    } finally {
      if (requestId === versionRequestId) loadingVersions.value = false
    }
  }
}

watch(
  () => [form.channelId, form.upstreamModel, activePrice.value?.id, showVersions.value],
  () => {
    if (!showVersions.value) loadingVersions.value = false
  },
)

async function cancelScheduledVersion(version) {
  if (!version?.id) return
  if (!window.confirm(`确定取消将于 ${formatTime(version.effectiveFrom)} 生效的版本 v${version.version}？`)) return
  cancellingVersion.value = true
  const requestId = ++cancellationRequestId
  const channelId = form.channelId
  const upstreamModel = form.upstreamModel.trim()
  const priceId = activePrice.value?.id
  try {
    await api.deleteModelPriceVersion(version.id)
    notify('定时生效版本已取消')
    if (
      activePrice.value?.id === priceId &&
      form.channelId === channelId &&
      form.upstreamModel.trim() === upstreamModel
    ) {
      activePrice.value.scheduledVersion = null
    }
    if (
      showVersions.value &&
      activePrice.value?.id === priceId &&
      form.channelId === channelId &&
      form.upstreamModel.trim() === upstreamModel
    ) {
      const versions = await api.listModelPriceVersions(channelId, upstreamModel)
      if (
        activePrice.value?.id === priceId &&
        form.channelId === channelId &&
        form.upstreamModel.trim() === upstreamModel
      ) versionHistory.value = versions
    }
    emit('saved')
  } catch (e) {
    notify(e.message, 'error')
  } finally {
    if (requestId === cancellationRequestId) cancellingVersion.value = false
  }
}

function applyDefaultPrice() {
  const price = inheritedPrice.value
  if (!price) return
  form.currency = price.currency || 'USD'
  form.inputPricePerMillion = price.inputPricePerMillion ?? ''
  form.outputPricePerMillion = price.outputPricePerMillion ?? ''
  form.cachedInputPricePerMillion = price.cachedInputPricePerMillion ?? ''
  form.cachedInputWritePricePerMillion = price.cachedInputWritePricePerMillion ?? ''
  form.pricingMode = price.pricingMode || 'token'
  form.perRequestPrice = price.perRequestPrice ?? ''
  form.priceTiers = (price.priceTiers || []).map((tier) => ({ ...tier }))
}

function toggleBatchAll() {
  if (allBatchSelected.value) {
    batchSelected.value = []
  } else {
    batchSelected.value = channelModelOptions.value.map((model) => model.id)
  }
}

function priceSourceLabel(model) {
  if (model?.price) return '渠道覆盖'
  if (model?.defaultPrice) return '默认价'
  return '未定价'
}

function priceSourceClass(model) {
  if (model?.price) return 'success'
  if (model?.defaultPrice) return 'info'
  return 'warning'
}

function pricingModeLabel(price) {
  return ({
    token: '按 Token',
    per_request: '按次',
    tiered: '阶梯价',
    volume: '阶梯总量价',
  })[price?.pricingMode || 'token'] || '按 Token'
}

async function fetchReferencePrice() {
  const modelName = form.upstreamModel.trim()
  if (!modelName) {
    notify('请先输入或选择上游模型', 'warning')
    return
  }
  if (form.currency !== 'USD' && hasPriceDraft(form)) {
    notify('参考价以 USD 计；当前单价不是 USD，请先清空金额或新建 USD 单价', 'warning')
    return
  }
  if (hasPriceDraft(form) && !window.confirm('获取参考价会用 USD 金额覆盖当前填写的单价和币种，是否继续？')) {
    return
  }
  const requestId = ++referenceRequestId
  const draftKey = referenceDraftKey()
  fetchingReference.value = true
  referenceHint.value = ''
  try {
    const res = await api.getModelPriceReference(modelName)
    if (requestId !== referenceRequestId || draftKey !== referenceDraftKey()) return
    form.currency = 'USD'
    form.pricingMode = 'token'
    form.perRequestPrice = ''
    form.priceTiers = []
    form.inputPricePerMillion = res.inputPricePerMillion
    form.outputPricePerMillion = res.outputPricePerMillion
    form.cachedInputPricePerMillion = res.cachedInputPricePerMillion || ''
    form.cachedInputWritePricePerMillion = ''
    referenceHint.value = `已填入参考价（匹配: ${res.matchedModel}，来源: ${res.source}）`
    notify(`已填入「${res.matchedModel}」USD 参考单价`)
  } catch (cause) {
    if (requestId === referenceRequestId) {
      notify(cause.message || '未在参考库中找到该模型，请手动输入', 'warning')
    }
  } finally {
    if (requestId === referenceRequestId) fetchingReference.value = false
  }
}

async function save() {
  if (!form.channelId) {
    notify('请选择渠道', 'error')
    return
  }
  if (!form.upstreamModel.trim()) {
    notify('请填写上游模型', 'error')
    return
  }
  const validationError = priceFormError(form)
  if (validationError) {
    notify(validationError, 'error')
    return
  }
  saving.value = true
  try {
    const payload = existingPricePayload(form, form.channelId, form.upstreamModel)
    if (activePrice.value?.id) {
      await api.updateModelPrice(activePrice.value.id, payload)
      notify('计费单价已更新')
    } else {
      await api.createModelPrice(payload)
      notify('计费单价已创建')
    }
    emit('saved')
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function applyBatch() {
  if (!form.channelId) {
    notify('请选择渠道', 'error')
    return
  }
  if (batchSelected.value.length === 0) {
    notify('请至少选择一个模型', 'warning')
    return
  }
  const validationError = priceFormError(form)
  if (validationError) {
    notify(validationError, 'error')
    return
  }
  saving.value = true
  try {
    await api.batchUpsertModelPrices(buildBatchPricePayload(form, form.channelId, batchSelected.value))
    notify(`已为 ${batchSelected.value.length} 个模型套用渠道单价`)
    batchSelected.value = []
    batchOpen.value = false
    emit('saved')
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :title="activePrice?.id ? '编辑计费单价' : '模型计费单价'" @close="$emit('close')">
    <form class="form-grid" @submit.prevent="save">
      <label class="field">
        <span>渠道</span>
        <input type="text" :value="currentChannelName" readonly disabled aria-label="渠道" />
        <small>渠道覆盖价按「渠道 + 上游模型」配置，优先于模型默认价；未配置时自动继承默认价。</small>
      </label>
      <div class="price-configured">
        <div class="price-configured-head">
          <div>
            <strong>渠道覆盖单价</strong>
            <span class="muted small"> · {{ channelPrices.length }} 个模型</span>
          </div>
          <div class="price-configured-actions">
            <button type="button" class="button ghost mini" @click="batchOpen = !batchOpen">批量套用</button>
            <button type="button" class="button ghost mini" @click="startNewPrice">新增单价</button>
          </div>
        </div>
        <div v-if="channelPrices.length" class="price-configured-list">
          <button
            v-for="price in channelPrices"
            :key="price.id"
            type="button"
            class="price-configured-item"
            :class="{ active: activePrice?.id === price.id }"
            :aria-pressed="activePrice?.id === price.id"
            :aria-label="`编辑 ${price.upstreamModel} 的单价：${formatPriceSummary(price)}`"
            @click="editPrice(price)"
          >
            <code>{{ price.upstreamModel }}</code>
            <span class="muted small">{{ formatPriceSummary(price) }}</span>
          </button>
        </div>
        <p v-else class="muted small price-configured-empty">该渠道还没有渠道覆盖价，模型会继承默认价（如有）。</p>
        <div v-if="batchOpen" class="price-batch">
          <div class="price-batch-head">
            <strong>批量套用当前金额</strong>
            <span class="muted small">将上方填写的币种和单价应用到所选上游模型，已有渠道价会被覆盖。</span>
          </div>
          <div v-if="channelModelOptions.length" class="price-batch-list">
            <label v-for="model in channelModelOptions" :key="model.id" class="price-batch-item">
              <input v-model="batchSelected" type="checkbox" :value="model.id" />
              <code>{{ model.id }}</code>
              <span class="badge" :class="priceSourceClass(model)">{{ priceSourceLabel(model) }}</span>
            </label>
          </div>
          <p v-else class="muted small price-configured-empty">当前渠道没有可批量套用的模型。</p>
          <div class="price-batch-actions">
            <button type="button" class="button ghost mini" @click="toggleBatchAll">
              {{ allBatchSelected ? '取消全选' : '全选' }}
            </button>
            <button type="button" class="button primary mini" :disabled="saving || batchSelected.length === 0" @click="applyBatch">
              套用到 {{ batchSelected.length }} 个模型
            </button>
          </div>
        </div>
      </div>
      <label class="field" for="price-upstream-model">
        <span>上游模型</span>
        <div class="model-picker" @focusin="openModelPicker" @focusout="handleModelPickerFocusOut">
          <div class="model-input-row">
            <input
              id="price-upstream-model"
              v-model="form.upstreamModel"
              placeholder="点击选择或输入模型名"
              autocomplete="off"
              role="combobox"
              :aria-expanded="modelPickerOpen"
              aria-controls="price-model-options"
              @click="openModelPicker"
              @keydown.escape.prevent="modelPickerOpen = false"
            />
            <button type="button" class="button subtle mini" :disabled="fetchingReference || !form.upstreamModel.trim()" @click="fetchReferencePrice">
              {{ fetchingReference ? '查询中…' : '获取参考单价' }}
            </button>
          </div>
          <div v-if="modelPickerOpen" id="price-model-options" class="model-picker-menu" role="listbox">
            <div v-if="modelsLoading" class="model-picker-message">正在读取渠道模型…</div>
            <div v-else-if="modelsError" class="model-picker-message danger">{{ modelsError }}</div>
            <template v-else-if="filteredChannelModels.length">
              <button
                v-for="model in filteredChannelModels"
                :key="model.id"
                type="button"
                class="model-picker-option"
                role="option"
                @click.stop.prevent="selectChannelModel(model)"
              >
                <span class="model-picker-option-main">
                  <code>{{ model.id }}</code>
                  <span v-if="model.ownedBy" class="muted small">{{ model.ownedBy }}</span>
                </span>
                <span class="badge" :class="priceSourceClass(model)">
                  {{ priceSourceLabel(model) }}
                </span>
              </button>
            </template>
            <div v-else class="model-picker-message">没有匹配的模型，可直接手动输入。</div>
          </div>
        </div>
        <small v-if="referenceHint" class="reference-hint">{{ referenceHint }}</small>
        <small v-else-if="selectedPriceSource === 'channel'">当前使用渠道覆盖价，模型默认价不会生效。</small>
        <small v-else-if="selectedPriceSource === 'default'" class="reference-hint">
          当前继承模型默认价（{{ formatPriceSummary(inheritedPrice) }}）。保存后将创建渠道覆盖价。
          <button type="button" class="link-button" @click="applyDefaultPrice">填入默认价</button>
        </small>
        <small v-else>从渠道模型选择或手动输入后，可获取 USD 参考价。</small>
      </label>
      <label class="field">
        <span>计费币种</span>
        <input
          type="text"
          :value="form.currency"
          readonly
          aria-label="计费币种"
        />
        <small>获取参考价会切换为 USD。</small>
      </label>
      <PriceFields :form="form" :has-current-price="Boolean(activePrice?.id || inheritedPrice)" />

      <div v-if="activePrice?.scheduledVersion" class="callout warning price-scheduled-banner">
        <div class="scheduled-banner-info">
          <strong>定时生效版本 (v{{ activePrice.scheduledVersion.version }})</strong>
          <span class="muted small"> · 将于 {{ formatTime(activePrice.scheduledVersion.effectiveFrom) }} 生效</span>
          <div class="small">{{ formatPriceSummary(activePrice.scheduledVersion) }}</div>
        </div>
        <button
          type="button"
          class="button ghost mini danger"
          :disabled="cancellingVersion"
          @click="cancelScheduledVersion(activePrice.scheduledVersion)"
        >
          取消定时
        </button>
      </div>

      <div v-if="activePrice?.id" class="price-version-history">
        <div class="version-history-head">
          <span class="muted small">
            当前版本：v{{ activePrice.currentVersion || 1 }}（生效于 {{ formatTime(activePrice.effectiveFrom || activePrice.createdAt) }}）
          </span>
          <button type="button" class="button ghost mini" @click="toggleVersionHistory">
            {{ showVersions ? '收起版本历史' : '查看版本历史' }}
          </button>
        </div>
        <div v-if="showVersions" class="version-history-list">
          <div v-if="loadingVersions" class="muted small">正在加载版本历史…</div>
          <div v-else-if="versionHistory.length === 0" class="muted small">暂无其他版本记录</div>
          <div v-for="ver in versionHistory" :key="ver.id" class="version-history-item">
            <span class="badge" :class="ver.status === 'scheduled' ? 'warning' : (ver.version === activePrice.currentVersion ? 'success' : 'muted')">
              {{ ver.status === 'scheduled' ? '待生效' : (ver.version === activePrice.currentVersion ? '生效中' : '历史') }}
            </span>
            <code>v{{ ver.version }}</code>
            <span class="muted small">{{ formatPriceSummary(ver) }} · {{ formatTime(ver.effectiveFrom) }}</span>
            <button
              v-if="ver.status === 'scheduled'"
              type="button"
              class="button ghost mini danger"
              :disabled="cancellingVersion"
              @click="cancelScheduledVersion(ver)"
            >
              取消
            </button>
          </div>
        </div>
      </div>

      <p v-if="activePrice?.id" class="muted small price-source-note">正在编辑渠道覆盖价。</p>
      <p v-else-if="inheritedPrice" class="callout info price-source-note">
        该模型当前继承默认价；保存后将为当前渠道创建覆盖价。
      </p>
      <p class="muted small price-hint">
        计费方式支持按 Token、按次、阶梯累进与阶梯总量。缓存读写单价留空时回退到输入单价；推理 token 已包含在输出 token 中，不重复计费。
      </p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="saving || channels.length === 0" @click="save">
        {{ saving ? '保存中…' : '保存单价' }}
      </button>
    </template>
  </Modal>
</template>

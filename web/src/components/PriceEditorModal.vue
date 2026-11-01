<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { Channel, ChannelModel, ModelDefaultPrice, ModelPrice } from '../types/api'
import { getAuthorizedUpstreamModelCache, hasUpstreamModelCache, setUpstreamModelCache } from '../upstreamModelCache'
import {
  channelDisplayName,
  channelModelChoiceMode,
  channelModelOptionsForChannel,
  channelModelPriceOptions,
  pricesForChannel,
  shouldSelectInitialModel,
} from '../utils/modelPrice'
import {
  applyPriceToForm,
  buildPricePayload,
  emptyPriceDraft,
  existingPricePayload,
  hasPriceDraft,
  priceFormError,
  priceFormState,
  resetPriceForm,
} from '../utils/priceForm'
import type { PriceForm } from '../utils/priceForm'
import {
  refreshedPriceToApply,
  resolveModelSelectionTarget,
} from '../utils/modelSelection'
import {
  buildChannelPriceDeleteMessage,
  clonePriceDraftForm,
  collectDirtyPriceDrafts,
  createPriceDraft,
  priceDraftHasChanges,
  priceDraftKey,
  priceDraftSnapshot,
} from '../utils/priceDraftState'
import type { PriceDraft } from '../utils/priceDraftState'
import { formatPriceSummary } from '../utils/format'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PriceFields from './PriceFields.vue'

type PriceModelTarget = { channelId?: string; modelId?: string } | null
type ChannelModelPriceOption = ChannelModel & { price: ModelPrice | null; defaultPrice: ModelDefaultPrice | null }

const props = withDefaults(defineProps<{
  channels: Channel[]
  price?: ModelPrice | null
  prices?: ModelPrice[]
  defaultPrices?: ModelDefaultPrice[]
  initialTarget?: PriceModelTarget
}>(), {
  price: null,
  prices: () => [],
  defaultPrices: () => [],
  initialTarget: null,
})
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
  (event: 'changed'): void
}>()
const channelModels = ref<ChannelModel[]>([])
const modelsLoading = ref(false)
const modelsError = ref('')
const fetchingReference = ref(false)
const referenceHint = ref('')
const saving = ref(false)
const deletingPriceId = ref<string | null>(null)
const activePrice = ref<ModelPrice | null>(props.price)
let modelsRequestId = 0
let referenceRequestId = 0
const manuallyEnteredModel = ref(false)
const draftStates = reactive(new Map<string, PriceDraft>())
const locallyDeletedPriceIds = ref(new Set<string>())
const activeDraftKey = ref('')

const initialPrice = priceFormState(props.price)
const form = reactive<PriceForm & { channelId: string; upstreamModel: string }>({
  channelId: props.price?.channelId || props.initialTarget?.channelId || props.channels[0]?.id || '',
  upstreamModel: props.price?.upstreamModel || props.initialTarget?.modelId || '',
  ...initialPrice,
})
const currentBaseline = ref<string>(priceDraftSnapshot(form))

const currentChannelName = computed(() => channelDisplayName(props.channels, form.channelId))
const currentDraftDirty = computed(() => priceDraftSnapshot(form) !== currentBaseline.value)

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

const visiblePrices = computed(() => (Array.isArray(props.prices) ? props.prices : [])
  .filter((price) => price && !locallyDeletedPriceIds.value.has(price.id)))
const channelPrices = computed(() => pricesForChannel(visiblePrices.value, form.channelId))
const selectedChannel = computed(() => props.channels.find((channel) => channel.id === form.channelId) || null)
const availableChannelModels = computed(() => channelModelOptionsForChannel(
  selectedChannel.value,
  channelModels.value,
))
const channelModelOptions = computed(() => channelModelPriceOptions(
  availableChannelModels.value,
  visiblePrices.value,
  form.channelId,
  props.defaultPrices,
))
const modelChoiceMode = computed(() => channelModelChoiceMode(channelModelOptions.value))
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
const dirtyDraftCount = computed(() => {
  let count = currentDraftDirty.value ? 1 : 0
  for (const [key, draft] of draftStates) {
    if (key !== activeDraftKey.value && priceDraftHasChanges(draft)) count += 1
  }
  return count
})
const saveButtonLabel = computed(() => {
  if (saving.value) return '保存中…'
  return dirtyDraftCount.value > 1 ? `保存全部（${dirtyDraftCount.value} 个模型）` : '保存单价'
})

function isModelDirty(modelId: string) {
  const normalized = String(modelId ?? '').trim()
  if (!normalized) return false
  if (normalized === form.upstreamModel.trim()) return currentDraftDirty.value
  return priceDraftHasChanges(draftStates.get(priceDraftKey(form.channelId, normalized)))
}

watch(
  () => form.channelId,
  async (channelId) => {
    const requestId = ++modelsRequestId
    cancelReferenceLookup()
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
      if (requestId === modelsRequestId) modelsError.value = cause instanceof Error ? cause.message : String(cause)
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

watch(
  channelModelOptions,
  (models) => {
    if (shouldSelectInitialModel(models, form.upstreamModel, manuallyEnteredModel.value)) {
      selectChannelModel(models[0])
    }
  },
  { immediate: true },
)

function applyExistingPrice(price: ModelPrice) {
  cancelReferenceLookup()
  activePrice.value = price
  Object.assign(form, priceFormState(price))
}

function currentPriceDraft(): PriceDraft {
  return createPriceDraft({
    channelId: form.channelId,
    modelId: form.upstreamModel,
    form,
    price: activePrice.value,
    baseline: currentBaseline.value,
  })
}

function stashCurrentDraft(): PriceDraft | null {
  if (!form.channelId || !form.upstreamModel.trim()) return null
  const draft = currentPriceDraft()
  draftStates.set(draft.key, draft)
  return draft
}

function restorePriceDraft(draft: PriceDraft, key: string) {
  cancelReferenceLookup()
  activePrice.value = draft.price && 'channelId' in draft.price ? draft.price : null
  Object.assign(form, clonePriceDraftForm(draft.form))
  currentBaseline.value = draft.baseline ?? priceDraftSnapshot(form)
  activeDraftKey.value = key
}

watch(
  () => [form.channelId, form.upstreamModel] as const,
  ([channelId, upstreamModel]) => {
    const key = priceDraftKey(channelId, upstreamModel)
    const stored = draftStates.get(key)
    if (stored) {
      restorePriceDraft(stored, key)
      return
    }
    const target = resolveModelSelectionTarget(channelId, upstreamModel, visiblePrices.value)
    if (target.price) applyExistingPrice(target.price)
    else {
      activePrice.value = null
      Object.assign(form, target.draft)
    }
    currentBaseline.value = priceDraftSnapshot(form)
    activeDraftKey.value = key
  },
  { immediate: true },
)

watch(
  () => props.prices,
  () => {
    for (const [key, draft] of draftStates) {
      if (key !== activeDraftKey.value && !priceDraftHasChanges(draft)) draftStates.delete(key)
    }
    if (currentDraftDirty.value) return
    const target = resolveModelSelectionTarget(form.channelId, form.upstreamModel, visiblePrices.value)
    const refreshedPrice = refreshedPriceToApply(target.price, activePrice.value)
    if (!refreshedPrice) return
    applyExistingPrice(refreshedPrice)
    currentBaseline.value = priceDraftSnapshot(form)
    if (activeDraftKey.value) draftStates.delete(activeDraftKey.value)
  },
)

function selectChannelModel(model: Pick<ChannelModel, 'id'> | null | undefined) {
  const nextModel = String(model?.id ?? '').trim()
  if (!nextModel || nextModel === form.upstreamModel.trim()) return
  stashCurrentDraft()
  cancelReferenceLookup()
  manuallyEnteredModel.value = false
  form.upstreamModel = nextModel
}

function editPrice(price: ModelPrice) {
  const modelId = String(price?.upstreamModel ?? '').trim()
  if (!modelId) return
  const targetKey = priceDraftKey(form.channelId, modelId)
  const targetDraft = draftStates.get(targetKey)

  if (modelId === form.upstreamModel.trim()) {
    if (currentDraftDirty.value &&
      !window.confirm(`「${modelId}」有未保存的修改，重新载入已保存价格会丢弃这些修改，是否继续？`)
    ) return
    draftStates.delete(targetKey)
    cancelReferenceLookup()
    activePrice.value = price
    applyPriceToForm(form, price)
    currentBaseline.value = priceDraftSnapshot(form)
    return
  }

  if (priceDraftHasChanges(targetDraft) &&
    !window.confirm(`「${modelId}」有未保存的修改，重新载入已保存价格会丢弃这些修改，是否继续？`)
  ) return
  stashCurrentDraft()
  draftStates.delete(targetKey)
  cancelReferenceLookup()
  manuallyEnteredModel.value = false
  form.upstreamModel = modelId
}

function startNewPrice() {
  if (currentDraftDirty.value &&
    !window.confirm('当前价格有未保存的修改，新增单价会丢弃这些修改，是否继续？')
  ) return
  draftStates.clear()
  activeDraftKey.value = ''
  cancelReferenceLookup()
  activePrice.value = null
  resetPriceForm(form)
  currentBaseline.value = priceDraftSnapshot(form)
  manuallyEnteredModel.value = false
}

function markModelAsManuallyEntered() {
  manuallyEnteredModel.value = true
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
  form.priceTiers = (price.priceTiers || []).map((tier) => ({
    upToTokens: tier.upToTokens ?? '',
    inputPricePerMillion: tier.inputPricePerMillion ?? '',
    outputPricePerMillion: tier.outputPricePerMillion ?? '',
    cachedInputPricePerMillion: tier.cachedInputPricePerMillion ?? '',
    cachedInputWritePricePerMillion: tier.cachedInputWritePricePerMillion ?? '',
  }))
}

function priceSourceLabel(model: ChannelModelPriceOption) {
  if (model?.price) return '渠道覆盖'
  if (model?.defaultPrice) return '默认价'
  return '未定价'
}

function priceSourceClass(model: ChannelModelPriceOption) {
  if (model?.price) return 'success'
  if (model?.defaultPrice) return 'info'
  return 'warning'
}

function pricingModeLabel(price: { pricingMode?: string | null } | null | undefined) {
  const labels: Record<string, string> = {
    token: '按 Token',
    per_request: '按次',
    tiered: '阶梯价',
    volume: '阶梯总量价',
  }
  return labels[price?.pricingMode || 'token'] || '按 Token'
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
      notify(cause instanceof Error && cause.message ? cause.message : '未在参考库中找到该模型，请手动输入', 'warning')
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
  const drafts = collectDirtyPriceDrafts(draftStates, currentPriceDraft())
  if (drafts.length === 0) {
    notify('没有需要保存的修改', 'info')
    return
  }
  for (const draft of drafts) {
    const validationError = priceFormError(draft.form)
    if (validationError) {
      if (draft.modelId !== form.upstreamModel.trim()) {
        stashCurrentDraft()
        draftStates.set(draft.key, draft)
        form.upstreamModel = draft.modelId
      }
      notify(`「${draft.modelId}」：${validationError}`, 'error')
      return
    }
  }
  if (drafts.length > 1 &&
    !window.confirm(`将保存 ${drafts.length} 个模型的渠道覆盖价，是否继续？`)
  ) return

  saving.value = true
  let savedCount = 0
  const failures: string[] = []
  try {
    for (const draft of drafts) {
      try {
        const payload = existingPricePayload(draft.form, draft.channelId, draft.modelId)
        const saved = draft.price?.id
          ? await api.updateModelPrice(draft.price.id, payload)
          : await api.createModelPrice(payload)
        const baseline = priceDraftSnapshot(draft.form)
        draftStates.set(draft.key, { ...draft, price: saved, baseline })
        if (draft.key === activeDraftKey.value) {
          activePrice.value = saved
          currentBaseline.value = baseline
        }
        savedCount += 1
      } catch (cause) {
        failures.push(draft.modelId + '：' + (cause instanceof Error ? cause.message : String(cause)))
      }
    }
    if (savedCount > 0) emit('saved')
    if (failures.length > 0) {
      notify(`已保存 ${savedCount} 个，${failures.length} 个失败。${failures[0]}`, 'error')
    } else if (drafts.length === 1) {
      notify(drafts[0].price?.id ? '计费单价已更新' : '计费单价已创建')
    } else {
      notify(`已保存 ${savedCount} 个模型的渠道单价`)
    }
  } finally {
    saving.value = false
  }
}

async function removePrice(price: ModelPrice) {
  if (!price?.id) return
  const modelId = String(price.upstreamModel ?? '').trim()
  if (!modelId) return
  const key = priceDraftKey(form.channelId, modelId)
  const storedDraft = draftStates.get(key)
  const hasUnsavedDraft = modelId === form.upstreamModel.trim()
    ? currentDraftDirty.value
    : priceDraftHasChanges(storedDraft)
  const defaultPrice = channelModelOptions.value.find(
    (model) => String(model.id ?? '').trim() === modelId,
  )?.defaultPrice || null
  if (!window.confirm(buildChannelPriceDeleteMessage({ modelId, defaultPrice, hasUnsavedDraft }))) return

  deletingPriceId.value = price.id
  try {
    await api.deleteModelPrice(price.id)
    locallyDeletedPriceIds.value = new Set([...locallyDeletedPriceIds.value, price.id])
    draftStates.delete(key)
    if (modelId === form.upstreamModel.trim()) {
      activePrice.value = null
      Object.assign(form, emptyPriceDraft())
      currentBaseline.value = priceDraftSnapshot(form)
    }
    notify(defaultPrice
      ? `「${modelId}」的渠道覆盖价已删除，将继续继承模型默认价`
      : `「${modelId}」的渠道覆盖价已删除，已恢复为未定价`)
    emit('changed')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    deletingPriceId.value = null
  }
}

function requestClose() {
  if (dirtyDraftCount.value > 0 &&
    !window.confirm(`还有 ${dirtyDraftCount.value} 项价格修改未保存，确定关闭？`)
  ) return
  emit('close')
}
</script>

<template>
  <Modal :title="activePrice?.id ? '编辑计费单价' : '模型计费单价'" @close="requestClose">
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
            <span v-if="dirtyDraftCount" class="muted small"> · {{ dirtyDraftCount }} 项未保存</span>
          </div>
          <div class="price-configured-actions">
            <button v-if="!modelsLoading && !channelModelOptions.length" type="button" class="button ghost mini" @click="startNewPrice">新增单价</button>
          </div>
        </div>
        <div v-if="channelPrices.length" class="price-configured-list">
          <div
            v-for="price in channelPrices"
            :key="price.id"
            class="price-configured-item"
            :class="{ active: activePrice?.id === price.id }"
          >
            <button
              type="button"
              class="price-configured-main"
              :aria-pressed="activePrice?.id === price.id"
              :aria-label="`编辑 ${price.upstreamModel} 的单价：${formatPriceSummary(price)}`"
              @click="editPrice(price)"
            >
              <code>{{ price.upstreamModel }}</code>
              <span class="muted small">{{ formatPriceSummary(price) }}</span>
            </button>
            <span v-if="isModelDirty(price.upstreamModel)" class="badge warning unsaved">未保存</span>
            <button
              type="button"
              class="icon-button danger"
              :disabled="deletingPriceId === price.id"
              :aria-label="`删除 ${price.upstreamModel} 的渠道覆盖价`"
              @click="removePrice(price)"
            >
              <Icon name="trash" :size="15" />
            </button>
          </div>
        </div>
        <p v-else class="muted small price-configured-empty">该渠道还没有渠道覆盖价，模型会继承默认价（如有）。</p>
      </div>
      <section class="field model-selection-field" aria-label="上游模型">
        <div class="model-selection-head">
          <span>上游模型</span>
          <small v-if="modelChoiceMode === 'multiple'">
            {{ channelModelOptions.length }} 个模型，可分别编辑，最后统一保存
          </small>
        </div>
        <div v-if="modelsLoading && channelModelOptions.length === 0" class="model-selection-message">
          正在读取渠道模型…
        </div>
        <template v-else-if="channelModelOptions.length">
          <div
            v-if="modelChoiceMode === 'single'"
            class="model-single-option"
          >
            <span class="model-option-copy">
              <code>{{ channelModelOptions[0].id }}</code>
              <small v-if="channelModelOptions[0].ownedBy">{{ channelModelOptions[0].ownedBy }}</small>
            </span>
            <span class="model-option-badges">
              <span v-if="isModelDirty(channelModelOptions[0].id)" class="badge warning unsaved">未保存</span>
              <span class="badge" :class="priceSourceClass(channelModelOptions[0])">
                {{ priceSourceLabel(channelModelOptions[0]) }}
              </span>
            </span>
          </div>
          <div v-else class="model-switcher" role="group" aria-label="选择要配置的上游模型">
            <button
              v-for="model in channelModelOptions"
              :key="model.id"
              type="button"
              class="model-switch-option"
              :class="{ active: form.upstreamModel.trim() === model.id }"
              :aria-pressed="form.upstreamModel.trim() === model.id"
              @click="selectChannelModel(model)"
            >
              <span class="model-option-copy">
                <code>{{ model.id }}</code>
                <small v-if="model.ownedBy">{{ model.ownedBy }}</small>
              </span>
              <span class="model-option-badges">
                <span v-if="isModelDirty(model.id)" class="badge warning unsaved">未保存</span>
                <span class="badge" :class="priceSourceClass(model)">
                  {{ priceSourceLabel(model) }}
                </span>
              </span>
            </button>
          </div>
          <div class="model-selected-bar">
            <span>当前配置：<code>{{ form.upstreamModel }}</code></span>
            <button type="button" class="button subtle mini" :disabled="fetchingReference || !form.upstreamModel.trim()" @click="fetchReferencePrice">
              {{ fetchingReference ? '查询中…' : '获取参考单价' }}
            </button>
          </div>
        </template>
        <div v-else class="model-input-row">
          <input
            id="price-upstream-model"
            v-model="form.upstreamModel"
            placeholder="输入模型名"
            autocomplete="off"
            @input="markModelAsManuallyEntered"
          />
          <button type="button" class="button subtle mini" :disabled="fetchingReference || !form.upstreamModel.trim()" @click="fetchReferencePrice">
            {{ fetchingReference ? '查询中…' : '获取参考单价' }}
          </button>
        </div>
        <small v-if="modelsError" class="danger">{{ modelsError }}</small>
        <small v-if="referenceHint" class="reference-hint">{{ referenceHint }}</small>
        <small v-else-if="selectedPriceSource === 'channel'">当前使用渠道覆盖价，模型默认价不会生效。</small>
        <small v-else-if="selectedPriceSource === 'default'" class="reference-hint">
          当前继承模型默认价（{{ formatPriceSummary(inheritedPrice) }}）。保存后将创建渠道覆盖价。
          <button type="button" class="link-button" @click="applyDefaultPrice">填入默认价</button>
        </small>
        <small v-else-if="channelModelOptions.length">该模型尚未配置渠道覆盖价，可手动填写或获取 USD 参考价。</small>
        <small v-else>该渠道没有可读取的模型，可直接输入模型名。</small>
      </section>
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
      <PriceFields :form="form" />

      <p v-if="activePrice?.id" class="muted small price-source-note">正在编辑渠道覆盖价。</p>
      <p v-else-if="inheritedPrice" class="callout info price-source-note">
        该模型当前继承默认价；保存后将为当前渠道创建覆盖价。
      </p>
      <p class="muted small price-hint">
        计费方式支持按 Token、按次、阶梯累进与阶梯总量。缓存读写单价留空时回退到输入单价；推理 token 已包含在输出 token 中，不重复计费。
      </p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="requestClose">取消</button>
      <button
        type="button"
        class="button primary"
        :disabled="saving || channels.length === 0 || dirtyDraftCount === 0"
        @click="save"
      >
        {{ saveButtonLabel }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.price-configured-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  flex: 1;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.price-configured-main code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.price-configured-main span {
  flex: 0 0 auto;
  white-space: nowrap;
}

.model-option-badges {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  flex: 0 0 auto;
  gap: 4px;
}

.model-selection-field {
  display: grid;
  gap: 9px;
}

.model-selection-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.model-selection-head > span {
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
}

.model-selection-head > small {
  color: var(--muted);
  font-size: 11px;
}

.model-selection-message {
  padding: 11px 12px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  color: var(--muted);
  font-size: 12px;
}

.model-switcher {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  max-height: 220px;
  overflow-y: auto;
  padding: 2px;
}

.model-switch-option,
.model-single-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  color: var(--ink);
}

.model-switch-option {
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease, box-shadow 150ms ease;
}

.model-switch-option:hover,
.model-switch-option:focus-visible {
  border-color: var(--line-strong);
  outline: 0;
}

.model-switch-option.active {
  border-color: var(--brand);
  background: var(--info-soft);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--brand) 12%, transparent);
}

.model-single-option {
  background: var(--bg);
}

.model-option-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.model-option-copy code {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 12px;
}

.model-option-copy small {
  color: var(--muted);
  font-size: 11px;
}

.model-switch-option .badge,
.model-single-option .badge {
  flex: 0 0 auto;
}

.model-selected-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 2px;
}

.model-selected-bar > span {
  min-width: 0;
  color: var(--muted);
  font-size: 11px;
  overflow-wrap: anywhere;
}

.model-selected-bar code {
  color: var(--ink);
  font-size: 11px;
}

.danger {
  color: var(--danger);
}

@media (max-width: 600px) {
  .price-configured-main {
    align-items: flex-start;
    flex-direction: column;
  }

  .price-configured-main span {
    white-space: normal;
  }

  .model-switcher {
    grid-template-columns: 1fr;
  }

  .model-selection-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .model-selected-bar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

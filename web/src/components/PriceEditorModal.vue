<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { getAuthorizedUpstreamModelCache, hasUpstreamModelCache, setUpstreamModelCache } from '../upstreamModelCache'
import { findModelPrice } from '../utils/modelPrice'
import { buildPricePayload, emptyPriceDraft, hasPriceDraft, priceFormState } from '../utils/priceForm'
import Modal from './Modal.vue'

const props = defineProps({
  channels: { type: Array, required: true },
  price: { type: Object, default: null },
  prices: { type: Array, default: () => [] },
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
let modelsRequestId = 0

const initialPrice = priceFormState(props.price)
const form = reactive({
  channelId: props.price?.channelId || props.initialTarget?.channelId || props.channels[0]?.id || '',
  upstreamModel: props.price?.upstreamModel || props.initialTarget?.modelId || '',
  ...initialPrice,
})

const filteredChannelModels = computed(() => {
  const query = form.upstreamModel.trim().toLowerCase()
  if (!query) return channelModels.value
  return channelModels.value.filter((model) => model.id.toLowerCase().includes(query))
})

watch(
  () => form.channelId,
  async (channelId) => {
    const requestId = ++modelsRequestId
    modelPickerOpen.value = false
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
  activePrice.value = price
  Object.assign(form, priceFormState(price))
}

watch(
  () => [form.channelId, form.upstreamModel, props.prices],
  ([channelId, upstreamModel]) => {
    if (props.price) return
    const existingPrice = findModelPrice(props.prices, channelId, upstreamModel.trim())
    if (existingPrice) {
      if (activePrice.value?.id !== existingPrice.id) applyExistingPrice(existingPrice)
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
  form.upstreamModel = model.id
  modelPickerOpen.value = false
}

async function fetchReferencePrice() {
  const modelName = form.upstreamModel.trim()
  if (!modelName) {
    notify('请先输入或选择上游模型', 'warning')
    return
  }
  if (hasPriceDraft(form) && !window.confirm('获取参考价会用 USD 金额覆盖当前填写的单价，是否继续？')) {
    return
  }
  fetchingReference.value = true
  referenceHint.value = ''
  try {
    const res = await api.getModelPriceReference(modelName)
    form.currency = 'USD'
    form.inputPricePerMillion = res.inputPricePerMillion
    form.outputPricePerMillion = res.outputPricePerMillion
    form.cachedInputPricePerMillion = res.cachedInputPricePerMillion || ''
    referenceHint.value = `已填入参考价（匹配: ${res.matchedModel}，来源: ${res.source}）`
    notify(`已填入「${res.matchedModel}」USD 参考单价`)
  } catch (cause) {
    notify(cause.message || '未在参考库中找到该模型，请手动输入', 'warning')
  } finally {
    fetchingReference.value = false
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
  const inputPrice = Number(form.inputPricePerMillion)
  const outputPrice = Number(form.outputPricePerMillion)
  const cachedPriceText = String(form.cachedInputPricePerMillion ?? '').trim()
  const cachedPrice = cachedPriceText ? Number(cachedPriceText) : null
  if (!Number.isFinite(inputPrice) || inputPrice < 0 || !Number.isFinite(outputPrice) || outputPrice < 0) {
    notify('输入和输出单价必须是非负数字', 'error')
    return
  }
  if (cachedPrice != null && (!Number.isFinite(cachedPrice) || cachedPrice < 0)) {
    notify('缓存输入单价必须是非负数字', 'error')
    return
  }
  saving.value = true
  try {
    const payload = buildPricePayload(form, form.channelId, form.upstreamModel)
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
</script>

<template>
  <Modal :title="activePrice?.id ? '编辑计费单价' : '新增计费单价'" @close="$emit('close')">
    <form class="form-grid" @submit.prevent="save">
      <label class="field">
        <span>渠道</span>
        <select v-model="form.channelId">
          <option v-for="channel in channels" :key="channel.id" :value="channel.id">
            {{ channel.name }}
          </option>
        </select>
        <small>价格绑定到渠道，同一模型在不同渠道可以配置不同价格。</small>
      </label>
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
                <code>{{ model.id }}</code>
                <span v-if="model.ownedBy" class="muted small">{{ model.ownedBy }}</span>
              </button>
            </template>
            <div v-else class="model-picker-message">没有匹配的模型，可直接手动输入。</div>
          </div>
        </div>
        <small v-if="referenceHint" class="reference-hint">{{ referenceHint }}</small>
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
        <small>已有单价保留原币种；获取参考价会切换为 USD。</small>
      </label>
      <div class="field price-grid">
        <label class="field">
          <span>输入单价 / 1M</span>
          <input v-model="form.inputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.15" />
        </label>
        <label class="field">
          <span>输出单价 / 1M</span>
          <input v-model="form.outputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.60" />
        </label>
        <label class="field">
          <span>缓存输入单价 / 1M</span>
          <input v-model="form.cachedInputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="留空则按输入单价" />
        </label>
      </div>
      <p class="muted small price-hint">
        单位为每百万 token，币种见上方。缓存 token 使用缓存输入单价，留空时按输入单价计费；推理 token 已包含在输出 token 中，不重复计费。
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

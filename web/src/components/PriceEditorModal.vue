<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { getAuthorizedUpstreamModelCache, hasUpstreamModelCache, setUpstreamModelCache } from '../upstreamModelCache'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({
  channels: { type: Array, required: true },
  price: { type: Object, default: null },
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
const exchangeRates = ref(null)
const exchangeLoading = ref(false)
const exchangeError = ref('')
const conversionInfo = ref(null)
const priceBase = ref(props.price ? {
  currency: props.price.currency,
  inputPricePerMillion: props.price.inputPricePerMillion,
  outputPricePerMillion: props.price.outputPricePerMillion,
  cachedInputPricePerMillion: props.price.cachedInputPricePerMillion || '',
} : null)
let modelsRequestId = 0
let exchangeRequest = null
let skipNextCurrencyConversion = false

const form = reactive({
  channelId: props.price?.channelId || props.initialTarget?.channelId || props.channels[0]?.id || '',
  upstreamModel: props.price?.upstreamModel || props.initialTarget?.modelId || '',
  currency: props.price?.currency || 'USD',
  inputPricePerMillion: props.price?.inputPricePerMillion || '',
  outputPricePerMillion: props.price?.outputPricePerMillion || '',
  cachedInputPricePerMillion: props.price?.cachedInputPricePerMillion || '',
})

const filteredChannelModels = computed(() => {
  const query = form.upstreamModel.trim().toLowerCase()
  if (!query) return channelModels.value
  return channelModels.value.filter((model) => model.id.toLowerCase().includes(query))
})

const currencyHint = computed(() => {
  if (exchangeLoading.value) return '正在获取汇率并换算价格…'
  if (exchangeError.value) return exchangeError.value
  if (conversionInfo.value) {
    if (conversionInfo.value.from === conversionInfo.value.to) {
      return `已使用 ${conversionInfo.value.from} 基准价格 · ${conversionInfo.value.date} · ${conversionInfo.value.source}`
    }
    return `已按 1 ${conversionInfo.value.from} = ${conversionInfo.value.rate} ${conversionInfo.value.to} 换算 · ${conversionInfo.value.date} · ${conversionInfo.value.source}`
  }
  return '参考价从 USD 基准换算；手动改价后会以当前币种作为新基准。'
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

watch(
  () => form.currency,
  async (nextCurrency, previousCurrency) => {
    if (nextCurrency === previousCurrency) return
    if (skipNextCurrencyConversion) {
      skipNextCurrencyConversion = false
      return
    }
    await convertCurrency(previousCurrency, nextCurrency)
  },
  { flush: 'sync' },
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

function setCurrencyWithoutConversion(currency) {
  if (form.currency !== currency) skipNextCurrencyConversion = true
  form.currency = currency
}

async function fetchReferencePrice() {
  const modelName = form.upstreamModel.trim()
  if (!modelName) {
    notify('请先输入或选择上游模型', 'warning')
    return
  }
  fetchingReference.value = true
  referenceHint.value = ''
  conversionInfo.value = null
  exchangeError.value = ''
  try {
    const res = await api.getModelPriceReference(modelName)
    setCurrencyWithoutConversion(res.currency || 'USD')
    form.inputPricePerMillion = res.inputPricePerMillion
    form.outputPricePerMillion = res.outputPricePerMillion
    form.cachedInputPricePerMillion = res.cachedInputPricePerMillion || ''
    priceBase.value = {
      currency: res.currency || 'USD',
      inputPricePerMillion: res.inputPricePerMillion,
      outputPricePerMillion: res.outputPricePerMillion,
      cachedInputPricePerMillion: res.cachedInputPricePerMillion || '',
    }
    referenceHint.value = `已填入参考价（匹配: ${res.matchedModel}，原币种: ${res.currency || 'USD'}，来源: ${res.source}）`
    notify(`已填入「${res.matchedModel}」参考单价`)
  } catch (cause) {
    notify(cause.message || '未在参考库中找到该模型，请手动输入', 'warning')
  } finally {
    fetchingReference.value = false
  }
}

async function ensureExchangeRates() {
  if (exchangeRates.value) return exchangeRates.value
  if (exchangeRequest) return exchangeRequest
  exchangeLoading.value = true
  exchangeError.value = ''
  exchangeRequest = (async () => {
    try {
      const data = await api.getExchangeRates()
      exchangeRates.value = data
      return data
    } finally {
      exchangeLoading.value = false
      exchangeRequest = null
    }
  })()
  return exchangeRequest
}

async function convertCurrency(fromCurrency, toCurrency) {
  const hasPrice = [form.inputPricePerMillion, form.outputPricePerMillion, form.cachedInputPricePerMillion]
    .some((value) => value !== '' && value != null)
  if (!hasPrice) {
    conversionInfo.value = null
    exchangeError.value = ''
    return
  }
  exchangeLoading.value = true
  exchangeError.value = ''
  try {
    const data = await ensureExchangeRates()
    const basis = priceBase.value
    const sourceCurrency = basis?.currency || fromCurrency
    const factor = exchangeRate(data, toCurrency) / exchangeRate(data, sourceCurrency)
    form.inputPricePerMillion = convertValue(basis?.inputPricePerMillion ?? form.inputPricePerMillion, factor)
    form.outputPricePerMillion = convertValue(basis?.outputPricePerMillion ?? form.outputPricePerMillion, factor)
    form.cachedInputPricePerMillion = convertValue(basis?.cachedInputPricePerMillion ?? form.cachedInputPricePerMillion, factor)
    conversionInfo.value = {
      from: sourceCurrency,
      to: toCurrency,
      rate: Number(factor.toFixed(8)).toString(),
      date: data.date,
      source: data.source,
    }
  } catch (cause) {
    exchangeError.value = cause.message || '无法获取汇率'
    setCurrencyWithoutConversion(fromCurrency)
    notify('无法获取汇率，币种已恢复', 'error')
  } finally {
    exchangeLoading.value = false
  }
}

function exchangeRate(data, currency) {
  if (currency === 'USD') return 1
  const rate = Number(data.rates?.[currency])
  if (!Number.isFinite(rate) || rate <= 0) throw new Error(`缺少 ${currency} 汇率`)
  return rate
}

function convertValue(value, factor) {
  if (value === '' || value == null) return value
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return Number((number * factor).toFixed(8)).toString()
}

function convertBaseValue(value, factor) {
  if (value === '' || value == null) return value
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return (number * factor).toFixed(12).replace(/0+$/, '').replace(/\.$/, '')
}

function markPriceEdited(event, field) {
  const basis = priceBase.value
  let nextBase
  if (basis && basis.currency === form.currency) {
    nextBase = { ...basis }
  } else if (basis && exchangeRates.value) {
    try {
      const factor = exchangeRate(exchangeRates.value, form.currency) / exchangeRate(exchangeRates.value, basis.currency)
      nextBase = {
        currency: form.currency,
        inputPricePerMillion: convertBaseValue(basis.inputPricePerMillion, factor),
        outputPricePerMillion: convertBaseValue(basis.outputPricePerMillion, factor),
        cachedInputPricePerMillion: convertBaseValue(basis.cachedInputPricePerMillion, factor),
      }
    } catch {
      nextBase = null
    }
  }
  if (!nextBase) {
    nextBase = {
      currency: form.currency,
      inputPricePerMillion: form.inputPricePerMillion,
      outputPricePerMillion: form.outputPricePerMillion,
      cachedInputPricePerMillion: form.cachedInputPricePerMillion,
    }
  }
  nextBase[field] = event.target.value
  priceBase.value = nextBase
  conversionInfo.value = null
  exchangeError.value = ''
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
  const inputPriceText = String(form.inputPricePerMillion).trim()
  const outputPriceText = String(form.outputPricePerMillion).trim()
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
    const payload = {
      channelId: form.channelId,
      upstreamModel: form.upstreamModel.trim(),
      currency: form.currency,
      inputPricePerMillion: inputPriceText,
      outputPricePerMillion: outputPriceText,
      cachedInputPricePerMillion: cachedPrice == null ? null : cachedPriceText,
    }
    if (props.price?.id) {
      await api.updateModelPrice(props.price.id, payload)
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
  <Modal :title="price?.id ? '编辑计费单价' : '新增计费单价'" @close="$emit('close')">
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
        <span>币种</span>
        <select v-model="form.currency" :disabled="exchangeLoading">
          <option value="USD">USD</option>
          <option value="CNY">CNY</option>
          <option value="EUR">EUR</option>
        </select>
        <small>每条价格规则使用独立币种。</small>
        <small class="currency-hint" :class="{ 'text-danger': exchangeError, 'reference-hint': conversionInfo && !exchangeError }">
          {{ currencyHint }}
        </small>
      </label>
      <div class="field price-grid">
        <label class="field">
          <span>输入单价 / 1M</span>
          <input v-model="form.inputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.15" :disabled="exchangeLoading" @input="markPriceEdited($event, 'inputPricePerMillion')" />
        </label>
        <label class="field">
          <span>输出单价 / 1M</span>
          <input v-model="form.outputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.60" :disabled="exchangeLoading" @input="markPriceEdited($event, 'outputPricePerMillion')" />
        </label>
        <label class="field">
          <span>缓存输入单价 / 1M</span>
          <input v-model="form.cachedInputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="留空则按输入单价" :disabled="exchangeLoading" @input="markPriceEdited($event, 'cachedInputPricePerMillion')" />
        </label>
      </div>
      <p class="muted small price-hint">
        单位为每百万 token。缓存 token 使用缓存输入单价，留空时按输入单价计费；推理 token 已包含在输出 token 中，不重复计费。
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

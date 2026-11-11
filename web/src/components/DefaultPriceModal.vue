<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { Channel, ModelDefaultPrice } from '../types/api'
import { formatPriceSummary } from '../utils/format'
import { defaultPriceModelOptions } from '../utils/modelPrice'
import {
  buildDefaultPricePayload,
  existingDefaultPricePayload,
  defaultPriceFormState,
  hasPriceDraft,
  priceFormError,
} from '../utils/priceForm'
import type { PriceForm } from '../utils/priceForm'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PriceFields from './PriceFields.vue'

const props = withDefaults(defineProps<{
  prices?: ModelDefaultPrice[]
  channels?: Channel[]
}>(), {
  prices: () => [],
  channels: () => [],
})
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
}>()

const activePrice = ref<ModelDefaultPrice | null>(null)
const saving = ref(false)
const deletingId = ref<string | null>(null)
const fetchingReference = ref(false)
const referenceHint = ref('')
const form = reactive<PriceForm & { modelId: string }>(defaultPriceFormState(null))
let referenceRequestId = 0

function referenceDraftKey() {
  return [
    activePrice.value?.id || '',
    form.modelId.trim(),
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

watch(
  () => form.modelId,
  () => {
    referenceHint.value = ''
  },
)

const modelOptions = computed(() => defaultPriceModelOptions(props.channels, props.prices, activePrice.value?.modelId))

const filteredModelOptions = computed(() => {
  const query = form.modelId.trim().toLowerCase()
  if (!query) return modelOptions.value
  return modelOptions.value.filter((model) =>
    model.id.toLowerCase().includes(query) ||
    model.upstreamModels.some((upstream) => upstream.toLowerCase().includes(query)))
})

function applyPrice(price: ModelDefaultPrice) {
  cancelReferenceLookup()
  activePrice.value = price
  Object.assign(form, defaultPriceFormState(price))
  referenceHint.value = ''
}

function startNewPrice() {
  cancelReferenceLookup()
  activePrice.value = null
  Object.assign(form, defaultPriceFormState(null))
  referenceHint.value = ''
}

async function fetchReferencePrice() {
  const modelName = form.modelId.trim()
  if (!modelName) {
    notify('请先填写或选择模型 ID', 'warning')
    return
  }
  if (hasPriceDraft(form) && !window.confirm('获取参考价会用 USD 参考金额覆盖当前填写的单价，是否继续？')) {
    return
  }
  const requestId = ++referenceRequestId
  const draftKey = referenceDraftKey()
  fetchingReference.value = true
  referenceHint.value = ''
  try {
    const res = await api.getModelPriceReference(modelName)
    if (requestId !== referenceRequestId || draftKey !== referenceDraftKey()) return
    form.pricingMode = 'token'
    form.perRequestPrice = ''
    form.cachedInputWritePricePerMillion = ''
    form.priceTiers = []
    form.inputPricePerMillion = res.inputPricePerMillion
    form.outputPricePerMillion = res.outputPricePerMillion
    form.cachedInputPricePerMillion = res.cachedInputPricePerMillion || ''
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
  const modelId = form.modelId.trim()
  if (!modelId) {
    notify('请填写模型 ID', 'error')
    return
  }
  const validationError = priceFormError(form)
  if (validationError) {
    notify(validationError, 'error')
    return
  }
  saving.value = true
  try {
    const payload = existingDefaultPricePayload(form, modelId)
    if (activePrice.value?.id) {
      await api.updateModelDefaultPrice(activePrice.value.id, payload)
      notify('模型默认价已更新')
    } else {
      await api.createModelDefaultPrice(payload)
      notify('模型默认价已创建')
    }
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    saving.value = false
  }
}

async function remove(price: ModelDefaultPrice) {
  if (!price?.id) return
  if (!window.confirm(`确定删除模型「${price.modelId}」的默认价？`)) return
  deletingId.value = price.id
  try {
    await api.deleteModelDefaultPrice(price.id)
    if (activePrice.value?.id === price.id) startNewPrice()
    notify('模型默认价已删除')
    emit('saved')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    deletingId.value = null
  }
}
</script>

<template>
  <Modal title="模型默认价" wide @close="$emit('close')">
    <form class="form-grid" @submit.prevent="save">
      <div class="price-configured">
        <div class="price-configured-head">
          <div>
            <strong>已配置默认价</strong>
            <span class="muted small"> · {{ prices.length }} 个模型</span>
          </div>
          <button type="button" class="button ghost mini" @click="startNewPrice">新增默认价</button>
        </div>
        <div v-if="prices.length" class="price-configured-list">
          <div
            v-for="price in prices"
            :key="price.id"
            class="price-configured-item"
            :class="{ active: activePrice?.id === price.id }"
          >
            <button
              type="button"
              class="price-default-main"
              :aria-pressed="activePrice?.id === price.id"
              :aria-label="`编辑 ${price.modelId} 的默认价：${formatPriceSummary(price)}`"
              @click="applyPrice(price)"
            >
              <code>{{ price.modelId }}</code>
              <span class="muted small">{{ formatPriceSummary(price) }}</span>
            </button>
            <button
              type="button"
              class="icon-button danger"
              :disabled="deletingId === price.id"
              :aria-label="`删除 ${price.modelId} 的默认价`"
              @click="remove(price)"
            >
              <Icon name="trash" :size="15" />
            </button>
          </div>
        </div>
        <p v-else class="muted small price-configured-empty">还没有配置模型默认价。</p>
      </div>

      <label class="field" for="default-price-model">
        <span>模型 ID</span>
        <input
          id="default-price-model"
          v-model="form.modelId"
          list="default-price-model-options"
          placeholder="例如 deepseek-v4.1-flash"
          autocomplete="off"
          :disabled="Boolean(activePrice)"
        />
        <datalist id="default-price-model-options">
          <option v-for="model in filteredModelOptions" :key="model.id" :value="model.id">
            {{ model.channelNames.length ? `渠道：${model.channelNames.join('、')}` : '' }}
          </option>
        </datalist>
        <small v-if="activePrice">已配置的默认单价不可修改模型 ID；如需更换模型请新增或删除后重新配置。</small>
        <small v-else>默认价按模型 ID 生效；渠道覆盖价优先。模型 ID 通常是渠道配置的规范 ID，未配置时使用上游模型名。</small>
      </label>

      <PriceFields :form="form" />

      <div class="price-default-reference">
        <button type="button" class="button subtle mini" :disabled="fetchingReference || !form.modelId.trim()" @click="fetchReferencePrice">
          {{ fetchingReference ? '查询中…' : '获取参考单价' }}
        </button>
        <small v-if="referenceHint" class="reference-hint">{{ referenceHint }}</small>
      </div>

      <p class="muted small price-hint">
        默认价用于没有渠道覆盖价的模型。计费方式支持按 Token、按次、阶梯累进与阶梯总量；缓存读写单价留空时回退到输入单价。
      </p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="saving" @click="save">
        {{ saving ? '保存中…' : '保存默认价' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.price-default-main {
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

.price-default-main code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.price-default-main span {
  flex: 0 0 auto;
  white-space: nowrap;
}

.price-default-reference {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

@media (max-width: 600px) {
  .price-default-main {
    align-items: flex-start;
    flex-direction: column;
  }

  .price-default-main span {
    white-space: normal;
  }
}
</style>

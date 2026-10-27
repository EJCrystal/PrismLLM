<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { formatUnitPrice } from '../utils/format'
import {
  buildDefaultPricePayload,
  defaultPriceFormState,
  hasPriceDraft,
  parseNonNegativePrice,
  parseOptionalNonNegativePrice,
} from '../utils/priceForm'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({
  prices: { type: Array, default: () => [] },
  channels: { type: Array, default: () => [] },
})
const emit = defineEmits(['close', 'saved'])

const activePrice = ref(null)
const saving = ref(false)
const deletingId = ref(null)
const fetchingReference = ref(false)
const referenceHint = ref('')
const form = reactive({ modelId: '', ...defaultPriceFormState(null) })
let referenceRequestId = 0

function referenceDraftKey() {
  return [
    activePrice.value?.id || '',
    form.modelId.trim(),
    form.currency,
    form.inputPricePerMillion,
    form.outputPricePerMillion,
    form.cachedInputPricePerMillion,
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

const modelOptions = computed(() => {
  const entries = new Map()
  for (const channel of Array.isArray(props.channels) ? props.channels : []) {
    for (const mapping of channel.modelMappings || []) {
      const upstreamModel = String(mapping.upstreamModel ?? '').trim()
      const modelId = String(mapping.canonicalModelId ?? '').trim() || upstreamModel
      if (!modelId) continue
      const entry = entries.get(modelId) || { id: modelId, upstreamModels: new Set(), channelNames: new Set() }
      if (upstreamModel) entry.upstreamModels.add(upstreamModel)
      if (channel.name) entry.channelNames.add(channel.name)
      entries.set(modelId, entry)
    }
  }
  for (const price of Array.isArray(props.prices) ? props.prices : []) {
    const modelId = String(price?.modelId ?? '').trim()
    if (modelId && !entries.has(modelId)) {
      entries.set(modelId, { id: modelId, upstreamModels: new Set(), channelNames: new Set() })
    }
  }
  return [...entries.values()]
    .map((entry) => ({
      id: entry.id,
      upstreamModels: [...entry.upstreamModels].sort(),
      channelNames: [...entry.channelNames].sort(),
    }))
    .sort((left, right) => left.id.localeCompare(right.id))
})

const filteredModelOptions = computed(() => {
  const query = form.modelId.trim().toLowerCase()
  if (!query) return modelOptions.value
  return modelOptions.value.filter((model) =>
    model.id.toLowerCase().includes(query) ||
    model.upstreamModels.some((upstream) => upstream.toLowerCase().includes(query)))
})

function applyPrice(price) {
  cancelReferenceLookup()
  activePrice.value = price
  Object.assign(form, defaultPriceFormState(price))
  referenceHint.value = ''
}

function startNewPrice() {
  cancelReferenceLookup()
  activePrice.value = null
  Object.assign(form, { modelId: '', ...defaultPriceFormState(null) })
  referenceHint.value = ''
}

async function fetchReferencePrice() {
  const modelName = form.modelId.trim()
  if (!modelName) {
    notify('请先填写或选择模型 ID', 'warning')
    return
  }
  if (form.currency !== 'USD' && hasPriceDraft(form)) {
    notify('参考价以 USD 计；当前单价不是 USD，请先清空金额或新建 USD 默认价', 'warning')
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
    form.inputPricePerMillion = res.inputPricePerMillion
    form.outputPricePerMillion = res.outputPricePerMillion
    form.cachedInputPricePerMillion = res.cachedInputPricePerMillion || ''
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
  const modelId = form.modelId.trim()
  if (!modelId) {
    notify('请填写模型 ID', 'error')
    return
  }
  const inputPrice = parseNonNegativePrice(form.inputPricePerMillion)
  const outputPrice = parseNonNegativePrice(form.outputPricePerMillion)
  const cachedPrice = parseOptionalNonNegativePrice(form.cachedInputPricePerMillion)
  if (inputPrice == null || outputPrice == null) {
    notify('输入和输出单价必须是非负数字', 'error')
    return
  }
  if (String(form.cachedInputPricePerMillion ?? '').trim() && cachedPrice == null) {
    notify('缓存输入单价必须是非负数字', 'error')
    return
  }
  saving.value = true
  try {
    const payload = buildDefaultPricePayload(form, modelId)
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
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(price) {
  if (!price?.id) return
  if (!window.confirm(`确定删除模型「${price.modelId}」的默认价？`)) return
  deletingId.value = price.id
  try {
    await api.deleteModelDefaultPrice(price.id)
    if (activePrice.value?.id === price.id) startNewPrice()
    notify('模型默认价已删除')
    emit('saved')
  } catch (cause) {
    notify(cause.message, 'error')
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
              :aria-label="`编辑 ${price.modelId} 的默认价：输入 ${formatUnitPrice(price.inputPricePerMillion)}，输出 ${formatUnitPrice(price.outputPricePerMillion)}，币种 ${price.currency}`"
              @click="applyPrice(price)"
            >
              <code>{{ price.modelId }}</code>
              <span class="muted small">
                输入 {{ formatUnitPrice(price.inputPricePerMillion) }} · 输出
                {{ formatUnitPrice(price.outputPricePerMillion) }} · {{ price.currency }} / 1M
              </span>
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
        />
        <datalist id="default-price-model-options">
          <option v-for="model in filteredModelOptions" :key="model.id" :value="model.id">
            {{ model.channelNames.length ? `渠道：${model.channelNames.join('、')}` : '' }}
          </option>
        </datalist>
        <small>默认价按模型 ID 生效；渠道覆盖价优先。模型 ID 通常是渠道配置的规范 ID，未配置时使用上游模型名。</small>
      </label>

      <label class="field">
        <span>计费币种</span>
        <input type="text" :value="form.currency" readonly aria-label="计费币种" />
        <small>获取参考价会切换为 USD。</small>
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

      <div class="price-default-reference">
        <button type="button" class="button subtle mini" :disabled="fetchingReference || !form.modelId.trim()" @click="fetchReferencePrice">
          {{ fetchingReference ? '查询中…' : '获取参考单价' }}
        </button>
        <small v-if="referenceHint" class="reference-hint">{{ referenceHint }}</small>
      </div>

      <p class="muted small price-hint">
        默认价用于没有渠道覆盖价的模型。单位为每百万 token；缓存 token 使用缓存输入单价，留空时按输入单价计费。
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

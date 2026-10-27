<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { formatPriceSummary, formatTime, formatUnitPrice } from '../utils/format'
import { defaultPriceModelOptions } from '../utils/modelPrice'
import {
  buildDefaultPricePayload,
  existingDefaultPricePayload,
  defaultPriceFormState,
  hasPriceDraft,
  priceFormError,
} from '../utils/priceForm'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import PriceFields from './PriceFields.vue'

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
const showVersions = ref(false)
const loadingVersions = ref(false)
const versionHistory = ref([])
const cancellingVersion = ref(false)
const form = reactive({ modelId: '', ...defaultPriceFormState(null) })
let referenceRequestId = 0
let versionRequestId = 0

function referenceDraftKey() {
  return [
    activePrice.value?.id || '',
    form.modelId.trim(),
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

function applyPrice(price) {
  cancelReferenceLookup()
  versionRequestId += 1
  loadingVersions.value = false
  activePrice.value = price
  showVersions.value = false
  versionHistory.value = []
  Object.assign(form, defaultPriceFormState(price))
  referenceHint.value = ''
}

function startNewPrice() {
  cancelReferenceLookup()
  versionRequestId += 1
  loadingVersions.value = false
  activePrice.value = null
  showVersions.value = false
  versionHistory.value = []
  Object.assign(form, { modelId: '', ...defaultPriceFormState(null) })
  referenceHint.value = ''
}

async function toggleVersionHistory() {
  showVersions.value = !showVersions.value
  if (showVersions.value && activePrice.value?.id) {
    const requestId = ++versionRequestId
    const priceId = activePrice.value.id
    loadingVersions.value = true
    try {
      const versions = await api.listModelDefaultPriceVersions(priceId)
      if (requestId === versionRequestId) versionHistory.value = versions
    } catch (e) {
      if (requestId === versionRequestId) notify(e.message, 'error')
    } finally {
      if (requestId === versionRequestId) loadingVersions.value = false
    }
  }
}

watch(
  () => [activePrice.value?.id, showVersions.value],
  () => {
    if (!showVersions.value) loadingVersions.value = false
  },
)

async function cancelScheduledVersion(version) {
  if (!version?.id) return
  if (!window.confirm(`确定取消将于 ${formatTime(version.effectiveFrom)} 生效的版本 v${version.version}？`)) return
  cancellingVersion.value = true
  const requestId = ++versionRequestId
  const priceId = activePrice.value?.id
  try {
    await api.deleteModelDefaultPriceVersion(version.id)
    notify('定时生效版本已取消')
    if (activePrice.value && requestId === versionRequestId) {
      activePrice.value.scheduledVersion = null
    }
    if (showVersions.value && priceId) {
      const versions = await api.listModelDefaultPriceVersions(priceId)
      if (requestId === versionRequestId) versionHistory.value = versions
    }
    emit('saved')
  } catch (e) {
    notify(e.message, 'error')
  } finally {
    if (requestId === versionRequestId) cancellingVersion.value = false
  }
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

      <label class="field">
        <span>计费币种</span>
        <input type="text" :value="form.currency" readonly aria-label="计费币种" />
        <small>获取参考价会切换为 USD。</small>
      </label>

      <PriceFields :form="form" />

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

<script setup>
import { toLocalInput } from '../utils/format'

const props = defineProps({
  form: { type: Object, required: true },
})

const modes = [
  { value: 'token', label: '按 Token' },
  { value: 'per_request', label: '按次' },
  { value: 'tiered', label: '阶梯价' },
  { value: 'volume', label: '阶梯总量价' },
]

function onScheduleChange(scheduled) {
  props.form.isScheduled = scheduled
  if (scheduled && !props.form.effectiveAt) {
    const tomorrow = new Date(Date.now() + 24 * 3600_000)
    tomorrow.setMinutes(0, 0, 0)
    props.form.effectiveAt = toLocalInput(tomorrow.getTime())
  }
}

function addTier() {
  const tiers = props.form.priceTiers || (props.form.priceTiers = [])
  const last = tiers[tiers.length - 1]
  if (last && String(last.upToTokens ?? '').trim() === '') last.upToTokens = '128000'
  tiers.push({
    upToTokens: '',
    inputPricePerMillion: '',
    outputPricePerMillion: '',
    cachedInputPricePerMillion: '',
    cachedInputWritePricePerMillion: '',
  })
}

function removeTier(index) {
  props.form.priceTiers.splice(index, 1)
  const last = props.form.priceTiers[props.form.priceTiers.length - 1]
  if (last) last.upToTokens = ''
}
</script>

<template>
  <label class="field">
    <span>计费方式</span>
    <select v-model="form.pricingMode">
      <option v-for="mode in modes" :key="mode.value" :value="mode.value">{{ mode.label }}</option>
    </select>
    <small v-if="form.pricingMode === 'tiered'">
      输入、输出、缓存读取和缓存写入分别按各自 Token 数逐档累计；每档缓存单价留空时使用该档输入价。
    </small>
    <small v-else-if="form.pricingMode === 'volume'">
      每类 Token 按该类请求总量选中一档，并将该类全部用量按该档单价计费。
    </small>
    <small v-else-if="form.pricingMode === 'per_request'">每个成功完成的请求收取一次固定费用，不依赖上游是否返回 Token 用量。</small>
    <small v-else>按各类 Token 的单价计费；缓存读写价格留空时回退到输入单价。</small>
  </label>

  <div v-if="form.pricingMode === 'token'" class="field price-grid">
    <label class="field">
      <span>输入单价 / 1M</span>
      <input v-model="form.inputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.15" />
    </label>
    <label class="field">
      <span>输出单价 / 1M</span>
      <input v-model="form.outputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.60" />
    </label>
    <label class="field">
      <span>缓存读取单价 / 1M</span>
      <input v-model="form.cachedInputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="留空则按输入单价" />
    </label>
    <label class="field">
      <span>缓存写入单价 / 1M</span>
      <input v-model="form.cachedInputWritePricePerMillion" type="number" min="0" step="0.00000001" placeholder="留空则按输入单价" />
    </label>
  </div>

  <label v-else-if="form.pricingMode === 'per_request'" class="field">
    <span>每次请求费用</span>
    <input v-model="form.perRequestPrice" type="number" min="0" step="0.00000001" placeholder="例如 0.01" />
  </label>

  <section v-else class="field price-tier-editor">
    <div class="price-tier-heading">
      <strong>Token 价格档位</strong>
      <button type="button" class="button ghost mini" :disabled="form.priceTiers.length >= 20" @click="addTier">添加档位</button>
    </div>
    <small>档位上限按对应类别累计 Token 数计算，含该上限；最后一档上限留空表示不限。</small>
    <div v-for="(tier, index) in form.priceTiers" :key="index" class="price-tier-card">
      <div class="price-tier-heading">
        <strong>第 {{ index + 1 }} 档</strong>
        <label class="field price-tier-limit">
          <span>{{ index === form.priceTiers.length - 1 ? '上限（不限）' : '截至 Token 数' }}</span>
          <input v-model="tier.upToTokens" type="number" min="1" step="1" :placeholder="index === form.priceTiers.length - 1 ? '不限' : '128000'" :disabled="index === form.priceTiers.length - 1" />
        </label>
        <button v-if="form.priceTiers.length > 1" type="button" class="button ghost mini" @click="removeTier(index)">移除</button>
      </div>
      <div class="price-tier-rates">
        <label class="field">
          <span>输入 / 1M</span>
          <input v-model="tier.inputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.15" />
        </label>
        <label class="field">
          <span>输出 / 1M</span>
          <input v-model="tier.outputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="0.60" />
        </label>
        <label class="field">
          <span>缓存读取 / 1M</span>
          <input v-model="tier.cachedInputPricePerMillion" type="number" min="0" step="0.00000001" placeholder="按输入价" />
        </label>
        <label class="field">
          <span>缓存写入 / 1M</span>
          <input v-model="tier.cachedInputWritePricePerMillion" type="number" min="0" step="0.00000001" placeholder="按输入价" />
        </label>
      </div>
    </div>
    <button v-if="form.priceTiers.length === 0" type="button" class="button subtle mini" @click="addTier">添加第一档</button>
  </section>

  <div class="field price-schedule-field">
    <span>生效方式</span>
    <div class="price-schedule-toggle">
      <label class="schedule-radio">
        <input type="radio" :value="false" v-model="form.isScheduled" @change="onScheduleChange(false)" />
        <span>立即生效</span>
      </label>
      <label class="schedule-radio">
        <input type="radio" :value="true" v-model="form.isScheduled" @change="onScheduleChange(true)" />
        <span>定时生效</span>
      </label>
    </div>
    <div v-if="form.isScheduled" class="schedule-date-wrap">
      <input v-model="form.effectiveAt" type="datetime-local" />
      <small>将在指定的未来时间自动切换为此价格版本；到达该时间前仍沿用当前生效价格。</small>
    </div>
    <small v-else>保存后立即作为当前有效价格生效，并保留版本历史。</small>
  </div>
</template>

<style scoped>
.price-schedule-field {
  display: grid;
  gap: 8px;
}

.price-schedule-toggle {
  display: flex;
  align-items: center;
  gap: 16px;
}

.schedule-radio {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 13px;
}

.schedule-date-wrap {
  display: grid;
  gap: 6px;
  margin-top: 4px;
}
.price-tier-editor {
  display: grid;
  gap: 10px;
}

.price-tier-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.price-tier-limit {
  width: min(180px, 52%);
  margin: 0;
}

.price-tier-card {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
}

.price-tier-rates {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 600px) {
  .price-tier-rates {
    grid-template-columns: 1fr;
  }
}
</style>

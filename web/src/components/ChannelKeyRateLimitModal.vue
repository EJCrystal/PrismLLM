<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  buildChannelKeyRateLimitPayload,
  channelKeyRateLimitSummary,
  normalizeChannelKeyRateLimit,
} from '../utils/channelKeyRateLimit'
import Modal from './Modal.vue'

const props = defineProps({
  keyName: { type: String, required: true },
  rule: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['close', 'save'])
const form = reactive(normalizeChannelKeyRateLimit(props.rule))
const error = ref('')
const summary = computed(() => channelKeyRateLimitSummary(form))

function save() {
  try {
    error.value = ''
    emit('save', buildChannelKeyRateLimitPayload(form))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  }
}
</script>

<template>
  <Modal :title="`密钥限流 · ${keyName}`" @close="$emit('close')">
    <form id="channel-key-rate-limit-form" class="form-grid" @submit.prevent="save">
      <label class="switch-row">
        <input v-model="form.enabled" type="checkbox" />
        <span class="switch"></span>
        <span>
          <strong>启用主动限流</strong>
          <small>该密钥代表一个上游分组；RPM 和并发额度按当前渠道与密钥共享，覆盖其全部模型。</small>
        </span>
      </label>

      <fieldset class="channel-key-rate-limit-fields" :disabled="!form.enabled">
        <legend>额度配置</legend>
        <label class="field">
          <span>每分钟请求数（RPM）</span>
          <input
            v-model="form.requestsPerMinute"
            type="number"
            min="1"
            max="1000000"
            step="1"
            inputmode="numeric"
            placeholder="留空表示不限制"
          />
          <small>采用平滑令牌桶，按上游请求尝试计数。</small>
        </label>
        <label class="field">
          <span>最大并发请求数</span>
          <input
            v-model="form.maxConcurrentRequests"
            type="number"
            min="1"
            max="100000"
            step="1"
            inputmode="numeric"
            placeholder="留空表示不限制"
          />
          <small>从实际发送上游请求开始占用，完成、失败或取消后释放。</small>
        </label>
      </fieldset>

      <div class="channel-key-rate-limit-preview">
        <span>当前规则</span>
        <strong>{{ summary }}</strong>
      </div>
      <p v-if="error" class="callout danger" role="alert">{{ error }}</p>
      <p class="muted small">
        未配置规则时默认不限流。启用时至少填写一项；未填写的单项保持不限流。
      </p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="submit" form="channel-key-rate-limit-form" class="button primary">应用规则</button>
    </template>
  </Modal>
</template>

<style scoped>
.channel-key-rate-limit-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
  margin: 0;
  padding: 14px;
  border: 1px solid var(--glass-line);
  border-radius: 12px;
}

.channel-key-rate-limit-fields legend {
  padding: 0 6px;
  font-weight: 700;
}

.channel-key-rate-limit-fields:disabled {
  opacity: 0.62;
}

.channel-key-rate-limit-preview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 13px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  background: var(--glass-field);
}

.channel-key-rate-limit-preview span { color: var(--muted); font-size: 12px; }
.channel-key-rate-limit-preview strong { text-align: right; }

@media (max-width: 560px) {
  .channel-key-rate-limit-fields { grid-template-columns: 1fr; }
  .channel-key-rate-limit-preview { align-items: flex-start; flex-direction: column; }
  .channel-key-rate-limit-preview strong { text-align: left; }
}
/* 降低透明度时，预览块回退到实色。 */
@media (prefers-reduced-transparency: reduce) {
  .channel-key-rate-limit-preview {
    background: var(--panel);
    border-color: var(--line);
  }
}
</style>

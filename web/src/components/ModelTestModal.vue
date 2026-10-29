<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { isKeyRoutable } from '../utils/channelForm'
import {
  buildModelTestRequest,
  formatModelTestUsage,
  modelTestProtocolLabel,
  modelTestResultTitle,
  modelTestResultTone,
  normalizeModelTestSettings,
} from '../utils/modelTest'
import Modal from './Modal.vue'

const props = defineProps({
  channel: { type: Object, required: true },
  model: { type: String, required: true },
})
const emit = defineEmits(['close'])

const protocols = computed(() => props.channel.protocols?.length
  ? props.channel.protocols
  : ['chat_completions'])
const supportingKeys = computed(() => (props.channel.keys || [])
  .filter((key) => isKeyRoutable(key) && key.models?.includes(props.model)))
const settings = ref(normalizeModelTestSettings(null))
const form = reactive({
  keyId: supportingKeys.value[0]?.id || '',
  protocol: protocols.value[0],
  prompt: settings.value.defaultPrompt,
})
const loadingSettings = ref(true)
const testing = ref(false)
const result = ref(null)
const usageText = computed(() => formatModelTestUsage(result.value?.usage))

onMounted(async () => {
  try {
    settings.value = normalizeModelTestSettings(await api.getModelTestSettings())
    form.prompt = settings.value.defaultPrompt
  } catch (cause) {
    notify(cause.message, 'warning')
    form.prompt = settings.value.defaultPrompt
  } finally {
    loadingSettings.value = false
  }
})

function resetPrompt() {
  form.prompt = settings.value.defaultPrompt
}

async function runTest() {
  const prompt = form.prompt.trim()
  if (!prompt) {
    notify('请输入测试提示词', 'error')
    return
  }
  testing.value = true
  result.value = null
  try {
    result.value = await api.testChannelModel(
      props.channel.id,
      buildModelTestRequest({
        model: props.model,
        protocol: form.protocol,
        prompt,
        keyId: form.keyId,
      }),
    )
    notify(result.value.success ? '模型连通性测试成功' : '模型连通性测试失败', result.value.success ? 'success' : 'error')
  } catch (cause) {
    result.value = { success: false, errorMessage: cause.message, latencyMs: 0, prompt }
    notify(cause.message, 'error')
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <Modal title="模型连通性测试" wide @close="$emit('close')">
    <form class="form-grid model-test-form" @submit.prevent="runTest">
      <div class="model-test-summary">
        <div>
          <span class="muted small">测试模型</span>
          <code>{{ model }}</code>
        </div>
        <span class="badge neutral">{{ channel.name }}</span>
      </div>

      <div class="model-test-grid">
        <label v-if="supportingKeys.length" class="field">
          <span>使用密钥</span>
          <select v-model="form.keyId" :disabled="testing">
            <option v-for="key in supportingKeys" :key="key.id" :value="key.id">
              {{ key.name }}（优先级 {{ key.priority }}）
            </option>
          </select>
          <small>同一模型可配置多个密钥，测试只使用选中的密钥。</small>
        </label>
        <label class="field">
          <span>协议</span>
          <select v-model="form.protocol" :disabled="testing">
            <option v-for="protocol in protocols" :key="protocol" :value="protocol">
              {{ modelTestProtocolLabel(protocol) }}
            </option>
          </select>
        </label>
      </div>

      <label class="field">
        <span>测试提示词</span>
        <textarea
          v-model="form.prompt"
          rows="5"
          maxlength="4000"
          placeholder="输入用于测试的提示词"
          :disabled="loadingSettings || testing"
        ></textarea>
        <small>
          {{ form.prompt.length }}/4000 · 使用已保存的渠道配置发送一次真实请求，最多 128 输出 token，超时 45 秒。
          <button type="button" class="link-button" @click="resetPrompt">恢复全局默认</button>
        </small>
      </label>

      <div v-if="result" class="callout model-test-result" :class="modelTestResultTone(result)">
        <div class="model-test-result-head">
          <strong>{{ modelTestResultTitle(result) }}</strong>
          <span v-if="result.keyName" class="badge neutral">{{ result.keyName }}</span>
          <span v-if="result.statusCode" class="badge" :class="modelTestResultTone(result)">HTTP {{ result.statusCode }}</span>
          <span class="muted small">{{ result.latencyMs }} ms</span>
        </div>
        <p v-if="result.errorMessage" class="model-test-error">{{ result.errorMessage }}</p>
        <pre v-if="result.responseText" class="model-test-response">{{ result.responseText }}</pre>
        <small v-if="usageText" class="muted">{{ usageText }}</small>
      </div>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">关闭</button>
      <button type="button" class="button primary" :disabled="loadingSettings || testing" @click="runTest">
        {{ testing ? '测试中…' : '开始测试' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.model-test-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.model-test-summary > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.model-test-summary code {
  overflow-wrap: anywhere;
}

.model-test-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 12px;
}

.model-test-result {
  display: grid;
  gap: 8px;
}

.model-test-result-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.model-test-error {
  margin: 0;
  overflow-wrap: anywhere;
}

.model-test-response {
  max-height: 220px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 600px) {
  .model-test-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .model-test-grid {
    grid-template-columns: 1fr;
  }
}
</style>

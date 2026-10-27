<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { MODEL_TEST_REASONING_OPTIONS, normalizeModelTestSettings } from '../utils/modelTest'
import Modal from './Modal.vue'

const emit = defineEmits(['close', 'saved'])
const form = reactive({ defaultPrompt: '', defaultReasoningEffort: '' })
const loading = ref(true)
const saving = ref(false)

onMounted(async () => {
  try {
    Object.assign(form, normalizeModelTestSettings(await api.getModelTestSettings()))
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    loading.value = false
  }
})

async function save() {
  const prompt = form.defaultPrompt.trim()
  if (!prompt) {
    notify('默认提示词不能为空', 'error')
    return
  }
  saving.value = true
  try {
    await api.updateModelTestSettings({
      defaultPrompt: prompt,
      defaultReasoningEffort: form.defaultReasoningEffort || null,
    })
    notify('模型测试设置已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal title="模型测试设置" @close="$emit('close')">
    <form class="form-grid" @submit.prevent="save">
      <label class="field">
        <span>默认测试提示词</span>
        <textarea
          v-model="form.defaultPrompt"
          rows="5"
          maxlength="4000"
          placeholder="例如：请只回复 OK"
          :disabled="loading"
        ></textarea>
        <small>每次打开模型测试会默认填入这段提示词，单次测试仍可临时修改。</small>
      </label>
      <label class="field">
        <span>默认推理强度</span>
        <select v-model="form.defaultReasoningEffort" :disabled="loading">
          <option v-for="option in MODEL_TEST_REASONING_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <small>Anthropic Messages 测试会忽略推理强度；Chat Completions 与 Responses 会按该值发送。</small>
      </label>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="loading || saving" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

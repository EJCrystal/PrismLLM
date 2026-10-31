<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { normalizeModelTestSettings } from '../utils/modelTest'
import Modal from './Modal.vue'

const emit = defineEmits(['close', 'saved'])
const form = reactive({ defaultPrompt: '' })
const loading = ref(true)
const saving = ref(false)

onMounted(async () => {
  try {
    Object.assign(form, normalizeModelTestSettings(await api.getModelTestSettings()))
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
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
    await api.updateModelTestSettings({ defaultPrompt: prompt })
    notify('模型测试设置已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause instanceof Error ? cause.message : String(cause), 'error')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal title="全局测试配置" @close="$emit('close')">
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
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="loading || saving" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

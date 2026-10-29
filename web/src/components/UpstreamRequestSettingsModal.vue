<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import Modal from './Modal.vue'
import RequestOverridesEditor from './RequestOverridesEditor.vue'
import HeaderProfileCatalog from './HeaderProfileCatalog.vue'

const emit = defineEmits(['close', 'saved'])
const settings = ref(null)
const editor = ref(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    settings.value = await api.getUpstreamRequestSettings()
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value || !editor.value) return
  let overrides
  try {
    overrides = editor.value.getValue()
  } catch (cause) {
    notify(cause.message, 'error')
    return
  }
  saving.value = true
  try {
    await api.updateUpstreamRequestSettings({ overrides })
    notify('全局上游请求配置已保存')
    emit('saved')
    emit('close')
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    saving.value = false
  }
}

function applyCatalog(response) {
  settings.value = response
}
onMounted(load)
</script>

<template>
  <Modal title="全局上游请求配置" @close="$emit('close')">
    <p class="muted small">应用于上游请求、模型列表、连通性测试和协议探测，保存后立即生效。渠道可单独覆盖。</p>
    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="error" role="alert">
      <p class="text-danger">加载失败：{{ error }}</p>
      <button type="button" class="button subtle" @click="load">重新加载</button>
    </div>
    <template v-else-if="settings">
      <HeaderProfileCatalog
        v-if="settings.headerCatalog"
        :catalog="settings.headerCatalog"
        :disabled="saving"
        @updated="applyCatalog"
      />
      <RequestOverridesEditor ref="editor" :value="settings.overrides" :profiles="settings.profiles" global :disabled="saving" />
    </template>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="loading || !!error || saving" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

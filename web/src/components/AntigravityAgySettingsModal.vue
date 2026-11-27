<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { AntigravityAgySettings } from '../types/api'
import { agyEnvironmentStatus } from '../utils/systemConfig'
import Modal from './Modal.vue'

const emit = defineEmits<{ (event: 'close'): void; (event: 'saved'): void }>()
const current = ref<AntigravityAgySettings | null>(null)
const loading = ref(true)
const saving = ref(false)
const enabled = ref(false)
const proxy = ref('http://127.0.0.1:7897')
const error = ref('')
const preparing = computed(() => current.value && ['CHECKING', 'INSTALLING', 'PREPARING', 'STOPPING'].includes(current.value.status))
let timer: ReturnType<typeof setTimeout> | undefined
let closed = false
let requestVersion = 0

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function poll() {
  clearTimeout(timer)
  if (!closed && !saving.value && preparing.value) timer = setTimeout(() => { void load(false) }, 2000)
}

async function load(initial = true) {
  const version = ++requestVersion
  if (initial) loading.value = true
  error.value = ''
  try {
    const settings = await api.getAntigravityAgySettings()
    if (closed || version !== requestVersion) return
    const changed = current.value?.status !== settings.status
    current.value = settings
    if (initial) {
      enabled.value = settings.enabled
      proxy.value = settings.proxy
    }
    if (changed) emit('saved')
  } catch (cause) {
    if (!closed && version === requestVersion) error.value = message(cause)
  } finally {
    if (version === requestVersion) {
      loading.value = false
      poll()
    }
  }
}

async function save() {
  if (loading.value || saving.value || !current.value) return
  requestVersion++
  clearTimeout(timer)
  saving.value = true
  error.value = ''
  try {
    current.value = await api.updateAntigravityAgySettings({ enabled: enabled.value, proxy: proxy.value.trim() })
    notify(enabled.value ? '设置已保存，服务端将检查并按需安装运行环境' : '关闭设置已保存，不会删除账号凭据')
    emit('saved')
  } catch (cause) {
    error.value = message(cause)
  } finally {
    saving.value = false
    poll()
  }
}

onMounted(() => { void load() })
onUnmounted(() => { closed = true; clearTimeout(timer) })
</script>

<template>
  <Modal title="Antigravity 运行环境" eyebrow="系统配置" @close="$emit('close')">
    <p class="muted small">开启后，在运行 PrismLLM 的服务环境检查依赖。仅缺少时下载安装 Python 3.11+、Bubblewrap 和固定版本官方 agy，不在当前浏览器所在电脑安装，不启动 Google 登录或生成请求。</p>
    <p v-if="loading" role="status">正在读取设置…</p>
    <p v-if="error" class="callout danger" role="alert">{{ error }} <button type="button" class="button" @click="load()">重新读取</button></p>
    <form v-if="current && !loading" class="form-grid" @submit.prevent="save">
      <label class="checkbox-row">
        <input v-model="enabled" type="checkbox" :disabled="saving" />
        <span>启用官方 agy</span>
      </label>
      <label class="field">
        <span>服务环境的 HTTP 代理</span>
        <input v-model="proxy" type="text" spellcheck="false" autocomplete="off" :disabled="saving || !!preparing" />
        <small>格式为 http://127.0.0.1:端口，不接受账号密码。容器中的 127.0.0.1 指容器自身，不是宿主机。</small>
      </label>
      <div class="callout" :class="current.status === 'FAILED' ? 'danger' : current.status === 'READY' ? 'success' : 'info'" role="status" aria-live="polite">
        <strong>{{ agyEnvironmentStatus(current.status) }}</strong>
        <p v-if="current.error">{{ current.error }}</p>
        <template v-if="current.missingDependencies.length">
          <p>{{ current.status === 'READY' ? '本次已补齐的依赖：' : '本次检测到缺少的依赖：' }}{{ current.missingDependencies.join('、') }}</p>
        </template>
        <p v-if="current.status === 'FAILED'">修复原因后，保留开关开启并点击“重新检查并准备”。</p>
      </div>
      <p class="muted small">自动安装目前支持 apt-get 软件源，需要服务用户具有安装权限且文件系统可写；不会执行 sudo、添加第三方软件源或降低沙盒隔离。安装期间请勿重启服务或更新容器。</p>
      <p class="muted small">关闭后不再接受新的 agy 登录或请求，不删除已安装依赖和账号凭据；已开始的系统包安装会在当前步骤完成后停止准备。</p>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">关闭</button>
      <button type="button" class="button primary" :disabled="loading || saving || !current" @click="save">
        {{ saving ? '保存中…' : current?.status === 'FAILED' && enabled ? '重新检查并准备' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

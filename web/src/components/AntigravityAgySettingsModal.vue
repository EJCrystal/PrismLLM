<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { AntigravityAgySettings } from '../types/api'
import { agyEnvironmentStatus, isAgyEnvironmentPreparing } from '../utils/systemConfig'
import Modal from './Modal.vue'
import SecretInput from './SecretInput.vue'

const props = defineProps<{ onSaved?: (settings: AntigravityAgySettings) => void }>()
defineEmits<{ (event: 'close'): void }>()
const current = ref<AntigravityAgySettings | null>(null)
const loading = ref(true)
const saving = ref(false)
const enabled = ref(false)
const proxy = ref('')
const username = ref('')
const password = ref('')
const authenticationEnabled = ref(false)
const hasSavedAuthentication = ref(false)
const error = ref('')
const preparing = computed(() => isAgyEnvironmentPreparing(current.value?.status))
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
      username.value = settings.username ?? ''
      authenticationEnabled.value = settings.hasAuthentication
      hasSavedAuthentication.value = settings.hasAuthentication
    }
    if (changed) props.onSaved?.(settings)
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
    const payload: { enabled: boolean; proxy: string; username?: string; password?: string; clearAuthentication?: boolean } = {
      enabled: enabled.value,
      proxy: proxy.value.trim(),
    }
    if (authenticationEnabled.value) {
      payload.username = username.value
      if (password.value) payload.password = password.value
    } else if (hasSavedAuthentication.value) {
      payload.clearAuthentication = true
    }
    const settings = await api.updateAntigravityAgySettings(payload)
    props.onSaved?.(settings)
    if (closed) return
    current.value = settings
    password.value = ''
    username.value = settings.username ?? ''
    authenticationEnabled.value = settings.hasAuthentication
    hasSavedAuthentication.value = settings.hasAuthentication
    notify(enabled.value ? '设置已保存，服务端将检查并按需安装运行环境' : '关闭设置已保存，不会删除账号凭据')
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
        <span>服务环境的 HTTP 代理（可选）</span>
        <input v-model="proxy" type="text" spellcheck="false" autocomplete="off" :disabled="saving || !!preparing" />
        <small>留空时尝试直连。地址格式为 http://主机:端口；账号密码请单独填写，不要放进地址。</small>
      </label>
      <label class="checkbox-row">
        <input v-model="authenticationEnabled" type="checkbox" :disabled="saving || !!preparing || !proxy.trim()" />
        <span>代理需要账号密码认证</span>
      </label>
      <template v-if="authenticationEnabled">
        <label class="field">
          <span>代理账号</span>
          <input v-model="username" type="text" autocomplete="off" :disabled="saving || !!preparing" />
        </label>
        <label class="field">
          <span>代理密码</span>
          <SecretInput v-model="password" :placeholder="hasSavedAuthentication ? '留空保持已保存密码' : '输入代理密码'" autocomplete="new-password" :disabled="saving || !!preparing" />
          <small v-if="hasSavedAuthentication">密码已加密保存且不会回显；更换代理地址时需重新填写密码。</small>
        </label>
      </template>
      <div class="callout" :class="current.status === 'FAILED' ? 'danger' : current.status === 'READY' ? 'success' : 'info'" role="status" aria-live="polite">
        <strong>{{ agyEnvironmentStatus(current.status) }}</strong>
        <p v-if="current.error">{{ current.error }}</p>
        <template v-if="current.missingDependencies.length">
          <p>{{ current.status === 'READY' ? '本次已补齐的依赖：' : '本次检测到缺少的依赖：' }}{{ current.missingDependencies.join('、') }}</p>
        </template>
        <p v-if="current.status === 'FAILED'">修复原因后，保留开关开启并点击“重新检查并准备”。</p>
      </div>
      <p class="muted small">自动安装目前支持 apt-get 软件源，需要服务用户具有安装权限且文件系统可写；不会执行 sudo、添加第三方软件源或降低沙盒隔离。直连和代理方式都只允许沙盒访问固定的官方服务域名。安装期间请勿重启服务或更新容器。</p>
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

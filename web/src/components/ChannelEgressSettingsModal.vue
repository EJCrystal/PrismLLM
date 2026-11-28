<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { ApiError } from '../types/api'
import type { ChannelEgressSettings } from '../types/api'
import { parseChannelProxyHostEntries } from '../utils/channelEgress'
import Modal from './Modal.vue'

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
}>()

const current = ref<ChannelEgressSettings | null>(null)
const loading = ref(true)
const saving = ref(false)
const hostsText = ref('')

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function load() {
  loading.value = true
  try {
    const settings = await api.getChannelEgressSettings()
    current.value = settings
    hostsText.value = settings.hosts.join('\n')
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (loading.value || saving.value) return
  let hosts: string[]
  try {
    hosts = parseChannelProxyHostEntries(hostsText.value)
  } catch (cause) {
    notify(errorMessage(cause), 'error')
    return
  }
  saving.value = true
  try {
    await api.updateChannelEgressSettings({
      hosts,
      expectedRevision: current.value?.revision ?? null,
    })
    notify('渠道出口代理白名单已保存，立即生效')
    emit('saved')
    emit('close')
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'channel_egress_settings_conflict') {
      // 其他管理员已修改名单：重新加载最新版本，避免用旧快照覆盖。
      notify('出口代理白名单已被其他管理员修改，已重新加载最新名单，请确认后再次保存', 'warning')
      await load()
    } else {
      notify(errorMessage(cause), 'error')
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <Modal title="渠道出口代理白名单" eyebrow="全局业务设置" @close="$emit('close')">
    <p class="muted small">
      渠道出口代理连接非回环主机前需要先放行。保存后立即生效，不需要重启网关；本机回环地址始终允许。
    </p>
    <p v-if="loading" class="muted">加载中…</p>
    <form v-else class="form-grid" @submit.prevent="save">
      <label class="field">
        <span>放行主机</span>
        <textarea
          v-model="hostsText"
          rows="7"
          spellcheck="false"
          autocapitalize="off"
          placeholder="例如：&#10;proxy.example.com&#10;*.example.com"
          :disabled="saving"
        ></textarea>
        <small>
          每行一个，也可用逗号或空格分隔。支持精确主机名、IPv4 和
          <code>*.example.com</code> 通配（不匹配后缀本身）；最多 64 个。
          移除仍有渠道在用的主机后，这些渠道的代理请求会被拒绝。
        </small>
      </label>
    </form>
    <template #footer>
      <button type="button" class="button ghost" @click="$emit('close')">取消</button>
      <button type="button" class="button primary" :disabled="loading || saving || !current" @click="save">
        {{ saving ? '保存中…' : '保存设置' }}
      </button>
    </template>
  </Modal>
</template>

<script setup>
import { computed, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { formatTime } from '../utils/format'

const props = defineProps({
  catalog: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['updated'])
const busy = ref('')

const intervalLabel = computed(() => {
  const minutes = Math.round((props.catalog.refreshIntervalMs || 0) / 60000)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.round(minutes / 60)
  return `${hours} 小时`
})

function history(profile) {
  return props.catalog.history?.[profile.id] || [profile]
}

async function refresh() {
  if (busy.value || props.disabled) return
  busy.value = 'refresh'
  try {
    const response = await api.refreshUpstreamHeaderCatalog()
    emit('updated', response)
    if (response.headerCatalog?.lastError) {
      notify('刷新完成，但部分版本来源失败，已继续使用缓存', 'warning')
    } else {
      notify('请求头预设目录已刷新')
    }
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    busy.value = ''
  }
}

async function selectVersion(profile, event) {
  if (busy.value || props.disabled) return
  const version = event.target.value
  busy.value = profile.id
  try {
    const response = version
      ? await api.pinUpstreamHeaderProfile(profile.id, version)
      : await api.unpinUpstreamHeaderProfile(profile.id)
    emit('updated', response)
    notify(version ? `已固定 ${profile.name} ${version}` : `${profile.name} 已恢复最新版本`)
  } catch (cause) {
    notify(cause.message, 'error')
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <section class="header-catalog-panel">
    <div class="header-catalog-heading">
      <div>
        <strong>动态预设目录</strong>
        <p class="muted small">
          {{ catalog.autoRefreshEnabled ? `每 ${intervalLabel} 自动检查` : '自动刷新已关闭' }} Claude Code、Codex 与 Google Gen AI SDK 的公开版本；失败时继续使用最近成功快照。
        </p>
      </div>
      <button type="button" class="button subtle mini" :disabled="disabled || !!busy" @click="refresh">
        {{ busy === 'refresh' || catalog.refreshing ? '刷新中…' : '立即刷新' }}
      </button>
    </div>
    <div class="header-catalog-status muted small">
      <span>快照：{{ catalog.revision || '内置默认' }}</span>
      <span>最后检查：{{ catalog.checkedAt ? formatTime(catalog.checkedAt) : '尚未检查' }}</span>
      <span>最后成功：{{ catalog.successAt ? formatTime(catalog.successAt) : '无' }}</span>
    </div>
    <p v-if="catalog.lastError" class="header-catalog-error small">
      部分来源刷新失败，当前继续使用已缓存版本：{{ catalog.lastError }}
    </p>
    <div class="header-catalog-list">
      <div v-for="profile in catalog.profiles" :key="profile.id" class="header-catalog-row">
        <div>
          <div class="primary-cell">{{ profile.name }}</div>
          <div class="muted small">{{ profile.source }}</div>
        </div>
        <div class="header-catalog-version">
          <span class="badge" :class="catalog.pins?.[profile.id] ? 'warning' : 'info'">
            {{ profile.version }}
          </span>
          <span v-if="catalog.pins?.[profile.id]" class="muted small">已固定</span>
          <span v-else class="muted small">自动最新</span>
        </div>
        <select
          :value="catalog.pins?.[profile.id] || ''"
          :disabled="disabled || !!busy"
          :aria-label="profile.name + ' 预设版本'"
          @change="selectVersion(profile, $event)"
        >
          <option value="">跟随最新版本</option>
          <option v-for="item in history(profile)" :key="item.version" :value="item.version">
            {{ item.version }}{{ catalog.pins?.[profile.id] === item.version ? '（已固定）' : '' }}
          </option>
        </select>
      </div>
    </div>
    <p class="muted small">固定版本会保留完整请求头快照；恢复“跟随最新版本”后，下一次成功刷新即采用最新目录。</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from './api'
import type { MeResponse } from './types/api'
import Icon from './components/Icon.vue'
import ToastStack from './components/ToastStack.vue'
import LoginView from './components/LoginView.vue'
import ChannelsView from './components/ChannelsView.vue'
import ClientKeysView from './components/ClientKeysView.vue'
import LogsView from './components/LogsView.vue'
import UsageReportsView from './components/UsageReportsView.vue'
import AdminAuditLogsView from './components/AdminAuditLogsView.vue'
import AccountSettingsModal from './components/AccountSettingsModal.vue'
import ThemeToggle from './components/ThemeToggle.vue'
import InfoPopover from './components/InfoPopover.vue'

const navItems = [
  { id: 'channels', label: '渠道', icon: 'channels' },
  { id: 'client-keys', label: '调用方密钥', icon: 'keys' },
  { id: 'logs', label: '请求日志', icon: 'logs' },
  { id: 'usage-reports', label: '用量报表', icon: 'chart' },
  { id: 'audit-logs', label: '操作审计', icon: 'shield' },
] as const

const meta = {
  channels: {
    title: '渠道',
    desc: '管理上游接入点，支持多协议组合。凭据加密保存，创建后不再回显。',
  },
  'client-keys': {
    title: '调用方密钥',
    desc: '调用方凭据只在创建或轮换时显示一次，请立即保存。服务端仅保存 SHA-256 哈希与展示前缀。',
  },

  logs: {
    title: '请求日志',
    desc: '查看请求状态、路由、耗时与 token 用量；点击“详情”查看完整上下文。失败请求的错误正文会截断脱敏保存。',
  },
  'usage-reports': {
    title: '用量报表',
    desc: '查看请求、token、费用、延迟与故障转移趋势，并按密钥、渠道和模型分析。',
  },
  'audit-logs': {
    title: '操作审计',
    desc: '记录写操作与密钥自动禁用，仅追加、不可修改或删除。名称使用事件快照，不保存请求正文。',
  },
}

const booting = ref(true)
const user = ref<MeResponse | null>(null)
const view = ref<keyof typeof meta>('channels')
const navOpen = ref(false)
const accountOpen = ref(false)
const currentMeta = computed(() => meta[view.value] || meta.channels)
const isDataView = computed(() => view.value === 'logs' || view.value === 'audit-logs')

async function restoreSession() {
  try {
    user.value = await api.me()
  } catch {
    user.value = null
  } finally {
    booting.value = false
  }
}

function onAuthenticated(authenticatedUser: MeResponse) {
  user.value = authenticatedUser
  view.value = 'channels'
  accountOpen.value = false
}

function selectView(id: keyof typeof meta) {
  view.value = id
  navOpen.value = false
}

async function logout() {
  try {
    await api.logout()
  } catch {
    // 会话已经失效时也直接回到登录页。
  }
  user.value = null
}

function handleUnauthorized() {
  user.value = null
  accountOpen.value = false
}

function onAvatarUpdated(updatedUser: MeResponse) {
  user.value = updatedUser
}

function onPasswordChanged() {
  accountOpen.value = false
  user.value = null
}

onMounted(() => {
  restoreSession()
  window.addEventListener('gateway:unauthorized', handleUnauthorized)
})

onBeforeUnmount(() => {
  window.removeEventListener('gateway:unauthorized', handleUnauthorized)
})
</script>

<template>
  <div v-if="booting" class="boot-screen">
    <div class="boot-mark">
      <span></span><span></span><span></span>
    </div>
    <p>正在连接网关</p>
  </div>

  <LoginView v-else-if="!user" @authenticated="onAuthenticated" />

  <div v-else class="app-shell">
    <aside class="sidebar" :class="{ 'is-open': navOpen }">
      <div class="brand">
        <div class="brand-mark">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z" />
            <path d="M9 12l2 2 4-4" />
          </svg>
        </div>
        <div>
          <strong>LLM Gateway</strong>
          <span>Control plane</span>
        </div>
      </div>

      <nav class="nav" aria-label="管理导航">
        <button
          v-for="item in navItems"
          :key="item.id"
          type="button"
          class="nav-item"
          :class="{ active: view === item.id }"
          @click="selectView(item.id)"
        >
          <Icon :name="item.icon" :size="19" />
          <span>{{ item.label }}</span>
          <span v-if="view === item.id" class="nav-indicator"></span>
        </button>
      </nav>

      <div class="sidebar-foot">
        <div class="runtime-note">
          <span class="pulse"></span>
          <span>数据面运行中</span>
        </div>
        <button type="button" class="logout-button" @click="logout">
          <Icon name="logout" :size="17" />
          退出登录
        </button>
      </div>
    </aside>

    <div v-if="navOpen" class="sidebar-scrim" @click="navOpen = false"></div>

    <div class="main" :class="{ 'main-data': isDataView, 'logs-data': view === 'logs' }">
      <header class="topbar">
        <div class="topbar-left">
          <button type="button" class="icon-button mobile-menu" aria-label="打开导航" @click="navOpen = true">
            <Icon name="menu" :size="20" />
          </button>
          <div class="page-heading">
            <div class="page-title-row">
              <h1>{{ currentMeta.title }}</h1>
              <div class="page-help">
                <InfoPopover :key="view" :label="currentMeta.title + '说明'">
                  <template #trigger><Icon name="info" :size="16" /></template>
                  <p class="page-help-copy">{{ currentMeta.desc }}</p>
                </InfoPopover>
              </div>
            </div>
            <p class="page-desc">{{ currentMeta.desc }}</p>
          </div>
        </div>
        <div class="topbar-right">
          <ThemeToggle />
          <button
            type="button"
            class="user-chip"
            aria-label="打开账户设置"
            aria-haspopup="dialog"
            :aria-expanded="accountOpen"
            @click="accountOpen = true"
          >
            <span class="avatar">
              <img v-if="user.avatarDataUrl" :src="user.avatarDataUrl" alt="" />
              <template v-else>{{ user.username.slice(0, 1).toUpperCase() }}</template>
            </span>
            <span class="user-chip-copy">
              <strong>{{ user.username }}</strong>
              <span>管理员</span>
            </span>
            <Icon name="chevron" :size="14" />
          </button>
        </div>
      </header>

      <main class="content" :class="{ 'content-data': isDataView, 'logs-content': view === 'logs' }">
        <ChannelsView v-if="view === 'channels'" />
        <ClientKeysView v-else-if="view === 'client-keys'" />
        <LogsView v-else-if="view === 'logs'" />
        <UsageReportsView v-else-if="view === 'usage-reports'" />
        <AdminAuditLogsView v-else-if="view === 'audit-logs'" />
      </main>
    </div>
  </div>

  <AccountSettingsModal
    v-if="accountOpen && user"
    :user="user"
    @close="accountOpen = false"
    @avatar-updated="onAvatarUpdated"
    @password-changed="onPasswordChanged"
  />

  <ToastStack />
</template>

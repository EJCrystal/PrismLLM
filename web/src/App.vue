<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from './api'
import Icon from './components/Icon.vue'
import ToastStack from './components/ToastStack.vue'
import LoginView from './components/LoginView.vue'
import ChannelsView from './components/ChannelsView.vue'
import ExposedModelsView from './components/ExposedModelsView.vue'
import ClientKeysView from './components/ClientKeysView.vue'
import LogsView from './components/LogsView.vue'
import UsageReportsView from './components/UsageReportsView.vue'
import AdminAuditLogsView from './components/AdminAuditLogsView.vue'
import AccountSettingsModal from './components/AccountSettingsModal.vue'
import ThemeToggle from './components/ThemeToggle.vue'

const navItems = [
  { id: 'channels', label: '渠道', icon: 'channels' },
  { id: 'exposed-models', label: '模型列表', icon: 'list' },
  { id: 'client-keys', label: '调用方密钥', icon: 'keys' },
  { id: 'logs', label: '请求日志', icon: 'logs' },
  { id: 'usage-reports', label: '用量报表', icon: 'chart' },
  { id: 'audit-logs', label: '操作审计', icon: 'shield' },
]

const meta = {
  channels: {
    title: '渠道',
    desc: '管理上游模型接入点。凭据加密保存，创建后不再回显。',
  },
  'exposed-models': {
    title: '模型列表',
    desc: '查看调用方通过 /v1/models 获取到的对外模型结果。',
  },
  'client-keys': {
    title: '调用方密钥',
    desc: '签发给调用方的访问凭据。服务端只保存哈希和展示前缀。',
  },

  logs: {
    title: '请求日志',
    desc: '查看请求元数据、状态、耗时与 token 用量。失败请求的上游错误正文会截断脱敏后保存。',
  },
  'usage-reports': {
    title: '用量报表',
    desc: '查看请求、token、费用、延迟与故障转移趋势，并按密钥、渠道和模型分析。',
  },
  'audit-logs': {
    title: '操作审计',
    desc: '查看管理面写操作的操作者、结果、请求 ID 与来源。',
  },
}

const booting = ref(true)
const user = ref(null)
const view = ref('channels')
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

function onAuthenticated(authenticatedUser) {
  user.value = authenticatedUser
  view.value = 'channels'
  accountOpen.value = false
}

function selectView(id) {
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

function onAvatarUpdated(updatedUser) {
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

    <div class="main" :class="{ 'main-data': isDataView }">
      <header class="topbar">
        <div class="topbar-left">
          <button type="button" class="icon-button mobile-menu" aria-label="打开导航" @click="navOpen = true">
            <Icon name="menu" :size="20" />
          </button>
          <div>
            <p class="eyebrow">管理面</p>
            <h1>{{ currentMeta.title }}</h1>
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

      <main class="content" :class="{ 'content-data': isDataView }">
        <ChannelsView v-if="view === 'channels'" />
        <ExposedModelsView v-else-if="view === 'exposed-models'" />
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

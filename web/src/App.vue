<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from './api'
import Icon from './components/Icon.vue'
import ToastStack from './components/ToastStack.vue'
import LoginView from './components/LoginView.vue'
import ChannelsView from './components/ChannelsView.vue'
import AliasesView from './components/AliasesView.vue'
import ClientKeysView from './components/ClientKeysView.vue'
import LogsView from './components/LogsView.vue'

const navItems = [
  { id: 'channels', label: '渠道', icon: 'channels' },
  { id: 'aliases', label: '模型别名', icon: 'aliases' },
  { id: 'client-keys', label: '调用方密钥', icon: 'keys' },
  { id: 'logs', label: '请求日志', icon: 'logs' },
]

const meta = {
  channels: {
    title: '渠道',
    desc: '管理上游模型接入点。凭据加密保存，创建后不再回显。',
  },
  aliases: {
    title: '模型别名',
    desc: '把调用方使用的模型名映射到一组有序候选，首 token 前失败才切换。',
  },
  'client-keys': {
    title: '调用方密钥',
    desc: '签发给调用方的访问凭据。服务端只保存哈希和展示前缀。',
  },
  logs: {
    title: '请求日志',
    desc: '查看请求元数据、状态、耗时与 token 用量。默认不记录正文。',
  },
}

const booting = ref(true)
const user = ref(null)
const view = ref('channels')
const navOpen = ref(false)
const currentMeta = computed(() => meta[view.value] || meta.channels)

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

    <div class="main">
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
          <div class="user-chip">
            <span class="avatar">{{ user.username.slice(0, 1).toUpperCase() }}</span>
            <div>
              <strong>{{ user.username }}</strong>
              <span>管理员</span>
            </div>
          </div>
        </div>
      </header>

      <main class="content">
        <ChannelsView v-if="view === 'channels'" />
        <AliasesView v-else-if="view === 'aliases'" />
        <ClientKeysView v-else-if="view === 'client-keys'" />
        <LogsView v-else-if="view === 'logs'" />
      </main>
    </div>
  </div>

  <ToastStack />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import OAuthAccountsView from '../../../src/components/OAuthAccountsView.vue'
import ToastStack from '../../../src/components/ToastStack.vue'
import Icon from '../../../src/components/Icon.vue'

const dark = ref(document.documentElement.dataset.theme === 'dark')
const navOpen = ref(false)
function toggleTheme() {
  dark.value = !dark.value
  document.documentElement.dataset.theme = dark.value ? 'dark' : 'light'
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ 'is-open': navOpen }">
      <div class="brand">
        <div class="brand-mark"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-9V6l7-3z" /><path d="M9 12l2 2 4-4" /></svg></div>
        <div><strong>PrismLLM</strong><span>Control plane</span></div>
      </div>
      <nav class="nav" aria-label="管理导航">
        <button class="nav-item" type="button" disabled><Icon name="channels" :size="19" /><span>渠道</span></button>
        <button class="nav-item active" type="button" @click="navOpen = false"><Icon name="users" :size="19" /><span>订阅账号</span><span class="nav-indicator"></span></button>
        <button class="nav-item" type="button" disabled><Icon name="keys" :size="19" /><span>调用方密钥</span></button>
        <button class="nav-item" type="button" disabled><Icon name="logs" :size="19" /><span>请求日志</span></button>
        <button class="nav-item" type="button" disabled><Icon name="chart" :size="19" /><span>用量报表</span></button>
        <button class="nav-item" type="button" disabled><Icon name="shield" :size="19" /><span>操作审计</span></button>
        <button class="nav-item" type="button" disabled><Icon name="settings" :size="19" /><span>系统配置</span></button>
      </nav>
      <div class="sidebar-foot"><div class="runtime-note"><span class="pulse"></span><span>仅本地合成数据 · 无后端</span></div></div>
    </aside>
    <div v-if="navOpen" class="sidebar-scrim" @click="navOpen = false"></div>
    <div class="main main-fill">
      <header class="topbar">
        <div class="topbar-left">
          <button type="button" class="icon-button mobile-menu" aria-label="打开导航" @click="navOpen = true"><Icon name="menu" :size="20" /></button>
          <div class="page-heading">
            <div class="page-title-row"><h1>订阅账号</h1></div>
            <p class="page-desc">统一管理 ChatGPT / Antigravity 订阅账号的授权、模型与调度；凭据自动续期，可关联多个渠道，并统一处理上游额度、滑动窗口与 429 冷却。</p>
          </div>
        </div>
        <div class="topbar-right">
          <button type="button" class="icon-button" :aria-label="dark ? '切换为浅色主题' : '切换为深色主题'" @click="toggleTheme"><Icon :name="dark ? 'sun' : 'moon'" :size="19" /></button>
          <div class="user-chip"><span class="avatar">F</span><span class="user-chip-copy"><strong>fixture</strong><span>合成数据</span></span></div>
        </div>
      </header>
      <main class="content content-fill"><OAuthAccountsView /></main>
    </div>
  </div>
  <ToastStack />
</template>

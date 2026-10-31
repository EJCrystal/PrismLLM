<script setup>
import { ref } from 'vue'
import { api } from '../api'
import SecretInput from './SecretInput.vue'
import ThemeToggle from './ThemeToggle.vue'

const emit = defineEmits(['authenticated'])
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const user = await api.login(username.value.trim(), password.value)
    emit('authenticated', user)
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <ThemeToggle class="login-theme-toggle" />
    <div class="login-art">
      <div class="login-orbit orbit-one"></div>
      <div class="login-orbit orbit-two"></div>
      <div class="login-copy">
        <p class="eyebrow light">LLM Gateway</p>
        <h1>把模型接入，<br />收进一个入口。</h1>
        <p>OpenAI 兼容、流式透传、统一模型路由。只暴露一把调用方密钥，上游凭据留在网关内。</p>
        <div class="login-points">
          <span><i></i>SSE 不缓冲</span>
          <span><i></i>首 token 前切换</span>
          <span><i></i>用量可追踪</span>
        </div>
      </div>
    </div>

    <div class="login-panel">
      <div class="login-card">
        <div class="login-brand">
          <div class="brand-mark dark">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z" />
              <path d="M9 12l2 2 4-4" />
            </svg>
          </div>
          <span>管理台</span>
        </div>
        <h2>登录控制面</h2>
        <p class="login-hint">使用首次启动时配置的管理员账号。</p>

        <form @submit.prevent="submit">
          <label class="field">
            <span>用户名</span>
            <input v-model="username" autocomplete="username" placeholder="admin" />
          </label>
          <label class="field">
            <span>密码</span>
            <SecretInput
              v-model="password"
              autocomplete="current-password"
              placeholder="输入管理员密码"
            />
          </label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <button class="button primary full" type="submit" :disabled="loading">
            {{ loading ? '正在登录…' : '进入管理台' }}
          </button>
        </form>
        <p class="login-foot">会话使用 HttpOnly Cookie，写操作需要 CSRF Token。</p>
      </div>
    </div>
  </div>
</template>

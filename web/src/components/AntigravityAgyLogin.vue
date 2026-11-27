<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '../api'
import type { AntigravityAgyAccount, AntigravityAgyLogin } from '../types/api'
import CopyButton from './CopyButton.vue'

const props = defineProps<{ name: string; sourceAccountId?: string | null; accountId?: string | null }>()
const emit = defineEmits<{ (event: 'authenticated', account: AntigravityAgyAccount): void }>()
const session = ref<AntigravityAgyLogin | null>(null)
const code = ref('')
const busy = ref(false)
const error = ref('')
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
const active = computed(() => session.value != null && ['PREPARING', 'AWAITING_CODE', 'VERIFYING'].includes(session.value.state))
const status = computed(() => ({
  PREPARING: '正在准备隔离环境并启动官方 agy…',
  AWAITING_CODE: '请打开官方登录链接，完成 Google 授权后提交页面显示的验证码。',
  VERIFYING: '官方 CLI 正在完成登录，并只读核对当前会话；不会发起模型生成。',
  AUTHENTICATED: '官方 CLI 登录成功，凭据由 agy 保存和续期。',
  FAILED: '官方 CLI 登录或会话核对失败；没有回退到旧 OAuth，请重新发起。',
  CANCELLED: '本次登录已取消。',
  EXPIRED: '登录已过期，请重新发起。',
}[session.value?.state ?? 'PREPARING']))

function accept(next: AntigravityAgyLogin) {
  session.value = next
  if (next.state === 'AUTHENTICATED' && next.account) emit('authenticated', next.account)
  else if (active.value) timer = setTimeout(poll, 1500)
}

async function poll() {
  const id = session.value?.loginId
  if (!id || disposed) return
  try {
    const next = await api.getAntigravityAgyLogin(id)
    if (!disposed && session.value?.loginId === id) accept(next)
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '无法读取 CLI 登录状态'
  }
}

async function start() {
  if (busy.value || active.value) return
  busy.value = true
  error.value = ''
  try {
    const next = await api.startAntigravityAgyLogin({ name: props.name.trim() || undefined, sourceAccountId: props.sourceAccountId, accountId: props.accountId })
    if (disposed) {
      void api.cancelAntigravityAgyLogin(next.loginId).catch(() => {})
      return
    }
    accept(next)
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '无法启动官方 CLI 登录'
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (busy.value || session.value?.state !== 'AWAITING_CODE') return
  busy.value = true
  error.value = ''
  clearTimeout(timer)
  const value = code.value.trim()
  code.value = ''
  try {
    const next = await api.submitAntigravityAgyCode(session.value.loginId, value)
    if (!disposed) accept(next)
  } catch (cause) {
    if (!disposed) {
      error.value = cause instanceof Error ? cause.message : '验证码提交失败；请先刷新登录状态，不要重复提交'
      await poll()
    }
  } finally {
    busy.value = false
  }
}

async function cancel() {
  const id = session.value?.loginId
  if (!id || busy.value) return
  busy.value = true
  clearTimeout(timer)
  try {
    await api.cancelAntigravityAgyLogin(id)
    if (!disposed) session.value = { ...session.value!, state: 'CANCELLED', authorizeUrl: null }
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '取消失败，请刷新状态'
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  code.value = ''
  if (active.value && session.value) void api.cancelAntigravityAgyLogin(session.value.loginId).catch(() => {})
})
</script>

<template>
  <section class="form-grid" aria-label="Antigravity 官方 CLI 登录">
    <div class="callout info">
      <strong>{{ sourceAccountId ? '用官方 agy 重新登录旧账号' : '通过官方 agy 登录' }}</strong>
      <p>授权链接由官方 CLI 生成，验证码只转交给 CLI；网关不换取、导入或保存 Google token。</p>
      <p v-if="sourceAccountId">请选择原来持有订阅的 Google 账号。登录成功后会清除旧 OAuth token，并保留原账号 ID、渠道关联和模型路由顺序；系统无法自动核对邮箱是否相同。</p>
      <p>登录完成后会登记为订阅路由凭据。新账号还要在模型凭据路由中手动加入；只使用 Pro 订阅额度，不启用额外 credits、API Key 或付费回退。使用官方 CLI 也不保证账号不会受限。</p>
    </div>
    <p v-if="error" class="callout danger" role="alert">{{ error }}</p>
    <p v-if="session" role="status">{{ status }}</p>
    <div v-if="session?.state === 'AWAITING_CODE' && session.authorizeUrl" class="oauth-login-link callout info">
      <a :href="session.authorizeUrl" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">打开 Google 官方授权页</a>
      <CopyButton :text="session.authorizeUrl" title="复制官方 CLI 登录链接" />
    </div>
    <label v-if="session?.state === 'AWAITING_CODE'" class="field">
      <span>官方页面验证码</span>
      <input v-model="code" type="password" autocomplete="off" spellcheck="false" maxlength="4096" placeholder="仅粘贴官方页面显示的验证码，不是 token 或回调 URL" @keydown.enter.prevent="submit">
      <small>验证码只在本次提交时转交 agy，不写入日志、账号档案或浏览器持久存储。</small>
    </label>
    <div class="form-actions">
      <button v-if="!active" type="button" class="button primary" :disabled="busy" @click="start">{{ busy ? '启动中…' : '启动官方 agy 登录' }}</button>
      <button v-if="session?.state === 'AWAITING_CODE'" type="button" class="button primary" :disabled="busy || !code.trim()" @click="submit">提交给官方 CLI</button>
      <button v-if="active" type="button" class="button" :disabled="busy" @click="cancel">取消 CLI 登录</button>
      <button v-if="active && error" type="button" class="button" :disabled="busy" @click="poll">刷新登录状态</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import AntigravityAgySettingsModal from './AntigravityAgySettingsModal.vue'
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { AntigravityAgyAccount } from '../types/api'
import { formatTime } from '../utils/format'

defineEmits<{ (event: 'create'): void; (event: 'reauthorize', account: AntigravityAgyAccount): void }>()
const accounts = ref<AntigravityAgyAccount[]>([])
const configured = ref(false)
const showSettings = ref(false)
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const deletingId = ref<string | null>(null)
async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.listAntigravityAgyAccounts()
    configured.value = result.configured
    accounts.value = result.accounts
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取 CLI 授权档案'
  } finally {
    loading.value = false
  }
}
async function cleanOrphan(account: AntigravityAgyAccount) {
  if (!account.cleanupEligible || deletingId.value) return
  const confirmed = window.confirm(
    '将永久删除“' + account.name + '”的孤儿 agy 档案及其本地凭据目录；此操作不可撤销，之后需要重新登录。继续？',
  )
  if (!confirmed) return
  deletingId.value = account.id
  actionError.value = ''
  try {
    await api.deleteAntigravityAgyAccount(account.id)
    await load()
  } catch (cause) {
    actionError.value = cause instanceof Error ? cause.message : '清理 CLI 档案失败'
  } finally {
    deletingId.value = null
  }
}
onMounted(load)
</script>

<template>
  <section class="card form-grid" aria-label="Antigravity agy 授权账号">
    <div class="section-heading">
      <div><h3>Antigravity 官方 agy 授权</h3><p class="muted small">独立 CLI 登录档案，不再通过网关自建 OAuth 新增或重新授权。</p></div>
      <button type="button" class="button" :disabled="loading || !configured || deletingId !== null" @click="$emit('create')">新增 CLI 账号</button>
    </div>
    <p v-if="loading" role="status">正在读取官方 CLI 配置…</p>
    <p v-else-if="error" class="callout danger" role="alert">{{ error }} <button type="button" class="button" @click="load">重试</button></p>
    <p v-else-if="!configured" class="callout warning">官方 agy 尚未开启或运行环境尚未准备完成，不会退回旧 OAuth 登录。<button type="button" class="button" @click="showSettings = true">配置运行环境</button></p>
    <button v-if="configured" type="button" class="button" @click="showSettings = true">运行环境设置</button>
    <p v-if="actionError" class="callout danger" role="alert">{{ actionError }}</p>
    <p v-if="configured && accounts.length" class="muted small">仅未被订阅账号、迁移来源、已保存路由或进行中的登录使用的孤儿档案可清理。清理会永久删除该档案的本地 agy 凭据，并释放一个档案名额。</p>
    <p v-if="!loading && !error && configured && !accounts.length" class="muted small">还没有通过管理台授权的 CLI 账号；新增账号或从旧账号菜单选择“使用 agy 登录迁移”。</p>
    <article v-for="account in accounts" :key="account.id" class="callout info">
      <strong>{{ account.name }}</strong>
      <span class="badge small" :class="account.registered ? 'info' : 'warning'">
        {{ account.registered ? '已登记为订阅路由凭据' : '未登记到普通路由' }}
      </span>
      <button type="button" class="button" :disabled="deletingId !== null" @click="$emit('reauthorize', account)">重新登录 CLI</button>
      <button
        v-if="account.cleanupEligible"
        type="button"
        class="button danger"
        :disabled="deletingId !== null"
        @click="cleanOrphan(account)"
      >{{ deletingId === account.id ? '正在清理…' : '清理孤儿档案' }}</button>
      <p class="small">官方档位：{{ account.subscriptionTier || '未确认' }} · 当前模型：{{ account.currentModelId || '未确认' }}</p>
      <p class="muted small">授权时间：{{ formatTime(account.authorizedAt) }}{{ account.sourceAccountId ? ' · 已关联旧账号记录，未自动核对邮箱同一性' : '' }}</p>
      <p class="muted small">以上为最近一次授权检查结果，不代表实时登录或额度状态。</p>
      <p v-if="account.routeModels?.length" class="small">已加入模型路由：{{ account.routeModels.join('、') }}</p>
      <p v-else-if="account.registered" class="small">已登记到 Antigravity 订阅渠道；请在模型凭据路由中选择账号后才会接收请求。</p>
      <p v-else-if="account.subscriptionTier !== 'PRO'" class="small text-warning">只有确认的 Google AI Pro 档位会登记为可路由账号。</p>
      <p v-if="account.sourceAccountId" class="muted small">旧账号迁移保留原账号 ID、渠道关联和模型路由顺序，并清除旧 OAuth token；请确认 CLI 登录的是同一个 Google 账号。</p>
      <p v-if="account.routeModels?.length && !account.enabled" class="small text-warning">账号已在模型路由中，但当前处于停用状态。</p>
    </article>
    <AntigravityAgySettingsModal v-if="showSettings" @close="showSettings = false" @saved="load" />
  </section>
</template>

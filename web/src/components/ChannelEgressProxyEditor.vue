<script setup lang="ts">
import type { ChannelEgressProxyDraft } from '../utils/channelForm'
import { proxyAuthenticationEnabled } from '../utils/channelForm'
import SecretInput from './SecretInput.vue'

const props = defineProps<{
  proxies: ChannelEgressProxyDraft[]
  keys: Array<{ egressProxyId: string | null }>
}>()

function bindingCount(id: string): number {
  return props.keys.filter(key => key.egressProxyId === id).length
}

function addProxy(): void {
  const id = globalThis.crypto?.randomUUID?.() ?? 'proxy-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
  props.proxies.push({ id, name: '', url: '' })
}
</script>

<template>
  <details class="channel-egress-editor" :open="proxies.length > 0">
    <summary>出口代理（{{ proxies.length }}）</summary>
    <p class="muted small">一个渠道可配置多个 HTTP 或 SOCKS5 代理，在下方为每个密钥选择一个；未绑定时沿用网关原出口。代理故障不会将该密钥改为直连。</p>
    <div v-for="(proxy, index) in proxies" :key="proxy.id" class="channel-egress-row">
      <label class="field">
        <span>代理名称</span>
        <input v-model="proxy.name" :aria-label="'代理 ' + (index + 1) + ' 名称'" maxlength="100" placeholder="例如：美国出口" />
      </label>
      <label class="field">
        <span>代理地址</span>
        <input v-model="proxy.url" :aria-label="'代理 ' + (index + 1) + ' 地址'" maxlength="500" placeholder="http://… 或 socks5://…" autocomplete="off" />
      </label>
      <label class="channel-egress-auth-toggle">
        <input type="checkbox" :aria-label="'代理 ' + (index + 1) + ' 账号密码认证'" :checked="proxyAuthenticationEnabled(proxy)" @change="proxy.authenticationEnabled = ($event.target as HTMLInputElement).checked" />
        <span>账号密码认证</span>
      </label>
      <template v-if="proxyAuthenticationEnabled(proxy)">
        <label class="field">
          <span>代理账号</span>
          <input v-model="proxy.username" :aria-label="'代理 ' + (index + 1) + ' 账号'" autocomplete="off" placeholder="输入代理账号" />
        </label>
        <label class="field">
          <span>代理密码</span>
          <SecretInput v-model="proxy.password" :placeholder="proxy.passwordSet ? '留空保持已保存密码' : '输入代理密码'" autocomplete="new-password" />
          <small v-if="proxy.passwordSet">密码已加密保存，不回显；地址不变时留空保留，变更地址需重填密码或关闭认证。</small>
        </label>
      </template>
      <div class="channel-egress-actions">
        <small class="muted">{{ bindingCount(proxy.id) }} 个密钥绑定</small>
        <button type="button" class="button ghost mini" :disabled="bindingCount(proxy.id) > 0" :title="bindingCount(proxy.id) ? '请先解除或修改密钥绑定' : '删除出口代理'" @click="proxies.splice(index, 1)">删除</button>
      </div>
    </div>
    <button type="button" class="button ghost mini" :disabled="proxies.length >= 100" @click="addProxy">添加出口代理</button>
    <p class="muted small">HTTP、SOCKS5 均支持账号密码认证，凭据请单独填写，不要放进代理地址。SOCKS5 的目标域名由代理解析。非本机回环地址需在服务端配置 CHANNEL_PROXY_ALLOWED_HOSTS。</p>
  </details>
</template>

<style scoped>
.channel-egress-editor { margin-bottom: 16px; }
.channel-egress-editor summary { cursor: pointer; font-weight: 600; }
.channel-egress-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr); gap: 10px; margin-bottom: 14px; }
.channel-egress-row input { width: 100%; min-width: 0; }
.channel-egress-auth-toggle { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; }
.channel-egress-auth-toggle input { width: auto; }
.channel-egress-actions { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
@media (max-width: 640px) { .channel-egress-row { grid-template-columns: minmax(0, 1fr); } }
</style>

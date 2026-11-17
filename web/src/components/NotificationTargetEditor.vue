<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { Channel, NotificationCredentialWrite, NotificationTargetRequest, NotificationTargetResponse } from '../types/api'
import {
  notificationCredentialSecretError,
  notificationCredentialUrlError,
  notificationEventOptions,
  notificationTargetError,
  notificationTemplateError,
  notificationTemplateVariables,
} from '../utils/notification'
import Icon from './Icon.vue'
import SecretInput from './SecretInput.vue'

const props = defineProps<{ target: NotificationTargetResponse | null }>()
const emit = defineEmits<{
  (event: 'saved', target: NotificationTargetResponse): void
  (event: 'cancel'): void
  (event: 'busy', busy: boolean): void
}>()

const draft = reactive<NotificationTargetRequest>({
  name: props.target?.name ?? '',
  type: props.target?.type ?? 'webhook',
  enabled: props.target?.enabled ?? true,
  events: [...(props.target?.events ?? notificationEventOptions.map(event => event.value))],
  channelIds: [...(props.target?.channelIds ?? [])],
  templateTitle: props.target?.templateTitle ?? '',
  templateBody: props.target?.templateBody ?? '',
  revision: props.target?.revision,
})
const credentialDraft = reactive({
  url: '',
  bearerToken: '',
  signingSecret: '',
  clearUrl: false,
  clearBearerToken: false,
  clearSigningSecret: false,
})
const allChannels = ref(draft.channelIds.length === 0)
const channels = ref<Pick<Channel, 'id' | 'name' | 'enabled'>[]>([])
const channelsLoading = ref(true)
const channelsError = ref('')
const channelsLoaded = ref(false)
const saving = ref(false)
const error = ref('')
let disposed = false

const credentials = computed(() => props.target?.credentials ?? null)
const urlState = computed(() => credentialState(credentials.value?.urlConfigured, credentials.value?.urlUnreadable, true))
const bearerState = computed(() => credentialState(credentials.value?.bearerTokenConfigured, credentials.value?.bearerTokenUnreadable))
const signingState = computed(() => credentialState(credentials.value?.signingSecretConfigured, credentials.value?.signingSecretUnreadable))
const templateVariableHints = notificationTemplateVariables.map(name => '{{' + name + '}}')
const templateBodyPlaceholder = '例如：\n**渠道** {{channel}}\n原因：{{reason}}'
const staleChannelIds = computed(() => channelsLoaded.value
  ? draft.channelIds.filter(id => !channels.value.some(channel => channel.id === id))
  : [])

function credentialState(configured: boolean | undefined, unreadable: boolean | undefined, required = false) {
  if (unreadable) return { label: '无法解密，请重新填写', className: 'is-missing' }
  if (configured) return { label: '已保存，留空保持不变', className: 'is-configured' }
  return { label: required ? '未配置（必需）' : '未配置（可选）', className: required ? 'is-missing' : 'is-optional' }
}

function credentialsPayload(): NotificationCredentialWrite | undefined {
  const payload: NotificationCredentialWrite = {}
  if (credentialDraft.url.trim()) payload.url = credentialDraft.url.trim()
  if (credentialDraft.bearerToken.trim()) payload.bearerToken = credentialDraft.bearerToken.trim()
  if (credentialDraft.signingSecret.trim()) payload.signingSecret = credentialDraft.signingSecret.trim()
  if (credentialDraft.clearUrl) payload.clearUrl = true
  if (credentialDraft.clearBearerToken) payload.clearBearerToken = true
  if (credentialDraft.clearSigningSecret) payload.clearSigningSecret = true
  return Object.keys(payload).length ? payload : undefined
}

function payload(): NotificationTargetRequest {
  const credentials = credentialsPayload()
  return {
    name: draft.name.trim(),
    type: draft.type,
    enabled: draft.enabled,
    events: [...draft.events],
    channelIds: allChannels.value ? [] : [...draft.channelIds],
    ...(draft.type === 'feishu'
      ? {
          templateTitle: draft.templateTitle?.trim() || null,
          templateBody: draft.templateBody?.trim() || null,
        }
      : {}),
    ...(credentials ? { credentials } : {}),
    ...(draft.revision === undefined ? {} : { revision: draft.revision }),
  }
}

const credentialError = computed(() =>
  notificationCredentialUrlError(credentialDraft.url) ||
  notificationCredentialSecretError(credentialDraft.bearerToken, 'Bearer 令牌') ||
  notificationCredentialSecretError(credentialDraft.signingSecret, '签名密钥'))

const validationError = computed(() => {
  const result = notificationTargetError(payload(), channelsLoaded.value ? channels.value : undefined, allChannels.value)
  if (result) return result
  if (credentialError.value) return credentialError.value
  if (draft.type === 'feishu') {
    const templateResult = notificationTemplateError(draft.templateTitle ?? '', draft.templateBody ?? '')
    if (templateResult) return templateResult
  }
  if (!allChannels.value && !channelsLoaded.value) return '请先成功读取渠道列表，再保存指定渠道范围。'
  return ''
})

async function loadChannels(): Promise<void> {
  if (saving.value) return
  channelsLoading.value = true
  channelsError.value = ''
  try {
    const result = await api.listChannels()
    if (disposed) return
    channels.value = result.map(channel => ({ id: channel.id, name: channel.name, enabled: channel.enabled }))
    channelsLoaded.value = true
  } catch (cause) {
    if (!disposed) channelsError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (!disposed) channelsLoading.value = false
  }
}

async function save(): Promise<void> {
  if (saving.value || disposed) return
  if (validationError.value) {
    error.value = validationError.value
    return
  }
  saving.value = true
  error.value = ''
  emit('busy', true)
  try {
    const result = props.target
      ? await api.updateNotificationTarget(props.target.id, payload())
      : await api.createNotificationTarget(payload())
    if (disposed) return
    notify(props.target ? '通知目标已更新' : '通知目标已创建')
    emit('saved', result)
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (!disposed) {
      saving.value = false
      emit('busy', false)
    }
  }
}

onMounted(() => { void loadChannels() })
onBeforeUnmount(() => { disposed = true })
</script>

<template>
  <section class="notification-target-editor" aria-labelledby="notification-editor-title">
    <div class="notification-editor-head">
      <h3 id="notification-editor-title">{{ target ? `编辑通知目标：${target.name}` : '添加通知目标' }}</h3>
      <p>此处只保存配置。发送测试需在目标列表中单独操作。</p>
    </div>
    <form class="notification-target-form" @submit.prevent="save" @input="error = ''" @change="error = ''">
      <div class="notification-editor-grid">
        <label class="field">
          <span>目标名称</span>
          <input v-model="draft.name" type="text" maxlength="100" required autocomplete="off" placeholder="例如：值班群" :disabled="saving" />
        </label>
        <label class="field">
          <span>目标类型</span>
          <select v-model="draft.type" :disabled="saving">
            <option value="webhook">Webhook</option>
            <option value="feishu">飞书机器人</option>
          </select>
        </label>
      </div>
      <label class="switch-row">
        <input v-model="draft.enabled" type="checkbox" :disabled="saving" />
        <span class="switch"></span>
        <span><strong>启用此目标</strong><small>可先停用并保存目标，配置凭据后再启用。</small></span>
      </label>

      <fieldset class="notification-credential-group" :disabled="saving">
        <legend>接收端凭据</legend>
        <p>凭据以 AES-256-GCM 加密保存在数据库，保存后不再回显；留空表示保持不变。清除已保存的值后，该字段变为未配置。</p>
        <div class="notification-credential-row">
          <label class="field">
            <span>接收端 URL</span>
            <input
              v-model="credentialDraft.url" type="url" maxlength="2048" autocomplete="off" spellcheck="false"
              placeholder="https://example.com/hooks/prism" :disabled="saving || credentialDraft.clearUrl"
            />
          </label>
          <div class="notification-credential-meta">
            <span class="notification-credential-state" :class="urlState.className">{{ urlState.label }}</span>
            <label class="notification-clear">
              <input v-model="credentialDraft.clearUrl" type="checkbox" :disabled="saving || credentialDraft.url.trim().length > 0" />
              <span>清除</span>
            </label>
          </div>
        </div>
        <div class="notification-credential-row">
          <label class="field">
            <span>Bearer 令牌</span>
            <SecretInput
              v-model="credentialDraft.bearerToken" :disabled="saving || credentialDraft.clearBearerToken"
              autocomplete="new-password" placeholder="可选，留空保持不变"
            />
          </label>
          <div class="notification-credential-meta">
            <span class="notification-credential-state" :class="bearerState.className">{{ bearerState.label }}</span>
            <label class="notification-clear">
              <input v-model="credentialDraft.clearBearerToken" type="checkbox" :disabled="saving || credentialDraft.bearerToken.length > 0" />
              <span>清除</span>
            </label>
          </div>
        </div>
        <div class="notification-credential-row">
          <label class="field">
            <span>签名密钥</span>
            <SecretInput
              v-model="credentialDraft.signingSecret" :disabled="saving || credentialDraft.clearSigningSecret"
              autocomplete="new-password" placeholder="可选，Webhook 用于 HMAC 签名；飞书填写机器人签名密钥"
            />
          </label>
          <div class="notification-credential-meta">
            <span class="notification-credential-state" :class="signingState.className">{{ signingState.label }}</span>
            <label class="notification-clear">
              <input v-model="credentialDraft.clearSigningSecret" type="checkbox" :disabled="saving || credentialDraft.signingSecret.length > 0" />
              <span>清除</span>
            </label>
          </div>
        </div>
      </fieldset>

      <fieldset v-if="draft.type === 'feishu'" class="notification-template-group" :disabled="saving">
        <legend>消息模板（可选）</legend>
        <p>
          留空使用默认卡片。可用变量：
          <code v-for="hint in templateVariableHints" :key="hint">{{ hint }}</code>
          ；正文支持 Markdown。
        </p>
        <label class="field">
          <span>卡片标题</span>
          <input
            v-model="draft.templateTitle" type="text" maxlength="100" autocomplete="off"
            placeholder="留空：按事件类型自动选择" :disabled="saving"
          />
        </label>
        <label class="field">
          <span>卡片正文</span>
          <textarea
            v-model="draft.templateBody" rows="5" maxlength="4096" :disabled="saving"
            :placeholder="templateBodyPlaceholder"
          ></textarea>
        </label>
      </fieldset>

      <fieldset class="notification-choice-group" :disabled="saving">
        <legend>订阅事件</legend>
        <p>至少选择一种事件。密钥事件针对上游渠道密钥，不涉及调用方密钥。</p>
        <div class="notification-event-grid">
          <label v-for="event in notificationEventOptions" :key="event.value" class="notification-choice">
            <input v-model="draft.events" type="checkbox" :value="event.value" />
            <span><strong>{{ event.label }}</strong><small>{{ event.description }}</small></span>
          </label>
        </div>
      </fieldset>

      <fieldset class="notification-choice-group" :disabled="saving">
        <legend>渠道范围</legend>
        <label class="notification-choice notification-all-channels">
          <input v-model="allChannels" type="checkbox" />
          <span><strong>全部渠道</strong><small>包含今后新增的渠道；取消勾选可指定范围。</small></span>
        </label>
        <template v-if="!allChannels">
          <p>已选择 {{ draft.channelIds.length }} 个渠道，最多 100 个。请至少选择一个渠道。</p>
          <p v-if="channelsLoading" role="status">正在读取渠道…</p>
          <div v-else-if="channelsError" class="notification-editor-error" role="alert">
            <span>{{ channelsError }}；已有渠道选择已保留。</span>
            <button type="button" class="button ghost mini" @click="loadChannels">重新加载渠道</button>
          </div>
          <div v-else class="notification-channel-list">
            <label v-for="channel in channels" :key="channel.id" class="notification-channel-choice">
              <input
                v-model="draft.channelIds" type="checkbox" :value="channel.id"
                :disabled="draft.channelIds.length >= 100 && !draft.channelIds.includes(channel.id)"
              />
              <span>{{ channel.name }}</span><small v-if="!channel.enabled">已停用</small>
            </label>
            <p v-if="!channels.length">没有可选择的渠道。</p>
            <label v-for="channelId in staleChannelIds" :key="channelId" class="notification-channel-choice is-stale">
              <input v-model="draft.channelIds" type="checkbox" :value="channelId" />
              <span>渠道已删除或不存在：{{ channelId }}</span>
            </label>
          </div>
        </template>
      </fieldset>

      <p v-if="error || validationError" class="text-danger small notification-validation" role="alert">
        {{ error || validationError }}
      </p>
      <div class="notification-editor-actions">
        <button type="button" class="button ghost" :disabled="saving" @click="$emit('cancel')">取消目标编辑</button>
        <button type="submit" class="button primary" :disabled="saving || Boolean(validationError)">
          {{ saving ? '保存中…' : target ? '保存目标' : '创建目标' }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.notification-target-editor { display: grid; gap: 14px; min-width: 0; }
.notification-editor-head h3 { margin: 0; font-size: 15px; font-weight: 650; overflow-wrap: anywhere; }
.notification-editor-head p,
.notification-choice-group > p,
.notification-channel-list > p { margin: 4px 0 10px; color: var(--muted); font-size: 12px; line-height: 1.6; }
.notification-target-form { display: grid; gap: 16px; }
.notification-editor-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(180px, .7fr); align-items: start; gap: 12px 16px; }
.notification-credential-group { min-width: 0; margin: 0; padding: 0; border: 0; display: grid; gap: 10px; }
.notification-credential-group legend { margin-bottom: 7px; font-size: 13px; font-weight: 650; }
.notification-credential-group > p { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.6; }
.notification-credential-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 8px 12px; padding: 10px; border: 1px solid var(--glass-line); border-radius: 8px; background: var(--glass-field); }
.notification-credential-row .field { min-width: 0; }
.notification-credential-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 10px; padding-bottom: 6px; }
.notification-credential-state { font-size: 11px; }
.notification-credential-state.is-configured { color: var(--success); }
.notification-credential-state.is-missing { color: var(--danger); }
.notification-credential-state.is-optional { color: var(--muted); }
.notification-clear { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 11px; cursor: pointer; }
.notification-clear input { flex: 0 0 auto; width: 15px; height: 15px; margin: 0; }
.notification-template-group { min-width: 0; margin: 0; padding: 0; border: 0; display: grid; gap: 10px; }
.notification-template-group legend { margin-bottom: 7px; font-size: 13px; font-weight: 650; }
.notification-template-group > p { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.7; }
.notification-template-group code { margin-right: 6px; font-size: 10px; }
.notification-choice-group { min-width: 0; margin: 0; padding: 0; border: 0; }
.notification-choice-group legend { margin-bottom: 7px; font-size: 13px; font-weight: 650; }
.notification-event-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 10px; }
.notification-choice { display: flex; align-items: flex-start; gap: 9px; min-width: 0; min-height: 44px; padding: 10px; border: 1px solid var(--glass-line); border-radius: 8px; background: var(--glass-field); cursor: pointer; }
.notification-choice input,
.notification-channel-choice input { flex: 0 0 auto; width: 16px; height: 16px; margin: 1px 0 0; }
.notification-choice > span { display: grid; gap: 3px; min-width: 0; }
.notification-choice strong { font-size: 12px; font-weight: 600; }
.notification-choice small { color: var(--muted); font-size: 11px; line-height: 1.5; }
.notification-channel-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; max-height: 240px; overflow: auto; padding: 4px 10px; border: 1px solid var(--glass-line); border-radius: 8px; }
.notification-channel-choice { display: flex; align-items: center; gap: 8px; min-width: 0; min-height: 40px; border-bottom: 1px solid var(--glass-line); font-size: 12px; }
.notification-channel-choice > span { min-width: 0; overflow-wrap: anywhere; }
.notification-channel-choice small { flex: 0 0 auto; color: var(--muted); font-size: 10px; }
.notification-channel-choice.is-stale { color: var(--danger); }
.notification-editor-error { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; color: var(--danger); font-size: 12px; }
.notification-validation { margin: 0; overflow-wrap: anywhere; }
.notification-editor-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
@media (max-width: 640px) {
  .notification-editor-grid,
  .notification-event-grid,
  .notification-credential-row,
  .notification-channel-list { grid-template-columns: minmax(0, 1fr); }
  .notification-credential-meta { padding-bottom: 0; }
  .notification-channel-choice { min-height: 44px; }
  .notification-editor-actions > .button { min-height: 44px; }
}
</style>

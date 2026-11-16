<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type {
  NotificationDelivery,
  NotificationSettings,
  NotificationTargetResponse,
} from '../types/api'
import { formatTime } from '../utils/format'
import {
  notificationDeliveryFinished,
  notificationDeliveryStatusLabel,
  notificationEventLabel,
  notificationTestDisabledReason,
  notificationThresholdError,
} from '../utils/notification'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import NotificationTargetEditor from './NotificationTargetEditor.vue'

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'saved'): void
}>()

const settings = ref<NotificationSettings | null>(null)
const settingsDraft = reactive({ enabled: false, consecutiveFailureThreshold: 5 })
const settingsLoading = ref(true)
const settingsSaving = ref(false)
const settingsError = ref('')
const thresholdError = computed(() => notificationThresholdError(settingsDraft.consecutiveFailureThreshold))
const settingsDirty = computed(() => settings.value !== null && (
  settingsDraft.enabled !== settings.value.enabled
  || settingsDraft.consecutiveFailureThreshold !== settings.value.consecutiveFailureThreshold
))

const targets = ref<NotificationTargetResponse[]>([])
const targetsLoading = ref(true)
const targetsError = ref('')
const editorVisible = ref(false)
const editingTarget = ref<NotificationTargetResponse | null>(null)
const editorBusy = ref(false)
const editorHost = ref<HTMLElement | null>(null)
const deletingTargetId = ref<string | null>(null)
const busy = computed(() => settingsSaving.value || editorBusy.value || deletingTargetId.value !== null)

const selectedHistoryTargetId = ref<string | null>(null)
const selectedHistoryTarget = computed(() => targets.value.find(target => target.id === selectedHistoryTargetId.value) ?? null)
const history = ref<NotificationDelivery[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historyLoading = ref(false)
const historyError = ref('')
const pageSize = 20
const historyHasNext = computed(() => historyPage.value * pageSize < historyTotal.value)
let requestedHistoryPage = 1
let historyController: AbortController | null = null
let historyGeneration = 0

const testingTargetId = ref<string | null>(null)
const testTargetName = ref('')
const testDeliveryId = ref('')
const testEventId = ref('')
const testDelivery = ref<NotificationDelivery | null>(null)
const testMessage = ref('')
let testController: AbortController | null = null
let pollTimer: number | null = null
let testDeadline: number | null = null
let disposed = false
const lifetimeController = new AbortController()

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

async function loadSettings(): Promise<void> {
  settingsLoading.value = true
  settingsError.value = ''
  try {
    const result = await api.getNotificationSettings({ signal: lifetimeController.signal })
    if (disposed) return
    settings.value = result
    Object.assign(settingsDraft, { enabled: result.enabled, consecutiveFailureThreshold: result.consecutiveFailureThreshold })
  } catch (cause) {
    if (!disposed) settingsError.value = errorMessage(cause)
  } finally {
    if (!disposed) settingsLoading.value = false
  }
}

async function saveSettings(): Promise<void> {
  if (!settings.value || settingsLoading.value || busy.value || thresholdError.value) return
  settingsSaving.value = true
  settingsError.value = ''
  try {
    const result = await api.updateNotificationSettings({
      enabled: settingsDraft.enabled,
      consecutiveFailureThreshold: settingsDraft.consecutiveFailureThreshold,
    })
    if (disposed) return
    settings.value = result
    Object.assign(settingsDraft, { enabled: result.enabled, consecutiveFailureThreshold: result.consecutiveFailureThreshold })
    notify('运行通知设置已保存')
    emit('saved')
  } catch (cause) {
    if (!disposed) settingsError.value = errorMessage(cause)
  } finally {
    if (!disposed) settingsSaving.value = false
  }
}

async function loadTargets(): Promise<void> {
  if (disposed) return
  targetsLoading.value = true
  targetsError.value = ''
  try {
    const result = await api.listNotificationTargets({ signal: lifetimeController.signal })
    if (disposed) return
    targets.value = result
    if (selectedHistoryTargetId.value && !result.some(target => target.id === selectedHistoryTargetId.value)) clearHistorySelection()
  } catch (cause) {
    if (!disposed) targetsError.value = errorMessage(cause)
  } finally {
    if (!disposed) targetsLoading.value = false
  }
}

async function openEditor(target: NotificationTargetResponse | null = null): Promise<void> {
  if (busy.value || editorVisible.value) return
  editingTarget.value = target
  editorVisible.value = true
  await nextTick()
  editorHost.value?.scrollIntoView?.({ block: 'start' })
  editorHost.value?.querySelector<HTMLInputElement>('input[type="text"]')?.focus({ preventScroll: true })
}

function closeEditor(): void {
  if (editorBusy.value) return
  editorVisible.value = false
  editingTarget.value = null
}

function onTargetSaved(target: NotificationTargetResponse): void {
  const index = targets.value.findIndex(item => item.id === target.id)
  if (index >= 0) targets.value.splice(index, 1, target)
  else targets.value.push(target)
  editorBusy.value = false
  closeEditor()
}

async function deleteTarget(target: NotificationTargetResponse): Promise<void> {
  if (busy.value || editorVisible.value || testingTargetId.value === target.id) return
  if (!window.confirm(`删除通知目标“${target.name}”？此目标将停止接收通知。`)) return
  deletingTargetId.value = target.id
  try {
    await api.deleteNotificationTarget(target.id)
    if (disposed) return
    targets.value = targets.value.filter(item => item.id !== target.id)
    if (selectedHistoryTargetId.value === target.id) clearHistorySelection()
    notify('通知目标已删除')
  } catch (cause) {
    if (!disposed) notify(errorMessage(cause), 'error')
  } finally {
    if (!disposed) deletingTargetId.value = null
  }
}

function clearHistorySelection(): void {
  historyGeneration += 1
  historyController?.abort()
  historyController = null
  selectedHistoryTargetId.value = null
  history.value = []
  historyTotal.value = 0
  historyPage.value = 1
  historyError.value = ''
  historyLoading.value = false
}

function updateTestDelivery(delivery: NotificationDelivery): void {
  testDelivery.value = delivery
  testMessage.value = `测试通知${notificationDeliveryStatusLabel(delivery.status)}。`
  const target = targets.value.find(item => item.id === delivery.targetId)
  if (target && (!target.latestDelivery || target.latestDelivery.createdAt <= delivery.createdAt)) target.latestDelivery = delivery
}

async function loadHistory(page = requestedHistoryPage): Promise<void> {
  const targetId = selectedHistoryTargetId.value
  if (!targetId || disposed) return
  historyController?.abort()
  const controller = new AbortController()
  historyController = controller
  const generation = ++historyGeneration
  requestedHistoryPage = page
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await api.listNotificationDeliveries({ targetId, page, pageSize }, { signal: controller.signal })
    if (controller.signal.aborted || generation !== historyGeneration || disposed) return
    history.value = result.items
    historyTotal.value = result.total
    historyPage.value = result.page
    const testResult = result.items.find(item => item.id === testDeliveryId.value)
    if (testResult) updateTestDelivery(testResult)
  } catch (cause) {
    if (!controller.signal.aborted && generation === historyGeneration && !disposed) historyError.value = errorMessage(cause)
  } finally {
    if (generation === historyGeneration && !disposed) {
      historyLoading.value = false
      historyController = null
    }
  }
}

function selectHistory(target: NotificationTargetResponse): void {
  clearHistorySelection()
  selectedHistoryTargetId.value = target.id
  requestedHistoryPage = 1
  void loadHistory(1)
}

function testDisabledReason(target: NotificationTargetResponse): string {
  if (settingsLoading.value) return '正在读取全局设置。'
  if (settingsDirty.value) return '请先保存全局设置，再发送测试。'
  return notificationTestDisabledReason(settings.value, target)
}

function waitForPoll(signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    const finish = () => {
      if (pollTimer !== null) window.clearTimeout(pollTimer)
      pollTimer = null
      signal.removeEventListener('abort', finish)
      resolve()
    }
    if (signal.aborted) return resolve()
    signal.addEventListener('abort', finish, { once: true })
    pollTimer = window.setTimeout(finish, 1200)
  })
}

async function testTarget(target: NotificationTargetResponse): Promise<void> {
  if (disposed || busy.value || editorVisible.value || testingTargetId.value || testDisabledReason(target)) return
  const controller = new AbortController()
  testController = controller
  let timedOut = false
  testingTargetId.value = target.id
  testTargetName.value = target.name
  testDelivery.value = null
  testDeliveryId.value = ''
  testEventId.value = ''
  testMessage.value = '正在将测试通知加入队列…'
  testDeadline = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, 30_000)
  try {
    const queued = await api.testNotificationTarget(target.id, { signal: controller.signal })
    if (disposed || controller.signal.aborted) return
    testDeliveryId.value = queued.deliveryId
    testEventId.value = queued.eventId
    testMessage.value = '测试通知已加入队列，正在读取投递结果。'
    selectHistory(target)
    for (let attempt = 0; attempt < 10; attempt += 1) {
      await waitForPoll(controller.signal)
      if (controller.signal.aborted || disposed) return
      const result = await api.listNotificationDeliveries({ targetId: target.id, page: 1, pageSize }, { signal: controller.signal })
      if (controller.signal.aborted || disposed) return
      if (selectedHistoryTargetId.value === target.id && historyPage.value === 1 && !historyLoading.value) {
        history.value = result.items
        historyTotal.value = result.total
      }
      const delivery = result.items.find(item => item.id === queued.deliveryId)
      if (delivery) {
        updateTestDelivery(delivery)
        if (notificationDeliveryFinished(delivery.status)) return
      }
    }
    testMessage.value = '测试通知已入队，已停止自动查询；请刷新投递记录查看后续结果。'
  } catch (cause) {
    if (!disposed && !controller.signal.aborted) {
      testMessage.value = testDeliveryId.value
        ? `测试通知已入队，投递状态读取失败：${errorMessage(cause)}`
        : `测试通知入队失败：${errorMessage(cause)}`
    }
  } finally {
    if (testDeadline !== null) window.clearTimeout(testDeadline)
    testDeadline = null
    if (testController === controller) testController = null
    if (!disposed) {
      if (timedOut) testMessage.value = testDeliveryId.value
        ? '测试通知已入队，状态查询超时；请刷新投递记录查看结果。'
        : '测试请求超时，入队结果未知；请先查看投递记录，避免重复发送。'
      testingTargetId.value = null
    }
  }
}

function tryClose(): void {
  if (!busy.value) emit('close')
}

onMounted(() => { void loadSettings(); void loadTargets() })
onBeforeUnmount(() => {
  disposed = true
  lifetimeController.abort()
  historyGeneration += 1
  historyController?.abort()
  testController?.abort()
  if (pollTimer !== null) window.clearTimeout(pollTimer)
  if (testDeadline !== null) window.clearTimeout(testDeadline)
})
</script>

<template>
  <Modal title="运行通知" eyebrow="全局业务设置" wide :mobileFullscreen="true" @close="tryClose">
    <div class="notification-settings">
      <section aria-labelledby="notification-global-title">
        <div class="notification-section-head">
          <div>
            <h3 id="notification-global-title">全局设置</h3>
            <p>启用后，将渠道健康、上游密钥和连续失败事件发送到已启用的目标。</p>
          </div>
          <small v-if="settings">{{ settings.updatedAt > 0 ? `更新于 ${formatTime(settings.updatedAt)}` : '默认关闭 · 连续失败阈值 5 次' }}</small>
        </div>
        <p v-if="settingsLoading" class="muted small" role="status">正在读取全局通知设置…</p>
        <div v-if="settingsError" class="notification-load-error" role="alert">
          <span>{{ settingsError }}</span>
          <button v-if="!settings" type="button" class="button ghost mini" :disabled="settingsLoading" @click="loadSettings">重新加载设置</button>
        </div>
        <form v-if="settings && !settingsLoading" class="notification-global-form" @submit.prevent="saveSettings">
          <label class="switch-row">
            <input v-model="settingsDraft.enabled" type="checkbox" :disabled="settingsSaving" />
            <span class="switch"></span>
            <span><strong>启用全局通知</strong><small>默认关闭。关闭时所有目标停止投递，已有配置保留。</small></span>
          </label>
          <label class="field notification-threshold">
            <span>连续失败通知阈值</span>
            <input v-model.number="settingsDraft.consecutiveFailureThreshold" type="number" min="1" max="100" step="1" required :aria-invalid="Boolean(thresholdError)" :disabled="settingsSaving" />
            <small>1 到 100 之间的整数，默认 5 次。</small>
          </label>
          <div class="notification-global-actions">
            <span v-if="thresholdError" class="text-danger" role="alert">{{ thresholdError }}</span>
            <span v-else-if="settingsDirty" class="muted">全局设置尚未保存</span>
            <button type="submit" class="button primary" :disabled="busy || Boolean(thresholdError)">{{ settingsSaving ? '保存中…' : '保存全局设置' }}</button>
          </div>
        </form>
      </section>

      <section aria-labelledby="notification-targets-title">
        <div class="notification-section-head">
          <div>
            <h3 id="notification-targets-title">通知目标</h3>
            <p>分别保存各个目标的订阅与渠道范围。保存配置不会发送测试通知。</p>
          </div>
          <div class="notification-head-actions">
            <button type="button" class="button ghost mini" :disabled="targetsLoading || busy || editorVisible" @click="loadTargets"><Icon name="refresh" :size="13" /> 刷新状态</button>
            <button type="button" class="button ghost mini" :disabled="targetsLoading || Boolean(targetsError) || busy || editorVisible || targets.length >= 20" @click="openEditor()"><Icon name="plus" :size="14" /> 添加目标</button>
          </div>
        </div>
        <p v-if="targetsLoading" class="muted small" role="status">正在读取通知目标…</p>
        <div v-if="targetsError" class="notification-load-error" role="alert">
          <span>通知目标读取失败：{{ targetsError }}</span>
          <button type="button" class="button ghost mini" :disabled="targetsLoading" @click="loadTargets">重新加载目标</button>
        </div>
        <p v-if="!targetsLoading && !targetsError && !targets.length" class="notification-empty">尚未添加通知目标。添加 Webhook 或飞书机器人后，可单独配置订阅范围。</p>
        <p v-if="targets.length >= 20" class="muted small">最多配置 20 个通知目标。</p>
        <div v-if="targets.length" class="notification-target-list">
          <article v-for="target in targets" :key="target.id" class="notification-target-row" :data-target-id="target.id">
            <div class="notification-target-main">
              <div class="notification-target-name">
                <strong>{{ target.name }}</strong>
                <span class="notification-type">{{ target.type === 'feishu' ? '飞书机器人' : 'Webhook' }}</span>
                <span class="notification-target-state" :class="target.enabled ? 'is-enabled' : 'is-disabled'">{{ target.enabled ? '已启用' : '已停用' }}</span>
              </div>
              <p class="notification-target-scope">{{ target.channelIds.length ? `指定 ${target.channelIds.length} 个渠道` : '全部渠道' }} · {{ target.events.length }} 种事件</p>
              <code class="notification-target-prefix">{{ target.environmentPrefix }}</code>
              <div class="notification-credentials" :aria-label="`${target.name} 的环境变量配置状态`">
                <span :class="target.credentials.urlConfigured ? 'is-configured' : 'is-missing'">_URL {{ target.credentials.urlConfigured ? '已配置' : '未配置（必需）' }}</span>
                <span :class="{ 'is-configured': target.credentials.bearerTokenConfigured }">_BEARER_TOKEN {{ target.credentials.bearerTokenConfigured ? '已配置' : '未配置（可选）' }}</span>
                <span :class="{ 'is-configured': target.credentials.signingSecretConfigured }">_SIGNING_SECRET {{ target.credentials.signingSecretConfigured ? '已配置' : '未配置（可选）' }}</span>
              </div>
              <p class="notification-latest-delivery">
                <template v-if="target.latestDelivery">最近投递：{{ notificationDeliveryStatusLabel(target.latestDelivery.status) }} · {{ notificationEventLabel(target.latestDelivery.eventType) }} · {{ formatTime(target.latestDelivery.updatedAt) }}</template>
                <template v-else>暂无投递记录</template>
              </p>
            </div>
            <div class="notification-target-actions">
              <button type="button" class="button ghost mini" :disabled="busy || editorVisible || testingTargetId !== null || Boolean(testDisabledReason(target))" :title="testDisabledReason(target) || '向此目标发送一条测试通知'" @click="testTarget(target)"><Icon name="bolt" :size="13" /> {{ testingTargetId === target.id ? '测试中…' : '发送测试' }}</button>
              <button type="button" class="button ghost mini" :aria-pressed="selectedHistoryTargetId === target.id" @click="selectHistory(target)">投递记录</button>
              <button type="button" class="button ghost mini" :disabled="busy || editorVisible || testingTargetId === target.id" @click="openEditor(target)"><Icon name="edit" :size="13" /> 编辑</button>
              <button type="button" class="button ghost mini" :disabled="busy || editorVisible || testingTargetId === target.id" :aria-label="`删除 ${target.name}`" @click="deleteTarget(target)"><Icon name="trash" :size="13" /> 删除</button>
              <small v-if="testDisabledReason(target)" class="notification-test-reason">{{ testDisabledReason(target) }}</small>
            </div>
          </article>
        </div>
        <div v-if="testMessage" class="notification-test-result" role="status" aria-live="polite">
          <strong>{{ testTargetName }}</strong>
          <span>{{ testMessage }}</span>
          <code v-if="testDeliveryId">投递 ID：{{ testDeliveryId }}</code>
          <code v-if="testEventId">事件 ID：{{ testEventId }}</code>
          <span v-if="testDelivery">{{ testDelivery.attempts }} 次尝试<span v-if="testDelivery.lastStatusCode !== null"> · HTTP {{ testDelivery.lastStatusCode }}</span></span>
          <span v-if="testDelivery?.errorCode" class="text-danger">{{ testDelivery.errorCode }}</span>
        </div>
      </section>

      <div v-if="editorVisible" ref="editorHost" class="notification-editor-host">
        <NotificationTargetEditor :key="editingTarget?.id || 'new'" :target="editingTarget" @saved="onTargetSaved" @cancel="closeEditor" @busy="editorBusy = $event" />
      </div>

      <section v-if="selectedHistoryTarget" class="notification-history" aria-labelledby="notification-history-title">
        <div class="notification-section-head">
          <div><h3 id="notification-history-title">{{ selectedHistoryTarget.name }} 的投递记录</h3><p>每页 {{ pageSize }} 条，按最近创建时间排列。</p></div>
          <div class="notification-head-actions">
            <button type="button" class="button ghost mini" :disabled="historyLoading" @click="loadHistory(historyPage)"><Icon name="refresh" :size="13" /> 刷新记录</button>
            <button type="button" class="icon-button" aria-label="关闭投递记录" @click="clearHistorySelection"><Icon name="close" :size="16" /></button>
          </div>
        </div>
        <p v-if="historyLoading" class="muted small" role="status">正在读取投递记录…</p>
        <div v-if="historyError" class="notification-load-error" role="alert"><span>投递记录读取失败：{{ historyError }}</span><button type="button" class="button ghost mini" :disabled="historyLoading" @click="loadHistory()">重试读取记录</button></div>
        <p v-if="!historyLoading && !historyError && !history.length" class="notification-empty">此目标还没有投递记录。</p>
        <div v-if="history.length" class="notification-delivery-list" :aria-busy="historyLoading">
          <article v-for="delivery in history" :key="delivery.id" class="notification-delivery-row">
            <div class="notification-delivery-main"><strong>{{ notificationEventLabel(delivery.eventType) }}</strong><span>创建于 {{ formatTime(delivery.createdAt) }} · 更新于 {{ formatTime(delivery.updatedAt) }}</span><code>投递：{{ delivery.id }}</code><code>事件：{{ delivery.eventId }}</code></div>
            <div class="notification-delivery-state">
              <span class="notification-status" :data-status="delivery.status">{{ notificationDeliveryStatusLabel(delivery.status) }}</span>
              <small>{{ delivery.attempts }} 次尝试<span v-if="delivery.lastStatusCode !== null"> · HTTP {{ delivery.lastStatusCode }}</span></small>
              <small v-if="delivery.errorCode" class="text-danger">{{ delivery.errorCode }}</small>
              <small v-if="delivery.status === 'pending' && delivery.nextAttemptAt > 0">下次尝试 {{ formatTime(delivery.nextAttemptAt) }}</small>
            </div>
          </article>
        </div>
        <div class="notification-history-pagination"><span>第 {{ historyPage }} 页 · 共 {{ historyTotal }} 条</span><div><button type="button" class="button ghost mini" :disabled="historyLoading || historyPage <= 1" @click="loadHistory(historyPage - 1)">上一页</button><button type="button" class="button ghost mini" :disabled="historyLoading || !historyHasNext" @click="loadHistory(historyPage + 1)">下一页</button></div></div>
      </section>
    </div>
    <template #footer><button type="button" class="button ghost" :disabled="busy" @click="tryClose">关闭</button></template>
  </Modal>
</template>

<style scoped>
.notification-settings { display: grid; gap: 24px; min-width: 0; }
.notification-settings > section,
.notification-editor-host { display: grid; gap: 14px; min-width: 0; }
.notification-settings > :not(:first-child) { padding-top: 22px; border-top: 1px solid var(--glass-line); }
.notification-section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px 16px; }
.notification-section-head > div { min-width: 0; }
.notification-section-head h3 { margin: 0; font-size: 15px; font-weight: 650; overflow-wrap: anywhere; }
.notification-section-head p { margin: 4px 0 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.notification-section-head > small { flex: 0 0 auto; color: var(--muted); font-size: 11px; }
.notification-head-actions { display: flex; align-items: center; flex: 0 0 auto; gap: 6px; flex-wrap: wrap; }
.notification-global-form { display: grid; grid-template-columns: minmax(0, 1fr) minmax(170px, 220px); align-items: start; gap: 12px 18px; }
.notification-global-form .switch-row { align-self: stretch; }
.notification-global-actions { grid-column: 1 / -1; display: flex; align-items: center; justify-content: flex-end; gap: 12px; font-size: 12px; }
.notification-load-error { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; color: var(--danger); font-size: 12px; overflow-wrap: anywhere; }
.notification-empty { margin: 0; padding: 12px 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.notification-target-list,
.notification-delivery-list { display: grid; border-top: 1px solid var(--glass-line); }
.notification-target-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(140px, 240px); align-items: start; gap: 12px 18px; padding: 16px 0; border-bottom: 1px solid var(--glass-line); }
.notification-target-main { min-width: 0; }
.notification-target-name { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.notification-target-name > strong { max-width: 100%; overflow-wrap: anywhere; font-size: 13px; font-weight: 650; }
.notification-type,
.notification-target-state,
.notification-status { display: inline-flex; align-items: center; min-height: 23px; padding: 3px 7px; border: 1px solid var(--glass-line); border-radius: 7px; font-size: 11px; line-height: 1.3; }
.notification-type,
.notification-target-state.is-disabled,
.notification-status[data-status='cancelled'] { color: var(--muted); background: var(--glass-field); }
.notification-target-state.is-enabled,
.notification-status[data-status='succeeded'] { color: var(--success); background: var(--success-soft); }
.notification-status[data-status='pending'] { color: var(--warning); background: var(--warning-soft); }
.notification-status[data-status='in_flight'] { color: var(--brand); background: var(--info-soft); }
.notification-status[data-status='failed'] { color: var(--danger); background: var(--danger-soft); }
.notification-target-scope,
.notification-latest-delivery { margin: 6px 0; color: var(--muted); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.notification-target-prefix { display: block; overflow-wrap: anywhere; font-size: 11px; }
.notification-credentials { display: flex; flex-wrap: wrap; gap: 4px 12px; margin: 8px 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.notification-credentials .is-configured { color: var(--success); }
.notification-credentials .is-missing { color: var(--danger); }
.notification-target-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-content: start; gap: 6px; }
.notification-target-actions .button { min-height: 36px; }
.notification-target-actions .button[aria-pressed='true'] { border-color: color-mix(in srgb, var(--brand) 38%, var(--glass-line)); background: var(--info-soft); color: var(--brand); }
.notification-test-reason { flex-basis: 100%; color: var(--muted); font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.notification-test-result { display: grid; gap: 5px; min-width: 0; padding: 12px; border-left: 3px solid var(--brand); background: var(--info-soft); font-size: 12px; line-height: 1.6; }
.notification-test-result code { overflow-wrap: anywhere; font-size: 11px; }
.notification-delivery-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(100px, 210px); align-items: start; gap: 10px 16px; padding: 13px 0; border-bottom: 1px solid var(--glass-line); }
.notification-delivery-main,
.notification-delivery-state { display: grid; gap: 4px; min-width: 0; }
.notification-delivery-main strong { font-size: 12px; font-weight: 600; }
.notification-delivery-main > span,
.notification-delivery-state small { color: var(--muted); font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.notification-delivery-main code { color: var(--muted); font-size: 10px; overflow-wrap: anywhere; }
.notification-delivery-state { justify-items: end; text-align: right; }
.notification-history-pagination { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; color: var(--muted); font-size: 11px; }
.notification-history-pagination > div { display: flex; gap: 6px; }
.notification-settings .text-danger { color: var(--danger); }
@media (max-width: 640px) {
  .notification-section-head { flex-direction: column; align-items: stretch; }
  .notification-head-actions { align-self: flex-start; }
  .notification-global-form,
  .notification-target-row,
  .notification-delivery-row { grid-template-columns: minmax(0, 1fr); }
  .notification-global-actions { flex-wrap: wrap; }
  .notification-target-actions { justify-content: flex-start; }
  .notification-target-actions .button,
  .notification-head-actions .button,
  .notification-history-pagination .button { min-height: 40px; }
  .notification-delivery-state { justify-items: start; text-align: left; }
}
</style>

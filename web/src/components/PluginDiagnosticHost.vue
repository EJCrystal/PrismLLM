<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import { ApiError } from '../types/api'
import type {
  ModelTestTarget, PluginChoice, PluginExecution, PluginField, PluginInvocationContext,
  PluginRuntimeState, PluginUiContribution, PluginValue, PluginValues,
} from '../types/api'
import { createLatestRequestGuard } from '../utils/latestRequest'
import { modelTestProtocolLabel, modelTestTargetKey } from '../utils/modelTest'
import { PLUGIN_STATE_CHANGED, pluginFieldDefaults, pluginFormError } from '../utils/pluginForm'
import Icon from './Icon.vue'
import PluginDiagnosticResult from './PluginDiagnosticResult.vue'
import PluginFieldInput from './PluginFieldInput.vue'
import TestEmptyState from './TestEmptyState.vue'
import TestWorkbench from './TestWorkbench.vue'

const props = defineProps<{
  contribution: PluginUiContribution
  context?: PluginInvocationContext
  initialModel?: string
  accountModels?: string[]
  accountProtocols?: string[]
  channelEnabled?: boolean
}>()
const emit = defineEmits<{ (event: 'unavailable'): void }>()

interface TestRow {
  key: string
  context: PluginInvocationContext
  model: string
  keyId: string
  keyName: string
  protocol: string
  values: PluginValues
  running: boolean
  execution: PluginExecution | null
  error: string
  waitingStopped: boolean
}

const targets = ref<ModelTestTarget[]>([])
const state = ref<PluginRuntimeState | null>(null)
const selectedTargetKey = ref('')
const form = reactive<PluginValues>({})
const loading = ref(true)
const loadError = ref('')
const runError = ref('')
const running = ref(false)
const rows = ref<TestRow[]>([])
const workbench = ref<InstanceType<typeof TestWorkbench> | null>(null)
const loadGuard = createLatestRequestGuard()
const runGuard = createLatestRequestGuard()
let loadController: AbortController | null = null
let runController: AbortController | null = null
let disposed = false
let activated = false

const contextKey = computed(() => JSON.stringify([
  props.contribution.pluginId, props.contribution.activeVersion, props.contribution.entry.id,
  props.context?.kind, props.context?.id, props.accountModels, props.accountProtocols,
]))
const fields = computed(() => props.contribution.entry.action.fields)
const selectedTarget = computed(() => targets.value.find((target) => modelTestTargetKey(target.channelId, target.model) === selectedTargetKey.value))
const isAccount = computed(() => props.context?.kind === 'oauth-account')
const batch = computed(() => form.scope === 'all' && props.contribution.entry.action.supportsBatch && !isAccount.value)
const defaultValues = computed(() => pluginFieldDefaults(fields.value, state.value?.activeConfiguration ?? {}))
const formError = computed(() => pluginFormError(fields.value, form))
const canRun = computed(() => !loading.value && !loadError.value && !running.value && !formError.value && targets.value.length > 0 && !!selectedTarget.value && !!form.protocol)

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function syncSelection() {
  const target = selectedTarget.value
  const choices = target?.credentials ?? []
  const protocols = target?.protocols ?? []
  form.model = target?.model ?? ''
  if (fields.value.some((field) => field.kind === 'credential')) {
    if (!choices.some((choice) => choice.id === form.keyId)) form.keyId = choices[0]?.id ?? ''
  }
  if (!protocols.includes(String(form.protocol) as typeof protocols[number])) form.protocol = protocols[0] ?? ''
}

function fieldValue(field: PluginField): PluginValue {
  return field.kind === 'model' ? selectedTargetKey.value : form[field.key] ?? ''
}

function fieldOptions(field: PluginField): PluginChoice[] | undefined {
  if (field.kind === 'model') return targets.value.map((target) => ({
    value: modelTestTargetKey(target.channelId, target.model),
    label: props.context ? target.model : `${target.model} · ${target.channelName}`,
  }))
  if (field.kind === 'credential') return (selectedTarget.value?.credentials ?? []).map((credential) => ({ value: credential.id, label: credential.name }))
  if (field.kind === 'protocol') return (selectedTarget.value?.protocols ?? []).map((protocol) => ({ value: protocol, label: modelTestProtocolLabel(protocol) }))
  if (field.kind === 'scope') return field.options.filter((choice) => !isAccount.value || choice.value === 'single').map((choice) => ({
    ...choice, label: choice.value === 'all' ? `${choice.label}（${targets.value.length}）` : choice.label,
  }))
  return undefined
}

function setField(field: PluginField, value: PluginValue) {
  if (field.kind === 'model') selectedTargetKey.value = String(value ?? '')
  else form[field.key] = value
}

function resetField(field: PluginField) {
  form[field.key] = defaultValues.value[field.key] ?? ''
}

function stopWaiting() {
  runGuard.invalidate()
  runController?.abort()
  runController = null
  for (const row of rows.value) {
    if (!row.execution && !row.error) {
      row.error = row.running ? '已停止等待；已发给上游的请求可能继续执行。' : '已停止队列；此项尚未发送。'
      row.waitingStopped = true
      row.running = false
    }
  }
  running.value = false
}

async function load(reset = true) {
  loadController?.abort()
  const controller = new AbortController()
  loadController = controller
  const key = contextKey.value
  loading.value = true
  loadError.value = ''
  let settled = false
  try {
    const loaded = await loadGuard.run(() => Promise.all([
      api.getPlugin(props.contribution.pluginId, { signal: controller.signal }),
      api.listPluginTargets(props.contribution.pluginId, props.context, { signal: controller.signal }),
    ]))
    if (!loaded.isLatest || disposed || key !== contextKey.value) return
    const [currentState, available] = loaded.value
    if (!currentState.admissionEnabled || currentState.activeVersion !== props.contribution.activeVersion) {
      stopWaiting()
      throw new Error('插件已停用或生效版本已改变，请重新打开诊断入口。')
    }
    state.value = currentState
    targets.value = available.filter((target) => {
      if (props.context && target.channelId !== props.context.id) return false
      if (isAccount.value && props.accountModels && !props.accountModels.includes(target.model)) return false
      return true
    }).map((target) => ({
      ...target,
      protocols: isAccount.value && props.accountProtocols
        ? target.protocols.filter((protocol) => props.accountProtocols!.includes(protocol))
        : target.protocols,
    })).filter((target) => target.protocols.length && target.credentials.length)
    const previous = { ...form }
    for (const fieldKey of Object.keys(form)) delete form[fieldKey]
    Object.assign(form, pluginFieldDefaults(fields.value, currentState.activeConfiguration ?? {}))
    if (!reset) for (const field of fields.value) if (Object.hasOwn(previous, field.key)) form[field.key] = previous[field.key]!
    if (!targets.value.some((target) => modelTestTargetKey(target.channelId, target.model) === selectedTargetKey.value)) {
      const preferred = targets.value.find((target) => target.model === props.initialModel) ?? targets.value[0]
      selectedTargetKey.value = preferred ? modelTestTargetKey(preferred.channelId, preferred.model) : ''
    }
    syncSelection()
    settled = true
  } catch (cause) {
    const latest = (cause as { isLatest?: boolean }).isLatest !== false
    if (!disposed && latest && key === contextKey.value && !controller.signal.aborted) {
      loadError.value = errorMessage(cause)
      settled = true
      emit('unavailable')
    }
  } finally {
    if (settled) loading.value = false
    if (loadController === controller) loadController = null
  }
}

function createRow(target: ModelTestTarget, values: PluginValues): TestRow {
  const credential = target.credentials.find((choice) => choice.id === values.keyId) ?? target.credentials[0]!
  const requestedProtocol = String(values.protocol ?? '')
  const protocol = target.protocols.find((choice) => choice === requestedProtocol) ?? target.protocols[0]!
  const context: PluginInvocationContext = { kind: props.context?.kind ?? 'channel', id: props.context?.id ?? target.channelId }
  const rowValues: PluginValues = { ...values, model: target.model, protocol }
  if (fields.value.some((field) => field.kind === 'credential')) rowValues.keyId = credential.id
  // 批量编排留在浏览器；每次调用依然绑定一个目标。
  if (fields.value.some((field) => field.kind === 'scope')) rowValues.scope = 'single'
  return {
    key: modelTestTargetKey(target.channelId, target.model), context, model: target.model,
    keyId: credential.id, keyName: credential.name, protocol, values: rowValues,
    running: false, execution: null, error: '', waitingStopped: false,
  }
}

async function run() {
  if (!canRun.value) return
  const key = contextKey.value
  const pluginId = props.contribution.pluginId
  const version = props.contribution.activeVersion
  const actionId = props.contribution.entry.action.id
  const all = batch.value
  const values: PluginValues = Object.fromEntries(fields.value.map((field) => [field.key, form[field.key] ?? null]))
  const chosen = (all ? targets.value : [selectedTarget.value!]).map((target) => ({
    ...target, protocols: [...target.protocols], credentials: target.credentials.map((credential) => ({ ...credential })),
  }))
  const controller = new AbortController()
  runController = controller
  running.value = true
  runError.value = ''
  const marker = await runGuard.run(async () => null)
  const isCurrent = () => !disposed && runGuard.isLatest(marker.request) && key === contextKey.value && !controller.signal.aborted
  if (!isCurrent()) return
  try {
    const currentState = await api.getPlugin(pluginId, { signal: controller.signal })
    if (!isCurrent()) return
    if (!currentState.admissionEnabled || currentState.activeVersion !== version) {
      emit('unavailable')
      throw new Error('插件已停用或生效版本已改变，请重新打开诊断入口。')
    }
    state.value = currentState
    rows.value = chosen.map((target) => {
      const targetValues = { ...values }
      if (all && fields.value.some((field) => field.kind === 'credential')) targetValues.keyId = target.credentials[0]!.id
      return createRow(target, targetValues)
    })
    // 使用响应式代理更新批量进度，而不是修改赋值前的原始对象。
    const queue = [...rows.value]
    workbench.value?.showResults()
    const workers = Array.from({ length: Math.min(2, queue.length) }, async () => {
      while (queue.length && isCurrent()) {
        const row = queue.shift()!
        row.running = true
        try {
          const execution = await api.invokePlugin(pluginId, {
            actionId, context: { ...row.context }, values: { ...row.values }, expectedActiveVersion: version,
          }, { signal: controller.signal })
          if (!isCurrent()) return
          if (execution.pluginId !== pluginId || execution.activeVersion !== version ||
            execution.target.kind !== row.context.kind || execution.target.id !== row.context.id ||
            execution.target.model !== row.model || execution.target.protocol !== row.protocol ||
            (row.context.kind === 'channel' && fields.value.some((field) => field.kind === 'credential') && execution.target.credentialId !== row.keyId)) {
            throw new Error('插件响应与本次检测目标不一致，已丢弃。')
          }
          row.execution = execution
        } catch (cause) {
          if (!isCurrent()) return
          row.error = errorMessage(cause)
          if (cause instanceof ApiError && (cause.status === 409 || cause.status === 503)) emit('unavailable')
        } finally {
          if (isCurrent()) row.running = false
        }
      }
    })
    await Promise.all(workers)
    if (!isCurrent()) return
    const failed = rows.value.filter((row) => row.error || !row.execution?.outcome.success).length
    if (all) notify(`${rows.value.length - failed} 个探测成功，${failed} 个失败`, failed ? 'warning' : 'success')
    else {
      const row = rows.value[0]!
      notify(row.error || row.execution?.report.title || '诊断结束', row.error || !row.execution?.outcome.success ? 'error' : 'success')
    }
  } catch (cause) {
    if (isCurrent()) {
      runError.value = errorMessage(cause)
      notify(runError.value, 'error')
    }
  } finally {
    if (isCurrent()) running.value = false
    if (runController === controller) runController = null
  }
}

function refreshState() { void load(false) }
watch(selectedTargetKey, syncSelection)
watch(contextKey, () => {
  stopWaiting()
  rows.value = []
  selectedTargetKey.value = ''
  void load()
})
onMounted(() => {
  void load()
  window.addEventListener(PLUGIN_STATE_CHANGED, refreshState)
})
onActivated(() => { if (activated) void load(false); activated = true })
onDeactivated(stopWaiting)
onBeforeUnmount(() => {
  disposed = true
  loadGuard.invalidate()
  loadController?.abort()
  stopWaiting()
  window.removeEventListener(PLUGIN_STATE_CHANGED, refreshState)
})
</script>

<template>
  <section class="plugin-diagnostic-host">
    <div v-if="loading" class="panel plugin-diagnostic-loading"><span class="spinner"></span><p>正在读取诊断配置与可测试的模型…</p></div>
    <div v-else-if="loadError" class="callout danger plugin-diagnostic-load-error" role="alert">
      <span>{{ loadError }}</span><button type="button" class="link-button" @click="load()">重新读取</button>
    </div>
    <TestEmptyState v-else-if="!targets.length" :icon="contribution.entry.icon" title="没有可测试的模型"
      :description="isAccount ? '请先同步订阅账号目录并配置模型授权。账号诊断使用自己的凭据，不需要选择渠道密钥。' : '请先在渠道中授权模型并添加可用凭据。停用渠道仍可手动诊断。'" />
    <TestWorkbench v-else ref="workbench">
      <template #configuration>
        <div class="test-form">
          <p class="test-form-note"><Icon name="info" :size="14" />{{ contribution.entry.action.label }}</p>
          <p v-if="channelEnabled === false" class="test-form-note">渠道已停用，仍可用已保存的凭据发起诊断。</p>
          <div class="test-form-section">
            <h3 class="test-form-section-title">诊断配置</h3>
            <template v-for="field in fields" :key="field.key">
              <PluginFieldInput v-if="!(batch && (field.kind === 'model' || field.kind === 'credential'))"
                :field="field" :model-value="fieldValue(field)" :options="fieldOptions(field)"
                :disabled="running || (batch && field.kind === 'protocol')" @update:model-value="setField(field, $event)" />
              <div v-if="field.kind === 'textarea'" class="test-form-meta">
                <span>{{ String(form[field.key] ?? '').trim().length }} 字符<span v-if="field.maxLength"> / {{ field.maxLength }}</span></span>
                <button type="button" class="link-button" :disabled="running" @click="resetField(field)">恢复默认</button>
              </div>
            </template>
            <small v-if="batch" class="muted">每个模型选择可用凭据和协议，最多两个请求同时运行。</small>
            <p v-if="formError" class="muted small" role="status">{{ formError }}</p>
            <details v-if="contribution.entry.action.notes.length" class="test-form-help">
              <summary>请求限制与记录说明</summary><p v-for="(note, index) in contribution.entry.action.notes" :key="index">{{ note }}</p>
            </details>
          </div>
          <p class="test-form-note">请求最长 45 秒、最多 128 输出 token；当前生效 {{ contribution.activeVersion }}。每次诊断使用独立的输入与配置快照。</p>
        </div>
      </template>
      <template #actions>
        <div v-if="runError" class="callout danger" role="alert">{{ runError }}</div>
        <button type="button" class="button primary full" :disabled="!canRun" @click="run">
          <Icon :name="running ? 'refresh' : contribution.entry.icon" :size="17" :class="{ 'is-spinning': running }" />
          {{ running ? '正在诊断…' : (batch ? '诊断全部模型' : contribution.entry.action.label) }}
        </button>
        <button v-if="running" type="button" class="button ghost" @click="stopWaiting">停止等待</button>
        <p class="test-action-note">真实上游请求，可能产生费用；不写生产请求日志，结果仅保留在当前面板。</p>
      </template>
      <div class="plugin-diagnostic-results" aria-live="polite">
        <TestEmptyState v-if="!rows.length" :icon="contribution.entry.icon" title="尚未运行测试"
          description="选择目标后开始诊断。关闭面板不会保存这些结果。" :steps="['选择授权目标', '向上游发起请求', '查看报告与响应']" />
        <template v-for="row in rows" :key="row.key">
          <PluginDiagnosticResult v-if="row.execution" :execution="row.execution" />
          <article v-else class="panel plugin-diagnostic-pending">
            <header><strong>{{ row.model }}</strong><span class="badge" :class="row.error && !row.waitingStopped ? 'danger' : 'neutral'">{{ row.running ? '诊断中' : row.error ? (row.waitingStopped ? '已停止等待' : '诊断失败') : '等待中' }}</span></header>
            <small class="muted">{{ row.keyName }} · {{ modelTestProtocolLabel(row.protocol) }}</small>
            <p v-if="row.error" :class="row.waitingStopped ? 'muted' : 'danger-text'">{{ row.error }}</p>
          </article>
        </template>
      </div>
    </TestWorkbench>
  </section>
</template>

<style scoped>
.plugin-diagnostic-host, .plugin-diagnostic-results { display: flex; flex: 1 1 0; flex-direction: column; gap: 14px; min-width: 0; min-height: 0; }
.plugin-diagnostic-loading { display: flex; align-items: center; gap: 12px; padding: 24px; }
.plugin-diagnostic-loading p { margin: 0; color: var(--muted); }
.plugin-diagnostic-load-error { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.plugin-diagnostic-pending { display: grid; flex-shrink: 0; gap: 8px; padding: 16px; overflow-wrap: anywhere; }
.plugin-diagnostic-pending header { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.plugin-diagnostic-pending strong { font-size: 14px; }
.plugin-diagnostic-pending p { margin: 0; font-size: 12px; line-height: 1.6; }
</style>

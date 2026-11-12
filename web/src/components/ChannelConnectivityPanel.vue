<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { Channel, ChannelModelTestResult, ModelTestTarget, ProtocolId } from '../types/api'
import { formatDuration } from '../utils/format'
import {
  buildModelTestRequest,
  formatModelTestUsage,
  modelTestErrorView,
  modelTestProtocolLabel,
  modelTestResultTitle,
  modelTestResultTone,
  normalizeModelTestSettings,
} from '../utils/modelTest'
import Icon from './Icon.vue'
import TestWorkbench from './TestWorkbench.vue'
import TestEmptyState from './TestEmptyState.vue'

const props = defineProps<{
  channel: Channel
  initialModel?: string
}>()

interface TestRow {
  model: string
  keyId: string
  keyName: string
  protocol: string
  running: boolean
  result: ChannelModelTestResult | null
  error: string
}

const targets = ref<ModelTestTarget[]>([])
const selectedModel = ref('')
const selectedKeyId = ref('')
const selectedProtocol = ref<ProtocolId | ''>('')
const prompt = ref('')
const defaultPrompt = ref('')
const loading = ref(true)
const loadError = ref('')
const running = ref(false)
const batch = ref(false)
const rows = ref<TestRow[]>([])
const workbench = ref<InstanceType<typeof TestWorkbench> | null>(null)

const channelTargets = computed(() => targets.value.filter((target) => target.channelId === props.channel.id))
const models = computed(() => channelTargets.value.map((target) => target.model))
const selectedTarget = computed(() => channelTargets.value.find((target) => target.model === selectedModel.value) ?? null)
const credentials = computed(() => selectedTarget.value?.credentials ?? [])
const protocols = computed(() => selectedTarget.value?.protocols ?? props.channel.protocols ?? [])
const canRun = computed(() => {
  if (running.value || loading.value || !prompt.value.trim()) return false
  return batch.value ? models.value.length > 0 : !!selectedTarget.value && !!selectedProtocol.value
})

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

function resultError(result: ChannelModelTestResult | null) {
  return modelTestErrorView(result)
}

function syncSelection() {
  const target = selectedTarget.value
  selectedKeyId.value = target?.credentials[0]?.id ?? ''
  selectedProtocol.value = target?.protocols[0] ?? ''
}

function resetPrompt() {
  prompt.value = defaultPrompt.value
}

function protocolFor(target: ModelTestTarget): string {
  if (selectedProtocol.value && target.protocols.includes(selectedProtocol.value)) return selectedProtocol.value
  return target.protocols[0] ?? ''
}

function createRow(target: ModelTestTarget, keyId: string): TestRow {
  const credential = target.credentials.find((item) => item.id === keyId) ?? target.credentials[0]
  return {
    model: target.model,
    keyId: credential?.id ?? '',
    keyName: credential?.name ?? '',
    protocol: protocolFor(target),
    running: false,
    result: null,
    error: '',
  }
}

async function execute(row: TestRow) {
  row.running = true
  row.result = null
  row.error = ''
  try {
    row.result = await api.testChannelModel(
      props.channel.id,
      buildModelTestRequest({
        model: row.model,
        protocol: row.protocol,
        prompt: prompt.value,
        keyId: row.keyId,
      }),
    )
  } catch (cause) {
    row.error = errorMessage(cause)
  } finally {
    row.running = false
  }
}

async function runSingle() {
  const target = selectedTarget.value
  const keyId = selectedKeyId.value || target?.credentials[0]?.id || ''
  if (!target) return
  const row = createRow(target, keyId)
  rows.value = [row]
  running.value = true
  workbench.value?.showResults()
  try {
    await execute(row)
    notify(row.error ? row.error : modelTestResultTitle(row.result), row.error || !row.result?.success ? 'error' : 'success')
  } finally {
    running.value = false
  }
}

async function runBatch() {
  const rowsToRun = channelTargets.value.map((target) => createRow(target, target.credentials[0]?.id ?? ''))
  rows.value = rowsToRun
  // 队列取 rows.value 里的响应式代理：直接改原始行对象不会触发进度更新。
  const queue = [...rows.value]
  running.value = true
  workbench.value?.showResults()
  try {
    const workers = Array.from({ length: Math.min(2, queue.length) }, async () => {
      while (queue.length) {
        const row = queue.shift()
        if (row) await execute(row)
      }
    })
    await Promise.all(workers)
    const failed = rows.value.filter((row) => row.error || !row.result?.success).length
    notify(
      failed ? `${rows.value.length - failed} 个模型通过，${failed} 个失败` : `${rows.value.length} 个模型全部通过`,
      failed ? 'warning' : 'success',
    )
  } finally {
    running.value = false
  }
}

async function run() {
  if (!canRun.value) return
  if (batch.value) await runBatch()
  else await runSingle()
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [loadedTargets, loadedSettings] = await Promise.all([
      api.listModelTestTargets(),
      api.getModelTestSettings(),
    ])
    targets.value = loadedTargets
    defaultPrompt.value = normalizeModelTestSettings(loadedSettings).defaultPrompt
    prompt.value = defaultPrompt.value
    const preferred = props.initialModel && models.value.includes(props.initialModel)
      ? props.initialModel
      : models.value[0] ?? ''
    selectedModel.value = preferred
    syncSelection()
  } catch (cause) {
    loadError.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

watch(selectedModel, syncSelection)
onMounted(load)
</script>

<template>
  <section class="connectivity-panel">
    <div v-if="loading" class="panel connectivity-loading">
      <span class="spinner"></span>
      <p>正在读取可测试的模型…</p>
    </div>

    <div v-else-if="loadError" class="callout danger connectivity-load-error">
      <span>{{ loadError }}</span>
      <button type="button" class="link-button" @click="load">重试</button>
    </div>

    <TestEmptyState
      v-else-if="!channelTargets.length"
      icon="bolt"
      title="没有可测试的模型"
      description="该渠道还没有授权模型，或所有模型都没有可用凭据。先在渠道中补齐配置后再测试。"
    />

    <TestWorkbench v-else ref="workbench">
      <template #configuration>
        <div class="test-form">
          <p class="test-form-note">
            <Icon name="info" :size="14" />
            检查模型能否正常响应，查看耗时与错误详情。
          </p>
          <p v-if="!channel.enabled" class="test-form-note">渠道已停用，仍可用已保存的凭据发起测试。</p>
          <div class="test-form-section">
            <h3 class="test-form-section-title">检测目标</h3>
            <label class="field">
              <span>测试范围</span>
              <div class="connectivity-scope">
                <label class="connectivity-radio">
                  <input v-model="batch" type="radio" :value="false" :disabled="running" />
                  单个模型
                </label>
                <label class="connectivity-radio">
                  <input v-model="batch" type="radio" :value="true" :disabled="running" />
                  全部模型（{{ models.length }}）
                </label>
              </div>
            </label>

            <label v-if="!batch" class="field">
              <span>模型</span>
              <select v-model="selectedModel" :disabled="running">
                <option v-for="model in models" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>

            <label v-if="!batch" class="field">
              <span>使用凭据</span>
              <select v-model="selectedKeyId" :disabled="running || !credentials.length">
                <option v-if="!credentials.length" value="">没有可用凭据</option>
                <option v-for="credential in credentials" :key="credential.id" :value="credential.id">
                  {{ credential.name }}（优先级 {{ credential.priority }}）
                </option>
              </select>
            </label>

            <label class="field">
              <span>协议</span>
              <select v-model="selectedProtocol" :disabled="running || batch">
                <option v-for="protocol in protocols" :key="protocol" :value="protocol">
                  {{ modelTestProtocolLabel(protocol) }}
                </option>
              </select>
              <small v-if="batch">每个模型使用自己支持的协议；优先使用这里选中的协议。</small>
            </label>
          </div>
          <div class="test-form-section">
            <h3 class="test-form-section-title">请求内容</h3>
            <label class="field">
              <span>测试提示词</span>
              <textarea
                v-model="prompt"
                rows="3"
                maxlength="4000"
                :disabled="running"
                spellcheck="false"
                aria-label="测试提示词"
              ></textarea>
            </label>
            <div class="test-form-meta">
              <span>{{ prompt.trim().length }} 字符 · 单次超时 45 秒</span>
              <button type="button" class="link-button" :disabled="running" @click="resetPrompt">恢复默认</button>
            </div>
            <details class="test-form-help">
              <summary>请求限制与记录说明</summary>
              <p>使用已保存的渠道配置直连上游，每个模型最多生成 128 个输出 token；不写入请求日志。</p>
            </details>
          </div>
        </div>
      </template>
      <template #actions>
        <button type="button" class="button primary full" :disabled="!canRun" @click="run">
          <Icon :name="running ? 'refresh' : 'bolt'" :size="17" :class="{ 'is-spinning': running }" />
          {{ running ? (batch ? '正在逐个测试…' : '正在请求上游…') : (batch ? '测试全部模型' : '开始测试') }}
        </button>
        <p class="test-action-note">真实上游请求，可能产生费用；结果仅保留在当前面板。</p>
      </template>

      <div class="connectivity-results">
        <TestEmptyState
          v-if="!rows.length"
          icon="bolt"
          title="尚未运行测试"
          description="选择模型与凭据后开始。结果只保留在当前面板，关闭后不会写入检测记录。"
          :steps="['选择模型与凭据', '向上游发起请求', '查看响应内容与耗时']"
        />

        <article
          v-for="row in rows"
          :key="row.model"
          class="panel connectivity-result"
          :class="{ 'is-success': row.result?.success, 'is-danger': row.error || (row.result && !row.result.success) }"
        >
          <header class="connectivity-result-head">
            <div class="connectivity-result-title">
              <strong>{{ row.model }}</strong>
              <span class="muted small">{{ row.keyName || '自动凭据' }} · {{ modelTestProtocolLabel(row.protocol) }}</span>
            </div>
            <div class="connectivity-result-badges">
              <span v-if="row.running" class="badge neutral"><span class="spinner tiny"></span>测试中</span>
              <template v-else-if="row.result">
                <span class="badge" :class="modelTestResultTone(row.result)">{{ modelTestResultTitle(row.result) }}</span>
                <span class="badge neutral">HTTP {{ row.result.statusCode ?? '—' }}</span>
                <span class="muted small">{{ formatDuration(row.result.latencyMs) }}</span>
              </template>
              <span v-else-if="row.error" class="badge danger">测试失败</span>
            </div>
          </header>

          <div v-if="row.error" class="callout danger connectivity-error">{{ row.error }}</div>
          <template v-else-if="row.result">
            <div class="connectivity-result-meta">
              <span>上游声明模型 <code>{{ row.result.upstreamReportedModel || '未返回' }}</code></span>
              <span v-if="formatModelTestUsage(row.result.usage)">{{ formatModelTestUsage(row.result.usage) }}</span>
            </div>
            <div v-if="resultError(row.result)" class="connectivity-error-detail">
              <strong>{{ resultError(row.result)?.title }}</strong>
              <p v-if="resultError(row.result)?.hint">{{ resultError(row.result)?.hint }}</p>
              <code v-if="resultError(row.result)?.detail">{{ resultError(row.result)?.detail }}</code>
            </div>
            <details v-if="row.result.responseText" class="connectivity-response">
              <summary>上游响应</summary>
              <pre>{{ row.result.responseText }}</pre>
            </details>
          </template>
        </article>
      </div>
    </TestWorkbench>
  </section>
</template>

<style scoped>
.connectivity-panel,
.connectivity-results {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.connectivity-panel {
  min-height: 0;
}

.connectivity-results > .connectivity-result {
  flex-shrink: 0;
}

.connectivity-scope {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}

.connectivity-radio {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 13px;
}

/* 全局 .field input 会把输入框拉满整行，单选框需要保持原生尺寸。 */
.connectivity-radio input[type='radio'] {
  flex: 0 0 auto;
  width: 16px;
  min-width: 16px;
  height: 16px;
  min-height: 0;
  margin: 0;
  padding: 0;
  accent-color: var(--brand);
}

.connectivity-loading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px;
}

.connectivity-loading p {
  margin: 0;
  color: var(--muted);
}

.connectivity-load-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.connectivity-result {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
}

.connectivity-result.is-success {
  border-color: color-mix(in srgb, var(--success) 30%, var(--glass-line));
}

.connectivity-result.is-danger {
  border-color: color-mix(in srgb, var(--danger) 30%, var(--glass-line));
}

.connectivity-result-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.connectivity-result-title {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.connectivity-result-title strong {
  overflow-wrap: anywhere;
  font-size: 14px;
}

.connectivity-result-badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.connectivity-result-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}

.connectivity-result-meta code {
  overflow-wrap: anywhere;
}

.connectivity-error-detail {
  display: grid;
  gap: 4px;
  overflow-wrap: anywhere;
}

.connectivity-error-detail p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.connectivity-error-detail code {
  justify-self: start;
  padding: 3px 7px;
  border: 1px solid var(--glass-line);
  border-radius: 6px;
  background: var(--glass-field);
  color: var(--muted);
  font-size: 11px;
  overflow-wrap: anywhere;
}

.connectivity-error {
  margin: 0;
}

.connectivity-response summary {
  cursor: pointer;
  color: var(--muted);
  font-size: 12px;
}

.connectivity-response pre {
  max-height: 220px;
  margin: 8px 0 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--glass-line);
  border-radius: 8px;
  background: var(--glass-field);
  color: var(--ink);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.spinner.tiny {
  width: 12px;
  height: 12px;
  margin-right: 5px;
}

.is-spinning {
  animation: spin 0.8s linear infinite;
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning {
    animation: none;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .connectivity-error-detail code,
  .connectivity-response pre {
    background: var(--panel);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }

  .connectivity-error-detail code,
  .connectivity-response pre {
    border-color: var(--line);
  }
}
</style>

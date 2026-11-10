<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { ChannelModelTestResult, ModelTestTarget } from '../types/api'
import {
  buildModelTestRequest,
  formatModelTestUsage,
  modelTestProtocolLabel,
  modelTestResultTitle,
  modelTestResultTone,
  modelTestTargetKey,
  normalizeModelTestSettings,
} from '../utils/modelTest'
import { formatIqLatency } from '../utils/iqTest'
import Icon from './Icon.vue'

const targets = ref<ModelTestTarget[]>([])
const loading = ref(true)
const loadError = ref('')
const running = ref(false)
const result = ref<ChannelModelTestResult | null>(null)
const runError = ref('')
const settings = ref(normalizeModelTestSettings(null))
const selectedTargetKey = ref('')
const resultRef = ref<HTMLElement | null>(null)
const form = reactive({
  keyId: '',
  protocol: '',
  prompt: settings.value.defaultPrompt,
})

const targetGroups = computed(() => {
  const groups = new Map<string, { channelId: string; channelName: string; channelEnabled: boolean; items: ModelTestTarget[] }>()
  for (const target of targets.value) {
    const group = groups.get(target.channelId)
    if (group) {
      group.items.push(target)
    } else {
      groups.set(target.channelId, {
        channelId: target.channelId,
        channelName: target.channelName,
        channelEnabled: target.channelEnabled,
        items: [target],
      })
    }
  }
  return [...groups.values()]
})
const selectedTarget = computed(() =>
  targets.value.find((item) => modelTestTargetKey(item.channelId, item.model) === selectedTargetKey.value) ?? null,
)
const canRun = computed(() =>
  !!selectedTarget.value && !!form.protocol && form.prompt.trim().length > 0 && !running.value && !loading.value,
)
const usageText = computed(() => formatModelTestUsage(result.value?.usage))
const responseText = computed(() => result.value?.responseText?.trim() ?? '')

function syncSelectedTarget() {
  const target = selectedTarget.value
  form.keyId = target?.credentials[0]?.id ?? ''
  form.protocol = target?.protocols[0] ?? ''
}

function resetPrompt() {
  form.prompt = settings.value.defaultPrompt
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

/** 堆叠布局下结果在控制面板下方，完成后把结果带回视口。 */
function scrollResultIntoView() {
  if (!window.matchMedia('(max-width: 960px)').matches) return
  requestAnimationFrame(() => resultRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [loadedTargets, loadedSettings] = await Promise.all([
      api.listModelTestTargets(),
      api.getModelTestSettings(),
    ])
    targets.value = loadedTargets
    settings.value = normalizeModelTestSettings(loadedSettings)
    if (!form.prompt.trim()) form.prompt = settings.value.defaultPrompt
    if (!selectedTargetKey.value && loadedTargets.length) {
      selectedTargetKey.value = modelTestTargetKey(loadedTargets[0].channelId, loadedTargets[0].model)
    }
    syncSelectedTarget()
  } catch (cause) {
    loadError.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function run() {
  const target = selectedTarget.value
  if (!target) return
  running.value = true
  runError.value = ''
  result.value = null
  try {
    result.value = await api.testChannelModel(
      target.channelId,
      buildModelTestRequest({
        model: target.model,
        protocol: form.protocol,
        prompt: form.prompt,
        keyId: form.keyId,
      }),
    )
    notify(result.value.success ? '模型连通性测试成功' : '模型连通性测试失败', result.value.success ? 'success' : 'error')
    scrollResultIntoView()
  } catch (cause) {
    runError.value = errorMessage(cause)
    notify(runError.value, 'error')
  } finally {
    running.value = false
  }
}

watch(selectedTargetKey, () => {
  syncSelectedTarget()
  result.value = null
  runError.value = ''
})
onMounted(loadAll)
</script>

<template>
  <section class="page-stack probe-page">
    <div class="probe-hero panel">
      <div>
        <span class="probe-eyebrow">CONNECTIVITY PROBE</span>
        <h2>用一次真实请求确认渠道可用</h2>
        <p>
          选择渠道、模型与凭据后向上游发送一条短提示词。请求不经过数据面，不写入请求日志，
          也不消耗调用方密钥额度；响应会原样展示，方便排查鉴权、协议与地址问题。
        </p>
      </div>
      <div class="probe-note">
        <Icon name="bolt" :size="18" />
        <span>默认 45 秒超时</span>
        <small>上游的 HTTP 状态、实际耗时与 token 用量都会显示在结果里。</small>
      </div>
    </div>

    <div v-if="loading" class="panel probe-loading">
      <span class="spinner"></span>
      <p>正在读取可测试的渠道与模型…</p>
    </div>

    <div v-else-if="loadError" class="callout danger probe-load-error">
      <span>{{ loadError }}</span>
      <button type="button" class="link-button" @click="loadAll">重试</button>
    </div>

    <div v-else class="probe-layout">
      <aside class="probe-control panel">
        <div class="probe-section">
          <span class="probe-section-label">检测目标</span>
          <label v-if="targets.length" class="field">
            <span>渠道与模型</span>
            <select v-model="selectedTargetKey" :disabled="running">
              <optgroup
                v-for="group in targetGroups"
                :key="group.channelId"
                :label="group.channelEnabled ? group.channelName : `${group.channelName}（已停用）`"
              >
                <option
                  v-for="target in group.items"
                  :key="modelTestTargetKey(target.channelId, target.model)"
                  :value="modelTestTargetKey(target.channelId, target.model)"
                >
                  {{ target.model }} · {{ target.credentials.length }} 个凭据
                </option>
              </optgroup>
            </select>
          </label>
          <p v-else class="probe-empty-copy">
            还没有可测试的模型。请先在「渠道」中授权模型，并至少配置一个可用凭据。
          </p>

          <div v-if="selectedTarget" class="probe-target-grid">
            <label class="field">
              <span>使用凭据</span>
              <select v-model="form.keyId" :disabled="running">
                <option v-for="credential in selectedTarget.credentials" :key="credential.id" :value="credential.id">
                  {{ credential.name }}（优先级 {{ credential.priority }}）
                </option>
              </select>
            </label>
            <label class="field">
              <span>协议</span>
              <select v-model="form.protocol" :disabled="running">
                <option v-for="protocol in selectedTarget.protocols" :key="protocol" :value="protocol">
                  {{ modelTestProtocolLabel(protocol) }}
                </option>
              </select>
            </label>
          </div>

          <div v-if="selectedTarget" class="probe-target-meta">
            <span class="badge neutral">{{ selectedTarget.channelName }}</span>
            <span v-if="!selectedTarget.channelEnabled" class="badge warning">渠道已停用</span>
          </div>
        </div>

        <div class="probe-section">
          <div class="probe-section-head">
            <span class="probe-section-label">测试提示词</span>
            <button type="button" class="link-button" :disabled="running" @click="resetPrompt">恢复默认</button>
          </div>
          <textarea
            v-model="form.prompt"
            class="probe-prompt"
            rows="7"
            maxlength="4000"
            :disabled="running"
            spellcheck="false"
            aria-label="测试提示词"
          ></textarea>
          <p class="probe-prompt-meta">{{ form.prompt.trim().length.toLocaleString() }} 字符 · 默认提示词来自「系统配置 → 模型测试」</p>
        </div>

        <button type="button" class="button primary full probe-run" :disabled="!canRun" @click="run">
          <Icon :name="running ? 'refresh' : 'bolt'" :size="17" :class="{ 'is-spinning': running }" />
          {{ running ? '正在请求上游…' : '开始测试' }}
        </button>
        <p class="probe-run-note">测试只发往当前选择的渠道；失败原因来自上游响应，不记录请求正文。</p>
      </aside>

      <div class="probe-main">
        <div v-if="runError" class="callout danger probe-run-error">
          <span>{{ runError }}</span>
          <button type="button" class="link-button" :disabled="!canRun" @click="run">重试</button>
        </div>

        <section v-if="running" class="panel probe-running">
          <span class="spinner"></span>
          <div>
            <strong>{{ selectedTarget?.model }}</strong>
            <p>正在等待上游返回；页面保持打开即可。</p>
          </div>
        </section>

        <section v-else-if="result" ref="resultRef" class="panel probe-result">
          <header class="probe-result-head">
            <div class="probe-result-title">
              <span class="badge" :class="modelTestResultTone(result)">{{ modelTestResultTitle(result) }}</span>
              <h3>{{ result.model }}</h3>
            </div>
            <div class="probe-result-meta">
              <span>HTTP {{ result.statusCode ?? '—' }}</span>
              <span>{{ formatIqLatency(result.latencyMs) }}</span>
              <span>{{ modelTestProtocolLabel(result.protocol) }}</span>
            </div>
          </header>

          <div class="probe-result-grid">
            <div><span>渠道</span><strong>{{ selectedTarget?.channelName || '—' }}</strong></div>
            <div><span>凭据</span><strong>{{ selectedTarget?.credentials.find((item) => item.id === form.keyId)?.name || '—' }}</strong></div>
            <div><span>上游声明模型</span><strong>{{ result.upstreamReportedModel || '未返回' }}</strong></div>
            <div><span>用量</span><strong>{{ usageText || '未返回' }}</strong></div>
          </div>

          <p v-if="result.errorMessage" class="callout danger probe-error">{{ result.errorMessage }}</p>

          <div class="probe-response">
            <div class="probe-section-head">
              <span class="probe-section-label">上游响应</span>
              <span class="muted small">{{ responseText.length.toLocaleString() }} 字符</span>
            </div>
            <pre v-if="responseText" class="probe-response-text">{{ responseText }}</pre>
            <p v-else class="muted small">上游没有返回文本内容。</p>
          </div>
        </section>

        <section v-else class="panel probe-intro">
          <Icon name="shield" :size="22" />
          <h3>尚未运行检测</h3>
          <p>在左侧选择渠道与模型，确认凭据和协议后开始测试。预期返回一条简短的正常回复；若鉴权或地址有误，会直接显示上游错误。</p>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.probe-page {
  max-width: 1240px;
  margin: 0 auto;
}

.probe-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 24px;
  background:
    radial-gradient(circle at 8% 0%, rgba(37, 99, 235, 0.12), transparent 42%),
    var(--panel);
}

.probe-eyebrow {
  color: var(--brand);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.probe-hero h2 {
  margin: 6px 0 8px;
  font-size: 22px;
  line-height: 1.25;
}

.probe-hero p {
  max-width: 760px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}

.probe-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  flex: 0 0 auto;
  gap: 6px;
  max-width: 250px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg);
  color: var(--muted);
  font-size: 12px;
}

.probe-note span {
  color: var(--ink);
  font-weight: 650;
}

.probe-note small {
  flex-basis: 100%;
  line-height: 1.5;
}

.probe-layout {
  display: grid;
  grid-template-columns: minmax(280px, 330px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.probe-control {
  position: sticky;
  top: 16px;
  display: grid;
  gap: 18px;
  padding: 18px;
}

.probe-section {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.probe-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.probe-section-label {
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.probe-target-grid {
  display: grid;
  gap: 10px;
}

.probe-target-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.probe-prompt {
  width: 100%;
  min-height: 140px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  color: var(--ink);
  font: inherit;
  line-height: 1.55;
  resize: vertical;
}

.probe-prompt-meta {
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.5;
}

.probe-empty-copy {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.probe-run {
  min-height: 42px;
}

.is-spinning {
  animation: spin 0.8s linear infinite;
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning {
    animation: none;
  }
}

.probe-run-note {
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.probe-main {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.probe-loading,
.probe-running {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px;
}

.probe-running p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.probe-intro {
  display: grid;
  place-items: center;
  gap: 8px;
  min-height: 260px;
  padding: 32px;
  text-align: center;
}

.probe-intro h3 {
  margin: 4px 0 0;
}

.probe-intro p {
  max-width: 520px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.probe-result {
  display: grid;
  gap: 16px;
  padding: 20px;
}

.probe-result-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.probe-result-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.probe-result-title h3 {
  margin: 0;
  font-size: 17px;
}

.probe-result-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}

.probe-result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg);
}

.probe-result-grid div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.probe-result-grid span {
  color: var(--muted);
  font-size: 11.5px;
}

.probe-result-grid strong {
  overflow-wrap: anywhere;
  font-size: 13px;
}

.probe-error {
  margin: 0;
}

.probe-response {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.probe-response-text {
  max-height: 420px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  color: var(--ink);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.probe-load-error,
.probe-run-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

@media (max-width: 960px) {
  .probe-hero {
    flex-direction: column;
    align-items: flex-start;
  }

  .probe-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .probe-control {
    position: static;
  }
}
</style>
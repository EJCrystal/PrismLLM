<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type {
  IqBenchmarkDefinition,
  IqBenchmarkId,
  IqJudgeFailure,
  IqJudgeInfo,
  IqTestRunDetail,
  IqTestRunSummary,
  IqTestTarget,
  ProtocolId,
} from '../types/api'
import {
  formatIqLatency,
  formatIqTime,
  iqTargetKey,
  iqVerdictTone,
  withIqPreviewCsp,
} from '../utils/iqTest'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'

const benchmarks = ref<IqBenchmarkDefinition[]>([])
const targets = ref<IqTestTarget[]>([])
const judges = ref<IqJudgeInfo[]>([])
const judgeFailures = ref<IqJudgeFailure[]>([])
const history = ref<IqTestRunSummary[]>([])
const selectedBenchmark = ref<IqBenchmarkId>('pelican')
const selectedTargetKey = ref('')
const selectedKeyId = ref('')
const selectedProtocol = ref('')
const selectedJudgeId = ref('')
const historyFilter = ref<'all' | IqBenchmarkId>('all')
const promptText = ref('')
const loading = ref(true)
const loadingHistory = ref(false)
const running = ref(false)
const detailLoading = ref(false)
const detail = ref<IqTestRunDetail | null>(null)
const previewMode = ref<'preview' | 'source'>('preview')
const loadError = ref('')
const runError = ref('')
const elapsed = ref(0)
const manualSubmitting = ref(false)
const resultRef = ref<HTMLElement | null>(null)
const manualForm = reactive<{
  verdict: '' | 'passed' | 'incorrect'
  score: number | null
  note: string
}>({
  verdict: '',
  score: null,
  note: '',
})
let timer: ReturnType<typeof setInterval> | null = null

const benchmark = computed(() =>
  benchmarks.value.find((item) => item.id === selectedBenchmark.value) ?? benchmarks.value[0] ?? null,
)
const selectedTarget = computed(() =>
  targets.value.find((item) => iqTargetKey(item.channelId, item.model) === selectedTargetKey.value) ?? null,
)
const selectedJudge = computed(() =>
  judges.value.find((item) => item.id === selectedJudgeId.value) ?? null,
)
const promptCustomized = computed(() =>
  !!benchmark.value && promptText.value.trim() !== benchmark.value.prompt.trim(),
)
const targetGroups = computed(() => {
  const groups = new Map<string, {
    channelId: string
    channelName: string
    channelEnabled: boolean
    items: IqTestTarget[]
  }>()
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
const filteredHistory = computed(() =>
  historyFilter.value === 'all'
    ? history.value
    : history.value.filter((run) => run.benchmark === historyFilter.value),
)
const canRun = computed(() =>
  !!selectedTarget.value &&
  !!benchmark.value &&
  (selectedBenchmark.value === 'candy' || !!selectedJudgeId.value) &&
  !running.value &&
  !loading.value,
)
const passedCheckCount = computed(() => detail.value?.checks.filter((check) => check.passed).length ?? 0)
const previewHtml = computed(() => (detail.value?.html ? withIqPreviewCsp(detail.value.html) : ''))
const sourceText = computed(() => detail.value?.html || detail.value?.responseText || '')
// 旧版糖果记录带有 HTML 或判分器结果；新记录不再调用判分器。
const legacyCandy = computed(
  () => detail.value?.benchmark === 'candy' && (!!detail.value?.html || !!detail.value?.judgeResult),
)

const protocolLabels: Record<string, string> = {
  chat_completions: 'Chat Completions',
  responses: 'Responses',
  messages: 'Messages',
  gemini: 'Gemini',
}

function protocolLabel(value: string): string {
  return protocolLabels[value] ?? value
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
}

function usageSummary(run: IqTestRunSummary | IqTestRunDetail | null): string {
  const usage = run?.usage
  if (!usage) return '上游未返回用量'
  const parts: string[] = []
  if (usage.inputTokens != null) parts.push(`输入 ${usage.inputTokens.toLocaleString()}`)
  if (usage.outputTokens != null) parts.push(`输出 ${usage.outputTokens.toLocaleString()}`)
  if (usage.reasoningTokens != null) parts.push(`推理 ${usage.reasoningTokens.toLocaleString()}`)
  if (usage.totalTokens != null) parts.push(`合计 ${usage.totalTokens.toLocaleString()}`)
  return parts.length ? parts.join(' · ') : '上游未返回用量'
}

function syncSelectedTarget() {
  const target = selectedTarget.value
  if (!target) {
    selectedKeyId.value = ''
    selectedProtocol.value = ''
    return
  }
  if (!target.credentials.some((credential) => credential.id === selectedKeyId.value)) {
    selectedKeyId.value = target.credentials[0]?.id ?? ''
  }
  if (selectedProtocol.value && !target.protocols.includes(selectedProtocol.value as ProtocolId)) {
    selectedProtocol.value = ''
  }
}

function startTimer() {
  stopTimer()
  elapsed.value = 0
  const startedAt = Date.now()
  timer = setInterval(() => {
    elapsed.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function loadHistory() {
  loadingHistory.value = true
  try {
    history.value = await api.listIqRuns(50)
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    loadingHistory.value = false
  }
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [definitions, targetList, judgeResponse] = await Promise.all([
      api.listIqBenchmarks(),
      api.listIqTargets(),
      api.listIqJudges(),
    ])
    benchmarks.value = definitions
    targets.value = targetList
    judges.value = judgeResponse.judges
    judgeFailures.value = judgeResponse.failures
    if (!judges.value.some((judge) => judge.id === selectedJudgeId.value)) {
      selectedJudgeId.value = judgeResponse.defaultJudgeId || judges.value[0]?.id || ''
    }
    if (!selectedTargetKey.value && targetList.length) {
      selectedTargetKey.value = iqTargetKey(targetList[0].channelId, targetList[0].model)
    }
    syncSelectedTarget()
    await loadHistory()
    if (!detail.value && history.value.length) {
      await openRun(history.value[0], { scroll: false })
    }
  } catch (cause) {
    loadError.value = errorMessage(cause)
    notify(loadError.value, 'error')
  } finally {
    loading.value = false
  }
}

function resetPrompt() {
  promptText.value = benchmark.value?.prompt ?? ''
}

const STACKED_LAYOUT_QUERY = '(max-width: 1360px)'

/** 宽屏时历史在独立侧栏，详情始终可见；堆叠布局下才需要把详情滚回视口。 */
function scrollResultIntoView() {
  if (!window.matchMedia(STACKED_LAYOUT_QUERY).matches) return
  requestAnimationFrame(() => {
    resultRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

async function run() {
  const target = selectedTarget.value
  const item = benchmark.value
  const judgeId = selectedJudgeId.value
  if (!target || !item || (item.id !== 'candy' && !judgeId)) return
  running.value = true
  runError.value = ''
  previewMode.value = 'preview'
  startTimer()
  let completed = false
  try {
    const result = await api.runIqTest({
      channelId: target.channelId,
      model: target.model,
      benchmark: item.id,
      keyId: selectedKeyId.value || null,
      protocol: (selectedProtocol.value || null) as ProtocolId | null,
      judgeId: item.id === 'candy' ? null : judgeId,
      prompt: promptText.value.trim() || undefined,
    })
    detail.value = result
    syncManualForm(result)
    completed = true
    await loadHistory()
    notify(
      `${item.label}：${result.verdictLabel}`,
      result.verdict === 'passed' ? 'success' : result.verdict === 'failed' ? 'error' : 'warning',
    )
  } catch (cause) {
    runError.value = errorMessage(cause)
    notify(runError.value, 'error')
  } finally {
    running.value = false
    stopTimer()
    if (completed) scrollResultIntoView()
  }
}

async function openRun(run: IqTestRunSummary, options: { scroll?: boolean } = {}) {
  if (detailLoading.value) return
  detailLoading.value = true
  runError.value = ''
  previewMode.value = 'preview'
  try {
    detail.value = await api.getIqRun(run.id)
    selectedBenchmark.value = detail.value.benchmark
    const judgeId = detail.value.judgeResult?.judgeId
    if (judgeId && judges.value.some((judge) => judge.id === judgeId)) {
      selectedJudgeId.value = judgeId
    }
    syncManualForm(detail.value)
    const targetKey = iqTargetKey(detail.value.channelId, detail.value.model)
    if (targets.value.some((target) => iqTargetKey(target.channelId, target.model) === targetKey)) {
      selectedTargetKey.value = targetKey
      selectedKeyId.value = detail.value.channelKeyId || ''
      selectedProtocol.value = detail.value.protocol
      syncSelectedTarget()
    }
    if (options.scroll !== false) scrollResultIntoView()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    detailLoading.value = false
  }
}

function syncManualForm(run: IqTestRunDetail) {
  manualForm.verdict = run.manualVerdict ?? ''
  manualForm.score = run.manualScore
  manualForm.note = run.manualNote ?? ''
}

async function submitManual() {
  if (!detail.value || detail.value.benchmark !== 'pelican' || !manualForm.verdict) return
  manualSubmitting.value = true
  try {
    detail.value = await api.submitIqManualVerdict(detail.value.id, {
      verdict: manualForm.verdict,
      score: manualForm.score,
      note: manualForm.note.trim() || null,
    })
    syncManualForm(detail.value)
    await loadHistory()
    notify('人工判定已保存')
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    manualSubmitting.value = false
  }
}

watch(selectedTargetKey, syncSelectedTarget)
watch(benchmark, (item) => {
  promptText.value = item?.prompt ?? ''
}, { immediate: true })

onMounted(loadAll)
onBeforeUnmount(stopTimer)
</script>

<template>
  <section class="page-stack iq-page">
    <div class="iq-hero panel">
      <div class="iq-hero-copy">
        <span class="iq-eyebrow">MODEL IQ PROBE</span>
        <h2>用两道内置题看模型表现</h2>
        <p>
          鹈鹕骑行与糖果测试来自社区常用的“降智检测”题。测试通过已保存的渠道凭据直接请求上游，
          不写入请求日志，也不消耗调用方密钥额度。
        </p>
      </div>
      <div class="iq-hero-note">
        <Icon name="shield" :size="18" />
        <span>本地生成 · 判分器可选</span>
        <small>local 判分不出本机；满血AI、视觉判分或外部插件会把 HTML/逐帧截图发到对应服务，渠道密钥永不发送</small>
      </div>
    </div>

    <div v-if="loading" class="panel iq-loading">
      <span class="spinner"></span>
      <p>正在读取可用模型与题目…</p>
    </div>

    <div v-else-if="loadError" class="callout danger iq-load-error">
      <span>{{ loadError }}</span>
      <button type="button" class="link-button" @click="loadAll">重试</button>
    </div>

    <div v-else class="iq-layout">
      <aside class="iq-control panel">
        <div class="iq-section">
          <span class="iq-section-label">检测题目</span>
          <div class="iq-benchmark-list">
            <button
              v-for="item in benchmarks"
              :key="item.id"
              type="button"
              class="iq-benchmark-card"
              :class="{ active: selectedBenchmark === item.id }"
              :disabled="running"
              @click="selectedBenchmark = item.id"
            >
              <span class="iq-benchmark-icon">
                <Icon :name="item.id === 'pelican' ? 'image' : 'candy'" :size="18" />
              </span>
              <span class="iq-benchmark-copy">
                <strong>{{ item.label }}</strong>
                <small>{{ item.summary }}</small>
              </span>
              <span v-if="selectedBenchmark === item.id" class="iq-benchmark-check">
                <Icon name="check" :size="14" />
              </span>
            </button>
          </div>
          <div v-if="benchmark" class="iq-prompt-editor">
            <div class="iq-section-head">
              <span class="iq-section-label">提示词</span>
              <button
                type="button"
                class="link-button"
                :disabled="running || !promptCustomized"
                @click="resetPrompt"
              >
                恢复默认
              </button>
            </div>
            <textarea
              v-model="promptText"
              class="iq-prompt-input"
              rows="9"
              maxlength="20000"
              :disabled="running"
              spellcheck="false"
              aria-label="检测提示词"
            ></textarea>
            <p class="iq-prompt-meta">
              {{ promptText.trim().length.toLocaleString() }} 字符
              <template v-if="selectedBenchmark === 'candy'">
                · 糖果题按 JSON 的 answer 等于 21 判定，修改题目后判分可能不再适用
              </template>
              <template v-else>· 留空则使用内置默认提示词</template>
            </p>
            <p class="iq-grading"><strong>判分：</strong>{{ benchmark.grading }}</p>
          </div>
        </div>

        <div class="iq-section">
          <span class="iq-section-label">检测目标</span>
          <label v-if="targets.length" class="field">
            <span>渠道与模型</span>
            <select v-model="selectedTargetKey" :disabled="running" @change="syncSelectedTarget">
              <optgroup
                v-for="group in targetGroups"
                :key="group.channelId"
                :label="group.channelEnabled ? group.channelName : `${group.channelName}（已停用）`"
              >
                <option
                  v-for="target in group.items"
                  :key="iqTargetKey(target.channelId, target.model)"
                  :value="iqTargetKey(target.channelId, target.model)"
                >
                  {{ target.model }} · {{ target.credentials.length }} 个凭据
                </option>
              </optgroup>
            </select>
          </label>
          <p v-else class="iq-no-target">
            还没有可测试的模型。请先在「渠道」中授权模型，并至少配置一个可用凭据。
          </p>

          <div v-if="selectedTarget" class="iq-target-grid">
            <label class="field">
              <span>使用凭据</span>
              <select v-model="selectedKeyId" :disabled="running || selectedTarget.credentials.length < 2">
                <option v-for="credential in selectedTarget.credentials" :key="credential.id" :value="credential.id">
                  {{ credential.name }}（优先级 {{ credential.priority }}）
                </option>
              </select>
            </label>
            <label class="field">
              <span>协议</span>
              <select v-model="selectedProtocol" :disabled="running">
                <option value="">自动</option>
                <option v-for="protocol in selectedTarget.protocols" :key="protocol" :value="protocol">
                  {{ protocolLabel(protocol) }}
                </option>
              </select>
            </label>
          </div>

          <div v-if="selectedTarget" class="iq-target-meta">
            <span class="badge neutral">{{ selectedTarget.channelName }}</span>
            <span v-if="!selectedTarget.channelEnabled" class="badge warning">渠道已停用</span>
            <span class="muted small">
              {{ selectedTarget.credentials.length }} 个可用凭据 · {{ selectedTarget.protocols.length }} 种协议
            </span>
          </div>
        </div>

        <div class="iq-section">
          <span class="iq-section-label">判分器</span>
          <p v-if="selectedBenchmark === 'candy'" class="iq-judge-description">
            糖果题只读取模型返回的 JSON 答案，不调用 HTML 判分器。
          </p>
          <template v-else>
            <label v-if="judges.length" class="field">
              <span>鹈鹕判分器</span>
              <select v-model="selectedJudgeId" :disabled="running">
                <option v-for="judge in judges" :key="judge.id" :value="judge.id">
                  {{ judge.label }}{{ judge.builtIn ? '' : '（插件）' }}
                </option>
              </select>
            </label>
            <p v-if="selectedJudge?.description" class="iq-judge-description">{{ selectedJudge.description }}</p>
            <p v-if="selectedJudge?.id === 'manxue'" class="callout warning iq-privacy-warning">
              选择满血AI 时，生成的 HTML 会发送到 manxue.ai；渠道密钥不会离开本机。
            </p>
            <p v-else-if="selectedJudge?.id === 'vision'" class="callout warning iq-privacy-warning">
              选择视觉判分器时，会把无头浏览器渲染的 6 帧截图发送到网关配置的视觉端点；渠道密钥不会发送。
            </p>
            <p v-else-if="selectedJudge && !selectedJudge.builtIn" class="callout warning iq-privacy-warning">
              外部插件「{{ selectedJudge.label }}」会收到生成的 HTML，并可能发送到其配置的服务；请确认插件来源可信。
            </p>
            <details v-if="judgeFailures.length" class="iq-judge-failures">
              <summary>{{ judgeFailures.length }} 个判分器插件加载失败</summary>
              <ul>
                <li v-for="failure in judgeFailures" :key="failure.source + failure.message">
                  {{ failure.source }}：{{ failure.message }}
                </li>
              </ul>
            </details>
            <p v-if="!judges.length" class="iq-no-target">没有可用判分器，请检查插件目录或配置。</p>
          </template>
        </div>

        <button type="button" class="button primary full iq-run-button" :disabled="!canRun" @click="run">
          <Icon :name="running ? 'refresh' : 'bolt'" :size="17" :class="{ 'is-spinning': running }" />
          {{ running ? `正在检测 · ${elapsed} 秒` : '开始测试' }}
        </button>
        <p class="iq-run-note">
          {{ benchmark?.id === 'pelican' ? '图形题通常需要 30–120 秒，超时 180 秒。' : '推理题通常需要 10–60 秒，超时 120 秒。' }}
          请保持页面打开；完成后会自动写入检测历史。
        </p>
      </aside>

      <div class="iq-main">
        <div v-if="runError" class="callout danger iq-run-error">
          <span>{{ runError }}</span>
          <button type="button" class="link-button" :disabled="!canRun" @click="run">重试</button>
        </div>

        <section v-if="running" class="panel iq-running">
          <span class="iq-running-spinner"></span>
          <div>
            <strong>{{ benchmark?.label }} · {{ selectedTarget?.model }}</strong>
            <p>正在等待上游返回，已等待 {{ elapsed }} 秒。</p>
          </div>
        </section>

        <section v-else-if="detailLoading" class="panel iq-loading">
          <span class="spinner"></span>
          <p>正在读取检测详情…</p>
        </section>

        <section v-else-if="detail" ref="resultRef" class="panel iq-result">
          <header class="iq-result-head">
            <div class="iq-result-title">
              <span class="badge" :class="iqVerdictTone(detail.verdict)">{{ detail.verdictLabel }}</span>
              <h3>{{ detail.benchmarkLabel }}</h3>
              <small>{{ detail.model }}</small>
            </div>
            <div class="iq-result-meta">
              <span>HTTP {{ detail.statusCode ?? '—' }}</span>
              <span>{{ formatIqLatency(detail.latencyMs) }}</span>
              <span>{{ formatIqTime(detail.createdAt) }}</span>
            </div>
          </header>

          <div class="iq-result-summary">
            <div><span class="muted small">渠道</span><strong>{{ detail.channelName }}</strong></div>
            <div><span class="muted small">凭据</span><strong>{{ detail.channelKeyName || '—' }}</strong></div>
            <div><span class="muted small">协议</span><strong>{{ protocolLabel(detail.protocol) }}</strong></div>
            <div><span class="muted small">上游声明模型</span><strong>{{ detail.upstreamReportedModel || '未返回' }}</strong></div>
          </div>

          <details v-if="detail.prompt" class="iq-used-prompt">
            <summary>本次使用的提示词</summary>
            <pre class="iq-prompt">{{ detail.prompt }}</pre>
          </details>

          <div v-if="detail.errorMessage" class="callout danger">{{ detail.errorMessage }}</div>

          <div
            v-if="detail.judgeResult"
            class="iq-external"
            :class="`tone-${iqVerdictTone(detail.judgeResult.verdict)}`"
          >
            <div class="iq-section-head">
              <strong>{{ detail.benchmark === 'pelican' ? '判分结果' : '判分器参考' }}</strong>
              <span class="badge" :class="iqVerdictTone(detail.judgeResult.verdict)">
                {{ detail.judgeResult.label }}
              </span>
            </div>
            <p>
              <strong>{{ detail.judgeResult.judgeLabel }}</strong>
              <template v-if="detail.judgeResult.score != null"> · {{ detail.judgeResult.score }} / 100</template>
              <template v-if="detail.judgeResult.reason"> · {{ detail.judgeResult.reason }}</template>
            </p>
            <small v-if="detail.judgeResult.checkedAt">判分时间：{{ formatIqTime(detail.judgeResult.checkedAt) }}</small>
            <details
              v-if="Object.keys(detail.judgeResult.details).length"
              class="iq-judge-details"
              :open="detail.judgeResult.judgeId === 'vision'"
            >
              <summary>逐项检测</summary>
              <dl>
                <template v-for="(value, key) in detail.judgeResult.details" :key="key">
                  <dt>{{ key }}</dt>
                  <dd>{{ value }}</dd>
                </template>
              </dl>
            </details>
          </div>

          <div v-if="detail.html" class="iq-preview">
            <div class="iq-preview-bar">
              <div class="iq-segmented">
                <button type="button" :class="{ active: previewMode === 'preview' }" @click="previewMode = 'preview'">预览</button>
                <button type="button" :class="{ active: previewMode === 'source' }" @click="previewMode = 'source'">HTML</button>
              </div>
              <span class="muted small">{{ detail.html.length.toLocaleString() }} 字符 · 已阻止外部资源和脚本</span>
              <CopyButton :text="sourceText" title="复制 HTML" variant="subtle" />
            </div>
            <iframe
              v-if="previewMode === 'preview'"
              class="iq-frame"
              :srcdoc="previewHtml"
              sandbox=""
              referrerpolicy="no-referrer"
              title="模型作品预览"
            ></iframe>
            <pre v-else class="iq-source">{{ detail.html }}</pre>
          </div>
          <div v-else class="iq-raw">
            <div class="iq-section-head">
              <strong>原始响应</strong>
              <CopyButton :text="detail.responseText || ''" title="复制原始响应" variant="subtle" />
            </div>
            <pre class="iq-source">{{ detail.responseText || '上游未返回文本' }}</pre>
          </div>

          <div v-if="detail.benchmark === 'pelican' && detail.html" class="iq-manual">
            <div class="iq-section-head">
              <strong>人工判定</strong>
              <span v-if="detail.manualVerdict" class="badge info">已人工判定</span>
            </div>
            <div class="iq-manual-grid">
              <label class="field">
                <span>结论</span>
                <select v-model="manualForm.verdict" :disabled="manualSubmitting">
                  <option value="">不修改</option>
                  <option value="passed">通过</option>
                  <option value="incorrect">未通过</option>
                </select>
              </label>
              <label class="field">
                <span>评分</span>
                <select v-model="manualForm.score" :disabled="manualSubmitting">
                  <option :value="null">不评分</option>
                  <option v-for="score in [1, 2, 3, 4, 5]" :key="score" :value="score">{{ score }} / 5</option>
                </select>
              </label>
            </div>
            <label class="field">
              <span>备注</span>
              <textarea v-model="manualForm.note" rows="2" maxlength="500" placeholder="可选" :disabled="manualSubmitting"></textarea>
            </label>
            <button type="button" class="button primary mini" :disabled="manualSubmitting || !manualForm.verdict" @click="submitManual">
              {{ manualSubmitting ? '保存中…' : '保存人工判定' }}
            </button>
            <p v-if="detail.manualUpdatedAt" class="muted small">上次人工判定：{{ formatIqTime(detail.manualUpdatedAt) }}</p>
          </div>

          <template v-if="detail.benchmark === 'pelican'">
            <div class="iq-checks">
              <div class="iq-section-head">
                <strong>本地辅助检查</strong>
                <span class="muted small">{{ passedCheckCount }}/{{ detail.checks.length }} 项通过</span>
              </div>
              <ul>
                <li v-for="check in detail.checks" :key="check.id" :class="{ passed: check.passed }">
                  <Icon :name="check.passed ? 'check' : 'close'" :size="14" />
                  <span>{{ check.label }}</span>
                  <small v-if="check.detail">{{ check.detail }}</small>
                </li>
              </ul>
              <p class="iq-caveat">
                {{ detail.judgeResult ? `本次由「${detail.judgeResult.judgeLabel}」判分；` : '' }}本地检查只判断 HTML、SVG、动画和资源依赖，不判断画得像不像。
              </p>
            </div>
          </template>

          <template v-else>
            <div class="iq-candy-layout">
              <div class="iq-candy-answer">
                <div class="iq-section-head">
                  <strong>判分依据</strong>
                  <span class="muted small">{{ passedCheckCount }}/{{ detail.checks.length }} 项通过</span>
                </div>
                <p v-if="legacyCandy">历史记录按旧版规则判定：HTML 可见文字中出现独立的 <strong>21</strong> 即通过。</p>
                <p v-else>读取模型返回的 JSON，<code>answer</code> 等于 <strong>21</strong> 即通过。</p>
                <div class="iq-checks">
                  <ul>
                    <li v-for="check in detail.checks" :key="check.id" :class="{ passed: check.passed }">
                      <Icon :name="check.passed ? 'check' : 'close'" :size="14" />
                      <span>{{ check.label }}</span>
                      <small v-if="check.detail">{{ check.detail }}</small>
                    </li>
                  </ul>
                </div>
                <p v-if="legacyCandy" class="iq-caveat">旧记录保留当时的判分结果，不会按新规则重算。</p>
                <p v-else class="iq-caveat">JSON 无法解析或 answer 不是整数时判定为失败；不评价 reason 的推理质量。</p>
              </div>
              <aside v-if="detail.success && detail.expectedAnswer != null" class="iq-candy-grade">
                <span class="muted small">本题判定</span>
                <strong :class="detail.verdict === 'passed' ? 'iq-pass-text' : 'iq-fail-text'">{{ detail.verdictLabel }}</strong>
                <p>期望答案：<strong>{{ detail.expectedAnswer }}</strong></p>
                <p v-if="legacyCandy">回答中{{ detail.matchedAnswer != null ? '出现' : '未出现' }}独立数字 21。</p>
                <p v-else>{{ detail.matchedAnswer != null ? 'answer 为 21。' : 'answer 不为 21 或无法解析。' }}</p>
                <p v-if="!legacyCandy" class="iq-caveat">只按 JSON 的 answer 判定；不评价 reason 的推理质量。</p>
              </aside>
              <aside v-else class="iq-candy-grade">
                <span class="muted small">本题判定</span>
                <strong class="iq-fail-text">未完成</strong>
                <p v-if="!detail.success">上游请求失败，无法判分。请查看错误信息后重试。</p>
                <p v-else>上游返回的文本为空，无法判分。</p>
              </aside>
            </div>
          </template>

          <footer class="iq-result-foot">
            <span class="muted small">{{ usageSummary(detail) }}</span>
          </footer>
        </section>

        <section v-else class="panel iq-empty">
          <div class="iq-empty-icon"><Icon name="brain" :size="30" /></div>
          <strong>选择模型，运行第一道题</strong>
          <p>每道题都是一次真实的上游请求。结果会保留最近 200 次，方便横向比较不同模型。</p>
        </section>

        <section class="panel iq-history">
          <header class="iq-history-head">
            <div>
              <strong>检测历史</strong>
              <small>最近 50 次 · 保留最近 200 次</small>
            </div>
            <div class="iq-history-actions">
              <div class="iq-segmented">
                <button type="button" :class="{ active: historyFilter === 'all' }" @click="historyFilter = 'all'">全部</button>
                <button type="button" :class="{ active: historyFilter === 'pelican' }" @click="historyFilter = 'pelican'">鹈鹕</button>
                <button type="button" :class="{ active: historyFilter === 'candy' }" @click="historyFilter = 'candy'">糖果</button>
              </div>
              <button type="button" class="button ghost mini" :disabled="loadingHistory" @click="loadHistory">
                <Icon name="refresh" :size="14" :class="{ 'is-spinning': loadingHistory }" />
                刷新
              </button>
            </div>
          </header>
          <div v-if="!filteredHistory.length" class="iq-history-empty">
            {{ loadingHistory ? '正在读取历史…' : '还没有检测记录' }}
          </div>
          <div v-else class="iq-history-list">
            <button
              v-for="run in filteredHistory"
              :key="run.id"
              type="button"
              class="iq-history-item"
              :class="{ active: detail?.id === run.id }"
              @click="openRun(run)"
            >
              <span class="iq-history-top">
                <span class="iq-history-main">
                  <span class="badge" :class="iqVerdictTone(run.verdict)">{{ run.benchmarkLabel }}</span>
                  <span v-if="run.manualVerdict" class="badge info">人工</span>
                  <strong>{{ run.model }}</strong>
                </span>
                <span class="iq-history-side">
                  <span class="badge" :class="iqVerdictTone(run.verdict)">{{ run.verdictLabel }}</span>
                  <small>{{ formatIqLatency(run.latencyMs) }}</small>
                </span>
              </span>
              <small class="iq-history-meta">
                {{ run.channelName }} · {{ formatIqTime(run.createdAt) }}
                <template v-if="run.judgeResult"> · {{ run.judgeResult.judgeLabel }}</template>
              </small>
            </button>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.iq-page {
  max-width: 1480px;
  margin: 0 auto;
}

.iq-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 24px;
  background:
    radial-gradient(circle at 8% 0%, rgba(37, 99, 235, 0.12), transparent 42%),
    var(--panel);
}

.iq-hero-copy {
  min-width: 0;
}

.iq-eyebrow {
  color: var(--brand);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.iq-hero h2 {
  margin: 6px 0 8px;
  font-size: 22px;
  line-height: 1.25;
}

.iq-hero p {
  max-width: 780px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}

.iq-hero-note {
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

.iq-hero-note span {
  color: var(--ink);
  font-weight: 650;
}

.iq-hero-note small {
  flex-basis: 100%;
  line-height: 1.5;
}

.iq-layout {
  display: grid;
  grid-template-columns: minmax(280px, 320px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.iq-control {
  position: sticky;
  top: 16px;
  display: grid;
  gap: 18px;
  padding: 18px;
}

.iq-section {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.iq-section-label {
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.iq-benchmark-list {
  display: grid;
  gap: 8px;
}

.iq-benchmark-card {
  display: flex;
  align-items: flex-start;
  width: 100%;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--panel);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, transform 0.16s ease;
}

.iq-benchmark-card:hover:not(:disabled) {
  border-color: var(--line-strong);
  transform: translateY(-1px);
}

.iq-benchmark-card.active {
  border-color: color-mix(in srgb, var(--brand) 45%, var(--line));
  background: color-mix(in srgb, var(--brand) 6%, var(--panel));
}

.iq-benchmark-card:disabled {
  cursor: not-allowed;
  opacity: 0.72;
}

.iq-benchmark-icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--bg);
  color: var(--brand);
}

.iq-benchmark-copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.iq-benchmark-copy strong {
  font-size: 13px;
}

.iq-benchmark-copy small {
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.45;
}

.iq-benchmark-check {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  margin-left: auto;
  border-radius: 999px;
  background: var(--brand);
  color: #fff;
}

.iq-prompt-editor {
  display: grid;
  gap: 8px;
  border-top: 1px dashed var(--line);
  padding-top: 10px;
}

.iq-prompt-input {
  width: 100%;
  min-height: 150px;
  max-height: 340px;
  padding: 10px;
  resize: vertical;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg);
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-prompt-input:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 1px;
}

.iq-prompt-input:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.iq-prompt-meta {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.iq-used-prompt {
  margin-top: 10px;
}

.iq-used-prompt summary {
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}

.iq-prompt {
  max-height: 240px;
  margin: 8px 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg);
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.iq-grading {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.iq-target-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.iq-target-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.iq-no-target {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: 9px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.iq-run-button {
  min-height: 42px;
}

.iq-run-note {
  margin: -6px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.is-spinning {
  animation: spin 0.8s linear infinite;
}

.iq-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 300px);
  align-items: start;
  min-width: 0;
  gap: 16px;
}

.iq-loading,
.iq-running {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px;
}

.iq-loading p,
.iq-running p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.iq-running-spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--line);
  border-top-color: var(--brand);
  border-radius: 999px;
  animation: spin 0.8s linear infinite;
}

.iq-load-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.iq-run-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.iq-result {
  display: grid;
  gap: 16px;
  padding: 18px;
}

.iq-result-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.iq-result-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.iq-result-title h3 {
  margin: 0;
  font-size: 17px;
}

.iq-result-title small {
  color: var(--muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.iq-result-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  color: var(--muted);
  font-size: 11.5px;
}

.iq-result-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.iq-result-summary div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.iq-result-summary strong {
  overflow-wrap: anywhere;
  font-size: 12.5px;
}

.iq-preview {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.iq-preview-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
}

.iq-segmented {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--bg);
}

.iq-segmented button {
  padding: 5px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}

.iq-segmented button.active {
  background: var(--panel);
  color: var(--ink);
  box-shadow: var(--shadow-soft);
}

.iq-frame {
  width: 100%;
  height: min(62vh, 620px);
  min-height: 320px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.iq-source,
.iq-answer-text {
  max-height: 420px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--bg);
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.iq-answer-text {
  font-family: inherit;
  font-size: 13px;
}

.iq-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 7px;
}

.iq-raw {
  display: grid;
  gap: 4px;
}

.iq-checks {
  display: grid;
  gap: 4px;
}

.iq-checks ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.iq-checks li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--danger);
  font-size: 12.5px;
}

.iq-checks li.passed {
  color: var(--success);
}

.iq-checks li span {
  color: var(--ink);
}

.iq-checks li small {
  flex-basis: 100%;
  padding-left: 21px;
  color: var(--muted);
  font-size: 11.5px;
}

.iq-caveat {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-candy-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(220px, 0.7fr);
  gap: 14px;
  align-items: start;
}

.iq-candy-answer {
  min-width: 0;
}

.iq-candy-answer p {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.55;
}

.iq-candy-grade {
  display: grid;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.iq-candy-grade strong {
  font-size: 20px;
}

.iq-candy-grade p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.iq-pass-text {
  color: var(--success);
}

.iq-fail-text {
  color: var(--danger);
}

.iq-judge-description {
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-privacy-warning {
  margin: 0;
  padding: 9px 12px;
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-judge-failures {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg);
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-judge-failures summary {
  cursor: pointer;
  font-weight: 600;
}

.iq-judge-failures ul {
  display: grid;
  gap: 4px;
  margin: 6px 0 0;
  padding-left: 18px;
  overflow-wrap: anywhere;
}

.iq-judge-details summary {
  color: var(--muted);
  font-size: 11.5px;
  cursor: pointer;
}

.iq-judge-details dl {
  display: grid;
  gap: 10px;
  margin: 8px 0 0;
  font-size: 11.5px;
}

.iq-judge-details dt {
  color: var(--ink);
  font-weight: 600;
}

.iq-judge-details dd {
  margin: 3px 0 0;
  color: var(--muted);
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-line;
}

.iq-manual {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.iq-manual-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.iq-manual > .button {
  justify-self: start;
}

.iq-external {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.iq-external p {
  margin: 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.iq-external small {
  color: var(--muted);
  font-size: 11.5px;
  overflow-wrap: anywhere;
}

.iq-result-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.iq-empty {
  display: grid;
  place-items: center;
  gap: 8px;
  padding: 42px 22px;
  text-align: center;
}

.iq-empty-icon {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin-bottom: 2px;
  border-radius: 18px;
  background: color-mix(in srgb, var(--brand) 9%, var(--panel));
  color: var(--brand);
}

.iq-empty strong {
  font-size: 15px;
}

.iq-empty p {
  max-width: 480px;
  margin: 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.6;
}

.iq-history {
  position: sticky;
  top: 16px;
  grid-column: 2;
  grid-row: 1;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  max-height: calc(100vh - 32px);
  gap: 12px;
  padding: 16px 18px;
}

.iq-history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.iq-history-head > div:first-child {
  display: grid;
  gap: 2px;
}

.iq-history-head small {
  color: var(--muted);
  font-size: 11.5px;
}

.iq-history-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.iq-history-empty {
  padding: 20px;
  border: 1px dashed var(--line);
  border-radius: 9px;
  color: var(--muted);
  font-size: 12.5px;
  text-align: center;
}

.iq-history-list {
  display: grid;
  gap: 6px;
  min-height: 0;
  overflow-y: auto;
  padding-right: 2px;
}

.iq-history-item {
  display: grid;
  width: 100%;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--panel);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.iq-history-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  gap: 10px;
}

.iq-history-item:hover {
  border-color: var(--line-strong);
  background: var(--bg);
}

.iq-history-item.active {
  border-color: color-mix(in srgb, var(--brand) 45%, var(--line));
  background: color-mix(in srgb, var(--brand) 5%, var(--panel));
}

.iq-history-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  min-width: 0;
  gap: 7px;
}

.iq-history-main strong {
  max-width: 100%;
  overflow: hidden;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.iq-history-meta {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.iq-history-side {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 8px;
}

.iq-history-side small {
  color: var(--muted);
  font-size: 11px;
}

@media (max-width: 1360px) {
  .iq-layout {
    grid-template-columns: minmax(290px, 356px) minmax(0, 1fr);
  }

  .iq-main {
    grid-template-columns: minmax(0, 1fr);
  }

  .iq-history {
    position: static;
    grid-column: auto;
    grid-row: auto;
    max-height: none;
  }

  .iq-history-list {
    overflow: visible;
  }
}

@media (max-width: 1000px) {
  .iq-layout {
    grid-template-columns: 1fr;
  }

  .iq-control {
    position: static;
  }
}

@media (max-width: 640px) {
  .iq-hero {
    align-items: flex-start;
    flex-direction: column;
    padding: 18px;
  }

  .iq-hero-note {
    max-width: none;
    width: 100%;
  }

  .iq-target-grid,
  .iq-candy-layout,
  .iq-manual-grid {
    grid-template-columns: 1fr;
  }

  .iq-result {
    padding: 14px;
  }

  .iq-result-head {
    flex-direction: column;
  }

  .iq-result-meta {
    justify-content: flex-start;
  }

  .iq-result-summary {
    grid-template-columns: 1fr;
  }

  .iq-frame {
    height: 420px;
    min-height: 260px;
  }

  .iq-history-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .iq-history-item {
    align-items: flex-start;
    flex-direction: column;
  }

  .iq-history-side {
    align-self: flex-end;
  }
}
</style>

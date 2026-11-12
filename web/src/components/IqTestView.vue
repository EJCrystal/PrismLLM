<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type {
  Channel,
  IqBenchmarkDefinition,
  IqQuestion,
  IqBenchmarkId,
  IqJudgeFailure,
  IqJudgeInfo,
  IqTestRunDetail,
  IqTestRunRequest,
  IqTestTarget,
  ProtocolId,
} from '../types/api'
import {
  formatIqLatency,
  formatIqTime,
  iqTargetKey,
  iqTimeoutHint,
  iqVerdictTone,
  resolveIqTimeoutOverride,
  shouldAdoptIqRunResult,
  withIqPreviewCsp,
} from '../utils/iqTest'
import { isKeyRoutable } from '../utils/channelForm'
import CopyButton from './CopyButton.vue'
import Icon from './Icon.vue'
import SearchSelect from './SearchSelect.vue'
import IqQuestionBankModal from './IqQuestionBankModal.vue'

interface ActiveRun {
  id: number
  benchmark: IqBenchmarkId
  benchmarkLabel: string
  channelId: string
  channelName: string
  model: string
  keyName: string
  startedAt: number
}

/** 失败任务的请求快照：重试必须复现当时的目标，而不是当前下拉框里的配置。 */
interface FailedRun {
  job: ActiveRun
  request: IqTestRunRequest
}

const props = withDefaults(defineProps<{
  /** run：渠道内发起检测；record：只展示一条历史记录。 */
  mode?: 'run' | 'record'
  channel?: Channel | null
  initialModel?: string
  recordId?: number | null
}>(), {
  mode: 'run',
  channel: null,
  initialModel: '',
  recordId: null,
})
const emit = defineEmits<{
  (event: 'open-record', id: number): void
  (event: 'record-updated', id: number): void
}>()

const questions = ref<IqQuestion[]>([])
const showQuestionBank = ref(false)
const enabledQuestionCount = computed(() => questions.value.filter(question => question.enabled).length)
const benchmarks = ref<IqBenchmarkDefinition[]>([])
const targets = ref<IqTestTarget[]>([])
const judges = ref<IqJudgeInfo[]>([])
const judgeFailures = ref<IqJudgeFailure[]>([])
const selectedBenchmark = ref<IqBenchmarkId>('pelican')
const selectedTargetKey = ref('')
const selectedKeyId = ref('')
const selectedProtocol = ref('')
const selectedJudgeId = ref('')
const promptText = ref('')
/** 本次检测的超时覆盖，单位秒；`type="number"` 的 v-model 会写入数字，留空时是空字符串。 */
const timeoutSeconds = ref<string | number>('')
const loading = ref(true)
const detailLoading = ref(false)
const detail = ref<IqTestRunDetail | null>(null)
const previewMode = ref<'preview' | 'source'>('preview')
const loadError = ref('')
const runError = ref('')
const manualSubmitting = ref(false)
const resultRef = ref<HTMLElement | null>(null)
const activeRuns = ref<ActiveRun[]>([])
const nowTick = ref(Date.now())
/** 用户手动点开历史记录后，后台完成的检测不再抢占详情区。 */
const detailPinned = ref(false)
/** 人工判定表单有未保存修改时，后台完成的结果不能替换详情并清空输入。 */
const manualDirty = ref(false)
/** 当前错误区展示的是哪次失败；重试只认这份快照。 */
const failedRun = ref<FailedRun | null>(null)
const manualForm = reactive<{
  verdict: '' | 'passed' | 'incorrect'
  score: number | null
  note: string
}>({
  verdict: '',
  score: null,
  note: '',
})
let runSeq = 0
let tickTimer: ReturnType<typeof setInterval> | null = null

const recordMode = computed(() => props.mode === 'record')
const compact = computed(() => props.mode === 'run')
const scopedTargets = computed(() =>
  props.channel ? targets.value.filter((target) => target.channelId === props.channel?.id) : targets.value,
)
const benchmark = computed(() =>
  benchmarks.value.find((item) => item.id === selectedBenchmark.value) ?? benchmarks.value[0] ?? null,
)
const selectedTarget = computed(() =>
  scopedTargets.value.find((item) => iqTargetKey(item.channelId, item.model) === selectedTargetKey.value) ?? null,
)
const selectedJudge = computed(() =>
  judges.value.find((item) => item.id === selectedJudgeId.value) ?? null,
)
const promptCustomized = computed(() =>
  !!benchmark.value && promptText.value.trim() !== benchmark.value.prompt.trim(),
)
/** 本次检测生效的超时：与服务端 resolveTimeoutMs 同口径，覆盖值夹在 1 秒与硬上限之间。 */
const effectiveTimeoutMs = computed(() => (benchmark.value
  ? resolveIqTimeoutOverride(timeoutSeconds.value, benchmark.value)
  : 0))
const timeoutHint = computed(() => benchmark.value
  ? iqTimeoutHint({ durationHint: benchmark.value.durationHint, effectiveTimeoutMs: effectiveTimeoutMs.value })
  : '')
const timeoutCustomized = computed(() =>
  effectiveTimeoutMs.value > 0 && effectiveTimeoutMs.value !== (benchmark.value?.effectiveTimeoutMs ?? 0),
)
const targetOptions = computed(() => scopedTargets.value.map((target) => ({
  value: iqTargetKey(target.channelId, target.model),
  label: target.model,
  hint: `${target.credentials.length} 个可用凭据`,
  group: target.channelEnabled ? target.channelName : `${target.channelName}（已停用）`,
  searchText: `${target.channelName} ${target.model}`,
})))
/** 渠道内只需要选模型，分组标题会重复渠道名，这里保持平铺。 */
const modelOptions = computed(() => targetOptions.value.map(({ group, ...option }) => option))
/**
 * 渠道里的启用密钥；未授权当前模型的置灰列出。
 * 凭据按模型授权过滤是刻意的路由约束，但只显示可用项会让人误以为密钥丢了。
 */
const credentialOptions = computed(() => {
  const target = selectedTarget.value
  if (!target) return []
  const supported = target.credentials.map((credential) => ({
    value: credential.id,
    label: credential.name,
    hint: `优先级 ${credential.priority}`,
    disabled: false,
  }))
  const supportedIds = new Set(supported.map((option) => option.value))
  const unavailable = (props.channel?.keys ?? [])
    .filter((key) => !supportedIds.has(key.id) && isKeyRoutable(key) && !key.models.includes(target.model))
    .sort((left, right) => left.priority - right.priority || left.name.localeCompare(right.name))
    .map((key) => ({
      value: key.id,
      label: key.name,
      hint: `未授权 ${target.model}`,
      disabled: true,
    }))
  return [...supported, ...unavailable]
})
const credentialHint = computed(() => {
  const unavailable = credentialOptions.value.filter((option) => option.disabled).length
  if (!unavailable) return ''
  return `该渠道另有 ${unavailable} 个启用密钥未授权当前模型，切换模型后可选。`
})
const canRun = computed(() =>
  !!selectedTarget.value &&
  !!benchmark.value &&
  (selectedBenchmark.value === 'candy' ? enabledQuestionCount.value > 0 : !!selectedJudgeId.value) &&
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

function usageSummary(run: IqTestRunDetail | null): string {
  const usage = run?.usage
  if (!usage) return '上游未返回用量'
  const parts: string[] = []
  if (usage.inputTokens != null) parts.push(`输入 ${usage.inputTokens.toLocaleString()}`)
  if (usage.outputTokens != null) parts.push(`输出 ${usage.outputTokens.toLocaleString()}`)
  if (usage.reasoningTokens != null) parts.push(`推理 ${usage.reasoningTokens.toLocaleString()}`)
  if (usage.totalTokens != null) parts.push(`合计 ${usage.totalTokens.toLocaleString()}`)
  return parts.length ? parts.join(' · ') : '上游未返回用量'
}

function activeSeconds(run: ActiveRun): number {
  return Math.max(0, Math.floor((nowTick.value - run.startedAt) / 1000))
}

function ensureTicker() {
  if (tickTimer) return
  nowTick.value = Date.now()
  tickTimer = setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
}

function stopTicker() {
  if (!tickTimer) return
  clearInterval(tickTimer)
  tickTimer = null
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

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [definitions, targetList, judgeResponse, questionList] = await Promise.all([
      api.listIqBenchmarks(),
      api.listIqTargets(),
      api.listIqJudges(),
      api.listIqQuestions(),
    ])
    questions.value = questionList
    benchmarks.value = definitions
    targets.value = targetList
    judges.value = judgeResponse.judges
    judgeFailures.value = judgeResponse.failures
    if (!judges.value.some((judge) => judge.id === selectedJudgeId.value)) {
      selectedJudgeId.value = judgeResponse.defaultJudgeId || judges.value[0]?.id || ''
    }
    const preferred = props.initialModel
      ? scopedTargets.value.find((target) => target.model === props.initialModel)
      : null
    const first = preferred ?? scopedTargets.value[0]
    if (!selectedTargetKey.value && first) {
      selectedTargetKey.value = iqTargetKey(first.channelId, first.model)
    }
    syncSelectedTarget()
  } catch (cause) {
    loadError.value = errorMessage(cause)
    notify(loadError.value, 'error')
  } finally {
    loading.value = false
  }
}

async function loadRecord(id: number) {
  detailLoading.value = true
  loadError.value = ''
  try {
    detail.value = await api.getIqRun(id)
    selectedBenchmark.value = detail.value.benchmark
    syncManualForm(detail.value)
  } catch (cause) {
    loadError.value = errorMessage(cause)
    notify(loadError.value, 'error')
  } finally {
    detailLoading.value = false
  }
}

/** 记录模式只需要重试详情本身，不能退回发起页的完整初始化。 */
function retryLoad() {
  if (recordMode.value && props.recordId != null) void loadRecord(props.recordId)
  else void loadAll()
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

/** 立即发起一个检测；界面不再锁死，可以连续提交多个目标。 */
function run() {
  const target = selectedTarget.value
  const item = benchmark.value
  const judgeId = selectedJudgeId.value
  if (!target || !item || (item.id !== 'candy' && !judgeId)) return
  const job: ActiveRun = {
    id: ++runSeq,
    benchmark: item.id,
    benchmarkLabel: item.label,
    channelId: target.channelId,
    channelName: target.channelName,
    model: target.model,
    keyName: target.credentials.find((credential) => credential.id === selectedKeyId.value)?.name ?? '默认凭据',
    startedAt: Date.now(),
  }
  const request: IqTestRunRequest = {
    channelId: target.channelId,
    model: target.model,
    benchmark: item.id,
    keyId: selectedKeyId.value || null,
    protocol: (selectedProtocol.value || null) as ProtocolId | null,
    judgeId: item.id === 'candy' ? null : judgeId,
    prompt: item.id === 'candy' ? undefined : promptText.value.trim() || undefined,
    useQuestionBank: item.id === 'candy',
    // 与服务端同口径：填了覆盖值才发送，未填写时交给服务端使用题目默认值。
    timeoutMs: timeoutCustomized.value ? effectiveTimeoutMs.value : null,
  }
  activeRuns.value.push(job)
  runError.value = ''
  failedRun.value = null
  previewMode.value = 'preview'
  detailPinned.value = false
  ensureTicker()
  void executeRun(job, request)
}

async function executeRun(job: ActiveRun, request: IqTestRunRequest) {
  try {
    const result = await api.runIqTest(request)
    if (shouldAdoptIqRunResult({ pinned: detailPinned.value, editing: manualDirty.value })) {
      detail.value = result
      syncManualForm(result)
      scrollResultIntoView()
    }
    notify(
      `${job.benchmarkLabel} · ${job.channelName} / ${job.model}：${result.verdictLabel}`,
      result.verdict === 'passed' ? 'success' : result.verdict === 'failed' ? 'error' : 'warning',
    )
  } catch (cause) {
    runError.value = `${job.model}：${errorMessage(cause)}`
    failedRun.value = { job, request }
    notify(runError.value, 'error')
  } finally {
    activeRuns.value = activeRuns.value.filter((item) => item.id !== job.id)
    if (!activeRuns.value.length) stopTicker()
  }
}

/** 用失败任务的请求快照重试，配置切换后也不会误测当前选中的目标。 */
function retryFailed() {
  const failed = failedRun.value
  if (!failed) return
  const job: ActiveRun = { ...failed.job, id: ++runSeq, startedAt: Date.now() }
  activeRuns.value.push(job)
  runError.value = ''
  failedRun.value = null
  previewMode.value = 'preview'
  detailPinned.value = false
  ensureTicker()
  void executeRun(job, failed.request)
}

function syncManualForm(run: IqTestRunDetail) {
  manualForm.verdict = run.manualVerdict ?? ''
  manualForm.score = run.manualScore
  manualForm.note = run.manualNote ?? ''
  manualDirty.value = false
}

function markManualDirty() {
  manualDirty.value = true
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
    notify('人工判定已保存')
    emit('record-updated', detail.value.id)
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

onMounted(() => {
  if (recordMode.value && props.recordId != null) {
    loading.value = false
    void loadRecord(props.recordId)
    return
  }
  void loadAll()
})
onBeforeUnmount(stopTicker)
</script>

<template>
  <section class="page-stack iq-page" :class="{ 'iq-embedded': mode === 'run' }">
    <p v-if="mode === 'run'" class="iq-embedded-note">
      <Icon name="shield" :size="14" />
      测试通过已保存的渠道凭据直接请求上游，不写入请求日志，也不消耗调用方密钥额度。
    </p>

    <div v-if="loading" class="panel iq-loading">
      <span class="spinner"></span>
      <p>正在读取可用模型与题目…</p>
    </div>

    <div v-else-if="loadError" class="callout danger iq-load-error">
      <span>{{ loadError }}</span>
      <button type="button" class="link-button" @click="retryLoad">重试</button>
    </div>

    <div v-else class="iq-layout" :class="{ 'iq-record-mode': recordMode }">
      <aside v-if="!recordMode" class="iq-control panel">
        <div class="iq-section">
          <span class="iq-section-label">检测题目</span>
          <div class="iq-benchmark-list">
            <button
              v-for="item in benchmarks"
              :key="item.id"
              type="button"
              class="iq-benchmark-card"
              :class="{ active: selectedBenchmark === item.id }"
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
          <div v-if="selectedBenchmark === 'candy'" class="iq-prompt-editor">
            <div class="iq-section-head"><span class="iq-section-label">随机题库</span><button type="button" class="link-button" @click="showQuestionBank = true">管理题库</button></div>
            <p class="iq-grading">共 {{ questions.length }} 道题，{{ enabledQuestionCount }} 道已启用。每次运行由服务端随机抽一道。</p>
            <p v-if="!enabledQuestionCount" class="callout warning">没有启用的题目，请先在题库中添加或启用题目。</p>
            <p class="iq-prompt-meta">标准答案不会发送给模型；收到 JSON 的 answer 后在本地比较。测试不提供搜索或工具调用。</p>
          </div>
          <div v-else-if="benchmark" class="iq-prompt-editor">
            <div class="iq-section-head">
              <span class="iq-section-label">提示词</span>
              <button
                type="button"
                class="link-button"
                :disabled="!promptCustomized"
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
              spellcheck="false"
              aria-label="检测提示词"
            ></textarea>
            <p class="iq-prompt-meta">
              {{ promptText.trim().length.toLocaleString() }} 字符
              · 留空则使用内置默认提示词
            </p>
            <p class="iq-grading"><strong>判分：</strong>{{ benchmark.grading }}</p>
          </div>
        </div>

        <div class="iq-section">
          <span class="iq-section-label">检测目标</span>
          <div v-if="scopedTargets.length" class="iq-target">
            <div class="field iq-select-field">
              <span>{{ channel ? '模型' : '渠道与模型' }}</span>
              <SearchSelect
                v-model="selectedTargetKey"
                :label="channel ? '模型' : '渠道与模型'"
                :options="channel ? modelOptions : targetOptions"
                :panel-width="channel ? 320 : 340"
                :placeholder="channel ? '选择模型' : '选择渠道与模型'"
              />
            </div>
            <template v-if="selectedTarget">
              <div class="field iq-select-field">
                <span>使用凭据</span>
                <SearchSelect
                  v-model="selectedKeyId"
                  label="上游凭据"
                  :options="credentialOptions"
                  :panel-width="300"
                  placeholder="选择上游凭据"
                  :searchable="credentialOptions.length > 6"
                />
                <small v-if="credentialHint">{{ credentialHint }}</small>
              </div>
              <label class="field">
                <span>协议</span>
                <select v-model="selectedProtocol">
                  <option value="">自动</option>
                  <option v-for="protocol in selectedTarget.protocols" :key="protocol" :value="protocol">
                    {{ protocolLabel(protocol) }}
                  </option>
                </select>
              </label>
            </template>
            <p v-if="selectedTarget && !selectedTarget.channelEnabled" class="iq-inline-warning">
              <Icon name="info" :size="14" />
              渠道已停用，仍可用已保存的凭据发起测试。
            </p>
          </div>
          <p v-else class="iq-no-target">
            还没有可测试的模型。请先在「渠道」中授权模型，并至少配置一个可用凭据。
          </p>
        </div>

        <div class="iq-section">
          <span class="iq-section-label">判分器</span>
          <p v-if="selectedBenchmark === 'candy'" class="iq-judge-description">
            糖果题只读取模型返回的 JSON 答案，不调用 HTML 判分器。
          </p>
          <template v-else>
            <label v-if="judges.length" class="field">
              <span>鹈鹕判分器</span>
              <select v-model="selectedJudgeId">
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

        <div class="iq-run-area">
          <label class="field iq-timeout-field">
            <span>本次超时（秒）</span>
            <input
              v-model="timeoutSeconds"
              type="number"
              inputmode="numeric"
              min="1"
              :max="benchmark ? Math.max(1, Math.floor(benchmark.maxTimeoutMs / 1000)) : undefined"
              :placeholder="benchmark ? String(Math.round(benchmark.effectiveTimeoutMs / 1000)) : ''"
              aria-label="本次检测超时秒数"
            />
          </label>
          <p class="iq-prompt-meta">
            留空使用默认 {{ benchmark ? Math.round(benchmark.effectiveTimeoutMs / 1000) : '—' }} 秒；上限
            {{ benchmark ? Math.round(benchmark.maxTimeoutMs / 1000) : '—' }} 秒。
          </p>
          <button type="button" class="button primary full iq-run-button" :disabled="!canRun" @click="run">
            <Icon name="bolt" :size="17" />
            开始测试
          </button>
          <p class="iq-run-note">
            {{ timeoutHint }}
            完成后自动写入检测历史。
          </p>
          <p v-if="activeRuns.length" class="iq-run-live">
            <i class="iq-live-dot"></i>
            已有 {{ activeRuns.length }} 个检测在后台进行，可继续切换目标开始新的检测。
          </p>
        </div>
      </aside>

      <div class="iq-main">
        <div class="iq-main-column">
          <div v-if="runError" class="callout danger iq-run-error">
            <span>{{ runError }}</span>
            <button type="button" class="link-button" :disabled="!failedRun" @click="retryFailed">重试该目标</button>
          </div>

          <section v-if="activeRuns.length" class="panel iq-active" aria-live="polite">
            <div class="iq-active-head">
              <span class="iq-active-title"><i class="iq-live-dot"></i>正在检测 {{ activeRuns.length }} 个目标</span>
              <span class="iq-active-hint">页面不会锁定，可继续提交其他模型</span>
            </div>
            <div class="iq-active-list">
              <article v-for="job in activeRuns" :key="job.id" class="iq-active-item">
                <span class="iq-active-spinner" aria-hidden="true"></span>
                <div class="iq-active-copy">
                  <strong>{{ job.model }}</strong>
                  <small>{{ job.channelName }} · {{ job.benchmarkLabel }} · {{ job.keyName }}</small>
                </div>
                <span class="iq-active-time">{{ activeSeconds(job) }} 秒</span>
              </article>
            </div>
          </section>

          <section v-if="detailLoading" class="panel iq-loading">
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
                <span class="iq-meta-chip">HTTP {{ detail.statusCode ?? '—' }}</span>
                <span class="iq-meta-chip">{{ formatIqLatency(detail.latencyMs) }}</span>
                <span class="iq-meta-chip">{{ formatIqTime(detail.createdAt) }}</span>
              </div>
            </header>

            <div class="iq-result-summary">
              <div class="iq-stat-tile"><span class="iq-stat-label">渠道</span><strong>{{ detail.channelName }}</strong></div>
              <div class="iq-stat-tile"><span class="iq-stat-label">凭据</span><strong>{{ detail.channelKeyName || '—' }}</strong></div>
              <div class="iq-stat-tile"><span class="iq-stat-label">协议</span><strong>{{ protocolLabel(detail.protocol) }}</strong></div>
              <div class="iq-stat-tile"><span class="iq-stat-label">上游声明模型</span><strong>{{ detail.upstreamReportedModel || '未返回' }}</strong></div>
            </div>

            <details v-if="detail.prompt && !compact" class="iq-used-prompt">
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
                v-if="!compact && Object.keys(detail.judgeResult.details).length"
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

            <div v-if="detail.html && !compact" class="iq-preview">
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
            <div v-else-if="!compact" class="iq-raw">
              <div class="iq-section-head">
                <strong>原始响应</strong>
                <CopyButton :text="detail.responseText || ''" title="复制原始响应" variant="subtle" />
              </div>
              <pre class="iq-source">{{ detail.responseText || '上游未返回文本' }}</pre>
            </div>

            <div v-if="!compact && detail.benchmark === 'pelican' && detail.html" class="iq-manual">
              <div class="iq-section-head">
                <strong>人工判定</strong>
                <span v-if="detail.manualVerdict" class="badge info">已人工判定</span>
              </div>
              <div class="iq-manual-grid">
                <label class="field">
                  <span>结论</span>
                  <select v-model="manualForm.verdict" :disabled="manualSubmitting" @change="markManualDirty">
                    <option value="">不修改</option>
                    <option value="passed">通过</option>
                    <option value="incorrect">未通过</option>
                  </select>
                </label>
                <label class="field">
                  <span>评分</span>
                  <select v-model="manualForm.score" :disabled="manualSubmitting" @change="markManualDirty">
                    <option :value="null">不评分</option>
                    <option v-for="score in [1, 2, 3, 4, 5]" :key="score" :value="score">{{ score }} / 5</option>
                  </select>
                </label>
              </div>
              <label class="field">
                <span>备注</span>
                <textarea v-model="manualForm.note" rows="2" maxlength="500" placeholder="可选" :disabled="manualSubmitting" @input="markManualDirty"></textarea>
              </label>
              <button type="button" class="button primary mini" :disabled="manualSubmitting || !manualForm.verdict" @click="submitManual">
                {{ manualSubmitting ? '保存中…' : '保存人工判定' }}
              </button>
              <p v-if="detail.manualUpdatedAt" class="muted small">上次人工判定：{{ formatIqTime(detail.manualUpdatedAt) }}</p>
            </div>

            <template v-if="!compact && detail.benchmark === 'pelican'">
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
                    <em v-if="check.decisive === false" class="iq-check-optional">提示</em>
                  </li>
                </ul>
                <p class="iq-caveat">
                  {{ detail.judgeResult ? `本次由「${detail.judgeResult.judgeLabel}」判分；` : '' }}本地检查只看 HTML、SVG、动画、资源依赖和能离线确认的图层顺序，需要看画面的比例与美感交给判分器或人工。标「提示」的项不参与判定。
                </p>
              </div>
            </template>

            <template v-else-if="!compact">
              <div class="iq-candy-layout">
                <div class="iq-candy-answer">
                  <div class="iq-section-head">
                    <strong>判分依据</strong>
                    <span class="muted small">{{ passedCheckCount }}/{{ detail.checks.length }} 项通过</span>
                  </div>
                  <p v-if="legacyCandy">历史记录按旧版规则判定：HTML 可见文字中出现独立的 <strong>21</strong> 即通过。</p>
                  <p v-else-if="detail.questionId != null"><strong>{{ detail.questionTitle }}</strong> · {{ detail.answerType === 'number' ? '数字按值比较' : '文本去除首尾空白后精确比较' }}。</p>
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
                  <p v-else class="iq-caveat">JSON 无法解析、answer 缺失或类型不符时判定为格式失败；不评价 reason 的推理质量。</p>
                </div>
                <aside v-if="detail.success && (detail.expectedAnswerValue != null || detail.expectedAnswer != null)" class="iq-candy-grade">
                  <span class="iq-stat-label">本题判定</span>
                  <strong :class="detail.verdict === 'passed' ? 'iq-pass-text' : 'iq-fail-text'">{{ detail.verdictLabel }}</strong>
                  <p>期望答案：<strong>{{ detail.expectedAnswerValue ?? detail.expectedAnswer }}</strong></p>
                  <p v-if="legacyCandy">回答中{{ detail.matchedAnswer != null ? '出现' : '未出现' }}独立数字 21。</p>
                  <p v-else-if="detail.questionId != null">实际答案：<strong>{{ detail.actualAnswer ?? '未返回有效答案' }}</strong></p>
                  <p v-else>{{ detail.matchedAnswer != null ? 'answer 为 21。' : 'answer 不为 21 或无法解析。' }}</p>
                  <p v-if="!legacyCandy" class="iq-caveat">只按 JSON 的 answer 判定；不评价 reason 的推理质量。</p>
                </aside>
                <aside v-else class="iq-candy-grade">
                  <span class="iq-stat-label">本题判定</span>
                  <strong class="iq-fail-text">未完成</strong>
                  <p v-if="!detail.success">上游请求失败，无法判分。请查看错误信息后重试。</p>
                  <p v-else>上游返回的文本为空，无法判分。</p>
                </aside>
              </div>
            </template>

            <p v-if="compact" class="muted small iq-compact-hint">
              完整判分细节、HTML 预览与人工判定可在「检测记录」中查看。
            </p>

            <footer class="iq-result-foot">
              <span class="muted small">{{ usageSummary(detail) }}</span>
              <button v-if="compact" type="button" class="button ghost mini" @click="emit('open-record', detail.id)">
                查看完整记录
              </button>
            </footer>
          </section>

          <section v-else-if="!recordMode && !activeRuns.length" class="panel iq-empty">
            <div class="iq-empty-icon"><Icon name="brain" :size="30" /></div>
            <strong>选择模型，运行第一道题</strong>
            <p>每道题都是一次真实的上游请求。结果会保留最近 200 次，方便横向比较不同模型。</p>
          </section>
        </div>
      </div>
    </div>
    <IqQuestionBankModal v-if="showQuestionBank" :questions="questions" @change="questions = $event" @close="showQuestionBank = false" />
  </section>
</template>

<style scoped>
.iq-page {
  --iq-radius: 22px;
  --iq-glass-strong: var(--glass-bg-strong);
  --iq-glass-soft: var(--glass-bg-soft);
  --iq-glass-border: var(--glass-border);
  --iq-glass-line: var(--glass-line);
  --iq-glass-shadow: var(--glass-shadow);
  --iq-glass-inset: var(--glass-inset);
  --iq-field-bg: var(--glass-field);
  --iq-field-hover: var(--glass-field-strong);
  --iq-field-border: var(--glass-line);
  --iq-tile-bg: var(--glass-bg-strong);
  position: relative;
  isolation: isolate;
  max-width: 1520px;
  margin: 0 auto;
  overflow-x: clip;
}

.iq-page > * {
  position: relative;
  z-index: 1;
}

.iq-page .panel {
  border: 1px solid var(--iq-glass-border);
  border-radius: var(--iq-radius);
  background: var(--glass-surface);
  box-shadow: var(--iq-glass-shadow), var(--iq-glass-inset);
  backdrop-filter: blur(var(--glass-blur)) saturate(155%);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(155%);
}

.iq-embedded-note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.iq-layout {
  display: grid;
  grid-template-columns: minmax(280px, 320px) minmax(0, 1fr);
  align-items: start;
  gap: 18px;
}

.iq-layout.iq-record-mode {
  grid-template-columns: minmax(0, 1fr);
}

.iq-compact-hint {
  margin: 0;
}

.iq-control {
  position: sticky;
  top: 16px;
  display: grid;
  gap: 20px;
  padding: 20px;
}

.iq-section {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.iq-section + .iq-section {
  padding-top: 18px;
  border-top: 1px solid var(--iq-glass-line);
}

.iq-section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 10.5px;
  font-weight: 750;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.iq-section-label::before {
  width: 14px;
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--brand), transparent);
  content: '';
}

.iq-section-head .iq-section-label {
  color: var(--muted);
}

.iq-benchmark-list {
  display: grid;
  gap: 8px;
}

.iq-benchmark-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  width: 100%;
  gap: 11px;
  padding: 12px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 14px;
  background: var(--iq-glass-soft);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  transition: transform 0.16s ease, border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.iq-benchmark-card:hover {
  border-color: color-mix(in srgb, var(--brand) 26%, var(--iq-glass-line));
  background: var(--iq-glass-strong);
  transform: translateY(-1px);
}

.iq-benchmark-card.active {
  border-color: color-mix(in srgb, var(--brand) 46%, var(--iq-glass-border));
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--brand) 14%, var(--iq-glass-strong)), color-mix(in srgb, var(--brand) 4%, var(--iq-glass-soft)));
  box-shadow:
    0 14px 30px -20px color-mix(in srgb, var(--brand) 65%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

[data-theme="dark"] .iq-benchmark-card.active {
  box-shadow:
    0 14px 30px -20px rgba(59, 130, 246, 0.8),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.iq-benchmark-icon {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 11px;
  background: var(--iq-tile-bg);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.55);
  color: var(--brand);
}

[data-theme="dark"] .iq-benchmark-icon {
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.iq-benchmark-card.active .iq-benchmark-icon {
  background: color-mix(in srgb, var(--brand) 15%, var(--iq-tile-bg));
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
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  margin-left: auto;
  place-items: center;
  border-radius: 999px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--brand) 72%, white), var(--brand));
  box-shadow: 0 4px 10px -4px color-mix(in srgb, var(--brand) 70%, transparent);
  color: #fff;
}

.iq-prompt-editor {
  display: grid;
  gap: 8px;
  border-top: 1px solid var(--iq-glass-line);
  padding-top: 10px;
}

.iq-prompt-input {
  width: 100%;
  min-height: 150px;
  max-height: 340px;
  padding: 11px;
  resize: vertical;
  border: 1px solid var(--iq-field-border);
  border-radius: 12px;
  background: var(--iq-field-bg);
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-prompt-input:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--brand) 65%, transparent);
  outline-offset: 1px;
}

.iq-prompt-meta {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.iq-used-prompt {
  margin-top: 2px;
}

.iq-used-prompt summary {
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}

.iq-prompt {
  max-height: 240px;
  margin: 8px 0;
  padding: 11px;
  overflow: auto;
  border: 1px solid var(--iq-glass-line);
  border-radius: 10px;
  background: var(--iq-field-bg);
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

.iq-target {
  display: grid;
  gap: 10px;
}

.iq-select-field {
  --sd-bg: var(--iq-field-bg);
  --sd-border: var(--iq-field-border);
  --sd-bg-hover: var(--iq-field-hover);
  --sd-border-hover: color-mix(in srgb, var(--brand) 34%, var(--iq-field-border));
  --sd-radius: 12px;
}

.iq-inline-warning {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid color-mix(in srgb, var(--warning) 24%, var(--iq-glass-line));
  border-radius: 9px;
  background: var(--warning-soft);
  color: var(--warning);
  font-size: 11.5px;
  line-height: 1.45;
}

.iq-no-target {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--iq-glass-line);
  border-radius: 10px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.iq-page .field > span {
  color: var(--ink);
  font-size: 12px;
  font-weight: 650;
}

.iq-page .field select,
.iq-page .field textarea {
  border: 1px solid var(--iq-field-border);
  border-radius: 12px;
  background: var(--iq-field-bg);
  color: var(--ink);
}

.iq-page .field select:focus,
.iq-page .field textarea:focus {
  border-color: color-mix(in srgb, var(--brand) 55%, var(--iq-field-border));
  outline: none;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--brand) 12%, transparent);
}

.iq-run-area {
  display: grid;
  gap: 9px;
}

.iq-timeout-field {
  gap: 4px;
}

.iq-timeout-field > span {
  font-size: 12px;
  color: var(--muted);
}

.iq-run-button {
  min-height: 46px;
  border-radius: 14px;
  font-size: 13px;
}

.iq-page .button.primary.iq-run-button {
  border: 1px solid color-mix(in srgb, var(--brand) 68%, white 32%);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.3), rgba(255, 255, 255, 0) 48%),
    linear-gradient(180deg, color-mix(in srgb, var(--brand) 82%, white), var(--brand) 58%, var(--brand-deep));
  box-shadow:
    0 16px 30px -16px color-mix(in srgb, var(--brand) 82%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.iq-page .button.primary.iq-run-button:hover:not(:disabled) {
  filter: brightness(1.05);
}

.iq-run-note {
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.iq-run-live {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  color: var(--brand);
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.5;
}

.iq-live-dot {
  display: inline-block;
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--brand);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--brand) 15%, transparent);
  animation: iq-pulse 1.5s ease-in-out infinite;
}

@keyframes iq-pulse {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.55;
    transform: scale(0.82);
  }
}

.iq-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  min-width: 0;
  gap: 18px;
}

.iq-main-column {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.iq-loading {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px;
}

.iq-loading p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.iq-load-error,
.iq-run-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.iq-active {
  position: relative;
  display: grid;
  gap: 12px;
  overflow: hidden;
  padding: 18px 20px;
}

.iq-active::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: -20%;
  width: 34%;
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(90deg, transparent, var(--brand), transparent);
  opacity: 0.65;
  animation: iq-progress 1.9s ease-in-out infinite;
}

@keyframes iq-progress {
  0% {
    left: -30%;
  }
  100% {
    left: 110%;
  }
}

.iq-active-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.iq-active-title {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
  font-weight: 700;
}

.iq-active-hint {
  color: var(--muted);
  font-size: 11.5px;
}

.iq-active-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px;
}

.iq-active-item {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 12px;
  background: var(--iq-glass-soft);
}

.iq-active-spinner {
  flex: 0 0 auto;
  width: 17px;
  height: 17px;
  border: 2px solid color-mix(in srgb, var(--brand) 18%, var(--iq-glass-line));
  border-top-color: var(--brand);
  border-radius: 999px;
  animation: spin 0.8s linear infinite;
}

.iq-active-copy {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
  gap: 2px;
}

.iq-active-copy strong {
  overflow: hidden;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.iq-active-copy small {
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.iq-active-time {
  flex: 0 0 auto;
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.iq-result {
  display: grid;
  gap: 18px;
  padding: 20px 22px;
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
  font-size: 18px;
  letter-spacing: -0.01em;
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
  gap: 6px;
}

.iq-meta-chip {
  padding: 4px 9px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 999px;
  background: var(--iq-glass-soft);
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.iq-result-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.iq-stat-tile {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 11px 12px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 12px;
  background: var(--iq-glass-soft);
}

.iq-stat-tile strong {
  overflow-wrap: anywhere;
  font-size: 12.5px;
}

.iq-stat-label {
  color: var(--muted);
  font-size: 10.5px;
  font-weight: 650;
  letter-spacing: 0.05em;
}

.iq-preview {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.iq-preview-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  padding: 6px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 12px;
  background: var(--iq-glass-soft);
}

.iq-segmented {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--bg) 46%, transparent);
}

.iq-segmented button {
  padding: 5px 11px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}

.iq-segmented button.active {
  background: var(--iq-glass-strong);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.6);
  color: var(--ink);
}

[data-theme="dark"] .iq-segmented button.active {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.iq-frame {
  width: 100%;
  height: min(62vh, 620px);
  min-height: 320px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 12px;
  background: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.15);
}

.iq-source {
  max-height: 420px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--iq-glass-line);
  border-radius: 10px;
  background: var(--iq-field-bg);
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
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
  border: 1px solid var(--iq-glass-line);
  border-radius: 10px;
  background: var(--iq-glass-soft);
  color: var(--danger);
  font-size: 12.5px;
}

.iq-check-optional {
  margin-left: 6px;
  padding: 0 5px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 5px;
  color: var(--muted);
  font-size: 11px;
  font-style: normal;
}

.iq-checks li.passed {
  border-color: color-mix(in srgb, var(--success) 24%, var(--iq-glass-line));
  background: color-mix(in srgb, var(--success) 6%, var(--iq-glass-soft));
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
  align-items: start;
  gap: 14px;
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
  overflow-wrap: anywhere;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--iq-glass-line);
  border-radius: 14px;
  background: var(--iq-glass-soft);
}

.iq-candy-grade strong {
  font-size: 21px;
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
  border: 1px solid var(--iq-glass-line);
  border-radius: 9px;
  background: var(--iq-glass-soft);
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
  border: 1px solid var(--iq-glass-line);
  border-radius: 14px;
  background: var(--iq-glass-soft);
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
  border: 1px solid var(--iq-glass-line);
  border-radius: 12px;
  background: var(--iq-glass-soft);
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
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--iq-glass-line);
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
  width: 60px;
  height: 60px;
  margin-bottom: 2px;
  place-items: center;
  border: 1px solid var(--iq-glass-line);
  border-radius: 19px;
  background:
    radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.7), transparent 60%),
    color-mix(in srgb, var(--brand) 10%, var(--iq-glass-soft));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5);
  color: var(--brand);
}

[data-theme="dark"] .iq-empty-icon {
  background:
    radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.1), transparent 60%),
    color-mix(in srgb, var(--brand) 18%, var(--iq-glass-soft));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
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

@media (prefers-reduced-motion: reduce) {
  .iq-page::before,
  .iq-live-dot,
  .iq-active::after {
    animation: none;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .iq-page .panel {
    background: var(--panel);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (max-width: 1360px) {
  .iq-layout {
    grid-template-columns: minmax(290px, 356px) minmax(0, 1fr);
  }

  .iq-main {
    grid-template-columns: minmax(0, 1fr);
  }

  .iq-result-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
  .iq-candy-layout,
  .iq-manual-grid {
    grid-template-columns: 1fr;
  }

  .iq-result {
    padding: 16px;
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
}
</style>

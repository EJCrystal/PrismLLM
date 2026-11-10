<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { notify } from '../toast'
import type { FingerprintCandidate, FingerprintRun, FingerprintRunRequest, ModelTestTarget } from '../types/api'
import {
  fingerprintBarWidths,
  fingerprintDecisionLabel,
  fingerprintDecisionTone,
  fingerprintSampleStatus,
  formatFingerprintProbability,
  formatFingerprintScore,
  parseFingerprintBankTime,
} from '../utils/fingerprint'
import { formatIqLatency, formatIqTime } from '../utils/iqTest'
import { modelTestProtocolLabel, modelTestTargetKey } from '../utils/modelTest'
import Icon from './Icon.vue'

const targets = ref<ModelTestTarget[]>([])
const detectorInfo = ref<{ modelCount: number; bankBuiltAt: string; detectorSha256: string } | null>(null)
const history = ref<FingerprintRun[]>([])
const loading = ref(true)
const loadError = ref('')
const running = ref(false)
const runError = ref('')
const historyLoading = ref(false)
const detailLoading = ref(false)
const result = ref<FingerprintRun | null>(null)
const selectedTargetKey = ref('')
const selectedKeyId = ref('')
const selectedProtocol = ref('')
const showAllCandidates = ref(false)
const elapsed = ref(0)
const resultRef = ref<HTMLElement | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

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
  !!selectedTarget.value && !!selectedProtocol.value && !running.value && !loading.value,
)
const analysis = computed(() => result.value?.analysis ?? null)
const candidates = computed<FingerprintCandidate[]>(() => analysis.value?.results ?? [])
const candidateWidths = computed(() => fingerprintBarWidths(candidates.value))
const visibleCandidates = computed(() =>
  showAllCandidates.value ? candidates.value : candidates.value.slice(0, 10),
)
const visibleWidths = computed(() => candidateWidths.value.slice(0, visibleCandidates.value.length))
const samples = computed(() => result.value?.samples ?? [])
const decisionTone = computed(() => (analysis.value ? fingerprintDecisionTone(analysis.value.decision) : 'warning'))
const evidence = computed(() => analysis.value?.evidenceReason?.trim() || '')
const predictionLabel = computed(() =>
  analysis.value?.decision === 'scored' || analysis.value?.decision === 'partial' ? '排名第一' : '检测结论',
)
const usageText = computed(() => {
  const usage = result.value?.usage
  if (!usage) return '上游未返回用量'
  const parts: string[] = []
  if (usage.inputTokens != null) parts.push(`输入 ${usage.inputTokens.toLocaleString()}`)
  if (usage.outputTokens != null) parts.push(`输出 ${usage.outputTokens.toLocaleString()}`)
  if (usage.totalTokens != null) parts.push(`合计 ${usage.totalTokens.toLocaleString()}`)
  return parts.length ? parts.join(' · ') : '上游未返回用量'
})

function errorMessage(cause: unknown): string {
  return cause instanceof Error && cause.message ? cause.message : String(cause)
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
  if (!selectedProtocol.value || !target.protocols.includes(selectedProtocol.value as (typeof target.protocols)[number])) {
    selectedProtocol.value = target.protocols[0] ?? ''
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

/** 堆叠布局下结果在控制面板下方，完成后把结果带回视口。 */
function scrollResultIntoView() {
  if (!window.matchMedia('(max-width: 960px)').matches) return
  requestAnimationFrame(() => resultRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = await api.listFingerprintRuns(20)
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  } finally {
    historyLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [targetList, info] = await Promise.all([
      api.listModelTestTargets(),
      api.getFingerprintInfo(),
    ])
    targets.value = targetList
    detectorInfo.value = info
    if (!selectedTargetKey.value && targetList.length) {
      selectedTargetKey.value = modelTestTargetKey(targetList[0].channelId, targetList[0].model)
    }
    syncSelectedTarget()
    await loadHistory()
    if (!result.value && history.value.length) {
      await openRun(history.value[0])
    }
  } catch (cause) {
    loadError.value = errorMessage(cause)
    notify(loadError.value, 'error')
  } finally {
    loading.value = false
  }
}

async function openRun(run: FingerprintRun) {
  if (result.value?.id === run.id && result.value.analysis) {
    return
  }
  detailLoading.value = true
  runError.value = ''
  try {
    result.value = await api.getFingerprintRun(run.id)
    showAllCandidates.value = false
  } catch (cause) {
    runError.value = errorMessage(cause)
    notify(runError.value, 'error')
  } finally {
    detailLoading.value = false
  }
}

async function run() {
  const target = selectedTarget.value
  if (!target) return
  running.value = true
  runError.value = ''
  startTimer()
  try {
    const payload: FingerprintRunRequest = {
      channelId: target.channelId,
      model: target.model,
      keyId: selectedKeyId.value || null,
      protocol: (selectedProtocol.value || null) as FingerprintRunRequest['protocol'],
    }
    const detail = await api.runFingerprintTest(payload)
    result.value = detail
    showAllCandidates.value = false
    notify(detail.usedOutputs > 0 ? '指纹检测已完成' : '三条回答都不够有效，请查看样本', detail.usedOutputs > 0 ? 'success' : 'error')
    await loadHistory()
    scrollResultIntoView()
  } catch (cause) {
    runError.value = errorMessage(cause)
    notify(runError.value, 'error')
  } finally {
    stopTimer()
    running.value = false
  }
}

watch(selectedTargetKey, syncSelectedTarget)
onMounted(loadAll)
onBeforeUnmount(stopTimer)
</script>

<template>
  <section class="page-stack fingerprint-page">
    <div class="fingerprint-hero panel">
      <div class="fingerprint-hero-copy">
        <span class="fingerprint-eyebrow">MODEL FINGERPRINT</span>
        <h2>从随机数字序列反推模型身份</h2>
        <p>
          每次检测生成三道随机数题，要求模型输出 292–332 个 1–355 的整数，再与本地参考库中的
          68 个模型比对。仅使用上游返回的文本，不依赖厂商声明；结果代表闭集内的相对相似度，
          不是确定性的来源鉴定。
        </p>
      </div>
      <div v-if="detectorInfo" class="fingerprint-bank">
        <div><span>参考模型</span><strong>{{ detectorInfo.modelCount }}</strong></div>
        <div><span>参考库</span><strong>{{ formatIqTime(parseFingerprintBankTime(detectorInfo.bankBuiltAt)) }}</strong></div>
        <div class="fingerprint-bank-sha" :title="detectorInfo.detectorSha256">
          <span>检测器</span><strong>{{ detectorInfo.detectorSha256.slice(0, 12) }}…</strong>
        </div>
        <small>基于 lm-detector（MIT）本地参考库，检测过程不联网。</small>
      </div>
    </div>

    <div v-if="loading" class="panel fingerprint-loading">
      <span class="spinner"></span>
      <p>正在加载参考库与历史记录…</p>
    </div>

    <div v-else-if="loadError" class="callout danger fingerprint-load-error">
      <span>{{ loadError }}</span>
      <button type="button" class="link-button" @click="loadAll">重试</button>
    </div>

    <div v-else class="fingerprint-layout">
      <aside class="fingerprint-control panel">
        <div class="fingerprint-section">
          <span class="fingerprint-section-label">检测目标</span>
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
                  {{ target.model }}
                </option>
              </optgroup>
            </select>
          </label>
          <p v-else class="fingerprint-empty-copy">
            还没有可检测的模型。请先在「渠道」中授权模型，并至少配置一个可用凭据。
          </p>

          <div v-if="selectedTarget" class="fingerprint-target-grid">
            <label class="field">
              <span>使用凭据</span>
              <select v-model="selectedKeyId" :disabled="running">
                <option v-for="credential in selectedTarget.credentials" :key="credential.id" :value="credential.id">
                  {{ credential.name }}（优先级 {{ credential.priority }}）
                </option>
              </select>
            </label>
            <label class="field">
              <span>协议</span>
              <select v-model="selectedProtocol" :disabled="running">
                <option v-for="protocol in selectedTarget.protocols" :key="protocol" :value="protocol">
                  {{ modelTestProtocolLabel(protocol) }}
                </option>
              </select>
            </label>
          </div>
        </div>

        <div class="fingerprint-run-card">
          <span class="fingerprint-section-label">本次检测</span>
          <ul class="fingerprint-steps">
            <li><span>1</span>生成三道数字题</li>
            <li><span>2</span>并行请求同一模型</li>
            <li><span>3</span>本地比对参考库</li>
          </ul>
          <button type="button" class="button primary full fingerprint-run" :disabled="!canRun" @click="run">
            <Icon :name="running ? 'refresh' : 'fingerprint'" :size="17" :class="{ 'is-spinning': running }" />
            {{ running ? `正在检测… ${elapsed}s` : '开始指纹检测' }}
          </button>
          <p class="fingerprint-run-note">
            三条请求并行发出，通常需要 30–120 秒；渠道较慢时最长等待 4 分钟。每次运行的题面都重新生成。
          </p>
        </div>

        <p class="fingerprint-disclaimer">
          <Icon name="info" :size="14" />
          检测结果只反映本次回答与参考库的相似程度，无法证明真实部署模型，也不应用作封禁或计费的唯一依据。
        </p>
      </aside>

      <div class="fingerprint-main">
        <div v-if="runError" class="callout danger fingerprint-run-error">
          <span>{{ runError }}</span>
        </div>

        <section v-if="running" class="panel fingerprint-running">
          <span class="spinner"></span>
          <div>
            <strong>正在等待 {{ selectedTarget?.model }} 的三条回答</strong>
            <p>已等待 {{ elapsed }} 秒。样本会逐个返回，全部结束后一次性给出排名。</p>
          </div>
        </section>

        <section v-else-if="detailLoading" class="panel fingerprint-running">
          <span class="spinner"></span>
          <p>正在读取检测详情…</p>
        </section>

        <template v-else-if="result && analysis">
          <section ref="resultRef" class="panel fingerprint-result">
            <header class="fingerprint-result-head">
              <div class="fingerprint-result-title">
                <span class="badge" :class="decisionTone">{{ fingerprintDecisionLabel(analysis.decision) }}</span>
                <h3>{{ result.model }}</h3>
                <span v-if="analysis.predictionName" class="fingerprint-prediction-inline">
                  → {{ analysis.predictionName }}
                </span>
              </div>
              <div class="fingerprint-result-meta">
                <span>{{ formatIqTime(result.createdAt) }}</span>
                <span>{{ result.channelName }}</span>
                <span>{{ modelTestProtocolLabel(result.protocol) }}</span>
                <span>{{ formatIqLatency(result.latencyMs) }}</span>
              </div>
            </header>

            <div class="fingerprint-summary">
              <div class="fingerprint-summary-main">
                <span>{{ predictionLabel }}</span>
                <strong>{{ analysis.predictionName || '无法判定' }}</strong>
                <small v-if="analysis.prediction">{{ analysis.prediction }}</small>
              </div>
              <div>
                <span>相似概率</span>
                <strong>{{ formatFingerprintProbability(analysis.probability) }}</strong>
                <small>{{ analysis.probabilityStatus === 'reference_calibrated' ? '参考库内相对值' : '校准不可用' }}</small>
              </div>
              <div>
                <span>模型家族</span>
                <strong>{{ analysis.familyPredictionName || '无法判定' }}</strong>
                <small v-if="analysis.familyPrediction">{{ analysis.familyPrediction }}</small>
              </div>
              <div>
                <span>有效样本</span>
                <strong>{{ analysis.usedOutputs }} / {{ analysis.totalOutputs }}</strong>
                <small>{{ analysis.method }}</small>
              </div>
              <div>
                <span>排名分数</span>
                <strong>{{ formatFingerprintScore(analysis.rankingScore) }}</strong>
                <small>越小越接近参考回答</small>
              </div>
              <div>
                <span>上游用量</span>
                <strong>{{ usageText }}</strong>
                <small>{{ result.channelKeyName || '未记录凭据' }}</small>
              </div>
            </div>

            <p v-if="result.errorMessage" class="callout danger fingerprint-error">{{ result.errorMessage }}</p>
            <p v-if="evidence" class="callout info fingerprint-evidence">
              <strong>{{ analysis.evidenceLabel }}：</strong>{{ evidence }}
            </p>

            <div class="fingerprint-candidates">
              <div class="fingerprint-section-head">
                <span class="fingerprint-section-label">候选模型</span>
                <span class="muted small">{{ candidates.length }} 个参考模型 · 按分数从近到远</span>
              </div>
              <ol v-if="visibleCandidates.length" class="fingerprint-candidate-list">
                <li v-for="(candidate, index) in visibleCandidates" :key="candidate.model">
                  <span class="fingerprint-rank">{{ index + 1 }}</span>
                  <div class="fingerprint-candidate-body">
                    <div class="fingerprint-candidate-line">
                      <strong>{{ candidate.displayName }}</strong>
                      <span class="badge neutral">{{ candidate.familyName }}</span>
                      <span class="muted small">{{ formatFingerprintScore(candidate.score) }}</span>
                      <span class="fingerprint-candidate-prob">{{ formatFingerprintProbability(candidate.probability) }}</span>
                    </div>
                    <div class="fingerprint-bar" aria-hidden="true">
                      <span :style="{ width: `${visibleWidths[index]}%` }"></span>
                    </div>
                  </div>
                </li>
              </ol>
              <p v-else class="muted small">本次没有达到排名条件，暂无候选列表。可查看下方样本诊断。</p>
              <button
                v-if="candidates.length > 10"
                type="button"
                class="link-button"
                @click="showAllCandidates = !showAllCandidates"
              >
                {{ showAllCandidates ? '收起，只看前 10 名' : `展开全部 ${candidates.length} 个候选` }}
              </button>
            </div>

            <div class="fingerprint-detail-grid">
              <details class="fingerprint-detail" open>
                <summary>样本诊断</summary>
                <div class="fingerprint-diagnostic-list">
                  <div v-for="diagnostic in analysis.diagnostics" :key="diagnostic.index" class="fingerprint-diagnostic">
                    <span class="badge" :class="diagnostic.accepted ? 'success' : 'warning'">
                      {{ diagnostic.accepted ? '有效' : '无效' }}
                    </span>
                    <strong>样本 {{ diagnostic.index + 1 }}</strong>
                    <span>{{ diagnostic.parsedNumbers }} / 至少 {{ diagnostic.minimumNumbers }} 个整数</span>
                    <span v-if="diagnostic.rawOutOfRangeCount" class="muted small">
                      越界 {{ diagnostic.rawOutOfRangeCount }} 个
                    </span>
                  </div>
                </div>
              </details>

              <details v-if="analysis.sequence" class="fingerprint-detail">
                <summary>序列特征</summary>
                <div class="fingerprint-sequence">
                  <div><span>一阶自相关</span><strong>{{ analysis.sequence.autocorrelation.toFixed(3) }}</strong></div>
                  <div><span>小步长占比</span><strong>{{ (analysis.sequence.smallStepShare * 100).toFixed(1) }}%</strong></div>
                  <div><span>像模型输出</span><strong>{{ analysis.sequence.modelLike ? '是' : '否' }}</strong></div>
                </div>
              </details>
            </div>

            <div class="fingerprint-samples">
              <div class="fingerprint-section-head">
                <span class="fingerprint-section-label">原始回答</span>
                <span class="muted small">保存最近 100 次检测，单条回答截断至 20000 字符</span>
              </div>
              <details v-for="sample in samples" :key="sample.index" class="fingerprint-sample">
                <summary>
                  <span class="badge" :class="sample.accepted ? 'success' : 'warning'">
                    {{ sample.accepted ? '有效' : '无效' }}
                  </span>
                  样本 {{ sample.index + 1 }}
                  <span class="muted small">
                    {{ fingerprintSampleStatus(sample.accepted, sample.parsedNumbers, sample.minimumNumbers) }}
                    · {{ formatIqLatency(sample.latencyMs) }}
                    <template v-if="sample.statusCode"> · HTTP {{ sample.statusCode }}</template>
                  </span>
                </summary>
                <div class="fingerprint-sample-body">
                  <p v-if="sample.reportedModel" class="muted small">上游声明模型：{{ sample.reportedModel }}</p>
                  <p v-if="sample.errorMessage" class="callout danger fingerprint-sample-error">{{ sample.errorMessage }}</p>
                  <details class="fingerprint-prompt">
                    <summary>查看题面（要求 {{ sample.expectedCount }} 个数）</summary>
                    <pre>{{ sample.prompt }}</pre>
                  </details>
                  <pre v-if="sample.text" class="fingerprint-sample-text">{{ sample.text }}</pre>
                  <p v-else class="muted small">上游没有返回文本。</p>
                </div>
              </details>
            </div>

            <p class="fingerprint-footer muted small">
              检测器 {{ analysis.detectorSha256.slice(0, 16) }}… · 参考库构建于
              {{ formatIqTime(parseFingerprintBankTime(analysis.bankBuiltAt)) }} · 基于
              <a href="https://github.com/Ikaleio/lm-detector" target="_blank" rel="noreferrer">lm-detector</a>
              的参考库，MIT 许可。
            </p>
          </section>
        </template>

        <section v-else class="panel fingerprint-intro">
          <Icon name="fingerprint" :size="26" />
          <h3>还没有检测记录</h3>
          <p>选择检测目标并开始。首次检测会并行发送三条数字题，完成后在这里展示排名、样本与历史记录。</p>
        </section>

        <section class="panel fingerprint-history">
          <div class="fingerprint-section-head">
            <span class="fingerprint-section-label">检测历史</span>
            <div class="fingerprint-history-actions">
              <span class="muted small">最近 {{ history.length }} 条</span>
              <button type="button" class="link-button" :disabled="historyLoading" @click="loadHistory">
                {{ historyLoading ? '刷新中…' : '刷新' }}
              </button>
            </div>
          </div>
          <div v-if="historyLoading && !history.length" class="fingerprint-history-empty">
            <span class="spinner"></span>
          </div>
          <div v-else-if="!history.length" class="fingerprint-history-empty muted small">
            完成一次检测后，历史会出现在这里。
          </div>
          <ul v-else class="fingerprint-history-list">
            <li
              v-for="run in history"
              :key="run.id"
              class="fingerprint-history-item"
              :class="{ active: result?.id === run.id }"
            >
              <button type="button" class="fingerprint-history-button" @click="openRun(run)">
                <span class="badge" :class="fingerprintDecisionTone(run.decision)">
                  {{ fingerprintDecisionLabel(run.decision) }}
                </span>
                <span class="fingerprint-history-model">
                  <strong>{{ run.model }}</strong>
                  <small>{{ run.channelName }} · {{ formatIqTime(run.createdAt) }}</small>
                </span>
                <span class="fingerprint-history-prediction">
                  {{ run.predictionName || '无法判定' }}
                  <small>{{ formatFingerprintProbability(run.probability) }}</small>
                </span>
                <span class="muted small fingerprint-history-samples">{{ run.usedOutputs }}/{{ run.totalOutputs }}</span>
              </button>
            </li>
          </ul>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.fingerprint-page {
  max-width: 1360px;
  margin: 0 auto;
}

.fingerprint-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 24px;
  background:
    radial-gradient(circle at 6% 0%, rgba(14, 165, 233, 0.12), transparent 44%),
    var(--panel);
}

.fingerprint-hero-copy {
  min-width: 0;
}

.fingerprint-eyebrow {
  color: var(--brand);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.fingerprint-hero h2 {
  margin: 6px 0 8px;
  font-size: 22px;
  line-height: 1.25;
}

.fingerprint-hero p {
  max-width: 760px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}

.fingerprint-bank {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(3, auto);
  gap: 6px 18px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg);
}

.fingerprint-bank div {
  display: grid;
  gap: 2px;
}

.fingerprint-bank span {
  color: var(--muted);
  font-size: 10.5px;
}

.fingerprint-bank strong {
  font-size: 13px;
}

.fingerprint-bank small {
  grid-column: 1 / -1;
  color: var(--muted);
  font-size: 11px;
}

.fingerprint-layout {
  display: grid;
  grid-template-columns: minmax(280px, 330px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.fingerprint-control {
  position: sticky;
  top: 16px;
  display: grid;
  gap: 18px;
  padding: 18px;
}

.fingerprint-section,
.fingerprint-run-card {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.fingerprint-section-label {
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.fingerprint-section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.fingerprint-target-grid {
  display: grid;
  gap: 10px;
}

.fingerprint-empty-copy {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.fingerprint-steps {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.fingerprint-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12.5px;
}

.fingerprint-steps span {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--brand) 12%, transparent);
  color: var(--brand);
  font-size: 11px;
  font-weight: 700;
}

.fingerprint-run {
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

.fingerprint-run-note,
.fingerprint-disclaimer {
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

.fingerprint-disclaimer {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.fingerprint-disclaimer :deep(svg) {
  flex: 0 0 auto;
  margin-top: 1px;
}

.fingerprint-main {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.fingerprint-loading,
.fingerprint-running {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px;
}

.fingerprint-running p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
}

.fingerprint-intro {
  display: grid;
  place-items: center;
  gap: 8px;
  min-height: 260px;
  padding: 32px;
  text-align: center;
}

.fingerprint-intro h3 {
  margin: 4px 0 0;
}

.fingerprint-intro p {
  max-width: 560px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.fingerprint-result {
  display: grid;
  gap: 16px;
  padding: 20px;
  min-width: 0;
}

.fingerprint-result-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.fingerprint-result-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.fingerprint-result-title h3 {
  margin: 0;
  font-size: 17px;
}

.fingerprint-prediction-inline {
  color: var(--brand);
  font-size: 13px;
  font-weight: 650;
}

.fingerprint-result-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}

.fingerprint-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--line);
}

.fingerprint-summary > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 12px 14px;
  background: var(--bg);
}

.fingerprint-summary span {
  color: var(--muted);
  font-size: 11.5px;
}

.fingerprint-summary strong {
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.4;
}

.fingerprint-summary small {
  color: var(--muted);
  font-size: 10.5px;
  line-height: 1.4;
}

.fingerprint-summary-main strong {
  color: var(--brand);
  font-size: 16px;
}

.fingerprint-error,
.fingerprint-evidence,
.fingerprint-sample-error {
  margin: 0;
}

.fingerprint-candidates {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.fingerprint-candidate-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.fingerprint-candidate-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.fingerprint-rank {
  flex: 0 0 22px;
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.fingerprint-candidate-body {
  display: grid;
  flex: 1 1 auto;
  gap: 5px;
  min-width: 0;
}

.fingerprint-candidate-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.fingerprint-candidate-line strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fingerprint-candidate-prob {
  margin-left: auto;
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.fingerprint-bar {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--line);
}

.fingerprint-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--brand);
  opacity: 0.75;
}

.fingerprint-candidate-list li:first-child .fingerprint-bar span {
  background: var(--brand);
  opacity: 1;
}

.fingerprint-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
  align-items: start;
}

.fingerprint-detail,
.fingerprint-sample {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}

.fingerprint-detail > summary,
.fingerprint-sample > summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 650;
  list-style: none;
}

.fingerprint-detail > summary::-webkit-details-marker,
.fingerprint-sample > summary::-webkit-details-marker,
.fingerprint-prompt > summary::-webkit-details-marker {
  display: none;
}

.fingerprint-detail > summary::after,
.fingerprint-sample > summary::after,
.fingerprint-prompt > summary::after {
  content: '▾';
  margin-left: auto;
  color: var(--muted);
  font-size: 11px;
  transition: transform 0.15s ease;
}

.fingerprint-detail[open] > summary::after,
.fingerprint-sample[open] > summary::after,
.fingerprint-prompt[open] > summary::after {
  transform: rotate(180deg);
}

.fingerprint-diagnostic-list {
  display: grid;
  gap: 8px;
  padding: 0 12px 12px;
}

.fingerprint-diagnostic,
.fingerprint-sequence div {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.fingerprint-diagnostic strong {
  font-size: 12px;
}

.fingerprint-sequence {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 8px;
  padding: 0 12px 12px;
}

.fingerprint-sequence div {
  justify-content: space-between;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
}

.fingerprint-sequence span {
  color: var(--muted);
}

.fingerprint-samples {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.fingerprint-sample-body {
  display: grid;
  gap: 8px;
  padding: 0 12px 12px;
  min-width: 0;
}

.fingerprint-prompt {
  min-width: 0;
}

.fingerprint-prompt > summary {
  color: var(--muted);
  font-size: 11.5px;
  cursor: pointer;
  list-style: none;
}

.fingerprint-prompt pre,
.fingerprint-sample-text {
  max-height: 280px;
  margin: 8px 0 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--ink);
  font-size: 11.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.fingerprint-footer {
  margin: 0;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.fingerprint-footer a {
  color: var(--brand);
}

.fingerprint-history {
  display: grid;
  gap: 10px;
  padding: 16px 18px;
}

.fingerprint-history-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.fingerprint-history-empty {
  display: grid;
  place-items: center;
  min-height: 60px;
  padding: 12px;
  text-align: center;
}

.fingerprint-history-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.fingerprint-history-item {
  border: 1px solid transparent;
  border-radius: 10px;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.fingerprint-history-item:hover {
  background: var(--bg);
}

.fingerprint-history-item.active {
  border-color: color-mix(in srgb, var(--brand) 35%, transparent);
  background: color-mix(in srgb, var(--brand) 6%, transparent);
}

.fingerprint-history-button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.fingerprint-history-model {
  display: grid;
  flex: 1 1 auto;
  gap: 1px;
  min-width: 0;
}

.fingerprint-history-model strong {
  overflow: hidden;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fingerprint-history-model small {
  overflow: hidden;
  color: var(--muted);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fingerprint-history-prediction {
  display: grid;
  flex: 0 0 auto;
  gap: 1px;
  color: var(--ink);
  font-size: 12px;
  text-align: right;
}

.fingerprint-history-prediction small {
  color: var(--muted);
  font-size: 10.5px;
}

.fingerprint-history-samples {
  flex: 0 0 auto;
  font-variant-numeric: tabular-nums;
}

.fingerprint-load-error,
.fingerprint-run-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

@media (max-width: 1080px) {
  .fingerprint-hero {
    flex-direction: column;
    align-items: flex-start;
  }

  .fingerprint-bank {
    width: 100%;
  }
}

@media (max-width: 960px) {
  .fingerprint-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .fingerprint-control {
    position: static;
  }
}

@media (max-width: 640px) {
  .fingerprint-history-prediction {
    display: none;
  }

  .fingerprint-candidate-line strong {
    white-space: normal;
  }
}
</style>
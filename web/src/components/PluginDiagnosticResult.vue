<script setup lang="ts">
import { computed } from 'vue'
import type { PluginExecution } from '../types/api'
import { formatDuration } from '../utils/format'
import { formatModelTestUsage, modelTestErrorView, modelTestProtocolLabel } from '../utils/modelTest'
import { pluginExecutionToModelTestResult } from '../utils/plugin'

const props = defineProps<{ execution: PluginExecution }>()
const result = computed(() => pluginExecutionToModelTestResult(props.execution))
const error = computed(() => modelTestErrorView(result.value))
const usage = computed(() => formatModelTestUsage(result.value.usage))
</script>

<template>
  <article class="panel plugin-result" :class="execution.outcome.success ? 'is-success' : 'is-danger'">
    <header class="plugin-result-head">
      <div class="plugin-result-title">
        <strong>{{ execution.target.model }}</strong>
        <span class="muted small">{{ execution.target.displayName }} · {{ execution.target.credentialName || '已授权凭据' }} · {{ modelTestProtocolLabel(execution.target.protocol) }}</span>
      </div>
      <div class="plugin-result-badges">
        <span class="badge" :class="execution.outcome.success ? 'success' : 'danger'">{{ execution.outcome.success ? '探测成功' : '探测失败' }}</span>
        <span class="badge neutral">HTTP {{ execution.outcome.statusCode ?? '—' }}</span>
        <span class="muted small">{{ formatDuration(execution.outcome.latencyMs) }}</span>
      </div>
    </header>
    <strong class="plugin-report-title">{{ execution.report.title }}</strong>
    <dl v-if="execution.report.metrics.length" class="plugin-result-metrics">
      <div v-for="(metric, index) in execution.report.metrics" :key="index"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd></div>
    </dl>
    <div class="plugin-result-model">
      <span>上游声明模型 <code>{{ execution.outcome.upstreamReportedModel || '未返回' }}</code></span>
      <small>这是上游响应声明的值，不能独立证明实际执行的模型。</small>
    </div>
    <small v-if="usage" class="muted">{{ usage }}</small>
    <div v-if="error" class="plugin-result-error">
      <strong>{{ error.title }}</strong>
      <p v-if="error.hint">{{ error.hint }}</p>
      <code v-if="error.detail">{{ error.detail }}</code>
    </div>
    <ul v-if="execution.report.notices.length" class="plugin-result-notices">
      <li v-for="(notice, index) in execution.report.notices" :key="index">{{ notice }}</li>
    </ul>
    <details v-if="execution.outcome.responseText" class="plugin-result-text">
      <summary>上游响应</summary><pre>{{ execution.outcome.responseText }}</pre>
    </details>
    <details v-if="execution.report.text && execution.report.text !== execution.outcome.responseText" class="plugin-result-text">
      <summary>报告文本</summary><pre>{{ execution.report.text }}</pre>
    </details>
    <small class="muted">{{ execution.pluginId }} · 生效版本 {{ execution.activeVersion }}</small>
  </article>
</template>

<style scoped>
.plugin-result { display: grid; flex-shrink: 0; gap: 10px; min-width: 0; padding: 16px; }
.plugin-result.is-success { border-color: color-mix(in srgb, var(--success) 30%, var(--glass-line)); }
.plugin-result.is-danger { border-color: color-mix(in srgb, var(--danger) 30%, var(--glass-line)); }
.plugin-result-head, .plugin-result-badges { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 8px; }
.plugin-result-head { justify-content: space-between; gap: 12px; }
.plugin-result-title, .plugin-result-model, .plugin-result-error { display: grid; gap: 4px; min-width: 0; overflow-wrap: anywhere; }
.plugin-result-title strong { font-size: 14px; }
.plugin-report-title { font-size: 13px; overflow-wrap: anywhere; }
.plugin-result-metrics { display: flex; flex-wrap: wrap; gap: 12px 24px; margin: 0; font-size: 12px; }
.plugin-result-metrics div { display: grid; gap: 3px; min-width: 0; }
.plugin-result-metrics dt, .plugin-result-model, .plugin-result-error p, .plugin-result-notices { color: var(--muted); font-size: 12px; line-height: 1.6; }
.plugin-result-metrics dd { margin: 0; overflow-wrap: anywhere; }
.plugin-result-error p { margin: 0; }
.plugin-result-notices { margin: 0; padding-left: 18px; overflow-wrap: anywhere; }
.plugin-result-text { font-size: 12px; }
.plugin-result-text summary { width: fit-content; min-height: 36px; align-content: center; cursor: pointer; }
.plugin-result-text pre { max-height: 280px; margin: 4px 0 0; padding: 12px; overflow: auto; border: 1px solid var(--glass-line); border-radius: 8px; background: var(--glass-field); color: var(--ink); white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
.plugin-result-text summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
@media (max-width: 720px) { .plugin-result-text summary { min-height: 44px; } }
</style>

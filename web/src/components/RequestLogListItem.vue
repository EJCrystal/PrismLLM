<script setup lang="ts">
import type { RequestLog } from '../types/api'
import { formatCost, formatDuration, formatNumber, formatTime } from '../utils/format'
import { requestLogOutcome, requestLogStatusLabel, requestLogTone, tokenUsageLabel, tokenUsageState } from '../utils/requestLogDisplay'
import { shouldOpenRequestLog } from '../utils/requestLogQuery'
import CopyButton from './CopyButton.vue'

defineProps<{ log: RequestLog; active?: boolean }>()
const emit = defineEmits<{ open: [event: MouseEvent | KeyboardEvent] }>()
function open(event: MouseEvent | KeyboardEvent) {
  if (!shouldOpenRequestLog(event)) return
  if ('key' in event) event.preventDefault()
  emit('open', event)
}
</script>

<template>
  <article class="log-card" :data-request-id="log.requestId" :class="[requestLogTone(log) ? 'log-row-' + requestLogTone(log) : '', { 'is-selected': active }]"
    :aria-current="active ? 'true' : undefined" :aria-label="log.alias + '，HTTP ' + log.statusCode" tabindex="0" @click="open" @keydown="open">
    <div class="log-card-heading">
      <strong class="log-card-model" :title="log.alias">{{ log.alias }}</strong>
      <span class="badge" :class="requestLogTone(log) === 'danger' ? 'danger' : requestLogTone(log) === 'warning' ? 'warning' : requestLogOutcome(log) === 'other' ? 'neutral' : 'success'">{{ log.statusCode }}</span>
      <span class="log-card-result">{{ requestLogStatusLabel(log) || (requestLogOutcome(log) === 'other' ? '其他结果' : '成功') }}</span>
    </div>
    <div class="log-card-route"><span :title="log.channelName || log.channelId || ''">{{ log.channelName || (log.channelId ? log.channelId.slice(0, 8) : '未路由') }}</span><time>{{ formatTime(log.createdAt) }}</time></div>
    <dl class="log-card-metrics">
      <div><dt>总耗时</dt><dd>{{ formatDuration(log.totalMs) }}</dd></div>
      <div><dt>Token</dt><dd>{{ tokenUsageState(log) === 'known' ? formatNumber(log.totalTokens) : tokenUsageLabel(log) }}</dd></div>
      <div><dt>费用</dt><dd>{{ log.costAmount != null ? formatCost(log.costAmount, log.costCurrency || 'USD') : log.usageKnown ? '未配置' : '—' }}</dd></div>
    </dl>
    <p v-if="log.errorMessage || log.errorCode" class="log-card-error">{{ log.errorMessage || log.errorCode }}</p>
    <div class="log-card-footer">
      <div class="request-id-cell"><code :title="log.requestId">{{ log.requestId.slice(0, 12) }}…</code><CopyButton :text="log.requestId" title="复制请求 ID" variant="list" :size="13" /></div>
      <button type="button" class="button subtle mini" :aria-label="'查看 ' + log.alias + ' 请求详情'" @click.stop="emit('open', $event)">详情</button>
    </div>
  </article>
</template>

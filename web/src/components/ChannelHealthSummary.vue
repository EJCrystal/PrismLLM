<script setup lang="ts">
import { computed } from 'vue'
import type { ChannelHealthSummary } from '../types/api'
import { HEALTH_LABELS, channelListHealthState, channelHealthSuccessRateText, healthAttemptTitle, healthTrendSlots } from '../utils/channelHealth'
import { formatTime } from '../utils/format'
import Icon from './Icon.vue'

const props = defineProps<{
  channel: { name: string; enabled: boolean }
  summary?: ChannelHealthSummary
  loading: boolean
  error: string
  observedAt?: number
}>()
defineEmits<{ open: [] }>()
const recent = computed(() => props.summary?.recent)
const state = computed(() => channelListHealthState(props.channel, recent.value?.state))
const stateText = computed(() => recent.value || !props.channel.enabled ? HEALTH_LABELS[state.value] : props.loading ? '汇总中…' : props.error ? '读取失败' : '未知')
const rate = computed(() => channelHealthSuccessRateText(props.summary, props.loading, props.error))
const slots = computed(() => healthTrendSlots(props.summary?.recentAttempts))
const attempts = computed(() => props.summary?.recentAttempts ?? [])
const failureCount = computed(() => attempts.value.filter(attempt => attempt.outcome === 'FAILURE').length)
const historyText = computed(() => attempts.value.length ? '最近 ' + attempts.value.length + ' 次' : props.loading ? '汇总中…' : props.error && !props.summary ? '读取失败' : '暂无记录')
const reason = computed(() => recent.value?.reason || (props.error ? '健康度读取失败，请刷新重试' : '暂无近 1 小时观测'))
const trendLabel = computed(() => attempts.value.length
  ? '24 小时内最近 ' + attempts.value.length + ' 次有效上游尝试，' + failureCount.value + ' 次失败；从左到右由早到晚'
  : props.loading ? '正在汇总近期尝试' : props.error && !props.summary ? '近期尝试读取失败，请刷新重试' : '24 小时内暂无有效上游尝试')
const actionLabel = computed(() => props.channel.name + '：近 1 小时' + stateText.value + '，' + reason.value + '；24 小时成功率 ' + rate.value + '；' + trendLabel.value + '。查看健康详情')
const freshness = computed(() => props.error && props.summary ? '刷新失败 · 显示上次数据' : '')
const observationTitle = computed(() => [
  recent.value ? '近 1 小时：' + recent.value.metrics.attempts + ' 次 / ' + recent.value.metrics.failures + ' 失败；' + reason.value : reason.value,
  props.summary ? '24h：' + props.summary.metrics.attempts + ' 次尝试；成功率 ' + rate.value : '24h 暂无观测',
  trendLabel.value,
  props.observedAt ? '上次更新：' + formatTime(props.observedAt) : '',
  freshness.value,
].filter(Boolean).join('；'))
</script>

<template>
  <button type="button" class="channel-health-overview" :aria-label="actionLabel" :title="observationTitle" @click="$emit('open')">
    <span class="health-overview-metrics">
      <span class="health-overview-metric">
        <span class="health-overview-label">近 1 小时状态</span>
        <span class="health-overview-state" :data-state="state" :title="reason">
          <span class="health-overview-dot" aria-hidden="true"></span>{{ stateText }}
        </span>
      </span>
      <span class="health-overview-metric health-overview-rate">
        <span class="health-overview-label">24h 成功率</span>
        <strong>{{ rate }}</strong>
      </span>
    </span>
    <span v-if="attempts.length" class="health-overview-trend" role="img" :aria-label="trendLabel">
      <span v-for="(attempt, index) in slots" :key="index" class="health-overview-bar" :data-outcome="attempt?.outcome ?? 'EMPTY'" :title="attempt ? healthAttemptTitle(attempt) : undefined" aria-hidden="true"></span>
    </span>
    <span class="health-overview-footer">
      <span :title="trendLabel">{{ historyText }}</span>
      <span class="health-overview-link">健康详情<Icon name="chevron" :size="12" /></span>
    </span>
    <span v-if="freshness" class="health-overview-stale" :title="observedAt ? '上次更新：' + formatTime(observedAt) : undefined">{{ freshness }}</span>
  </button>
</template>

<style scoped>
.channel-health-overview { display: grid; gap: 5px; width: 100%; min-width: 0; padding: 0; border: 0; border-radius: 0; background: transparent; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
.channel-health-overview:hover .health-overview-link { color: var(--brand); }
.channel-health-overview:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; border-radius: 4px; }
.health-overview-metrics { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px; }
.health-overview-metric { display: grid; align-content: start; justify-items: start; gap: 2px; min-width: 0; }
.health-overview-rate { padding-left: 10px; border-left: 1px solid var(--line); }
.health-overview-label, .health-overview-footer { color: var(--muted); font-size: 11px; line-height: 1.4; }
.health-overview-label { display: flex; align-items: center; min-height: var(--channel-first-line-height, 20px); line-height: 20px; white-space: nowrap; }
.health-overview-state { display: inline-flex; align-items: center; justify-content: flex-start; gap: 5px; min-height: 20px; font-size: 12px; font-weight: 650; line-height: 20px; }
.health-overview-state[data-state='HEALTHY'] { color: var(--success); }
.health-overview-state[data-state='WARNING'] { color: var(--warning); }
.health-overview-state[data-state='CRITICAL'], .health-overview-state[data-state='NO_CREDENTIALS'] { color: var(--danger); }
.health-overview-state[data-state='UNKNOWN'], .health-overview-state[data-state='DISABLED'] { color: var(--muted); }
.health-overview-dot { width: 6px; height: 6px; flex: 0 0 6px; border-radius: 50%; background: currentColor; }
.health-overview-rate strong { min-height: 20px; font-size: 16px; line-height: 20px; font-weight: 700; font-variant-numeric: tabular-nums; }
.health-overview-trend { display: grid; grid-template-columns: repeat(30, minmax(0, 1fr)); align-items: end; gap: 2px; height: 12px; }
.health-overview-bar { height: 100%; border-radius: 2px; background: var(--line); }
.health-overview-bar[data-outcome='SUCCESS'] { background: var(--success); }
.health-overview-bar[data-outcome='FAILURE'] { height: 55%; background: var(--danger); }
.health-overview-bar[data-outcome='EMPTY'] { height: 25%; }
.health-overview-footer { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 4px 12px; font-size: 10px; }
.health-overview-link { display: inline-flex; align-items: center; gap: 2px; }
.health-overview-stale { color: var(--warning); font-size: 10px; }
</style>

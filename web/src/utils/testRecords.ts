import type { FingerprintRun, IqTestRunSummary } from '../types/api'
import { fingerprintDecisionLabel, fingerprintDecisionTone } from './fingerprint'
import { iqVerdictTone } from './iqTest'

export type TestRecordType = 'iq' | 'fingerprint'
export type TestRecordTone = 'success' | 'warning' | 'danger'

/** 智商与指纹记录归一后的展示行；详情仍按类型回到各自的完整视图。 */
export interface TestRecordRow {
  type: TestRecordType
  id: number
  createdAt: number
  channelId: string
  channelName: string
  channelKeyName: string | null
  model: string
  protocol: string
  success: boolean
  statusCode: number | null
  latencyMs: number
  summary: string
  tone: TestRecordTone
}

export interface TestRecordFilters {
  type: '' | TestRecordType
  channelId: string
  model: string
  result: '' | TestRecordTone
}

export const EMPTY_TEST_RECORD_FILTERS: TestRecordFilters = {
  type: '',
  channelId: '',
  model: '',
  result: '',
}

export function testRecordTypeLabel(type: TestRecordType): string {
  return type === 'iq' ? '智商' : '指纹'
}

export function iqTestRecord(run: IqTestRunSummary): TestRecordRow {
  return {
    type: 'iq',
    id: run.id,
    createdAt: run.createdAt,
    channelId: run.channelId,
    channelName: run.channelName,
    channelKeyName: run.channelKeyName,
    model: run.model,
    protocol: run.protocol,
    success: run.success,
    statusCode: run.statusCode,
    latencyMs: run.latencyMs,
    summary: `${run.benchmarkLabel} · ${run.verdictLabel}`,
    // 请求失败按失败展示；incorrect（答错或人工判定未通过）也归入“未通过”，与筛选口径一致。
    tone: run.success ? (run.verdict === 'incorrect' ? 'danger' : iqVerdictTone(run.verdict)) : 'danger',
  }
}

export function fingerprintTestRecord(run: FingerprintRun): TestRecordRow {
  const decision = fingerprintDecisionLabel(run.decision)
  return {
    type: 'fingerprint',
    id: run.id,
    createdAt: run.createdAt,
    channelId: run.channelId,
    channelName: run.channelName,
    channelKeyName: run.channelKeyName,
    model: run.model,
    protocol: run.protocol,
    success: run.success,
    statusCode: run.statusCode,
    latencyMs: run.latencyMs,
    summary: run.predictionName && run.decision === 'scored' ? `${decision} · ${run.predictionName}` : decision,
    tone: run.success ? fingerprintDecisionTone(run.decision) : 'danger',
  }
}

/** 合并两类记录并按时间倒序；同一毫秒内用 id 稳定排序。 */
export function mergeTestRecords(
  iqRuns: IqTestRunSummary[],
  fingerprintRuns: FingerprintRun[],
): TestRecordRow[] {
  return [...iqRuns.map(iqTestRecord), ...fingerprintRuns.map(fingerprintTestRecord)].sort(
    (left, right) => right.createdAt - left.createdAt || right.id - left.id,
  )
}

export function filterTestRecords(rows: TestRecordRow[], filters: TestRecordFilters): TestRecordRow[] {
  return rows.filter(
    (row) =>
      (!filters.type || row.type === filters.type) &&
      (!filters.channelId || row.channelId === filters.channelId) &&
      (!filters.model || row.model === filters.model) &&
      (!filters.result || row.tone === filters.result),
  )
}

/** 渠道选项来自已加载记录，渠道被删除后仍能按当时的名称筛选。 */
export function testRecordChannelOptions(rows: TestRecordRow[]): Array<{ value: string; label: string }> {
  const channels = new Map<string, string>()
  for (const row of rows) {
    if (!channels.has(row.channelId)) channels.set(row.channelId, row.channelName)
  }
  return [...channels.entries()]
    .map(([value, label]) => ({ value, label }))
    .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'))
}

/** 模型选项跟随渠道筛选；未选渠道时列出全部记录里出现过的模型。 */
export function testRecordModelOptions(
  rows: TestRecordRow[],
  channelId = '',
): Array<{ value: string; label: string }> {
  const models = new Set<string>()
  for (const row of rows) {
    if (!channelId || row.channelId === channelId) models.add(row.model)
  }
  return [...models]
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))
    .map((model) => ({ value: model, label: model }))
}

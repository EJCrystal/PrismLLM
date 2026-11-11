import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  EMPTY_TEST_RECORD_FILTERS,
  filterTestRecords,
  fingerprintTestRecord,
  iqTestRecord,
  mergeTestRecords,
  testRecordChannelOptions,
  testRecordModelOptions,
} from '../src/utils/testRecords'

function iqRun(overrides = {}) {
  return {
    id: 1,
    benchmark: 'pelican',
    benchmarkLabel: '鹈鹕',
    verdict: 'passed',
    verdictLabel: '通过',
    success: true,
    channelId: 'channel-a',
    channelName: '渠道 A',
    channelKeyId: null,
    channelKeyName: '默认密钥',
    model: 'gpt-4.1',
    upstreamModel: 'gpt-4.1-2025',
    protocol: 'chat_completions',
    statusCode: 200,
    latencyMs: 1234,
    createdAt: 1_700_000_000_000,
    errorMessage: null,
    usage: null,
    judgeResult: null,
    manualVerdict: null,
    manualScore: null,
    manualNote: null,
    manualUpdatedAt: null,
    ...overrides,
  }
}

function fingerprintRun(overrides = {}) {
  return {
    id: 2,
    channelId: 'channel-b',
    channelName: '渠道 B',
    channelKeyId: null,
    channelKeyName: null,
    model: 'claude-sonnet-5-5',
    protocol: 'messages',
    success: true,
    statusCode: 200,
    latencyMs: 800,
    decision: 'scored',
    prediction: 'anthropic',
    predictionName: 'Anthropic 系列',
    probability: 0.8,
    usedOutputs: 3,
    totalOutputs: 3,
    createdAt: 1_700_000_060_000,
    errorMessage: null,
    usage: null,
    ...overrides,
  }
}

describe('testRecords: 智商与指纹记录归一', () => {
  it('智商记录保留题型与判定，并按判定着色', () => {
    const passed = iqTestRecord(iqRun())
    assert.equal(passed.type, 'iq')
    assert.equal(passed.summary, '鹈鹕 · 通过')
    assert.equal(passed.tone, 'success')

    const review = iqTestRecord(iqRun({ id: 3, verdict: 'review', verdictLabel: '需复核' }))
    assert.equal(review.tone, 'warning')

    const incorrect = iqTestRecord(iqRun({ id: 4, verdict: 'incorrect', verdictLabel: '回答错误' }))
    assert.equal(incorrect.tone, 'warning')

    const failed = iqTestRecord(iqRun({ id: 5, verdict: 'failed', verdictLabel: '请求失败' }))
    assert.equal(failed.tone, 'danger')
  })

  it('请求失败时即使判定是兜底值也按失败着色', () => {
    const failed = iqTestRecord(iqRun({
      success: false,
      verdict: 'review',
      verdictLabel: '需复核',
      statusCode: 502,
      errorMessage: '上游不可达',
    }))
    assert.equal(failed.tone, 'danger')
    assert.equal(failed.statusCode, 502)
  })

  it('指纹记录为已完成排名补充预测模型，非排名结论不拼接', () => {
    const scored = fingerprintTestRecord(fingerprintRun())
    assert.equal(scored.type, 'fingerprint')
    assert.equal(scored.summary, '已完成排名 · Anthropic 系列')
    assert.equal(scored.tone, 'success')

    const partial = fingerprintTestRecord(fingerprintRun({
      decision: 'partial',
      predictionName: 'Anthropic 系列',
    }))
    assert.equal(partial.summary, '部分样本排名')
    assert.equal(partial.tone, 'warning')

    const failed = fingerprintTestRecord(fingerprintRun({ success: false, decision: 'scored' }))
    assert.equal(failed.tone, 'danger')
  })

  it('两类记录按时间倒序合并', () => {
    const rows = mergeTestRecords(
      [iqRun({ id: 10, createdAt: 1_000 })],
      [fingerprintRun({ id: 20, createdAt: 2_000 }), fingerprintRun({ id: 21, createdAt: 500 })],
    )
    assert.deepEqual(rows.map((row) => `${row.type}-${row.id}`), ['fingerprint-20', 'iq-10', 'fingerprint-21'])
  })
})

describe('testRecords: 筛选与选项', () => {
  const rows = mergeTestRecords(
    [
      iqRun({ id: 1, channelId: 'a', channelName: '渠道 A', model: 'gpt-4.1', createdAt: 300 }),
      iqRun({ id: 2, channelId: 'b', channelName: '渠道 B', model: 'claude', success: false, verdict: 'failed', verdictLabel: '失败', createdAt: 200 }),
    ],
    [fingerprintRun({ id: 3, channelId: 'a', channelName: '渠道 A', model: 'claude', createdAt: 100 })],
  )

  it('空筛选返回全部记录', () => {
    assert.equal(filterTestRecords(rows, { ...EMPTY_TEST_RECORD_FILTERS }).length, 3)
  })

  it('按类型、渠道、模型和结论组合筛选', () => {
    assert.deepEqual(
      filterTestRecords(rows, { ...EMPTY_TEST_RECORD_FILTERS, type: 'iq' }).map((row) => row.id),
      [1, 2],
    )
    assert.deepEqual(
      filterTestRecords(rows, { ...EMPTY_TEST_RECORD_FILTERS, channelId: 'a' }).map((row) => row.id),
      [1, 3],
    )
    assert.deepEqual(
      filterTestRecords(rows, { ...EMPTY_TEST_RECORD_FILTERS, model: 'claude' }).map((row) => row.id),
      [2, 3],
    )
    assert.deepEqual(
      filterTestRecords(rows, { ...EMPTY_TEST_RECORD_FILTERS, type: 'iq', channelId: 'a', model: 'gpt-4.1' }).map((row) => row.id),
      [1],
    )
    assert.deepEqual(
      filterTestRecords(rows, { ...EMPTY_TEST_RECORD_FILTERS, result: 'danger' }).map((row) => row.id),
      [2],
    )
  })

  it('渠道选项去重并保留历史名称', () => {
    assert.deepEqual(testRecordChannelOptions(rows), [
      { value: 'a', label: '渠道 A' },
      { value: 'b', label: '渠道 B' },
    ])
  })

  it('模型选项跟随渠道筛选', () => {
    assert.deepEqual(testRecordModelOptions(rows).map((option) => option.value), ['claude', 'gpt-4.1'])
    assert.deepEqual(testRecordModelOptions(rows, 'a').map((option) => option.value), ['claude', 'gpt-4.1'])
    assert.deepEqual(testRecordModelOptions(rows, 'b').map((option) => option.value), ['claude'])
  })
})

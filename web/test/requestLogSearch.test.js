import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { filterRequestLogs, requestLogFilterOptions, UNROUTED_CHANNEL } from '../src/utils/requestLogSearch'

const logs = [
  {
    requestId: 'req-404',
    traceId: 'trace-a',
    alias: 'deepseek-flash',
    channelId: 'channel-a',
    channelName: 'primary',
    channelKeyName: 'key-alpha',
    upstreamModel: 'deepseek-v4.1-flash',
    protocol: 'chat_completions',
    statusCode: 404,
    errorCode: 'model_not_found',
    failureStage: 'routing',
  },
  {
    requestId: 'req-200',
    alias: 'qwen3.8-flash',
    channelName: 'secondary',
    statusCode: 200,
    errorCode: null,
  },
]

describe('requestLogSearch: 请求日志当前页搜索', () => {
  it('可以用 HTTP 状态码搜索，例如 404', () => {
    assert.deepEqual(filterRequestLogs(logs, '404').map((log) => log.requestId), ['req-404'])
  })

  it('覆盖请求 ID、模型、渠道和错误码', () => {
    assert.equal(filterRequestLogs(logs, 'REQ-200').length, 1)
    assert.equal(filterRequestLogs(logs, 'deepseek-v4.1').length, 1)
    assert.equal(filterRequestLogs(logs, 'secondary').length, 1)
    assert.equal(filterRequestLogs(logs, 'key-alpha').length, 1)
    assert.equal(filterRequestLogs(logs, 'model_not_found').length, 1)
  })

  it('空搜索返回全部当前已加载记录', () => {
    assert.equal(filterRequestLogs(logs, '  '), logs)
  })

  it('组合渠道、请求模型、状态与关键词筛选', () => {
    assert.deepEqual(filterRequestLogs(logs, 'req', { channelId: 'channel-a', model: 'deepseek-flash', status: 'error' }), [logs[0]])
    assert.deepEqual(filterRequestLogs(logs, '', { channelId: 'channel-a', model: 'qwen3.8-flash' }), [])
    assert.deepEqual(filterRequestLogs(logs, '', { status: 'success' }), [logs[1]])
  })

  it('精确匹配请求模型，避免混淆上游模型和模型名前缀', () => {
    assert.deepEqual(filterRequestLogs(logs, '', { model: 'deepseek-v4.1-flash' }), [])
    assert.deepEqual(filterRequestLogs(logs, '', { model: 'deepseek' }), [])
    assert.deepEqual(filterRequestLogs(logs, '', { status: '404' }), [logs[0]])
    assert.deepEqual(filterRequestLogs(logs, '', { status: '499' }), [])
  })

  it('成功与异常状态分组保留明确的 HTTP 范围', () => {
    const rows = [200, 299, 302, 400, 499, 500, 599, 0].map(statusCode => ({ statusCode }))
    assert.deepEqual(filterRequestLogs(rows, '', { status: 'success' }).map(row => row.statusCode), [200, 299])
    assert.deepEqual(filterRequestLogs(rows, '', { status: 'error' }).map(row => row.statusCode), [400, 499, 500, 599])
  })

  it('同名渠道独立筛选，未路由记录单独处理', () => {
    const rows = [
      { channelId: 'id-one', channelName: 'same', alias: 'model-a', statusCode: 200 },
      { channelId: 'id-two', channelName: 'same', alias: 'model-b', statusCode: 499 },
      { channelId: null, channelName: null, alias: 'model-a', statusCode: 503 },
    ]
    const options = requestLogFilterOptions(rows)
    assert.deepEqual(options.channels, [
      { value: 'id-one', label: 'same · id-one' },
      { value: 'id-two', label: 'same · id-two' },
      { value: UNROUTED_CHANNEL, label: '未路由到渠道' },
    ])
    assert.deepEqual(options.models.map(item => item.value), ['model-a', 'model-b'])
    assert.deepEqual(options.statuses.map(item => item.value), ['200', '499', '503'])
    assert.deepEqual(filterRequestLogs(rows, '', { channelId: 'id-two' }), [rows[1]])
    assert.deepEqual(filterRequestLogs(rows, '', { channelId: UNROUTED_CHANNEL }), [rows[2]])
    assert.deepEqual(requestLogFilterOptions([]), { channels: [], models: [], statuses: [] })
  })
})

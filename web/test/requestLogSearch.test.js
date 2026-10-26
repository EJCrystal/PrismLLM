import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { filterRequestLogs } from '../src/utils/requestLogSearch.js'

const logs = [
  {
    requestId: 'req-404',
    traceId: 'trace-a',
    alias: 'deepseek-flash',
    channelId: 'channel-a',
    channelName: 'primary',
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
    assert.equal(filterRequestLogs(logs, 'model_not_found').length, 1)
  })

  it('空搜索返回全部当前已加载记录', () => {
    assert.equal(filterRequestLogs(logs, '  '), logs)
  })
})

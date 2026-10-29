import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildRequestOverrides, createRequestOverrideDrafts, normalizeRequestOverrides, REQUEST_OVERRIDE_SCOPES } from '../src/utils/requestOverrides.js'
import { buildChannelPayload, buildPriorityUpdatePayload } from '../src/utils/channelForm.js'

describe('上游请求覆盖配置', () => {
  it('配置范围明确区分通用配置和协议专用覆盖', () => {
    assert.equal(REQUEST_OVERRIDE_SCOPES[0].label, '通用配置（所有协议）')
    assert.ok(REQUEST_OVERRIDE_SCOPES.slice(1).every((scope) => scope.label.endsWith('（仅该协议）')))
  })

  it('切换协议的草稿分别保留，保存时一起提交', () => {
    const drafts = createRequestOverrideDrafts()
    drafts.common.userAgent = 'global-UA'
    drafts.responses.bodyText = '{"reasoning":{"effort":"high"},"store":null}'
    drafts.messages.headersText = '{"anthropic-beta":"test-beta"}'
    drafts.gemini.headerProfile = 'antigravity'
    const value = buildRequestOverrides(drafts)
    assert.equal(value.userAgent, 'global-UA')
    assert.equal(value.protocols.responses.body.reasoning.effort, 'high')
    assert.equal(value.protocols.responses.body.store, null)
    assert.equal(value.protocols.messages.headers['anthropic-beta'], 'test-beta')
    assert.equal(value.protocols.gemini.headerProfile, 'antigravity')
    assert.equal(Object.hasOwn(value.protocols, 'chat_completions'), false)
  })

  it('重新打开保存过的配置时保留删除规则和显式清空 UA', () => {
    const original = normalizeRequestOverrides({
      inheritGlobal: false, userAgent: '', headers: { 'X-Remove': null },
      protocols: { gemini: { headerProfile: 'google', body: { generationConfig: { temperature: 0.7 } } } },
    })
    assert.deepEqual(buildRequestOverrides(createRequestOverrideDrafts(original), false), original)
  })

  it('渠道保存携带配置，单独修改优先级时不覆盖配置', () => {
    const requestOverrides = { userAgent: 'channel-UA' }
    const form = { name: 'c', baseUrl: 'https://example.com/v1', protocols: ['responses'], enabled: true, requestOverrides }
    assert.deepEqual(buildChannelPayload(form, [], {}).requestOverrides, requestOverrides)
    assert.equal(Object.hasOwn(buildPriorityUpdatePayload(form, 2), 'requestOverrides'), false)
  })

  it('保存时提示其他协议中未修正的 JSON，而不是丢弃草稿', () => {
    const drafts = createRequestOverrideDrafts()
    drafts.messages.bodyText = '{broken'
    assert.throws(() => buildRequestOverrides(drafts), /Anthropic Messages请求体不是有效的 JSON/)
    assert.equal(drafts.messages.bodyText, '{broken')
  })

  it('拒绝非法请求头、凭据和传输头覆盖，以及 model/stream 改写', () => {
    for (const headers of [
      { Authorization: 'secret' }, { 'Content-Encoding': 'gzip' }, { 'cOnTeNt-EnCoDiNg': 'br' },
      { 'Accept-Encoding': 'gzip' }, { 'aCcEpT-EnCoDiNg': 'br' }, { 'Accept-Encoding': null },
      { 'Content-Encoding': null }, { 'X-A': 'a', 'x-a': 'b' }, { 'X-A': 'x\r\ny' }, { 'X-A': 1 },
    ]) {
      for (const scope of ['common', 'gemini']) {
        const drafts = createRequestOverrideDrafts()
        drafts[scope].headersText = JSON.stringify(headers)
        assert.throws(() => buildRequestOverrides(drafts))
      }
    }
    for (const body of [{ model: 'other' }, { stream: null }, [], null]) {
      const drafts = createRequestOverrideDrafts()
      drafts.gemini.bodyText = JSON.stringify(body)
      assert.throws(() => buildRequestOverrides(drafts))
    }
  })
})

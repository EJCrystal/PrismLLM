import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildRequestOverrides, createRequestOverrideDrafts, normalizeRequestOverrides, requestOverrideDraftSnapshot, REQUEST_OVERRIDE_SCOPES } from '../src/utils/requestOverrides'
import { computed, reactive, ref } from 'vue'
import { buildChannelPayload, buildPriorityUpdatePayload } from '../src/utils/channelForm'

describe('上游请求覆盖配置', () => {
  it('草稿快照追踪无效 JSON 和继承设置，恢复后不再标记未保存', () => {
    const drafts = reactive(createRequestOverrideDrafts({ userAgent: 'saved-UA' }))
    const inherit = ref(true)
    const baseline = requestOverrideDraftSnapshot(drafts, inherit.value)
    const dirty = computed(() => requestOverrideDraftSnapshot(drafts, inherit.value) !== baseline)
    assert.equal(dirty.value, false)
    drafts.responses.bodyText = '{unfinished'
    assert.equal(dirty.value, true)
    assert.throws(() => buildRequestOverrides(drafts), /不是有效的 JSON/)
    assert.equal(dirty.value, true)
    drafts.responses.bodyText = '{}'
    assert.equal(dirty.value, false)
    inherit.value = false
    assert.equal(dirty.value, true)
    inherit.value = true
    assert.equal(dirty.value, false)
  })

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
    const requestOverrides = normalizeRequestOverrides({
      userAgent: 'channel-UA',
      extraBody: { metadata: { thread: '${threadId}' } },
      protocols: { responses: { extraBody: { prompt_cache_key: '${sessionId}' } } },
    })
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

  it('旧配置缺省 extraBody 时保持为空，不自动添加缓存字段', () => {
    const value = normalizeRequestOverrides({ userAgent: 'legacy-UA', protocols: { responses: { body: { store: false } } } })
    assert.deepEqual(value.extraBody, {})
    assert.deepEqual(value.protocols.responses.extraBody, {})
    assert.deepEqual(buildRequestOverrides(createRequestOverrideDrafts(value)), value)
    assert.deepEqual(buildRequestOverrides(createRequestOverrideDrafts()).protocols, {})
  })

  it('各范围仅配置 extraBody 时读取、保存和重新打开都不丢失', () => {
    const extraBody = {
      prompt_cache_key: '${sessionId}',
      '!metadata': { thread: '${threadId}', nested: ['agent-${subagentId}', { session: '${sessionId}' }] },
      explicit_null: null,
      '!replace_null': null,
      empty: '',
      disabled: false,
      count: 0,
      ' spaced ': 'preserve draft spelling',
    }
    for (const { id } of REQUEST_OVERRIDE_SCOPES) {
      const source = id === 'common' ? { extraBody } : { protocols: { [id]: { extraBody } } }
      const normalized = normalizeRequestOverrides(source)
      const drafts = createRequestOverrideDrafts(normalized)
      assert.deepEqual(JSON.parse(drafts[id].extraBodyText), extraBody)
      const saved = buildRequestOverrides(drafts)
      assert.deepEqual(id === 'common' ? saved.extraBody : saved.protocols[id].extraBody, extraBody)
      assert.deepEqual(buildRequestOverrides(createRequestOverrideDrafts(saved)), saved)
    }
  })

  it('extraBody 草稿与 body、请求头分别保留，变量保存为原始模板', () => {
    const drafts = createRequestOverrideDrafts()
    drafts.common.extraBodyText = '{"tenant":"shared"}'
    drafts.responses.bodyText = '{"store":null,"reasoning":{"effort":"high"},"messages":[]}'
    drafts.responses.extraBodyText = '{"prompt_cache_key":"${sessionId}","!reasoning":{"summary":"auto"},"store":null}'
    drafts.responses.headersText = '{"X-Session":"${sessionId}","X-Thread":"${threadId}","X-Agent":"${subagentId}","X-Remove":null}'
    drafts.gemini.extraBodyText = '{"generationConfig":{"temperature":0.7}}'
    const saved = buildRequestOverrides(drafts, false)
    assert.equal(saved.inheritGlobal, false)
    assert.deepEqual(saved.extraBody, { tenant: 'shared' })
    assert.deepEqual(saved.protocols.responses.body, { store: null, reasoning: { effort: 'high' }, messages: [] })
    assert.deepEqual(saved.protocols.responses.extraBody, { prompt_cache_key: '${sessionId}', '!reasoning': { summary: 'auto' }, store: null })
    assert.deepEqual(saved.protocols.responses.headers, { 'X-Session': '${sessionId}', 'X-Thread': '${threadId}', 'X-Agent': '${subagentId}', 'X-Remove': null })
    assert.deepEqual(saved.protocols.gemini.extraBody, { generationConfig: { temperature: 0.7 } })
    assert.deepEqual(buildRequestOverrides(createRequestOverrideDrafts(saved), false), saved)
  })

  it('extraBody 未完成或无效 JSON 仍计入 dirty snapshot，恢复后清除标记', () => {
    const drafts = reactive(createRequestOverrideDrafts())
    const baseline = requestOverrideDraftSnapshot(drafts, true)
    const dirty = computed(() => requestOverrideDraftSnapshot(drafts, true) !== baseline)
    drafts.responses.extraBodyText = '{unfinished'
    assert.equal(dirty.value, true)
    assert.throws(() => buildRequestOverrides(drafts), /Responses请求体补充（extraBody）不是有效的 JSON/)
    assert.equal(drafts.responses.extraBodyText, '{unfinished')
    drafts.responses.extraBodyText = '{"prompt_cache_key":"${sessionId}"}'
    assert.equal(dirty.value, true)
    drafts.responses.extraBodyText = '{}'
    assert.equal(dirty.value, false)
  })

  it('extraBody 必须是 JSON 对象，未显示范围的错误也阻止保存', () => {
    for (const { id, errorLabel } of REQUEST_OVERRIDE_SCOPES) {
      for (const text of ['{broken', '[]', 'null', '"text"', '1']) {
        const drafts = createRequestOverrideDrafts()
        drafts[id].extraBodyText = text
        assert.throws(() => buildRequestOverrides(drafts), error => error.message.startsWith(`${errorLabel}请求体补充（extraBody）`))
        assert.equal(drafts[id].extraBodyText, text)
      }
      const drafts = createRequestOverrideDrafts()
      drafts[id].extraBodyText = '  '
      assert.deepEqual(buildRequestOverrides(drafts), buildRequestOverrides(createRequestOverrideDrafts()))
    }
  })

  it('extraBody 拒绝空字段名与去 !、trim 后重复的目标名', () => {
    const invalid = [
      { '': 1 }, { '   ': 1 }, { '!': 1 }, { ' !   ': 1 },
      { foo: 1, '!foo': 2 }, { ' foo ': 1, foo: 2 }, { '!foo': 1, ' ! foo ': 2 },
    ]
    for (const { id } of REQUEST_OVERRIDE_SCOPES) {
      for (const extraBody of invalid) {
        const drafts = createRequestOverrideDrafts()
        drafts[id].extraBodyText = JSON.stringify(extraBody)
        assert.throws(() => buildRequestOverrides(drafts), /字段名不能为空|字段名规范化后不能重复/)
      }
    }
  })

  it('extraBody 普通和强制规则均禁止改写保留的顶层字段', () => {
    const reserved = ['model', 'stream', 'messages', 'input', 'contents', 'system', 'systemInstruction', 'system_instruction', 'instructions', 'tools']
    for (const { id } of REQUEST_OVERRIDE_SCOPES) {
      for (const name of reserved) {
        for (const field of [name, `!${name}`, ` ${name} `, ` ! ${name} `]) {
          const drafts = createRequestOverrideDrafts()
          drafts[id].extraBodyText = JSON.stringify({ [field]: null })
          assert.throws(() => buildRequestOverrides(drafts), /不能修改保留字段/)
        }
      }
      const drafts = createRequestOverrideDrafts()
      drafts[id].extraBodyText = '{"metadata":{"model":"nested","tools":[]}}'
      assert.doesNotThrow(() => buildRequestOverrides(drafts))
    }
  })

  it('body 与 extraBody 均保留 32 层限制，嵌套数组也计入深度', () => {
    for (const { id } of REQUEST_OVERRIDE_SCOPES) {
      for (const field of ['bodyText', 'extraBodyText']) {
        for (const wrap of [value => ({ nested: value }), value => [value]]) {
          const drafts = createRequestOverrideDrafts()
          let nested = 'value'
          for (let depth = 0; depth < 31; depth += 1) nested = wrap(nested)
          drafts[id][field] = JSON.stringify({ value: nested })
          assert.doesNotThrow(() => buildRequestOverrides(drafts))
          drafts[id][field] = JSON.stringify({ value: wrap(nested) })
          assert.throws(() => buildRequestOverrides(drafts), /嵌套不能超过 32 层/)
        }
      }
    }
  })

  it('extraBody 与其他范围共同受整体 64KB UTF-8 大小限制', () => {
    const drafts = createRequestOverrideDrafts()
    drafts.common.headersText = '{"X-Session":"${sessionId}"}'
    drafts.responses.bodyText = '{"store":false}'
    drafts.gemini.extraBodyText = '{"metadata":""}'
    const overhead = new TextEncoder().encode(JSON.stringify(buildRequestOverrides(drafts))).length
    drafts.gemini.extraBodyText = JSON.stringify({ metadata: 'a'.repeat(65536 - overhead) })
    assert.equal(new TextEncoder().encode(JSON.stringify(buildRequestOverrides(drafts))).length, 65536)
    drafts.gemini.extraBodyText = JSON.stringify({ metadata: 'a'.repeat(65537 - overhead) })
    assert.throws(() => buildRequestOverrides(drafts), /不能超过 64KB/)
    drafts.gemini.extraBodyText = JSON.stringify({ metadata: '汉'.repeat(22000) })
    assert.throws(() => buildRequestOverrides(drafts), /不能超过 64KB/)
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

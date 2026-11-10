import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api'

describe('IQ question bank API', () => {
  it('loads questions beyond the first page', async (t) => {
    const original = globalThis.fetch
    t.after(() => { globalThis.fetch = original })
    const urls = []
    globalThis.fetch = async (url) => {
      urls.push(String(url))
      const offset = Number(new URL(String(url), 'http://localhost').searchParams.get('offset'))
      const items = offset === 0 ? Array.from({ length: 200 }, (_, id) => ({ id })) : [{ id: 200 }]
      return new Response(JSON.stringify(items))
    }
    const questions = await api.listIqQuestions()
    assert.equal(questions.length, 201)
    assert.equal(questions.at(-1).id, 200)
    assert.equal(urls.length, 2)
    assert.ok(urls[1].endsWith('offset=200'))
  })
  it('writes with CSRF and accepts empty delete responses', async (t) => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    t.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: 'gw_csrf=test-csrf' }
    const calls = []
    globalThis.fetch = async (url, init) => {
      calls.push({ url, ...init })
      return init.method === 'DELETE' ? new Response(null, { status: 204 }) : new Response(JSON.stringify({ id: 1 }))
    }
    const input = { title: 'text', prompt: 'question', answerType: 'text', expectedAnswer: 'hello', enabled: true }
    await api.createIqQuestion(input)
    await api.updateIqQuestion(1, input)
    await api.deleteIqQuestion(1)
    assert.deepEqual(calls.map(call => call.method), ['POST', 'PUT', 'DELETE'])
    assert.ok(calls.every(call => call.headers.get('X-CSRF-Token') === 'test-csrf'))
    assert.deepEqual(JSON.parse(calls[0].body), input)
  })
})

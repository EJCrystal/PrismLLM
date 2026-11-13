import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { api } from '../src/api'
import { requestLogCustomRange, requestLogPresetRange, shouldOpenRequestLog } from '../src/utils/requestLogQuery'
import { requestLogOutcome } from '../src/utils/requestLogDisplay'

describe('request log server queries', () => {
  it('uses precise rolling presets without imposing an all-history limit', () => {
    const now = 1794555000123
    assert.deepEqual(requestLogPresetRange('last24h', now), { from: now - 86_400_000, to: now })
    assert.deepEqual(requestLogPresetRange('last15m', now), { from: now - 900_000, to: now })
    assert.deepEqual(requestLogPresetRange('last1h', now), { from: now - 3_600_000, to: now })
    assert.deepEqual(requestLogPresetRange('all', now), {})
  })

  it('includes the complete selected ending minute and rejects invalid ranges', () => {
    const range = requestLogCustomRange('2026-11-13T10:00', '2026-11-13T10:00')
    assert.equal(range.to - range.from, 60_000)
    assert.throws(() => requestLogCustomRange('', '2026-11-13T10:00'), /有效/)
    assert.throws(() => requestLogCustomRange('2026-11-13T11:00', '2026-11-13T10:00'), /晚于/)
  })

  it('keeps server outcomes authoritative and handles legacy outcomes consistently', () => {
    assert.equal(requestLogOutcome({ statusCode: 200, errorCode: 'stream_interrupted' }), 'failed')
    assert.equal(requestLogOutcome({ statusCode: 499 }), 'cancelled')
    assert.equal(requestLogOutcome({ statusCode: 200, outcome: 'recovered' }), 'recovered')
    assert.equal(requestLogOutcome({ statusCode: 302 }), 'other')
    assert.equal(requestLogOutcome({ statusCode: 200, attempts: [{ skipped: true, statusCode: 503 }] }), 'success')
    assert.equal(requestLogOutcome({ statusCode: 200, attempts: [{ statusCode: 200 }, { statusCode: 200 }] }), 'recovered')
  })

  it('serializes all filters with cursors while options only receive time bounds', async (context) => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    context.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: '' }
    const urls = []
    globalThis.fetch = async (url) => {
      urls.push(new URL(url, 'http://localhost'))
      return new Response(JSON.stringify(url.includes('filter-options') ? { channels: [], models: [], statuses: [] } : []))
    }
    const query = { from: 0, to: 200, q: '%_ + 中文', channelId: '__unrouted__', model: 'model/name', httpStatus: '2xx', outcome: 'failed' }
    await api.listRequestLogs({ ...query, limit: 25, beforeId: 50 })
    await api.listRequestLogFilterOptions(query)
    for (const [key, value] of Object.entries({ ...query, limit: 25, beforeId: 50 })) assert.equal(urls[0].searchParams.get(key), String(value))
    assert.deepEqual([...urls[1].searchParams.keys()].sort(), ['from', 'to'])
  })

  it('does not open rows for controls, text selection or unrelated keys', (context) => {
    const dom = new JSDOM('<article><span>request</span><button>copy</button><a href="#">link</a></article>')
    const originalElement = globalThis.Element
    const originalWindow = globalThis.window
    globalThis.Element = dom.window.Element
    globalThis.window = dom.window
    context.after(() => { globalThis.Element = originalElement; globalThis.window = originalWindow; dom.window.close() })
    const row = dom.window.document.querySelector('article')
    assert.equal(shouldOpenRequestLog({ target: row.querySelector('span'), currentTarget: row }), true)
    for (const target of row.querySelectorAll('button,a')) assert.equal(shouldOpenRequestLog({ target, currentTarget: row }), false)
    assert.equal(shouldOpenRequestLog({ target: row, currentTarget: row, key: 'Enter' }), true)
    assert.equal(shouldOpenRequestLog({ target: row, currentTarget: row, key: 'ArrowDown' }), false)
    const selection = dom.window.getSelection()
    selection.selectAllChildren(row.querySelector('span'))
    assert.equal(shouldOpenRequestLog({ target: row, currentTarget: row }), false)
  })
})

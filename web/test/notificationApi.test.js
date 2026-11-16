import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api'

describe('notification admin API contract', () => {
  it('uses the settings and target CRUD routes with the exact payloads', async t => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    t.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: 'gw_csrf=notify-csrf' }
    const target = {
      id: 'target/one', name: '值班群', type: 'feishu', enabled: false,
      events: ['channel.unhealthy'], channelIds: [], environmentPrefix: 'PRISM_NOTIFY_ONCALL',
      revision: 2, updatedAt: 100, credentials: { urlConfigured: false, bearerTokenConfigured: false, signingSecretConfigured: true }, latestDelivery: null,
    }
    const calls = []
    globalThis.fetch = async (input, init = {}) => {
      calls.push({ url: String(input), method: init.method || 'GET', body: init.body, csrf: init.headers.get('X-CSRF-Token') })
      if (init.method === 'DELETE') return new Response(null, { status: 204 })
      if (String(input) === '/admin/api/notification-targets') return new Response(JSON.stringify([target]))
      return new Response(JSON.stringify(String(input).includes('notification-settings')
        ? { enabled: false, consecutiveFailureThreshold: 5, updatedAt: 99 }
        : target))
    }

    await api.getNotificationSettings()
    const settingsPayload = { enabled: true, consecutiveFailureThreshold: 12 }
    await api.updateNotificationSettings(settingsPayload)
    await api.listNotificationTargets()
    const targetPayload = {
      name: '值班群', type: 'feishu', enabled: false, events: ['channel.unhealthy'],
      channelIds: [], environmentPrefix: 'PRISM_NOTIFY_ONCALL',
    }
    await api.createNotificationTarget(targetPayload)
    await api.updateNotificationTarget('target/one', targetPayload)
    await api.deleteNotificationTarget('target/one')

    assert.deepEqual(calls.map(({ method, url }) => [method, url]), [
      ['GET', '/admin/api/notification-settings'],
      ['PUT', '/admin/api/notification-settings'],
      ['GET', '/admin/api/notification-targets'],
      ['POST', '/admin/api/notification-targets'],
      ['PUT', '/admin/api/notification-targets/target%2Fone'],
      ['DELETE', '/admin/api/notification-targets/target%2Fone'],
    ])
    assert.deepEqual(JSON.parse(calls[1].body), settingsPayload)
    assert.deepEqual(JSON.parse(calls[3].body), targetPayload)
    assert.deepEqual(JSON.parse(calls[4].body), targetPayload)
    assert.deepEqual(calls.filter(call => call.method !== 'GET').map(call => call.csrf), Array(4).fill('notify-csrf'))
  })

  it('enqueues a test and paginates deliveries with encoded filters and abort signals', async t => {
    const originalFetch = globalThis.fetch
    const originalDocument = globalThis.document
    t.after(() => { globalThis.fetch = originalFetch; globalThis.document = originalDocument })
    globalThis.document = { cookie: '' }
    const calls = []
    globalThis.fetch = async (input, init = {}) => {
      calls.push({ url: String(input), method: init.method || 'GET', signal: init.signal })
      if ((init.method || 'GET') === 'POST') return new Response(JSON.stringify({ deliveryId: 'delivery-1', eventId: 'event-1' }), { status: 202 })
      return new Response(JSON.stringify({ items: [], total: 0, page: 3, pageSize: 10 }))
    }
    const controller = new AbortController()
    assert.deepEqual(await api.testNotificationTarget('target/one', { signal: controller.signal }), { deliveryId: 'delivery-1', eventId: 'event-1' })
    assert.deepEqual(await api.listNotificationDeliveries({ targetId: 'target/one', page: 3, pageSize: 10 }, { signal: controller.signal }), { items: [], total: 0, page: 3, pageSize: 10 })
    assert.equal(calls[0].url, '/admin/api/notification-targets/target%2Fone/test')
    assert.equal(calls[0].method, 'POST')
    assert.equal(calls[0].signal, controller.signal)
    const query = new URL(calls[1].url, 'http://localhost').searchParams
    assert.equal(new URL(calls[1].url, 'http://localhost').pathname, '/admin/api/notification-deliveries')
    assert.equal(query.get('targetId'), 'target/one')
    assert.equal(query.get('page'), '3')
    assert.equal(query.get('pageSize'), '10')
    assert.equal(calls[1].signal, controller.signal)
  })
})

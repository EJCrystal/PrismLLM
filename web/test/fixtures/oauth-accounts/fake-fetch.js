import { createOAuthAccountsFixture } from './accounts.js'

// Deliberately never captures/calls native fetch. All writes stay in this fresh fixture.
export function createOAuthFixtureFetch({ fixture = createOAuthAccountsFixture(), origin = 'http://127.0.0.1', ResponseClass = globalThis.Response } = {}) {
  const requests = []
  const respond = (value, status = 200) => new ResponseClass(status === 204 ? null : JSON.stringify(value), {
    status, headers: { 'Content-Type': 'application/json' },
  })
  const deny = (message) => respond({ error: { code: 'fixture_request_blocked', message } }, 403)
  const fetch = async (input, init = {}) => {
    const requestInput = typeof input === 'object' && input !== null && 'url' in input ? input : null
    const rawUrl = requestInput ? requestInput.url : String(input)
    const method = (init.method || requestInput?.method || 'GET').toUpperCase()
    const log = { method, url: rawUrl, path: '', body: null, allowed: false }
    requests.push(log)
    let url
    try { url = new URL(rawUrl, origin) } catch { return deny('Fixture blocks invalid URLs') }
    log.path = url.pathname
    if (url.origin !== origin || url.username || url.password || url.search || url.hash) return deny('Fixture blocks external or non-allowlisted URLs')
    if (method === 'GET') {
      const lists = { '/admin/api/oauth-accounts': fixture.accounts, '/admin/api/oauth-providers': fixture.providers, '/admin/api/channels': fixture.channels }
      if (Object.hasOwn(lists, url.pathname)) { log.allowed = true; return respond(lists[url.pathname]) }
      return deny('Fixture blocks this GET route')
    }
    const match = /^\/admin\/api\/oauth-accounts\/(fixture-[a-z0-9-]+)(?:\/(settings|policy|models|channels|refresh|models\/refresh|quota\/refresh|cooldown\/clear))?$/.exec(url.pathname)
    if (!match) return deny('Fixture blocks authentication, upstream tests and unmatched routes')
    const [, id, action] = match
    const account = fixture.accounts.find((item) => item.id === id)
    if (!account) return respond({ error: { code: 'fixture_not_found', message: 'Synthetic account not found' } }, 404)
    if (method === 'DELETE' && !action) {
      log.allowed = true
      fixture.accounts.splice(fixture.accounts.indexOf(account), 1)
      return respond(null, 204)
    }
    if (method === 'PUT' && ['settings', 'policy', 'models', 'channels'].includes(action)) {
      let body
      try {
        const raw = init.body ?? (requestInput ? await requestInput.clone().text() : '')
        body = JSON.parse(raw)
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error()
      } catch { return deny('Fixture accepts only object JSON bodies') }
      const keys = { settings: ['name', 'priority', 'enabled', 'proxyUrl', 'clearProxy'], policy: ['windowLimit', 'windowSeconds', 'maxConcurrentRequests'], models: ['models'], channels: ['channelIds'] }[action]
      if (Object.keys(body).some((key) => !keys.includes(key))) return deny('Fixture blocks unexpected settings fields')
      if (action === 'settings') {
        if (body.proxyUrl != null && body.proxyUrl !== '') return deny('Proxy configuration is disabled in this fixture')
        if ('name' in body && body.name != null && typeof body.name !== 'string') return deny('Invalid synthetic account name')
        if ('enabled' in body && typeof body.enabled !== 'boolean') return deny('Invalid enabled flag')
        if ('priority' in body && (!Number.isInteger(body.priority) || body.priority < 0)) return deny('Invalid priority')
        if (typeof body.name === 'string' && body.name.trim()) account.name = body.name.trim()
        if ('enabled' in body) account.enabled = body.enabled
        if ('priority' in body) account.priority = body.priority
        account.proxyUrl = null
      } else if (action === 'policy') {
        if (Object.entries(body).some(([key, value]) => !(key === 'windowLimit' && value === null) && (!Number.isInteger(value) || value < 1))) return deny('Invalid policy')
        Object.assign(account.policy, body)
      } else if (action === 'models') {
        if (!Array.isArray(body.models) || body.models.some((value) => typeof value !== 'string')) return deny('Invalid models')
        account.models = [...new Set(body.models)]
      } else {
        if (!Array.isArray(body.channelIds) || body.channelIds.some((id) => !fixture.channels.some((channel) => channel.id === id))) return deny('Unknown synthetic channel')
        account.channelIds = [...new Set(body.channelIds)]
        account.channels = fixture.channels.filter((channel) => account.channelIds.includes(channel.id)).map(({ id, name }) => ({ id, name }))
      }
      log.body = body
      log.allowed = true
      account.updatedAt = fixture.now
      return respond(account)
    }
    if (method === 'POST' && ['refresh', 'models/refresh', 'quota/refresh', 'cooldown/clear'].includes(action)) {
      if (init.body != null || requestInput?.body) return deny('Fixture refresh routes accept no body')
      log.allowed = true
      if (action === 'refresh') {
        account.lastRefreshedAt = fixture.now
        account.accessTokenExpiresAt = fixture.now + 3_600_000
      } else if (action === 'quota/refresh') {
        if (account.quota) account.quota.checkedAt = fixture.now
      } else if (action === 'models/refresh') {
        account.modelCatalog = [...new Set(fixture.channels.filter((c) => account.channelIds.includes(c.id)).flatMap((c) => c.models))]
        account.modelCatalogSyncedAt = fixture.now
        return respond({ models: account.modelCatalog.map((id) => ({ id, name: null })) })
      } else {
        account.runtime.cooldownUntil = null
        account.runtime.consecutiveRateLimits = 0
        return respond(null, 204)
      }
      return respond(account)
    }
    return deny('Fixture blocks this method/route combination')
  }
  return { fetch, requests, fixture }
}

export function installOAuthFixtureFetch(target = window, { now = target.Date.now(), expose = true } = {}) {
  // Browser event timestamps use the real clock; rebase only the synthetic deadlines.
  const mock = createOAuthFixtureFetch({ fixture: createOAuthAccountsFixture(now), origin: target.location.origin, ResponseClass: target.Response || globalThis.Response })
  target.fetch = mock.fetch
  if (expose) target.__oauthAccountsFixture = { now, requests: mock.requests, fixture: mock.fixture }
  return mock
}

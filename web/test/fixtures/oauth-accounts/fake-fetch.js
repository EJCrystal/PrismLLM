import { createOAuthAccountsFixture } from './accounts.js'

// No native fetch is captured or called. Even proxy strings are inert fixture data.
export function createOAuthFixtureFetch({ fixture = createOAuthAccountsFixture(), origin = 'http://127.0.0.1', ResponseClass = globalThis.Response } = {}) {
  const requests = []
  const respond = (value, status = 200) => new ResponseClass(status === 204 ? null : JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
  const error = (message, status = 400, code = 'fixture_validation') => respond({ error: { code, message } }, status)
  const deny = message => error(message, 403, 'fixture_request_blocked')
  const effective = mapping => {
    // Match ProviderModelMapping.mode: provenance marks an explicit override.
    mapping.mode = mapping.mappingSource == null ? 'auto' : mapping.canonicalModelId == null ? 'upstream' : 'manual'
    if (mapping.mode === 'auto') mapping.canonicalModelId = mapping.automaticCanonicalModelId
    mapping.publicModelId = mapping.canonicalModelId ?? mapping.upstreamModel
  }
  const readBody = async (init, input) => {
    const body = JSON.parse(init.body ?? (input ? await input.clone().text() : ''))
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('Object JSON required')
    return body
  }
  const fetch = async (input, init = {}) => {
    const requestInput = typeof input === 'object' && input !== null && 'url' in input ? input : null
    const rawUrl = requestInput ? requestInput.url : String(input)
    const method = (init.method || requestInput?.method || 'GET').toUpperCase()
    const log = { method, url: rawUrl, path: '', body: null, allowed: false }
    requests.push(log)
    let url
    try { url = new URL(rawUrl, origin) } catch { return deny('Fixture blocks invalid URLs') }
    log.path = url.pathname
    if (url.origin !== origin || url.username || url.password || url.hash) return deny('Fixture blocks external or non-allowlisted URLs')
    if (method === 'GET') {
      const lists = {
        '/admin/api/oauth-accounts': fixture.accounts,
        '/admin/api/antigravity/agy/accounts': fixture.agyAccounts ?? { configured: false, accounts: [] },
        '/admin/api/oauth-providers': fixture.providers,
        '/admin/api/channels': fixture.channels,
        '/admin/api/model-prices': [],
        '/admin/api/model-default-prices': [],
        '/admin/api/occupancy': { sampledAt: fixture.now, channels: [], clientKeys: [] },
        '/admin/api/audit-logs?limit=200&action=channel.probe_protocols': [],
        '/admin/api/channel-health?window=24h': {
          window: '24h', from: fixture.now - 86_400_000, to: fixture.now, retentionDays: 7, indexing: false,
          minimumSamples: 5, staleAfterMs: 300_000, warningErrorRate: 0.1, criticalErrorRate: 0.5,
          slowFirstTokenMs: 5_000, slowP50FirstTokenMs: 5_000, channels: [],
        },
      }
      const route = url.pathname + url.search
      if (Object.hasOwn(lists, route)) { log.allowed = true; return respond(lists[route]) }
      if (url.search) return deny('Fixture blocks non-allowlisted query strings')
      const match = /^\/admin\/api\/oauth-providers\/([a-z0-9-]+)\/model-mappings$/.exec(url.pathname)
      if (match && fixture.providerMappings[match[1]]) {
        log.allowed = true
        if (fixture.controls.failMappingReads > 0) { fixture.controls.failMappingReads--; return error('合成共享映射读取失败', 503) }
        const resource = fixture.providerMappings[match[1]]
        return respond({ ...resource, affectedAccountCount: fixture.accounts.filter(item => item.provider === match[1]).length })
      }
      return deny('Fixture blocks this GET route')
    }
    if (url.search) return deny('Fixture blocks non-allowlisted query strings')
    if (method === 'POST' && url.pathname === '/admin/api/model-suggestions') {
      let body
      try { body = await readBody(init, requestInput) } catch { return deny('Invalid suggestion JSON') }
      if (Object.keys(body).some(key => key !== 'models') || !Array.isArray(body.models) || body.models.some(value => typeof value !== 'string')) return deny('Invalid suggestion models')
      log.allowed = true; log.body = body
      if (fixture.controls.failSuggestions) return error('合成候选读取失败', 503)
      return respond({ loaded: true, error: null, data: body.models.map(model => ({ model, candidates: [{ id: model, canonicalId: `catalog/${model}`, provider: 'Fixture catalog', providerId: 'fixture', developer: 'Synthetic developer', developerId: 'fixture' }] })) })
    }
    const agyDelete = /^\/admin\/api\/antigravity\/agy\/accounts\/([^/]+)$/.exec(url.pathname)
    if (method === 'DELETE' && agyDelete) {
      const accounts = fixture.agyAccounts?.accounts ?? []
      const index = accounts.findIndex(account => account.id === agyDelete[1])
      if (index < 0) return error('Synthetic agy account not found', 404, 'fixture_not_found')
      log.allowed = true
      accounts.splice(index, 1)
      return respond(null, 204)
    }
    const match = /^\/admin\/api\/oauth-accounts\/(fixture-[a-z0-9-]+)(?:\/(settings|policy|models|channels|refresh|models\/refresh|quota\/refresh|cooldown\/clear))?$/.exec(url.pathname)
    if (!match) return deny('Fixture blocks authentication, upstream tests and unmatched routes')
    const [, id, action] = match
    const account = fixture.accounts.find(item => item.id === id)
    if (!account) return error('Synthetic account not found', 404, 'fixture_not_found')
    if (method === 'DELETE' && !action) {
      log.allowed = true; fixture.accounts.splice(fixture.accounts.indexOf(account), 1)
      return respond(null, 204)
    }
    if (method === 'PUT' && ['settings', 'policy', 'models', 'channels'].includes(action)) {
      let body
      try { body = await readBody(init, requestInput) } catch { return deny('Fixture accepts only object JSON bodies') }
      const keys = { settings: ['name', 'priority', 'enabled', 'proxyUrl', 'clearProxy', 'models', 'modelMappingChanges', 'expectedModelMappingRevision'], policy: ['windowLimit', 'windowSeconds', 'maxConcurrentRequests'], models: ['models'], channels: ['channelIds'] }[action]
      if (Object.keys(body).some(key => !keys.includes(key))) return deny('Fixture blocks unexpected settings fields')
      log.body = body; log.allowed = true
      // Validate every field before mutating account or shared mappings (atomic rollback).
      if (action === 'settings') {
        if ('name' in body && body.name != null && typeof body.name !== 'string') return error('Invalid synthetic account name')
        if ('enabled' in body && typeof body.enabled !== 'boolean') return error('Invalid enabled flag')
        if ('priority' in body && (!Number.isInteger(body.priority) || body.priority < 1 || body.priority > 1000)) return error('Invalid priority')
        if ('proxyUrl' in body && body.proxyUrl != null && typeof body.proxyUrl !== 'string') return error('Invalid inert proxy string')
        if ('models' in body && (!Array.isArray(body.models) || body.models.some(value => typeof value !== 'string' || value.trim().length > 200))) return error('Invalid models')
        if ('clearProxy' in body && typeof body.clearProxy !== 'boolean') return error('Invalid clearProxy')
        if (body.clearProxy && body.proxyUrl != null) return error('Cannot change and clear proxy together')
        const resource = fixture.providerMappings[account.provider]
        if ('expectedModelMappingRevision' in body && (!Number.isInteger(body.expectedModelMappingRevision) || body.expectedModelMappingRevision < 0)) return error('Invalid revision')
        const changes = body.modelMappingChanges ?? []
        if (!Array.isArray(changes)) return error('Invalid changes')
        if (changes.length && !Number.isInteger(body.expectedModelMappingRevision)) return error('Revision required')
        const normalized = []
        const seen = new Set()
        for (const change of changes) {
          if (!change || typeof change !== 'object' || Array.isArray(change) || typeof change.upstreamModel !== 'string' || !['manual', 'auto', 'upstream'].includes(change.mode) || Object.keys(change).some(key => !['upstreamModel', 'mode', 'canonicalModelId'].includes(key))) return error('Invalid mapping change')
          const upstreamModel = change.upstreamModel.trim()
          if (!upstreamModel || upstreamModel.length > 200 || seen.has(upstreamModel)) return error('Invalid or duplicate upstream model')
          seen.add(upstreamModel)
          if (!resource.mappings.some(mapping => mapping.upstreamModel === upstreamModel)) return error(`共享目录不存在模型: ${upstreamModel}`)
          let canonicalModelId
          if (change.mode === 'manual') {
            if (typeof change.canonicalModelId !== 'string' || !change.canonicalModelId.trim() || change.canonicalModelId.trim().length > 200) return error('Manual ID requires 1..200 characters')
            canonicalModelId = change.canonicalModelId.trim()
          } else if ('canonicalModelId' in change) return error('auto/upstream must omit canonicalModelId')
          normalized.push({ upstreamModel, mode: change.mode, canonicalModelId })
        }
        if (normalized.length && body.expectedModelMappingRevision !== resource.revision) return error('共享映射版本冲突，整笔设置未保存', 409, 'model_mapping_revision_conflict')
        // This control is a synthetic second administrator, after the caller's full validation.
        if (normalized.length && fixture.controls.conflictNextSave) {
          fixture.controls.conflictNextSave = false
          resource.revision++
          const latest = resource.mappings.find(item => item.upstreamModel === normalized[0].upstreamModel)
          latest.mappingSource = 'manual'; latest.canonicalModelId = 'other-admin/new-public-id'; effective(latest)
        }
        if (normalized.length && body.expectedModelMappingRevision !== resource.revision) return error('共享映射版本冲突，整笔设置未保存', 409, 'model_mapping_revision_conflict')
        if (typeof body.name === 'string' && body.name.trim()) account.name = body.name.trim()
        if ('enabled' in body) account.enabled = body.enabled
        if ('priority' in body) account.priority = body.priority
        if (body.clearProxy) account.proxyUrl = null
        else if ('proxyUrl' in body) account.proxyUrl = body.proxyUrl || null
        if ('models' in body) account.models = [...new Set(body.models)]
        // Mirror the backend contract: only a real shared-state change advances the revision,
        // so re-submitting the same mode/canonical/source must not trigger spurious 409s.
        let mappingsChanged = false
        for (const change of normalized) {
          const mapping = resource.mappings.find(item => item.upstreamModel === change.upstreamModel)
          const nextSource = change.mode === 'auto' ? null : 'manual'
          const nextCanonical = change.mode === 'manual' ? change.canonicalModelId : change.mode === 'auto' ? mapping.automaticCanonicalModelId : null
          if (mapping.mappingSource !== nextSource || mapping.canonicalModelId !== nextCanonical) mappingsChanged = true
          mapping.mappingSource = nextSource
          mapping.canonicalModelId = nextCanonical
          effective(mapping)
        }
        if (mappingsChanged) resource.revision++
        if (fixture.controls.failMappingAfterSave) { fixture.controls.failMappingAfterSave = false; fixture.controls.failMappingReads++ }
      } else if (action === 'policy') {
        if (Object.entries(body).some(([key, value]) => !(key === 'windowLimit' && value === null) && (!Number.isInteger(value) || value < 1))) return error('Invalid policy')
        Object.assign(account.policy, body)
      } else if (action === 'models') {
        if (!Array.isArray(body.models) || body.models.some(value => typeof value !== 'string')) return error('Invalid models')
        account.models = [...new Set(body.models)]
      } else {
        if (!Array.isArray(body.channelIds) || body.channelIds.some(id => !fixture.channels.some(channel => channel.id === id))) return error('Unknown synthetic channel')
        account.channelIds = [...new Set(body.channelIds)]
        account.channels = fixture.channels.filter(channel => account.channelIds.includes(channel.id)).map(({ id, name }) => ({ id, name }))
      }
      account.updatedAt = fixture.now
      return respond(account)
    }
    if (method === 'POST' && ['refresh', 'models/refresh', 'quota/refresh', 'cooldown/clear'].includes(action)) {
      if (init.body != null || requestInput?.body) return deny('Fixture refresh routes accept no body')
      log.allowed = true
      if (action === 'refresh') {
        account.lastRefreshedAt = fixture.now; account.accessTokenExpiresAt = fixture.now + 3_600_000
      } else if (action === 'quota/refresh') {
        if (account.quota) account.quota.checkedAt = fixture.now
      } else if (action === 'models/refresh') {
        account.modelCatalog = [...new Set(fixture.syncCatalogs[id] ?? fixture.channels.filter(channel => account.channelIds.includes(channel.id)).flatMap(channel => channel.models))]
        account.modelCatalogSyncedAt = fixture.now
        const resource = fixture.providerMappings[account.provider]
        let changed = false
        if (fixture.controls.syncConflict) {
          const conflict = fixture.controls.syncConflict
          const latest = resource.mappings.find(item => item.upstreamModel === conflict.upstreamModel)
          if (latest) {
            latest.mappingSource = 'manual'; latest.canonicalModelId = conflict.canonicalModelId; effective(latest)
            changed = true
          }
          fixture.controls.syncConflict = null
        }
        for (const upstreamModel of account.modelCatalog) {
          let mapping = resource.mappings.find(item => item.upstreamModel === upstreamModel)
          const automatic = fixture.automaticMappings[upstreamModel] ?? `catalog/${upstreamModel}`
          if (!mapping) {
            mapping = { upstreamModel, mode: 'auto', canonicalModelId: null, publicModelId: upstreamModel, automaticCanonicalModelId: automatic, mappingSource: null, catalogActive: true }
            resource.mappings.push(mapping); changed = true
          } else if (mapping.automaticCanonicalModelId !== automatic) {
            mapping.automaticCanonicalModelId = automatic; changed = true
          }
          // A model refresh updates automatic defaults, never overwrites manual/upstream policy.
          effective(mapping)
        }
        const activeModels = new Set(fixture.accounts.filter(item => item.provider === account.provider).flatMap(item => item.modelCatalog ?? fixture.channels.filter(channel => item.channelIds.includes(channel.id)).flatMap(channel => channel.models)))
        resource.mappings = resource.mappings.filter(mapping => {
          const active = activeModels.has(mapping.upstreamModel)
          if (mapping.catalogActive !== active) changed = true
          mapping.catalogActive = active
          return active || mapping.mappingSource != null
        })
        if (changed) resource.revision++
        return respond({ models: account.modelCatalog.map(id => ({ id, name: null })) })
      } else {
        account.runtime.cooldownUntil = null; account.runtime.consecutiveRateLimits = 0
        return respond(null, 204)
      }
      return respond(account)
    }
    return deny('Fixture blocks this method/route combination')
  }
  return { fetch, requests, fixture }
}

export function installOAuthFixtureFetch(target = window, { now = target.Date.now(), expose = true } = {}) {
  const mock = createOAuthFixtureFetch({ fixture: createOAuthAccountsFixture(now), origin: target.location.origin, ResponseClass: target.Response || globalThis.Response })
  target.fetch = mock.fetch
  if (expose) target.__oauthAccountsFixture = { now, requests: mock.requests, fixture: mock.fixture }
  return mock
}

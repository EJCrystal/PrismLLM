import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { channelDisplayModels, hiddenModelsTitle } from '../src/utils/channelDisplay'
import { channelPriceSummaries } from '../src/utils/modelPrice'
import {
  buildProviderListItems,
  providerDisplayName,
  providerMatchesFilters,
  providerModelFilterOptions,
  summarizeSubscriptionAlerts,
} from '../src/utils/providerDisplay'

const NOW = 1_760_000_000_000

function makeChannel(overrides = {}) {
  return {
    id: 'ch-1',
    name: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    authType: 'bearer',
    protocols: ['chat_completions'],
    enabled: true,
    credentialSet: true,
    createdAt: NOW - 10_000,
    updatedAt: NOW - 1_000,
    models: ['gpt-4o'],
    modelMappings: [{ upstreamModel: 'gpt-4o', canonicalModelId: null, mappingSource: null }],
    priority: 1,
    keys: [],
    kind: 'custom',
    provider: null,
    ...overrides,
  }
}

function makeAccount(overrides = {}) {
  return {
    id: 'acc-1',
    name: 'alice',
    provider: 'chatgpt',
    accountEmail: 'alice@example.com',
    accountPlan: 'plus',
    authState: 'ACTIVE',
    enabled: true,
    priority: 1,
    models: [],
    modelCatalog: null,
    modelCatalogSyncedAt: null,
    channelIds: ['ch-chatgpt'],
    channels: [{ id: 'ch-chatgpt', name: 'OpenAI 订阅（系统）' }],
    tokenType: 'Bearer',
    scope: null,
    accessTokenExpiresAt: NOW + 3_600_000,
    lastRefreshedAt: NOW - 5_000,
    refreshFailureCount: 0,
    lastRefreshError: null,
    proxyUrl: null,
    chatgptAccountId: null,
    quota: null,
    createdAt: NOW - 10_000,
    updatedAt: NOW - 1_000,
    authorizedAt: NOW - 10_000,
    policy: { windowLimit: null, windowSeconds: 10800, maxConcurrentRequests: 1 },
    runtime: { windowUsed: 0, cooldownUntil: null, consecutiveRateLimits: 0, activeRequests: 0, quotaBlockedUntil: null },
    ...overrides,
  }
}

describe('providerDisplay', () => {
  it('strips legacy system suffix only for subscription providers', () => {
    assert.equal(providerDisplayName('OpenAI 订阅（系统）', 'subscription'), 'OpenAI 订阅')
    assert.equal(providerDisplayName('Google Antigravity(系统)', 'subscription'), 'Google Antigravity')
    assert.equal(providerDisplayName('自建（系统）', 'custom'), '自建（系统）')
  })

  it('uses the enabled authorization projection for labels filters and pricing without changing the editable catalog', () => {
    const subscription = makeChannel({
      kind: 'provider', provider: 'antigravity',
      models: ['claude-first', 'claude-second', 'gemini-3-flash', 'gemini-3.1-flash-image'],
      enabledModels: ['claude-first', 'claude-second', 'outside-catalog'],
      modelMappings: [
        { upstreamModel: 'claude-first', canonicalModelId: 'claude-family', mappingSource: 'manual' },
        { upstreamModel: 'claude-second', canonicalModelId: 'claude-family', mappingSource: null },
        { upstreamModel: 'gemini-3-flash', canonicalModelId: 'saved-flash', mappingSource: 'manual' },
      ],
    })
    const custom = makeChannel({ id: 'custom', enabledModels: [] })
    const providers = [{ id: 'antigravity', label: 'Antigravity', configured: true }]
    // Both the normal and missing-provider-metadata fallback paths must use the same projection.
    for (const metadata of [providers, []]) {
      const items = buildProviderListItems([subscription, custom], metadata, [], NOW)
      const display = items.find(item => item.kind === 'subscription')
      assert.deepEqual(display.channel.models, ['claude-first', 'claude-second'])
      assert.deepEqual(channelDisplayModels(display.channel).map(item => item.displayName), ['claude-family'])
      assert.doesNotMatch(hiddenModelsTitle(display.channel), /gemini|saved-flash/)
      assert.deepEqual(providerModelFilterOptions(items), ['claude-family', 'gpt-4o'])
      assert.equal(providerMatchesFilters(display, 'all', 'all', 'saved-flash', ''), false)
      const prices = channelPriceSummaries(items.map(item => item.channel), [], [])
      assert.deepEqual(prices.get(subscription.id), { count: 2, unpriced: ['claude-first', 'claude-second'] })
      assert.deepEqual(items.find(item => item.kind === 'custom').channel.models, ['gpt-4o'])
    }
    assert.equal(subscription.models.length, 4)
    assert.equal(subscription.modelMappings[2].canonicalModelId, 'saved-flash')

    subscription.enabledModels = []
    const [empty] = buildProviderListItems([subscription], providers, [], NOW)
    assert.deepEqual(empty.channel.models, [])
    assert.deepEqual(providerModelFilterOptions([empty]), [])
    assert.equal(channelPriceSummaries([empty.channel], [], []).get(subscription.id).count, 0)
    assert.equal(subscription.models.length, 4)
  })

  it('merges provisioned provider channels, unprovisioned oauth providers, and custom channels', () => {
    const channels = [
      makeChannel({
        id: 'ch-chatgpt',
        name: 'OpenAI 订阅（系统）',
        baseUrl: 'https://chatgpt.com/backend-api/codex',
        protocols: ['responses'],
        kind: 'provider',
        provider: 'chatgpt',
        models: ['gpt-6-luna'],
        enabledModels: ['gpt-6-luna'],
        modelMappings: [{ upstreamModel: 'gpt-6-luna', canonicalModelId: 'gpt-6-luna', mappingSource: null }],
      }),
      makeChannel({ id: 'ch-custom', name: 'OpenRouter' }),
    ]
    const oauthProviders = [
      { id: 'chatgpt', label: 'ChatGPT', configured: true, managedChannel: true, channelId: 'ch-chatgpt', baseUrl: 'https://chatgpt.com/backend-api/codex', protocols: ['responses'] },
      { id: 'antigravity', label: 'Antigravity', configured: true, managedChannel: true, channelId: null, baseUrl: 'https://daily-cloudcode-pa.googleapis.com', protocols: ['gemini'] },
    ]
    const accounts = [
      makeAccount({ id: 'acc-1', provider: 'chatgpt' }),
      makeAccount({ id: 'acc-2', provider: 'chatgpt', authState: 'REAUTH_REQUIRED' }),
      makeAccount({ id: 'acc-3', provider: 'chatgpt', runtime: { windowUsed: 0, cooldownUntil: NOW + 60_000, consecutiveRateLimits: 1, activeRequests: 0, quotaBlockedUntil: null } }),
      makeAccount({ id: 'acc-4', provider: 'chatgpt', runtime: { windowUsed: 0, cooldownUntil: null, consecutiveRateLimits: 0, activeRequests: 0, quotaBlockedUntil: NOW + 60_000 } }),
    ]

    const items = buildProviderListItems(channels, oauthProviders, accounts, NOW)
    assert.equal(items.length, 3)
    assert.equal(items[0].kind, 'subscription')
    assert.equal(items[0].displayName, 'OpenAI 订阅')
    assert.equal(items[0].channel?.id, 'ch-chatgpt')
    assert.equal(items[0].accountSummary.total, 4)
    assert.equal(items[0].accountSummary.ready, 3)
    assert.equal(items[0].accountSummary.schedulable, 1)
    assert.equal(items[0].accountSummary.reauth, 1)
    assert.equal(items[0].accountSummary.cooling, 1)
    assert.equal(items[0].accountSummary.quotaBlocked, 1)

    assert.equal(items[1].kind, 'subscription')
    assert.equal(items[1].key, 'oauth:antigravity')
    assert.equal(items[1].displayName, 'Google Antigravity')
    assert.equal(items[1].channel, null)
    assert.deepEqual(items[1].protocols, ['gemini'])
    assert.equal(items[1].accountSummary.total, 0)

    assert.equal(items[2].kind, 'custom')
    assert.equal(items[2].displayName, 'OpenRouter')

    assert.deepEqual(providerModelFilterOptions(items), ['gpt-4o', 'gpt-6-luna'])
    assert.equal(providerMatchesFilters(items[0], 'subscription', 'enabled', 'gpt-6-luna', 'alice'), true)
    assert.equal(providerMatchesFilters(items[0], 'custom', 'all', '', ''), false)
    assert.equal(providerMatchesFilters(items[2], 'custom', 'enabled', 'gpt-4o', 'open'), true)

    const alerts = summarizeSubscriptionAlerts(accounts, NOW)
    assert.equal(alerts.reauthCount, 1)
    assert.equal(alerts.coolingCount, 1)
    assert.equal(alerts.quotaBlockedCount, 1)
    assert.equal(alerts.firstReauthProviderId, 'chatgpt')
    assert.equal(alerts.firstCoolingProviderId, 'chatgpt')
    assert.equal(alerts.firstQuotaBlockedProviderId, 'chatgpt')
  })
})

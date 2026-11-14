// Synthetic UI data only. No token, credential, live account ID, proxy or upstream URL.
export const FIXTURE_NOW = Date.UTC(2026, 10, 14, 8, 0, 0)
export const FIXTURE_ACCOUNT_IDS = Object.freeze({
  groupedHealthy: 'fixture-ag-grouped',
  chatgptWarning: 'fixture-chatgpt-80',
  groupedWarning: 'fixture-ag-91',
  exhausted: 'fixture-chatgpt-100',
  quotaUnknown: 'fixture-ag-unknown',
  quotaError: 'fixture-ag-error',
  disabledReauth: 'fixture-chatgpt-disabled-reauth',
  reauth: 'fixture-chatgpt-reauth',
  blockedWithoutQuota: 'fixture-ag-blocked-null',
})

const HOUR = 3_600_000
const DAY = 24 * HOUR
const CHATGPT_MODELS = ['gpt-5', 'gpt-5-mini', 'gpt-5-nano', 'gpt-5-codex', 'gpt-5-codex-mini', 'gpt-5-extended-context-synthetic-long-model-name']
const ANTIGRAVITY_MODELS = ['claude-sonnet-4-5', 'claude-opus-4-5-thinking', 'gemini-3-pro', 'gemini-3-flash', 'gemini-4-argon', 'gemini-4-argon-extended-context-synthetic-long-model-name']

export function createMockOAuthProviders() {
  return [
    { id: 'chatgpt', label: 'ChatGPT', configured: true, managedChannel: false },
    { id: 'antigravity', label: 'Antigravity', configured: true, managedChannel: true },
  ]
}

export function createMockOAuthChannels(now = FIXTURE_NOW) {
  return [
    ['fixture-chatgpt-channel', 'ChatGPT 订阅渠道', 'chatgpt', CHATGPT_MODELS],
    ['fixture-antigravity-channel', 'Antigravity 订阅渠道', 'antigravity', ANTIGRAVITY_MODELS],
    ['fixture-secondary-channel', '演示备用渠道（仅本地合成数据）', null, [...CHATGPT_MODELS, ...ANTIGRAVITY_MODELS]],
  ].map(([id, name, provider, models]) => ({
    id, name, provider, models: [...models], baseUrl: '', authType: 'NONE', protocolAuthTypes: {},
    protocols: ['OPENAI'], enabled: true, credentialSet: false, createdAt: now - 30 * DAY,
    updatedAt: now - DAY, modelMappings: [], priority: 1, keys: [], requestOverrides: {},
    quota: { providerId: '', config: {}, credentialSource: 'CHANNEL_KEY', credentialSet: false },
    kind: provider ? 'provider' : 'custom',
  }))
}

export function createMockOAuthAccounts(now = FIXTURE_NOW) {
  const channels = createMockOAuthChannels(now)
  const window = (usedPercent, windowSeconds = 18_000, resetIn = HOUR) => ({
    usedPercent, remainingPercent: 100 - usedPercent, windowSeconds, resetsAt: now + resetIn,
  })
  const quota = (patch = {}) => ({
    primary: null, secondary: null, allowed: true, creditsBalance: null,
    creditsUnlimited: false, resetCredits: null, groups: [], checkedAt: now - 2 * 60_000,
    error: null, ...patch,
  })
  const group = (name, usedPercent, secondWindow = false) => ({
    name, windows: [window(usedPercent), ...(secondWindow ? [window(34, 604_800, 3 * DAY)] : [])],
  })
  const account = (id, name, provider, patch = {}) => {
    const channelIds = [provider === 'chatgpt' ? 'fixture-chatgpt-channel' : 'fixture-antigravity-channel']
    return {
      id, name, provider, accountEmail: `${id}@example.invalid`,
      accountPlan: provider === 'chatgpt' ? 'Plus' : 'Google AI Pro',
      authState: 'ACTIVE', enabled: true, priority: 1, models: [],
      modelCatalog: provider === 'chatgpt' ? null : [...ANTIGRAVITY_MODELS],
      modelCatalogSyncedAt: provider === 'chatgpt' ? null : now - HOUR,
      channelIds, channels: channels.filter((c) => channelIds.includes(c.id)).map(({ id, name }) => ({ id, name })),
      tokenType: 'Bearer', scope: null, accessTokenExpiresAt: now + HOUR,
      lastRefreshedAt: now - 12 * 60_000, refreshFailureCount: 0, lastRefreshError: null,
      proxyUrl: null, chatgptAccountId: null, quota: null,
      createdAt: now - 30 * DAY, updatedAt: now - HOUR, authorizedAt: now - 14 * DAY,
      policy: { windowLimit: 40, windowSeconds: 10_800, maxConcurrentRequests: 2 },
      runtime: { windowUsed: 12, cooldownUntil: null, consecutiveRateLimits: 0, activeRequests: 1, quotaBlockedUntil: null },
      ...patch,
    }
  }
  const runtime = (patch) => ({ windowUsed: 3, cooldownUntil: null, consecutiveRateLimits: 0, activeRequests: 0, quotaBlockedUntil: null, ...patch })
  return [
    account(FIXTURE_ACCOUNT_IDS.groupedHealthy, '多家族额度演示', 'antigravity', {
      accountEmail: 'synthetic.account.with.a.very.long.email.for.subscription.layout.review@example.invalid',
      priority: 3,
      quota: quota({ groups: [group('Claude', 27, true), group('Claude and GPT', 43), group('Gemini 3 Pro', 18), group('Gemini 3 Flash', 61), group('Gemini 4 Argon', 8)] }),
    }),
    account(FIXTURE_ACCOUNT_IDS.chatgptWarning, 'ChatGPT 高消耗 80%', 'chatgpt', {
      models: [...CHATGPT_MODELS],
      quota: quota({ primary: window(80, 18_000, 2 * HOUR), secondary: window(54, 604_800, 2 * DAY), creditsBalance: 12.5 }),
    }),
    account(FIXTURE_ACCOUNT_IDS.groupedWarning, 'Antigravity 高消耗 91%', 'antigravity', {
      quota: quota({ groups: [group('Claude', 91, true), group('Claude & GPT', 80), group('Gemini 3 Pro', 42), group('Gemini 4 Argon', 16)] }),
      runtime: runtime({ cooldownUntil: now + 15 * 60_000, consecutiveRateLimits: 2 }),
    }),
    account(FIXTURE_ACCOUNT_IDS.exhausted, 'ChatGPT 额度用尽', 'chatgpt', {
      quota: quota({ primary: window(100, 18_000, 45 * 60_000), secondary: window(91, 604_800, 3 * DAY), allowed: false, creditsBalance: 0 }),
      runtime: runtime({ quotaBlockedUntil: now + 45 * 60_000 }),
    }),
    account(FIXTURE_ACCOUNT_IDS.quotaUnknown, '尚无额度读数', 'antigravity', {
      quota: null, modelCatalog: null, modelCatalogSyncedAt: null,
      lastRefreshedAt: null,
    }),
    account(FIXTURE_ACCOUNT_IDS.quotaError, '额度读取错误', 'antigravity', {
      quota: quota({ allowed: null, error: 'project_missing' }),
      models: ['gemini-3-pro'],
    }),
    account(FIXTURE_ACCOUNT_IDS.disabledReauth, '停用且需重新授权', 'chatgpt', {
      enabled: false, authState: 'REAUTH_REQUIRED', refreshFailureCount: 2,
      lastRefreshError: '演示凭据失效，请重新授权（合成状态）',
      accessTokenExpiresAt: now - HOUR,
      quota: quota({ primary: window(18), secondary: window(62, 604_800, DAY) }),
      runtime: runtime({}),
    }),
    account(FIXTURE_ACCOUNT_IDS.reauth, '已启用但需重新授权', 'chatgpt', {
      authState: 'REAUTH_REQUIRED', refreshFailureCount: 3,
      lastRefreshError: '演示授权已撤销，请重新授权（合成状态）',
      accountEmail: null, accessTokenExpiresAt: now - 2 * HOUR,
      quota: quota({ allowed: null, error: 'credential_unavailable' }),
      runtime: runtime({}),
    }),
    account(FIXTURE_ACCOUNT_IDS.blockedWithoutQuota, '账号阻断但额度读数为空', 'antigravity', {
      quota: null, models: ['gemini-4-argon'],
      runtime: runtime({ quotaBlockedUntil: now + 6 * HOUR }),
      policy: { windowLimit: null, windowSeconds: 10_800, maxConcurrentRequests: 1 },
    }),
  ]
}

// Each call returns fresh nested data so DOM tests and browser mutations cannot leak state.
export function createOAuthAccountsFixture(now = FIXTURE_NOW) {
  return { now, accounts: createMockOAuthAccounts(now), providers: createMockOAuthProviders(), channels: createMockOAuthChannels(now) }
}

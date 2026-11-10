import { ApiError } from './types/api'
import type {
  AdminAuditLog,
  AuditLogPageOptions,
  ApiErrorBody,
  AvatarRequest,
  ChangePasswordRequest,
  Channel,
  ChannelHealthReport,
  ChannelHealthDetail,
  HealthWindow,
  ChannelQuotaSettingsRequest,
  ChannelQuotaSnapshot,
  QuotaProvider,
  ChannelModelTestRequest,
  ChannelModelTestResult,
  ChannelModelsResponse,
  ChannelKeyDisablePolicyRequest,
  ChannelKeyDisablePolicyResponse,
  ChannelRequest,
  CleanupResult,
  ClientKey,
  ClientKeyCreated,
  ClientKeyRequest,
  ExchangeRates,
  IqBenchmarkDefinition,
  IqJudgesResponse,
  IqTestRunDetail,
  IqTestRunRequest,
  IqTestManualVerdictRequest,
  IqTestRunSummary,
  IqTestTarget,
  KeyAutoDisableSettingsRequest,
  KeyAutoDisableSettingsResponse,
  LoginRequest,
  MeResponse,
  ModelDefaultPrice,
  ModelDefaultPriceRequest,
  ModelDiscoveryRequest,
  ModelPrice,
  ModelPriceReference,
  ModelPriceRequest,
  ModelSuggestionsResponse,
  ModelTestSettings,
  IqVisionSettings,
  IqVisionSettingsRequest,
  WebSearchSettings,
  WebSearchSettingsRequest,
  OAuthAccount,
  OAuthAccountImportRequest,
  OAuthAccountChannelsRequest,
  OAuthAccountModelsRequest,
  OAuthAccountSettingsRequest,
  OAuthAccountPolicyRequest,
  OAuthLoginCompleteRequest,
  OAuthLoginStart,
  OAuthLoginStartRequest,
  OAuthProviderInfo,
  OAuthProviderModelsResponse,
  PageOptions,
  ProtocolProbeResponse,
  RequestLog,
  RetentionPolicy,
  RuntimeOccupancy,
  UpstreamRequestOverrides,
  UpstreamRequestSettings,
  UsageReport,
  UsageReportQuery,
} from './types/api'

const API_BASE = '/admin/api'

function readCookie(name: string): string | null {
  const prefix = `${name}=`
  const item = document.cookie.split('; ').find((value) => value.startsWith(prefix))
  return item ? decodeURIComponent(item.slice(prefix.length)) : null
}

type RequestOptions = Omit<RequestInit, 'body'> & { body?: BodyInit | null }

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers || {})
  if (options.body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const csrf = readCookie('gw_csrf')
    if (csrf) headers.set('X-CSRF-Token', csrf)
  }

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      method,
      headers,
      credentials: 'include',
    })
  } catch {
    throw new Error('无法连接网关，请确认服务已启动')
  }

  if (response.status === 204) return null as T

  const text = await response.text()
  let data: unknown = null
  let parseFailed = false
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      parseFailed = true
    }
  }

  if (!response.ok) {
    if (response.status === 401 && path !== '/login') {
      window.dispatchEvent(new CustomEvent('gateway:unauthorized'))
    }
    const body = data as ApiErrorBody | null
    throw new ApiError(
      body?.error?.message || `请求失败（${response.status}）`,
      response.status,
      body?.error?.code,
    )
  }

  if (parseFailed) {
    throw new ApiError('网关返回了无效的 JSON 响应', response.status, 'invalid_response')
  }

  return data as T
}

async function requestList<T>(path: string, options: RequestOptions = {}): Promise<T[]> {
  const data = await request<T[] | null>(path, options)
  if (data === null) return []
  if (!Array.isArray(data)) {
    throw new ApiError('网关返回的列表格式无效', 200, 'invalid_response')
  }
  return data
}

export const api = {
  login: (username: string, password: string) =>
    request<MeResponse>('/login', { method: 'POST', body: JSON.stringify({ username, password } satisfies LoginRequest) }),
  logout: () => request<null>('/logout', { method: 'POST' }),
  me: () => request<MeResponse>('/me'),
  updateAccountAvatar: (payload: AvatarRequest) =>
    request<MeResponse>('/account/avatar', { method: 'PUT', body: JSON.stringify(payload) }),
  changePassword: (payload: ChangePasswordRequest) =>
    request<null>('/account/password', { method: 'PUT', body: JSON.stringify(payload) }),
  getExchangeRates: () => request<ExchangeRates>('/exchange-rates'),

  listChannels: () => requestList<Channel>('/channels'),
  getOccupancy: () => request<RuntimeOccupancy>('/occupancy'),
  getChannelHealth: (window: HealthWindow = '24h', options: RequestOptions = {}) => request<ChannelHealthReport>('/channel-health?' + new URLSearchParams({ window }), options),
  getChannelHealthDetail: (id: string, window: HealthWindow = '24h', options: RequestOptions = {}) => request<ChannelHealthDetail>(`/channels/${encodeURIComponent(id)}/health?` + new URLSearchParams({ window }), options),
  clearChannelCooldown: (id: string, payload: { scope: 'channel' | 'key' | 'candidate'; keyId?: string; upstreamModel?: string }, options: RequestOptions = {}) =>
    request<null>(`/channels/${encodeURIComponent(id)}/cooldown/clear`, { ...options, method: 'POST', body: JSON.stringify(payload) }),
  listQuotaProviders: () => requestList<QuotaProvider>('/quota-providers'),
  updateChannelQuota: (id: string, payload: ChannelQuotaSettingsRequest) =>
    request<Channel>('/channels/' + encodeURIComponent(id) + '/quota-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  queryChannelQuota: (id: string) => request<ChannelQuotaSnapshot>('/channels/' + encodeURIComponent(id) + '/quota', { method: 'POST' }),
  updateChannelKeyQuota: (id: string, keyId: string, payload: ChannelQuotaSettingsRequest) =>
    request<Channel>('/channels/' + encodeURIComponent(id) + '/keys/' + encodeURIComponent(keyId) + '/quota-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  queryChannelKeyQuota: (id: string, keyId: string) =>
    request<ChannelQuotaSnapshot>('/channels/' + encodeURIComponent(id) + '/keys/' + encodeURIComponent(keyId) + '/quota', { method: 'POST' }),
  createChannel: (payload: ChannelRequest) => request<Channel>('/channels', { method: 'POST', body: JSON.stringify(payload) }),
  updateChannel: (id: string, payload: ChannelRequest) =>
    request<Channel>(`/channels/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteChannel: (id: string) => request<null>(`/channels/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  listOAuthProviders: () => requestList<OAuthProviderInfo>('/oauth-providers'),
  refreshOAuthProviderModels: (provider: string) =>
    request<OAuthProviderModelsResponse>(`/oauth-providers/${encodeURIComponent(provider)}/models/refresh`, {
      method: 'POST',
    }),
  listOAuthAccounts: () => requestList<OAuthAccount>('/oauth-accounts'),
  importOAuthAccount: (payload: OAuthAccountImportRequest) =>
    request<OAuthAccount>('/oauth-accounts', { method: 'POST', body: JSON.stringify(payload) }),
  deleteOAuthAccount: (id: string) =>
    request<null>(`/oauth-accounts/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  updateOAuthAccount: (id: string, payload: OAuthAccountImportRequest) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  updateOAuthAccountSettings: (id: string, payload: OAuthAccountSettingsRequest) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}/settings`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  updateOAuthAccountChannels: (id: string, payload: OAuthAccountChannelsRequest) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}/channels`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  updateOAuthAccountModels: (id: string, payload: OAuthAccountModelsRequest) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}/models`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  updateOAuthAccountPolicy: (id: string, payload: OAuthAccountPolicyRequest) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}/policy`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  refreshOAuthAccount: (id: string) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}/refresh`, { method: 'POST' }),
  refreshOAuthAccountQuota: (id: string) =>
    request<OAuthAccount>(`/oauth-accounts/${encodeURIComponent(id)}/quota/refresh`, { method: 'POST' }),
  startOAuthLogin: (payload: OAuthLoginStartRequest) =>
    request<OAuthLoginStart>('/oauth-login/start', { method: 'POST', body: JSON.stringify(payload) }),
  completeOAuthLogin: (loginId: string, payload: OAuthLoginCompleteRequest) =>
    request<OAuthAccount>(`/oauth-login/${encodeURIComponent(loginId)}/complete`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  clearOAuthAccountCooldown: (id: string) =>
    request<null>(`/oauth-accounts/${encodeURIComponent(id)}/cooldown/clear`, { method: 'POST' }),
  listChannelModels: (id: string) => request<ChannelModelsResponse>(`/channels/${encodeURIComponent(id)}/models`),
  discoverChannelModels: (payload: ModelDiscoveryRequest) =>
    request<ChannelModelsResponse>('/channels/model-discovery', { method: 'POST', body: JSON.stringify(payload) }),
  getModelSuggestions: (models: string[]) =>
    request<ModelSuggestionsResponse>('/model-suggestions', { method: 'POST', body: JSON.stringify({ models }) }),
  probeChannelProtocols: (id: string, model: string | null = null, keyId: string | null = null) =>
    request<ProtocolProbeResponse>(`/channels/${encodeURIComponent(id)}/probe-protocols`, {
      method: 'POST',
      body: JSON.stringify({ model, keyId }),
    }),
  testChannelModel: (id: string, payload: ChannelModelTestRequest) =>
    request<ChannelModelTestResult>(`/channels/${encodeURIComponent(id)}/model-test`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  testOAuthAccountModel: (id: string, payload: ChannelModelTestRequest) =>
    request<ChannelModelTestResult>(`/oauth-accounts/${encodeURIComponent(id)}/model-test`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  listIqBenchmarks: () => request<IqBenchmarkDefinition[]>('/iq-tests/benchmarks'),
  listIqJudges: () => request<IqJudgesResponse>('/iq-tests/judges'),
  listIqTargets: () => requestList<IqTestTarget>('/iq-tests/targets'),
  listIqRuns: (limit = 50) => requestList<IqTestRunSummary>(`/iq-tests?limit=${limit}`),
  getIqRun: (id: number) => request<IqTestRunDetail>(`/iq-tests/${id}`),
  runIqTest: (payload: IqTestRunRequest) =>
    request<IqTestRunDetail>('/iq-tests', { method: 'POST', body: JSON.stringify(payload) }),
  submitIqManualVerdict: (id: number, payload: IqTestManualVerdictRequest) =>
    request<IqTestRunDetail>(`/iq-tests/${id}/manual-verdict`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  getModelTestSettings: () => request<ModelTestSettings>('/model-test-settings'),
  getUpstreamRequestSettings: () => request<UpstreamRequestSettings>('/upstream-request-settings'),
  updateUpstreamRequestSettings: (payload: { overrides: UpstreamRequestOverrides }) =>
    request<UpstreamRequestSettings>('/upstream-request-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  refreshUpstreamHeaderCatalog: () =>
    request<UpstreamRequestSettings>('/upstream-request-settings/header-catalog/refresh', { method: 'POST' }),
  pinUpstreamHeaderProfile: (profileId: string, version: string) =>
    request<UpstreamRequestSettings>(`/upstream-request-settings/header-profiles/${encodeURIComponent(profileId)}/pin`, {
      method: 'PUT',
      body: JSON.stringify({ version }),
    }),
  unpinUpstreamHeaderProfile: (profileId: string) =>
    request<UpstreamRequestSettings>(`/upstream-request-settings/header-profiles/${encodeURIComponent(profileId)}/pin`, {
      method: 'DELETE',
    }),
  updateModelTestSettings: (payload: { defaultPrompt: string }) =>
    request<ModelTestSettings>('/model-test-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  getWebSearchSettings: () => request<WebSearchSettings>('/web-search-settings'),
  updateWebSearchSettings: (payload: WebSearchSettingsRequest) =>
    request<WebSearchSettings>('/web-search-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  getIqVisionSettings: () => request<IqVisionSettings>('/iq-vision-settings'),
  updateIqVisionSettings: (payload: IqVisionSettingsRequest) =>
    request<IqVisionSettings>('/iq-vision-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  getKeyAutoDisableSettings: () => request<KeyAutoDisableSettingsResponse>('/key-auto-disable-settings'),
  updateKeyAutoDisableSettings: (payload: KeyAutoDisableSettingsRequest) =>
    request<KeyAutoDisableSettingsResponse>('/key-auto-disable-settings', { method: 'PUT', body: JSON.stringify(payload) }),
  getChannelKeyDisablePolicy: (channelId: string) =>
    request<ChannelKeyDisablePolicyResponse>(`/channels/${encodeURIComponent(channelId)}/key-disable-policy`),
  updateChannelKeyDisablePolicy: (channelId: string, payload: ChannelKeyDisablePolicyRequest) =>
    request<ChannelKeyDisablePolicyResponse>(`/channels/${encodeURIComponent(channelId)}/key-disable-policy`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),

  listClientKeys: () => requestList<ClientKey>('/client-keys'),
  createClientKey: (payload: ClientKeyRequest) =>
    request<ClientKeyCreated>('/client-keys', { method: 'POST', body: JSON.stringify(payload) }),
  updateClientKey: (id: string, payload: ClientKeyRequest) =>
    request<ClientKey>(`/client-keys/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteClientKey: (id: string) =>
    request<null>(`/client-keys/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  rotateClientKey: (id: string) =>
    request<ClientKeyCreated>('/client-keys/' + encodeURIComponent(id) + '/rotate', { method: 'POST' }),
  revokeClientKey: (id: string) =>
    request<ClientKey>('/client-keys/' + encodeURIComponent(id) + '/revoke', { method: 'POST' }),

  listAuditLogs: ({ limit = 100, beforeId = null, from = null, to = null, actor = null, action = null, result = null }: AuditLogPageOptions = {}) => {
    const params = new URLSearchParams({ limit: String(limit) })
    if (beforeId != null) params.set('beforeId', String(beforeId))
    if (from != null) params.set('from', String(from))
    if (to != null) params.set('to', String(to))
    if (actor) params.set('actor', actor)
    if (action) params.set('action', action)
    if (result) params.set('result', result)
    return requestList<AdminAuditLog>('/audit-logs?' + params)
  },

  listModelPrices: () => requestList<ModelPrice>('/model-prices'),
  getModelPrices: () => requestList<ModelPrice>('/model-prices'),
  getModelPriceReference: (model: string) =>
    request<ModelPriceReference>(`/model-prices/reference?model=${encodeURIComponent(model)}`),
  createModelPrice: (payload: ModelPriceRequest) =>
    request<ModelPrice>('/model-prices', { method: 'POST', body: JSON.stringify(payload) }),
  updateModelPrice: (id: string, payload: ModelPriceRequest) =>
    request<ModelPrice>(`/model-prices/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteModelPrice: (id: string) =>
    request<null>(`/model-prices/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  listModelDefaultPrices: () => requestList<ModelDefaultPrice>('/model-default-prices'),
  createModelDefaultPrice: (payload: ModelDefaultPriceRequest) =>
    request<ModelDefaultPrice>('/model-default-prices', { method: 'POST', body: JSON.stringify(payload) }),
  updateModelDefaultPrice: (id: string, payload: ModelDefaultPriceRequest) =>
    request<ModelDefaultPrice>(`/model-default-prices/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteModelDefaultPrice: (id: string) =>
    request<null>(`/model-default-prices/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  listRequestLogs: ({ limit = 100, beforeId = null }: PageOptions = {}) => {
    const params = new URLSearchParams({ limit: String(limit) })
    if (beforeId != null) params.set('beforeId', String(beforeId))
    return requestList<RequestLog>('/request-logs?' + params)
  },
  getUsageReport: ({ from, to, clientKeyId = null, channelId = null, model = null, protocol = null, timezoneOffsetMinutes = 0 }: UsageReportQuery, options: RequestOptions = {}) => {
    const params = new URLSearchParams({ from: String(from), to: String(to) })
    if (clientKeyId) params.set('clientKeyId', clientKeyId)
    if (channelId) params.set('channelId', channelId)
    if (model) params.set('model', model)
    if (protocol) params.set('protocol', protocol)
    if (timezoneOffsetMinutes) params.set('timezoneOffsetMinutes', String(timezoneOffsetMinutes))
    return request<UsageReport>(`/usage-report?${params}`, options)
  },
  getRetentionPolicy: () => request<RetentionPolicy>('/request-log-retention'),
  updateRetentionPolicy: (payload: { cleanupEnabled: boolean }) =>
    request<RetentionPolicy>('/request-log-retention', { method: 'PUT', body: JSON.stringify(payload) }),
  cleanupRequestLogs: () => request<CleanupResult>('/request-log-retention/cleanup', { method: 'POST' }),
}

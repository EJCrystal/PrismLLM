export type ProtocolId = 'chat_completions' | 'responses' | 'messages' | 'gemini'

export type ModelRouteCredentialType = 'static' | 'oauth'

/** A credential is ordered once, even when it supports several upstream aliases. */
export interface ModelRouteIdentity {
  channelId: string
  credentialType: ModelRouteCredentialType
  credentialId: string
}

export interface ModelRouteCredential extends ModelRouteIdentity {
  channelName: string
  credentialName: string
  upstreamModels: string[]
  protocols: string[]
  selectable: boolean
  reason: string | null
}

export interface ModelRouteSummary {
  modelId: string
  configured: boolean
  entryCount: number
  availableCount: number
  revision: number
}

export interface ModelRouteListOptions {
  q?: string
  offset?: number
  limit?: number
}

export interface ModelRoutePage {
  items: ModelRouteSummary[]
  total: number
  offset: number
  limit: number
}

export interface ModelRouteDetail {
  modelId: string
  revision: number
  configured: boolean
  entries: ModelRouteCredential[]
  availableCredentials: ModelRouteCredential[]
}

/** 调用方访问范围使用的紧凑路由选项；不包含凭据名称或明文。 */
export interface ModelRouteAccessEntry extends ModelRouteIdentity {
  selectable: boolean
  reason?: string | null
}

export interface ModelRouteAccessOption {
  modelId: string
  revision: number
  entries: ModelRouteAccessEntry[]
}

export interface ModelRouteAccessOptionsResponse {
  models: ModelRouteAccessOption[]
}

export interface ModelRouteUpdateRequest {
  modelId: string
  expectedRevision: number
  entries: ModelRouteIdentity[]
}

export type HealthWindow = '24h' | '7d' | '15d' | '30d'
export type HealthState = 'HEALTHY' | 'WARNING' | 'CRITICAL' | 'UNKNOWN' | 'DISABLED' | 'NO_CREDENTIALS'
export interface HealthMetrics {
  attempts: number; successes: number; failures: number; excluded: number; successRate: number | null
  firstTokenSamples: number; p50FirstTokenMs: number | null; p95FirstTokenMs: number | null
  p50TotalMs: number | null; p95TotalMs: number | null
  outputTokensPerSecond: number | null; throughputSamples: number
  lastSuccessAt: number | null; lastFailureAt: number | null; lastObservedAt: number | null
}
export interface ChannelHealthSummary {
  channelId: string; metrics: HealthMetrics; state: HealthState; reason: string
  requests: { requests: number; successes: number; successRate: number | null; failoverRequests: number; failoverRate: number | null; legacyRequests: number }
  cooldownUntil?: number | null; cooldownReason?: string | null
  coolingCandidates?: number; totalCandidates?: number
  recent?: { from: number; to: number; metrics: HealthMetrics; state: HealthState; reason: string } | null
  recentAttempts?: HealthRecentAttempt[]
}
export interface HealthRecentAttempt {
  logId: number; attemptIndex: number; createdAt: number; outcome: 'SUCCESS' | 'FAILURE'
  statusCode: number | null; errorCode: string | null; upstreamModel: string | null
  elapsedMs: number; firstTokenMs: number | null
}
export interface ChannelHealthReport {
  window: HealthWindow; from: number; to: number; retentionDays: number; indexing: boolean
  minimumSamples: number; staleAfterMs: number; warningErrorRate: number; criticalErrorRate: number
  slowFirstTokenMs: number; slowP50FirstTokenMs: number
  channels: ChannelHealthSummary[]
}
export interface HealthBreakdown { id: string; name: string; metrics: HealthMetrics }
export interface ChannelHealthDetail {
  report: ChannelHealthReport
  trend: { from: number; to: number; metrics: HealthMetrics }[]
  byModel: HealthBreakdown[]; byProtocol: HealthBreakdown[]; byKey: HealthBreakdown[]
  errors: { code: string; count: number; lastAt: number }[]
  availability: { keyId: string; keyName: string; model: string; state: string; reason: string | null; until: number | null; cooldownScope?: string | null }[]
}

export type ChannelKeyState = 'ACTIVE' | 'MANUAL_DISABLED' | 'AUTO_TEMPORARY' | 'AUTO_PERMANENT'

export interface ApiErrorBody {
  error?: {
    code?: string
    message?: string
  }
}

export class ApiError extends Error {
  status: number
  code?: string

  constructor(message: string, status: number, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export interface LoginRequest {
  username: string
  password: string
}

export interface MeResponse {
  username: string
  avatarDataUrl: string | null
}

export interface AvatarRequest {
  avatarDataUrl: string | null
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export interface ChannelKeyRateLimit {
  enabled: boolean
  requestsPerMinute: number | null
  maxConcurrentRequests: number | null
}

export interface ChannelKey {
  id: string
  name: string
  egressProxyId?: string | null
  quota?: ChannelQuotaSettings | null
  state: ChannelKeyState
  priority: number
  credentialSet: boolean
  models: string[]
  rateLimit: ChannelKeyRateLimit
  stateVersion: number
  autoDisableUntil: number | null
  probeUntil: number | null
  autoDisableReason: string | null
}

export interface ChannelModelMapping {
  upstreamModel: string
  canonicalModelId: string | null
  mappingSource: string | null
}

export interface JsonObject {
  [key: string]: JsonValue
}

export type JsonValue = string | number | boolean | null | JsonObject | JsonValue[]

export interface UpstreamProtocolOverrides {
  headerProfile: string | null
  userAgent: string | null
  headers: Record<string, string | null>
  body: JsonObject
  extraBody: JsonObject
}

export interface UpstreamRequestOverrides extends UpstreamProtocolOverrides {
  inheritGlobal: boolean
  protocols: Partial<Record<ProtocolId, UpstreamProtocolOverrides>>
}

export interface ChannelEgressProxy {
  id: string
  name: string
  url: string
  username?: string | null
  passwordSet?: boolean
}

export interface ChannelEgressProxyRequest extends Pick<ChannelEgressProxy, 'id' | 'name' | 'url'> {
  username?: string | null
  password?: string | null
  clearAuthentication?: boolean
}

export interface ChannelEgressSettings {
  /** 管理台保存的主机，可在管理台增删。 */
  hosts: string[]
  /** CHANNEL_PROXY_ALLOWED_HOSTS 提供的部署默认值，只读。 */
  deploymentHosts: string[]
  /** 实际生效的并集。 */
  effectiveHosts: string[]
  /** 白名单版本；保存时回传用于乐观并发校验。 */
  revision: number
  updatedAt: number | null
}

export interface ChannelEgressSettingsRequest {
  hosts: string[]
  expectedRevision?: number | null
}

export interface Channel {
  id: string
  name: string
  baseUrl: string
  authType: string
  protocolAuthTypes: Record<string, string>
  protocols: string[]
  enabled: boolean
  credentialSet: boolean
  createdAt: number
  updatedAt: number
  models: string[]
  /** 订阅渠道列表的展示范围；完整目录仍由 models/modelMappings 提供。 */
  enabledModels?: string[] | null
  modelMappings: ChannelModelMapping[]
  priority: number
  keys: ChannelKey[]
  egressProxies?: ChannelEgressProxy[]
  /** 渠道下订阅账号摘要，仅展示字段；访问范围树用它渲染 OAuth 行。 */
  oauthAccounts?: ChannelOAuthAccount[]
  requestOverrides: UpstreamRequestOverrides
  quota: ChannelQuotaSettings
  kind: 'custom' | 'provider'
  provider?: string | null
  /** 是否配置了渠道级密钥自动禁用策略；false 表示继承全局设置。 */
  keyDisablePolicySet?: boolean
  /** 渠道策略形态：inherit（继承）、custom（自定义，未命中回落全局）、disabled（关闭）。 */
  keyDisableMode?: ChannelKeyDisableMode
}

/** 渠道订阅账号摘要；不含任何凭据。 */
export interface ChannelOAuthAccount {
  id: string
  name: string
  enabled: boolean
  authMethod: 'oauth' | 'agy'
  models: string[]
}

export interface ChannelQuotaSettings {
  providerId: string
  config: JsonObject
  credentialSource?: 'CHANNEL_KEY' | 'DEDICATED'
  credentialSet?: boolean
}

export interface ChannelQuotaSettingsRequest extends Omit<ChannelQuotaSettings, 'credentialSet'> {
  credential?: string
  clearCredential?: boolean
}

export interface QuotaProvider {
  id: string
  displayName: string
  apiVersion: number
}

export type ChannelQuotaStatus = 'AVAILABLE' | 'UNLIMITED' | 'UNSUPPORTED' | 'AUTHENTICATION_FAILED' | 'RATE_LIMITED' | 'ERROR' | 'DISABLED'

export interface ChannelQuotaKeyRef {
  id: string
  name: string
}

export interface ChannelQuotaSnapshot {
  channelId: string
  providerId: string
  status: ChannelQuotaStatus
  scope: 'CHANNEL' | 'KEY'
  targetKeyId?: string | null
  remaining?: string | null
  used?: string | null
  limit?: string | null
  unit?: string | null
  planName?: string | null
  message?: string | null
  checkedAt: number
  sharedKeyCount: number
  cached: boolean
  queryKey?: ChannelQuotaKeyRef | null
  attemptedKeys?: ChannelQuotaKeyRef[]
  credentialSource?: 'CHANNEL_KEY' | 'DEDICATED' | null
}

export interface ChannelKeyRateLimitRequest {
  enabled?: boolean
  requestsPerMinute?: number | null
  maxConcurrentRequests?: number | null
}

export interface ChannelKeyRequest {
  id?: string | null
  name: string
  credential?: string | null
  state?: 'ACTIVE' | 'MANUAL_DISABLED'
  priority?: number | null
  models?: string[]
  rateLimit?: ChannelKeyRateLimitRequest | null
  stateVersion?: number
  egressProxyId?: string | null
  clearEgressProxy?: boolean
}

export interface ChannelModelMappingRequest {
  upstreamModel: string
  canonicalModelId: string | null
  mappingSource: string | null
}

export interface ChannelRequest {
  name: string
  baseUrl: string
  credential?: string | null
  authType?: string | null
  protocolAuthTypes?: Record<string, string> | null
  protocols?: string[]
  enabled?: boolean
  models?: string[]
  modelMappings?: ChannelModelMappingRequest[]
  priority?: number | null
  keys?: ChannelKeyRequest[]
  requestOverrides?: UpstreamRequestOverrides
  quota?: ChannelQuotaSettingsRequest
  egressProxies?: ChannelEgressProxyRequest[]
}

export type KeyRuleMatchLogic = 'AND' | 'OR'

export type KeyRuleRequestAction = 'DEFAULT' | 'FAILOVER' | 'FAIL_FAST'

export type KeyDisableAction = 'NONE' | 'TEMPORARY' | 'PERMANENT'

export interface KeyDisableRule {
  id: string
  name: string
  statusCodes: number[]
  statusCodeAtLeast: number | null
  bodyPatterns: string[]
  matchLogic: KeyRuleMatchLogic
  requestAction: KeyRuleRequestAction
  threshold: number
  windowMinutes: number
  streak: boolean
  action: KeyDisableAction
  disableMinutes: number | null
  includeTransportFailures: boolean
}

export interface KeyAutoDisableSettingsResponse {
  enabled: boolean
  rules: KeyDisableRule[]
  revision: number
  updatedAt: number
  authFailureThreshold: number
  failureThreshold: number
  failureWindowMinutes: number
}

export interface KeyAutoDisableSettingsRequest {
  enabled: boolean
  rules: KeyDisableRule[]
}

/**
 * 渠道策略形态：
 * - inherit：未配置覆盖，完全使用全局规则；
 * - custom：渠道规则在前，未命中时回落全局规则；
 * - disabled：本渠道不执行任何自动禁用（渠道规则和全局规则都不执行）。
 */
export type ChannelKeyDisableMode = 'inherit' | 'custom' | 'disabled'

/** 渠道覆盖的自动禁用策略；`override` 为 null 表示继承全局。 */
export interface ChannelKeyDisablePolicyResponse {
  channelId: string
  mode: ChannelKeyDisableMode
  override: KeyAutoDisableSettingsRequest | null
  globalPolicy: KeyAutoDisableSettingsRequest
  /** 合并渠道规则与全局兜底后的实际策略。 */
  effective: KeyAutoDisableSettingsRequest
}

export interface ChannelKeyDisablePolicyRequest {
  override: KeyAutoDisableSettingsRequest | null
}

export interface UpstreamHeaderProfile {
  id: string
  name: string
  version: string
  headers: Record<string, string>
  source: string
  fetchedAt: number
}

export interface UpstreamHeaderCatalog {
  revision: string
  checkedAt: number
  successAt: number | null
  lastError: string | null
  refreshing: boolean
  autoRefreshEnabled: boolean
  refreshIntervalMs: number
  profiles: UpstreamHeaderProfile[]
  history: Record<string, UpstreamHeaderProfile[]>
  pins: Record<string, string>
}

export interface UpstreamRequestSettings {
  overrides: UpstreamRequestOverrides
  profiles: UpstreamHeaderProfile[]
  updatedAt: number
  headerCatalog: UpstreamHeaderCatalog
}

export type SystemPromptScope = 'global' | 'protocols' | 'channels' | 'channel_protocols'
export type SystemPromptAction = 'replace' | 'preserve'

export interface SystemPromptRule {
  id: string
  name: string
  enabled: boolean
  scope: SystemPromptScope
  protocols: ProtocolId[]
  channelIds: string[]
  priority: number
  action: SystemPromptAction
  prompt: string
}

export interface SystemPromptSettings {
  enabled: boolean
  rules: SystemPromptRule[]
  updatedAt: number
}

export type SystemPromptSettingsRequest = Omit<SystemPromptSettings, 'updatedAt'>

export interface ClientKeyQuotaUsage {
  dailyTokens: number
  dailyUsdCost: string
  concurrentRequests: number
  currentMinuteRequests: number
}

export interface ChannelKeyOccupancy {
  keyId: string
  concurrentRequests: number
}

export interface ChannelOccupancy {
  channelId: string
  concurrentRequests: number
  keys: ChannelKeyOccupancy[]
}

export interface ClientKeyOccupancy {
  clientKeyId: string
  concurrentRequests: number
}

export interface RuntimeOccupancy {
  sampledAt: number
  channels: ChannelOccupancy[]
  clientKeys: ClientKeyOccupancy[]
}

/** 白名单为空时拒绝该维度的所有对象；不限制模式使用空数组。 */
export type ClientKeyAccessMode = 'unrestricted' | 'allowlist' | 'denylist'

export interface ClientKeyIdScope {
  mode: ClientKeyAccessMode
  ids: string[]
}

export type ClientKeyCredentialType = 'static' | 'oauth'

/** 渠道凭据身份；旧数据省略类型时按静态密钥处理。 */
export interface ClientKeyAllowedChannelKey {
  channelId: string
  keyId: string
  credentialType?: ClientKeyCredentialType
}

export interface ClientKeyChannelKeysScope {
  mode: ClientKeyAccessMode
  keys: ClientKeyAllowedChannelKey[]
}

/** 同维度取并集，三个维度取交集；模型 ID 精确匹配。 */
export interface ClientKeyAccessPolicy {
  channels: ClientKeyIdScope
  channelKeys: ClientKeyChannelKeysScope
  models: ClientKeyIdScope
}

export interface ClientKeyIpPolicy {
  allowlist: string[]
  denylist: string[]
}

export interface ClientKey {
  id: string
  name: string
  keyPrefix: string
  enabled: boolean
  expiresAt: number | null
  dailyTokenLimit: number | null
  dailyUsdLimit: string | null
  maxConcurrentRequests: number | null
  requestsPerMinute: number | null
  quotaUsage: ClientKeyQuotaUsage
  createdAt: number
  rotatedAt: number | null
  revokedAt: number | null
  accessPolicy: ClientKeyAccessPolicy
  ipPolicy: ClientKeyIpPolicy
}

export interface ClientKeyRequest {
  name: string
  enabled?: boolean
  expiresAt?: number | null
  dailyTokenLimit?: number | null
  dailyUsdLimit?: string | null
  maxConcurrentRequests?: number | null
  requestsPerMinute?: number | null
  /** 更新省略或传 null 时保留已有范围；完整对象原子替换三个维度。 */
  accessPolicy?: ClientKeyAccessPolicy | null
  ipPolicy?: ClientKeyIpPolicy | null
}

export interface ClientKeyCreated extends Omit<ClientKey, 'quotaUsage'> {
  key: string
}

export interface UsageDimensions {
  requests: number
  errors: number
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  cachedWriteTokens: number
  reasoningTokens: number
  totalTokens: number
}

export interface UsageCosts {
  /** USD 费用合计；没有任何可计费请求时为 null。 */
  costUsd: string | null
}

export interface UsageTotal extends UsageDimensions, UsageCosts {
  activeClientKeys: number
  failovers: number
  avgFirstTokenMs: number | null
  avgTotalMs: number | null
  successfulLatencySamples?: number
  p50TotalMs?: number | null
  p90TotalMs?: number | null
  p99TotalMs?: number | null
}

export interface UsageGroup extends UsageDimensions, UsageCosts {
  id: string
  name: string | null
  failovers: number
  avgFirstTokenMs: number | null
  avgTotalMs: number | null
}

export interface UsageTrend extends UsageDimensions, UsageCosts {
  bucketStart: number
}

export interface UsageReport {
  from: number
  to: number
  granularity: string
  total: UsageTotal
  trend: UsageTrend[]
  byClientKey: UsageGroup[]
  byChannel: UsageGroup[]
  byModel: UsageGroup[]
  byChannelKey: UsageGroup[]
  byProtocol: UsageGroup[]
  byErrorCode: UsageGroup[]
}

export interface RetentionPolicy {
  retentionDays: number
  batchSize: number
  cleanupEnabled: boolean
  cleanupIntervalMs: number
  cutoff: number
}

export interface CleanupResult {
  deleted: number
  cutoff: number
}


export interface AdminAuditLog {
  id: number
  actor: string | null
  action: string
  method: string
  path: string
  statusCode: number
  result: string
  requestId: string
  sourceIp: string | null
  errorCode: string | null
  createdAt: number
  details: JsonObject | null
}

export interface ModelPriceTier {
  upToTokens: number | null
  inputPricePerMillion: string
  outputPricePerMillion: string
  cachedInputPricePerMillion: string | null
  cachedInputWritePricePerMillion: string | null
}

export interface PricingPayload {
  inputPricePerMillion?: string | null
  outputPricePerMillion?: string | null
  cachedInputPricePerMillion?: string | null
  cachedInputWritePricePerMillion?: string | null
  pricingMode?: string
  perRequestPrice?: string | null
  priceTiers?: ModelPriceTier[]
}

export interface ModelPriceRequest extends PricingPayload {
  channelId: string
  upstreamModel: string
  currency: string
}

export interface ModelPrice extends ModelPriceRequest {
  id: string
  channelName: string
  createdAt: number
  updatedAt: number
}

export interface ModelDefaultPriceRequest extends PricingPayload {
  modelId: string
  currency: string
}

export interface ModelDefaultPrice extends ModelDefaultPriceRequest {
  id: string
  createdAt: number
  updatedAt: number
}

export interface ModelPriceReference {
  model: string
  matchedModel: string
  currency: string
  inputPricePerMillion: string
  outputPricePerMillion: string
  cachedInputPricePerMillion: string | null
  provider: string | null
  source: string
}

export type RequestLogOutcome = 'success' | 'recovered' | 'failed' | 'cancelled' | 'other'

export interface RequestLog {
  outcome?: RequestLogOutcome
  id: number
  requestId: string
  traceId: string | null
  clientKeyId: string | null
  clientKeyName: string | null
  clientIp: string | null
  clientUserAgent: string | null
  alias: string
  channelId: string | null
  channelName: string | null
  channelKeyId: string | null
  channelKeyName: string | null
  upstreamModel: string | null
  protocol: string
  upstreamProtocol: string | null
  stream: boolean | null
  statusCode: number
  firstTokenMs: number | null
  totalMs: number
  inputTokens: number | null
  outputTokens: number | null
  cachedTokens: number | null
  cachedWriteTokens: number | null
  reasoningTokens: number | null
  reasoningEffort: string | null
  totalTokens: number | null
  usageKnown: boolean
  costAmount: string | null
  costCurrency: string | null
  inputPricePerMillion: string | null
  outputPricePerMillion: string | null
  cachedInputPricePerMillion: string | null
  cachedInputWritePricePerMillion: string | null
  pricingMode: string
  perRequestPrice: string | null
  priceTiers: ModelPriceTier[]
  errorCode: string | null
  failureStage: string | null
  errorMessage: string | null
  upstreamErrorCode: string | null
  upstreamErrorBody: string | null
  createdAt: number
  upstreamAttempts: number | null
  attempts: RequestLogAttempt[]
}

export interface RequestLogAttempt {
  index: number
  channelId: string | null
  channelName: string | null
  channelKeyId: string | null
  channelKeyName: string | null
  upstreamModel: string | null
  upstreamReportedModel?: string | null
  upstreamProtocol: string | null
  statusCode?: number | null
  upstreamErrorStatusCode?: number | null
  errorCode: string | null
  upstreamErrorCode: string | null
  errorMessage: string | null
  elapsedMs: number
  firstTokenMs: number | null
  committed: boolean
  retryable: boolean
  skipped?: boolean
  skipReason?: string | null
}

export interface ChannelModel {
  id: string
  name: string | null
  ownedBy: string | null
  authorized: boolean
  canonicalModelId: string | null
  mappingSource: string | null
}

export interface ChannelModelsResponse {
  object: string
  data: ChannelModel[]
}

export interface ModelsDevModel {
  id: string
  canonicalId: string
  provider: string
  providerId: string
  developer: string
  developerId: string
}

export interface ModelDiscoveryRequest {
  baseUrl: string
  credential?: string | null
  channelId?: string | null
  keyId?: string | null
  authType?: string | null
  protocolAuthTypes?: Record<string, string> | null
  protocols?: string[]
  requestOverrides?: UpstreamRequestOverrides
  egressProxy?: ChannelEgressProxyRequest | null
  clearEgressProxy?: boolean
}

export interface ModelSuggestion {
  model: string
  candidates: ModelsDevModel[]
}

export interface ModelSuggestionsResponse {
  loaded: boolean
  error: string | null
  data: ModelSuggestion[]
}

export interface ProtocolProbeResult {
  protocol: string
  supported: boolean
  statusCode: number | null
  message: string
}

export interface ProtocolProbeResponse {
  model: string
  keyId: string | null
  keyName: string | null
  results: ProtocolProbeResult[]
}

export interface ModelTestSettings {
  defaultPrompt: string
  defaultReasoningEffort: string | null
  updatedAt: number
}

export interface NotificationSettingsRequest {
  enabled: boolean
  consecutiveFailureThreshold: number
}

export interface NotificationSettings extends NotificationSettingsRequest {
  updatedAt: number
}

export type NotificationTargetType = 'webhook' | 'feishu'
export type NotificationEventType =
  | 'channel.unhealthy' | 'channel.recovered'
  | 'key.disabled' | 'key.recovered'
  | 'attempt.consecutive_failures' | 'attempt.recovered'
export type NotificationDeliveryStatus = 'pending' | 'in_flight' | 'succeeded' | 'failed' | 'cancelled'

/** 只写凭据：留空表示保持不变，clear 标记表示删除。 */
export interface NotificationCredentialWrite {
  url?: string
  bearerToken?: string
  signingSecret?: string
  clearUrl?: boolean
  clearBearerToken?: boolean
  clearSigningSecret?: boolean
}

export interface NotificationTargetRequest {
  name: string
  type: NotificationTargetType
  enabled: boolean
  events: NotificationEventType[]
  /** 空数组订阅全部渠道，包括今后新增的渠道。 */
  channelIds: string[]
  /** 飞书卡片模板；留空使用默认卡片，仅 feishu 目标使用。 */
  templateTitle?: string | null
  templateBody?: string | null
  /** 凭据由管理台录入并加密保存；响应不会回显明文。 */
  credentials?: NotificationCredentialWrite
  revision?: number
}

export interface NotificationCredentials {
  urlConfigured: boolean
  bearerTokenConfigured: boolean
  signingSecretConfigured: boolean
  urlUnreadable: boolean
  bearerTokenUnreadable: boolean
  signingSecretUnreadable: boolean
}

export interface NotificationTargetResponse {
  id: string
  name: string
  type: NotificationTargetType
  enabled: boolean
  events: NotificationEventType[]
  channelIds: string[]
  templateTitle: string | null
  templateBody: string | null
  revision: number
  updatedAt: number
  credentials: NotificationCredentials
  latestDelivery: NotificationDelivery | null
}

export interface NotificationDelivery {
  id: string
  eventId: string
  targetId: string
  targetName: string
  eventType: string
  status: NotificationDeliveryStatus
  attempts: number
  nextAttemptAt: number
  createdAt: number
  updatedAt: number
  lastStatusCode: number | null
  errorCode: string | null
}

export interface NotificationDeliveryPage {
  items: NotificationDelivery[]
  total: number
  page: number
  pageSize: number
}

export interface NotificationDeliveryPageOptions {
  targetId?: string
  page?: number
  pageSize?: number
}

export interface NotificationTestResponse {
  deliveryId: string
  eventId: string
}

export type WebSearchApiKeySource = 'settings' | 'environment' | 'none'
export type WebSearchBaseUrlSource = 'settings' | 'environment' | 'none'
export type WebSearchSettingsSource = 'settings' | 'environment'

export interface WebSearchSettings {
  enabled: boolean
  provider: string
  apiKeySet: boolean
  apiKeySource: WebSearchApiKeySource
  apiKeyUnreadable: boolean
  baseUrl: string | null
  baseUrlSource: WebSearchBaseUrlSource
  maxResults: number
  timeoutMs: number
  maxRounds: number
  maxPageChars: number
  source: WebSearchSettingsSource
  updatedAt: number
}

export interface WebSearchSettingsRequest {
  enabled: boolean
  provider: string
  apiKey?: string
  clearApiKey?: boolean
  baseUrl?: string | null
  maxResults: number
  timeoutMs: number
  maxRounds: number
  maxPageChars: number
}

export interface ChannelModelTestRequest {
  model: string
  protocol: string
  keyId?: string | null
  prompt?: string
  reasoningEffort?: string | null
}

export interface ChannelModelTestUsage {
  inputTokens: number | null
  outputTokens: number | null
  cachedTokens: number | null
  reasoningTokens: number | null
  totalTokens: number | null
}

export interface ChannelModelTestResult {
  success: boolean
  model: string
  protocol: string
  keyId: string | null
  keyName: string | null
  statusCode: number | null
  latencyMs: number
  prompt: string
  reasoningEffort: string | null
  responseText: string | null
  upstreamReportedModel?: string | null
  errorMessage: string | null
  usage: ChannelModelTestUsage | null
}

/** 连通性、智商与指纹检测共用的渠道模型目标。 */
export interface ModelTestTargetCredential {
  id: string
  name: string
  priority: number
}

export interface ModelTestTarget {
  channelId: string
  channelName: string
  channelEnabled: boolean
  model: string
  protocols: ProtocolId[]
  credentials: ModelTestTargetCredential[]
}

export type FingerprintDecision = 'scored' | 'partial' | 'not_model' | 'unscorable'

export interface FingerprintCandidate {
  model: string
  displayName: string
  family: string
  familyName: string
  score: number
  probability: number | null
}

export interface FingerprintDiagnostic {
  index: number
  parsedNumbers: number
  minimumNumbers: number
  accepted: boolean
  rawOutOfRangeCount: number
}

export interface FingerprintSequence {
  autocorrelation: number
  smallStepShare: number
  modelLike: boolean
}

export interface FingerprintAnalysis {
  decision: FingerprintDecision
  method: string
  prediction: string | null
  predictionName: string
  familyPrediction: string | null
  familyPredictionName: string
  probability: number | null
  probabilityStatus: 'reference_calibrated' | 'unavailable'
  usedOutputs: number
  totalOutputs: number
  rankingScore: number | null
  results: FingerprintCandidate[]
  diagnostics: FingerprintDiagnostic[]
  sequence: FingerprintSequence | null
  evidenceLabel: string
  evidenceReason: string
  detectorSha256: string
  bankBuiltAt: string
}

export interface FingerprintSample {
  index: number
  expectedCount: number
  prompt: string
  text: string | null
  parsedNumbers: number
  minimumNumbers: number
  accepted: boolean
  statusCode: number | null
  latencyMs: number
  errorMessage: string | null
  reportedModel: string | null
}

export interface FingerprintRunRequest {
  channelId: string
  model: string
  keyId?: string | null
  protocol?: ProtocolId | null
}

export interface FingerprintDetectorInfo {
  modelCount: number
  bankBuiltAt: string
  detectorSha256: string
}

export interface FingerprintRun {
  id: number
  channelId: string
  channelName: string
  channelKeyId: string | null
  channelKeyName: string | null
  model: string
  protocol: string
  success: boolean
  statusCode: number | null
  latencyMs: number
  decision: FingerprintDecision
  prediction: string | null
  predictionName: string | null
  probability: number | null
  usedOutputs: number
  totalOutputs: number
  createdAt: number
  errorMessage: string | null
  usage: ChannelModelTestUsage | null
  analysis?: FingerprintAnalysis | null
  samples?: FingerprintSample[]
}

export type IqBenchmarkId = 'pelican' | 'candy'
export type IqVerdict = 'passed' | 'incorrect' | 'review' | 'failed'

export interface IqBenchmarkDefinition {
  id: IqBenchmarkId
  label: string
  summary: string
  prompt: string
  grading: string
  /** 通常耗时提示。 */
  durationHint: string
  /** 题目默认超时（毫秒）。 */
  defaultTimeoutMs: number
  /** 单次检测允许的最大超时（硬上限）。 */
  maxTimeoutMs: number
  /** 实际生效的默认超时：IQ_TEST_TIMEOUT_MS > 题目默认值。 */
  effectiveTimeoutMs: number
}

export interface IqTestTargetCredential {
  id: string
  name: string
  priority: number
}

export interface IqTestTarget {
  channelId: string
  channelName: string
  channelEnabled: boolean
  model: string
  protocols: ProtocolId[]
  credentials: IqTestTargetCredential[]
}

export interface IqQuestionInput {
  title: string
  prompt: string
  answerType: 'number' | 'text'
  expectedAnswer: string
  enabled: boolean
}

export interface IqQuestion extends IqQuestionInput {
  id: number
  updatedAt: number
}

export interface IqTestRunRequest {
  channelId: string
  model: string
  benchmark: IqBenchmarkId
  keyId?: string | null
  protocol?: ProtocolId | null
  judgeId?: string | null
  /** 本次使用的提示词；留空时由服务端使用题目内置提示词。 */
  useQuestionBank?: boolean
  prompt?: string | null
  /** 本次超时覆盖（毫秒）；留空使用默认值，最多 600000 毫秒。 */
  timeoutMs?: number | null
}

/** 后台检测任务状态；完成后用 runId 读取完整检测记录。 */
export interface IqTestJob {
  jobId: string
  status: 'running' | 'completed' | 'failed'
  runId: number | null
  error: string | null
}

export interface IqTestCheck {
  id: string
  label: string
  passed: boolean
  detail: string | null
  /** 为 false 时只是提示，不参与自动判定。 */
  decisive?: boolean
}

export interface IqJudgeResult {
  judgeId: string
  judgeLabel: string
  verdict: IqVerdict
  label: string
  reason: string | null
  score: number | null
  details: Record<string, string>
  checkedAt: number | null
}

export interface IqJudgeInfo {
  id: string
  label: string
  description: string
  builtIn: boolean
  apiVersion: number
}

export interface IqJudgeFailure {
  source: string
  message: string
}

export interface IqJudgesResponse {
  judges: IqJudgeInfo[]
  failures: IqJudgeFailure[]
  defaultJudgeId: string
}

export interface IqTestManualVerdictRequest {
  verdict: 'passed' | 'incorrect'
  score?: number | null
  note?: string | null
}

export interface IqTestRunSummary {
  id: number
  benchmark: IqBenchmarkId
  benchmarkLabel: string
  verdict: IqVerdict
  verdictLabel: string
  success: boolean
  channelId: string
  channelName: string
  channelKeyId: string | null
  channelKeyName: string | null
  model: string
  upstreamModel: string
  protocol: string
  statusCode: number | null
  latencyMs: number
  questionId?: number | null
  questionTitle?: string | null
  answerType?: 'number' | 'text' | null
  createdAt: number
  errorMessage: string | null
  usage: ChannelModelTestUsage | null
  judgeResult: IqJudgeResult | null
  manualVerdict: 'passed' | 'incorrect' | null
  manualScore: number | null
  manualNote: string | null
  manualUpdatedAt: number | null
}

export interface IqTestRunDetail extends IqTestRunSummary {
  prompt: string | null
  responseText: string | null
  html: string | null
  checks: IqTestCheck[]
  expectedAnswerValue?: string | null
  actualAnswer?: string | null
  expectedAnswer: number | null
  matchedAnswer: number | null
  upstreamReportedModel: string | null
}

export interface PageOptions {
  limit?: number
  beforeId?: number | null
}

export interface AuditLogPageOptions extends PageOptions {
  from?: number | null
  to?: number | null
  actor?: string | null
  action?: string | null
  result?: 'success' | 'failure' | null
}

export interface RequestLogPageOptions extends PageOptions {
  from?: number | null
  to?: number | null
  q?: string
  channelId?: string
  model?: string
  httpStatus?: string
  outcome?: RequestLogOutcome | 'abnormal' | ''
}

export interface RequestLogFilterOptions {
  channels: Array<{ value: string; label: string }>
  models: Array<{ value: string; label: string }>
  statuses: Array<{ value: string; label: string }>
}

export interface UsageReportQuery {
  from: number
  to: number
  clientKeyId?: string | null
  channelId?: string | null
  model?: string | null
  protocol?: string | null
  timezoneOffsetMinutes?: number
}

export interface OAuthProviderInfo {
  id: string
  label: string
  configured: boolean
  managedChannel: boolean
  /** 系统已置备该 provider 端点渠道时的渠道 ID；尚未置备时为 null。 */
  channelId?: string | null
  baseUrl?: string
  protocols?: string[]
}

export interface OAuthProviderModelMappingsUpdateRequest {
  changes: OAuthModelMappingChange[]
  expectedRevision: number
}

export interface OAuthProviderModel {
  id: string
  name: string | null
}

export interface OAuthProviderModelsResponse {
  models: OAuthProviderModel[]
}

export interface OAuthAccountPolicy {
  windowLimit: number | null
  windowSeconds: number
  maxConcurrentRequests: number
}

export interface OAuthAccountRuntime {
  windowUsed: number
  cooldownUntil: number | null
  consecutiveRateLimits: number
  activeRequests: number
  quotaBlockedUntil: number | null
}

export interface OAuthQuotaWindow {
  usedPercent: number
  remainingPercent: number
  windowSeconds: number | null
  resetsAt: number | null
}

export interface OAuthQuotaGroup {
  name: string
  windows: OAuthQuotaWindow[]
}

export interface OAuthQuota {
  primary: OAuthQuotaWindow | null
  secondary: OAuthQuotaWindow | null
  allowed: boolean | null
  creditsBalance: number | null
  creditsUnlimited: boolean
  resetCredits: number | null
  /** Antigravity 按模型家族分组的额度；ChatGPT 为空。 */
  groups: OAuthQuotaGroup[]
  checkedAt: number
  error: string | null
}

export type OAuthAuthState = 'ACTIVE' | 'REAUTH_REQUIRED'

export interface OAuthAccount {
  id: string
  name: string
  provider: string
  authMethod: 'oauth' | 'agy'
  accountEmail: string | null
  accountPlan: string | null
  authState: OAuthAuthState
  enabled: boolean
  priority: number
  models: string[]
  /** 该账号自己的上游模型目录（upstream id）；null 表示尚未同步。 */
  modelCatalog: string[] | null
  modelCatalogSyncedAt: number | null
  channelIds: string[]
  channels: Array<{ id: string; name: string }>
  tokenType: string
  scope: string | null
  accessTokenExpiresAt: number | null
  lastRefreshedAt: number | null
  refreshFailureCount: number
  lastRefreshError: string | null
  proxyUrl: string | null
  chatgptAccountId: string | null
  quota: OAuthQuota | null
  createdAt: number
  updatedAt: number
  /** refresh token 的获取时间；重新授权时更新，日常 token 刷新不改变。 */
  authorizedAt: number
  policy: OAuthAccountPolicy
  runtime: OAuthAccountRuntime
}

export interface OAuthLoginStartRequest {
  provider: string
  accountId?: string | null
  name?: string | null
  channelIds?: string[]
  proxyUrl?: string | null
}

export interface OAuthLoginStart {
  loginId: string
  authorizeUrl: string
  redirectUri: string
  expiresAt: number
}

export interface OAuthLoginCompleteRequest {
  callbackUrl: string
}

export interface AntigravityAgyAccount {
  id: string
  name: string
  sourceAccountId: string | null
  subscriptionTier: string | null
  currentModelId: string | null
  authorizedAt: number
  routingEnabled: boolean
  registered?: boolean
  accountId?: string | null
  enabled?: boolean
  routeModels?: string[]
  cleanupEligible?: boolean
}

export interface AntigravityAgyAccounts {
  configured: boolean
  accounts: AntigravityAgyAccount[]
}

export interface AntigravityAgyLogin {
  loginId: string
  state: 'PREPARING' | 'AWAITING_CODE' | 'VERIFYING' | 'REGISTERING' | 'AUTHENTICATED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
  expiresAt: number
  authorizeUrl?: string | null
  account?: AntigravityAgyAccount | null
  error?: string | null
}

export type OAuthModelMappingMode = 'auto' | 'manual' | 'upstream'

export interface OAuthProviderModelMapping {
  upstreamModel: string
  canonicalModelId: string | null
  publicModelId: string
  automaticCanonicalModelId: string | null
  mode: OAuthModelMappingMode
  mappingSource: string | null
  catalogActive: boolean
  /** 至少有一个启用账号授权且自身目录支持，不表示实时调度资格。 */
  enabled: boolean
}

/** 映射按提供方共享，账号自己的 modelCatalog 与 models 仍是 upstream id。 */
export interface OAuthProviderModelMappingsResponse {
  provider: string
  scope: 'provider'
  revision: number
  affectedAccountCount: number
  mappings: OAuthProviderModelMapping[]
}

export interface OAuthModelMappingChange {
  upstreamModel: string
  mode: OAuthModelMappingMode
  canonicalModelId?: string
}

export interface OAuthAccountSettingsRequest {
  name?: string | null
  priority?: number
  enabled?: boolean
  proxyUrl?: string | null
  clearProxy?: boolean
  models?: string[]
  modelMappingChanges?: OAuthModelMappingChange[]
  expectedModelMappingRevision?: number
}

export interface OAuthAccountChannelsRequest {
  channelIds: string[]
}

export interface OAuthAccountModelsRequest {
  models: string[]
}

export interface OAuthAccountPolicyRequest {
  windowLimit?: number | null
  windowSeconds?: number
  maxConcurrentRequests?: number
}

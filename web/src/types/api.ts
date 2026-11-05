export type ProtocolId = 'chat_completions' | 'responses' | 'messages' | 'gemini'

export type HealthWindow = '24h' | '7d' | '15d' | '30d'
export type HealthState = 'HEALTHY' | 'WARNING' | 'CRITICAL' | 'UNKNOWN' | 'DISABLED'
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
}
export interface ChannelHealthReport {
  window: HealthWindow; from: number; to: number; retentionDays: number; indexing: boolean
  minimumSamples: number; staleAfterMs: number; warningErrorRate: number; criticalErrorRate: number; slowFirstTokenMs: number
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
  quota?: ChannelQuotaSettings | null
  state: ChannelKeyState
  priority: number
  credentialSet: boolean
  models: string[]
  rateLimit: ChannelKeyRateLimit
  stateVersion: number
  autoDisableUntil: number | null
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
}

export interface UpstreamRequestOverrides extends UpstreamProtocolOverrides {
  inheritGlobal: boolean
  protocols: Partial<Record<ProtocolId, UpstreamProtocolOverrides>>
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
  modelMappings: ChannelModelMapping[]
  priority: number
  keys: ChannelKey[]
  requestOverrides: UpstreamRequestOverrides
  quota: ChannelQuotaSettings
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
}

export interface KeyDisableRule {
  id: string
  name: string
  statusCodes: number[]
  statusCodeAtLeast: number | null
  bodyPatterns: string[]
  threshold: number
  windowMinutes: number
  streak: boolean
  action: 'TEMPORARY' | 'PERMANENT'
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

export interface ClientKeyQuotaUsage {
  dailyTokens: number
  dailyUsdCost: string
  dailyCnyCost: string
  dailyEurCost: string
  concurrentRequests: number
  currentMinuteRequests: number
}

export interface ClientKey {
  id: string
  name: string
  keyPrefix: string
  enabled: boolean
  expiresAt: number | null
  dailyTokenLimit: number | null
  dailyUsdLimit: string | null
  dailyCnyLimit: string | null
  dailyEurLimit: string | null
  maxConcurrentRequests: number | null
  requestsPerMinute: number | null
  quotaUsage: ClientKeyQuotaUsage
  createdAt: number
  rotatedAt: number | null
  revokedAt: number | null
}

export interface ClientKeyRequest {
  name: string
  enabled?: boolean
  expiresAt?: number | null
  dailyTokenLimit?: number | null
  dailyUsdLimit?: string | null
  dailyCnyLimit?: string | null
  dailyEurLimit?: string | null
  maxConcurrentRequests?: number | null
  requestsPerMinute?: number | null
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
  costs: Record<string, string>
}

export interface UsageTotal extends UsageDimensions, UsageCosts {
  activeClientKeys: number
  failovers: number
  avgFirstTokenMs: number | null
  avgTotalMs: number | null
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

export interface ExchangeRates {
  base: string
  date: string
  source: string
  rates: Record<string, string>
}

export interface RequestLog {
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

export interface UsageReportQuery {
  from: number
  to: number
  clientKeyId?: string | null
  channelId?: string | null
  model?: string | null
  protocol?: string | null
  timezoneOffsetMinutes?: number
}

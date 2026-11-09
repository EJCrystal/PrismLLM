package dev.soven.llmgateway.api

import dev.soven.llmgateway.channel.domain.ChannelQuotaSettings
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import dev.soven.llmgateway.channel.domain.ChannelQuotaCredentialSource

import dev.soven.llmgateway.domain.AdminAuditRecord
import dev.soven.llmgateway.quota.domain.ClientKeyRecord
import dev.soven.llmgateway.channel.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.pricing.domain.ModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
import dev.soven.llmgateway.pricing.domain.DefaultModelPriceRecord
import dev.soven.llmgateway.domain.ModelTestSettingsRecord
import dev.soven.llmgateway.oauth.domain.OAuthAccountPolicy
import dev.soven.llmgateway.oauth.domain.OAuthAccountRecord
import dev.soven.llmgateway.oauth.domain.OAuthAccountRuntimeState
import dev.soven.llmgateway.oauth.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.oauth.domain.OAuthQuotaWindow
import dev.soven.llmgateway.shared.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.channel.domain.UpstreamRequestSettingsRecord
import dev.soven.llmgateway.channel.domain.UpstreamHeaderCatalogSnapshot
import dev.soven.llmgateway.channel.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.domain.IqTestRunRecord
import dev.soven.llmgateway.requestlog.domain.RequestLogRecord
import dev.soven.llmgateway.plugin.iq.IqJudgeInfo
import dev.soven.llmgateway.plugin.iq.IqJudgePluginFailure
import dev.soven.llmgateway.proxy.IqBenchmark
import dev.soven.llmgateway.proxy.IqTestEvaluation
import dev.soven.llmgateway.proxy.IqVerdict
import dev.soven.llmgateway.proxy.analyzePelicanStructure
import dev.soven.llmgateway.proxy.evaluateIqTest
import dev.soven.llmgateway.proxy.evaluateLegacyCandy
import dev.soven.llmgateway.proxy.ModelConnectivityTestResult
import dev.soven.llmgateway.shared.domain.Usage
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaUsage
import dev.soven.llmgateway.requestlog.domain.CleanupResult
import dev.soven.llmgateway.requestlog.domain.RetentionPolicy
import dev.soven.llmgateway.requestlog.domain.UsageGroup
import dev.soven.llmgateway.requestlog.domain.UsageReport
import dev.soven.llmgateway.requestlog.domain.UsageTotal
import dev.soven.llmgateway.requestlog.domain.UsageTrendPoint
import dev.soven.llmgateway.channel.domain.key.KeyAutoDisableSettingsRecord
import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.channel.domain.key.KeyDisablePolicy
import dev.soven.llmgateway.channel.domain.key.KeyDisableRule
import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import dev.soven.llmgateway.spi.iq.IqStructureCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 管理面登录请求。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class LoginRequest(val username: String, val password: String)

/**
 * 当前管理员信息。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class MeResponse(
    val username: String,
    val avatarDataUrl: String? = null,
)

/**
 * 管理员头像更新请求。空值表示恢复默认首字母头像。
 */
@Serializable
data class AvatarRequest(val avatarDataUrl: String? = null)

/**
 * 管理员修改密码请求。
 */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/**
 * 渠道写请求。credential 为空时表示保持原凭据。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ChannelModelMappingRequest(
    val upstreamModel: String,
    val canonicalModelId: String? = null,
    val mappingSource: String? = null,
)

/**
 * 渠道密钥写请求。id 为空表示新建；credential 为空表示保持原凭据。
 */
@Serializable
data class ChannelKeyRateLimitRequest(
    val enabled: Boolean = true,
    val requestsPerMinute: Int? = null,
    val maxConcurrentRequests: Int? = null,
)

/**
 * 渠道密钥写请求。id 为空表示新建；credential 为空表示保持原凭据。
 *
 * state 只接受 ACTIVE 与 MANUAL_DISABLED：自动停用状态只能由系统写入，
 * 客户端通过显式提交 ACTIVE 执行人工恢复；省略时保持已有状态。
 */
@Serializable
data class ChannelKeyRequest(
    val id: String? = null,
    val name: String,
    val credential: String? = null,
    val state: ChannelKeyState? = null,
    val priority: Int? = null,
    val models: List<String> = emptyList(),
    val rateLimit: ChannelKeyRateLimitRequest? = null,
    val stateVersion: Long? = null,
)

@Serializable
data class ChannelRequest(
    val name: String,
    val baseUrl: String,
    val credential: String? = null,
    val authType: String? = null,
    val protocols: List<String>? = null,
    val enabled: Boolean = true,
    val models: List<String>? = null,
    val modelMappings: List<ChannelModelMappingRequest>? = null,
    val priority: Int? = null,
    val keys: List<ChannelKeyRequest>? = null,
    val requestOverrides: UpstreamRequestOverrides? = null,
    /** Optional protocol-specific overrides; omitted entries inherit authType. */
    val protocolAuthTypes: Map<String, String>? = null,
    val quota: ChannelQuotaSettingsRequest? = null,
)

/** 写入凭据后只返回 credentialSet；省略或留空保留，clearCredential 显式清除。 */
@Serializable
data class ChannelQuotaSettingsRequest(
    val providerId: String = "",
    val config: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val credentialSource: ChannelQuotaCredentialSource = ChannelQuotaCredentialSource.CHANNEL_KEY,
    val credential: String? = null,
    val clearCredential: Boolean = false,
) {
    fun toSettings() = ChannelQuotaSettings(providerId, config, credentialSource)
    override fun toString() = "ChannelQuotaSettingsRequest(providerId=$providerId, credential=[REDACTED])"
}

@Serializable
data class ChannelQuotaSettingsResponse(
    val providerId: String = "",
    val config: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val credentialSource: ChannelQuotaCredentialSource = ChannelQuotaCredentialSource.CHANNEL_KEY,
    val credentialSet: Boolean = false,
)

/**
 * 渠道读响应。永不回显上游凭据。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ChannelResponse(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: String,
    val protocols: List<String>,
    val enabled: Boolean,
    val credentialSet: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val models: List<String>,
    val modelMappings: List<ChannelModelMappingResponse>,
    val priority: Int,
    val keys: List<ChannelKeyResponse> = emptyList(),
    val requestOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    val protocolAuthTypes: Map<String, String> = emptyMap(),
    val quota: ChannelQuotaSettingsResponse = ChannelQuotaSettingsResponse(),
    /** custom：用户自建；provider：系统按订阅 provider 置备。 */
    val kind: String = "custom",
    val provider: String? = null,
    /** 是否配置了渠道级密钥自动禁用策略；false 表示继承全局设置。 */
    val keyDisablePolicySet: Boolean = false,
    /** inherit、custom 或 disabled，供渠道列表直接展示。 */
    val keyDisableMode: String = "inherit",
)

@Serializable
data class ChannelKeyRateLimitResponse(
    val enabled: Boolean = false,
    val requestsPerMinute: Int? = null,
    val maxConcurrentRequests: Int? = null,
)

fun ChannelKeyRateLimitPolicy.toResponse() = ChannelKeyRateLimitResponse(
    enabled = enabled,
    requestsPerMinute = requestsPerMinute,
    maxConcurrentRequests = maxConcurrentRequests,
)

@Serializable
data class ChannelKeyResponse(
    val id: String,
    val name: String,
    val state: ChannelKeyState,
    val priority: Int,
    val credentialSet: Boolean,
    val models: List<String>,
    val rateLimit: ChannelKeyRateLimitResponse = ChannelKeyRateLimitResponse(),
    val stateVersion: Long = 0,
    val autoDisableUntil: Long? = null,
    /** 临时停用到期后的探测租约；未到期时这条密钥仍不参与路由，管理台据此与后端口径对齐。 */
    val probeUntil: Long? = null,
    val autoDisableReason: String? = null,
    val quota: ChannelQuotaSettingsResponse? = null,
)

fun ChannelKeyRecord.toResponse() = ChannelKeyResponse(
    id = id,
    name = name,
    state = state,
    priority = priority,
    credentialSet = credentialSet,
    models = models,
    rateLimit = rateLimit.toResponse(),
    stateVersion = stateVersion,
    autoDisableUntil = autoDisableUntil,
    probeUntil = probeUntil,
    autoDisableReason = autoDisableReason,
    quota = quota?.let { ChannelQuotaSettingsResponse(it.providerId, it.config, it.credentialSource, quotaCredential != null) },
)

/**
 * 自动禁用设置。新客户端提交 rules；旧客户端仍可提交四个标量字段，
 * 后端会把它们翻译成两条兼容规则。
 */
@Serializable
data class KeyAutoDisableSettingsRequest(
    val enabled: Boolean,
    val rules: List<KeyDisableRule>? = null,
    val authFailureThreshold: Int? = null,
    val failureThreshold: Int? = null,
    val failureWindowMinutes: Int? = null,
)

@Serializable
data class KeyAutoDisableSettingsResponse(
    val enabled: Boolean,
    val rules: List<KeyDisableRule>,
    val revision: Long,
    val updatedAt: Long,
    // 旧管理台读取的兼容字段；实际判定只使用 rules。
    val authFailureThreshold: Int,
    val failureThreshold: Int,
    val failureWindowMinutes: Int,
)

fun KeyAutoDisableSettingsRecord.toResponse() = KeyAutoDisableSettingsResponse(
    enabled = policy.enabled,
    rules = policy.rules,
    revision = revision,
    updatedAt = updatedAt,
    authFailureThreshold = policy.rules.firstOrNull {
        it.id != "insufficient_balance" && 401 in it.statusCodes && it.streak
    }?.threshold ?: 3,
    failureThreshold = policy.rules.firstOrNull { it.includeTransportFailures }?.threshold
        ?: policy.rules.firstOrNull { !it.streak }?.threshold ?: 5,
    failureWindowMinutes = policy.rules.firstOrNull { it.includeTransportFailures }?.windowMinutes
        ?: policy.rules.firstOrNull { !it.streak }?.windowMinutes ?: 5,
)

/**
 * 渠道覆盖的自动禁用策略。`override` 为 null 表示继承全局设置；
 * [effective] 是解析后的实际生效策略（自定义模式已把全局规则拼在后面），
 * [globalPolicy] 是当前全局设置，管理台用它展示「未命中时回落到哪些规则」。
 */
@Serializable
data class ChannelKeyDisablePolicyResponse(
    val channelId: String,
    /** inherit、custom 或 disabled。 */
    val mode: String,
    val override: KeyDisablePolicy? = null,
    val globalPolicy: KeyDisablePolicy,
    val effective: KeyDisablePolicy,
)

/** `override` 为 null 时恢复继承全局设置；[KeyDisablePolicy] 自带校验，非法规则在保存时失败。 */
@Serializable
data class ChannelKeyDisablePolicyRequest(val override: KeyDisablePolicy? = null)

/** 手动解除冷却的范围：channel 清整个渠道，key 清密钥及其候选，candidate 只清单个候选。 */
@Serializable
data class ClearCooldownRequest(
    val scope: String,
    val keyId: String? = null,
    val upstreamModel: String? = null,
)

@Serializable
data class ChannelModelMappingResponse(
    val upstreamModel: String,
    val canonicalModelId: String? = null,
    val mappingSource: String? = null,
)

fun ChannelRecord.toResponse() = ChannelResponse(
    id = id,
    name = name,
    baseUrl = baseUrl,
    authType = authType,
    protocolAuthTypes = protocolAuthTypes,
    protocols = protocols.map { it.value },
    enabled = enabled,
    credentialSet = keys.any { it.credentialSet },
    createdAt = createdAt,
    updatedAt = updatedAt,
    models = models,
    modelMappings = modelMappings.map {
        ChannelModelMappingResponse(
            upstreamModel = it.upstreamModel,
            canonicalModelId = it.canonicalModelId,
            mappingSource = it.mappingSource,
        )
    },
    priority = priority,
    keys = keys.map { it.toResponse() },
    requestOverrides = requestOverrides,
    quota = ChannelQuotaSettingsResponse(quota.providerId, quota.config, quota.credentialSource, quotaCredential != null),
    kind = kind.value,
    provider = provider?.value,
    keyDisablePolicySet = keyDisablePolicySet,
    keyDisableMode = keyDisableMode.value,
)

@Serializable
data class QuotaProviderResponse(val id: String, val displayName: String, val apiVersion: Int)

@Serializable
data class UpstreamRequestSettingsRequest(val overrides: UpstreamRequestOverrides)

@Serializable
data class HeaderProfilePinRequest(val version: String)

@Serializable
data class UpstreamHeaderCatalogResponse(
    val revision: String,
    val checkedAt: Long,
    val successAt: Long?,
    val lastError: String?,
    val refreshing: Boolean,
    val autoRefreshEnabled: Boolean,
    val refreshIntervalMs: Long,
    val profiles: List<UpstreamHeaderProfile>,
    val history: Map<String, List<UpstreamHeaderProfile>>,
    val pins: Map<String, String>,
)

@Serializable
data class UpstreamRequestSettingsResponse(
    val overrides: UpstreamRequestOverrides,
    val profiles: List<UpstreamHeaderProfile>,
    val updatedAt: Long,
    val headerCatalog: UpstreamHeaderCatalogResponse,
)

fun UpstreamHeaderCatalogSnapshot.toResponse() = UpstreamHeaderCatalogResponse(
    revision = revision,
    checkedAt = checkedAt,
    successAt = successAt,
    lastError = lastError,
    refreshing = refreshing,
    autoRefreshEnabled = autoRefreshEnabled,
    refreshIntervalMs = refreshIntervalMs,
    profiles = profiles,
    history = history,
    pins = pins,
)

fun UpstreamRequestSettingsRecord.toResponse(catalog: UpstreamHeaderCatalogSnapshot) = UpstreamRequestSettingsResponse(
    overrides = overrides,
    profiles = catalog.profiles,
    updatedAt = updatedAt,
    headerCatalog = catalog.toResponse(),
)

/**
 * 调用方密钥写请求。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ClientKeyRequest(
    val name: String,
    val enabled: Boolean = true,
    val expiresAt: Long? = null,
    val dailyTokenLimit: Long? = null,
    val dailyUsdLimit: String? = null,
    val dailyCnyLimit: String? = null,
    val dailyEurLimit: String? = null,
    val maxConcurrentRequests: Int? = null,
    val requestsPerMinute: Int? = null,
)

/**
 * 调用方密钥读响应。只包含展示前缀。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ClientKeyResponse(
    val id: String,
    val name: String,
    val keyPrefix: String,
    val enabled: Boolean,
    val expiresAt: Long?,
    val dailyTokenLimit: Long?,
    val dailyUsdLimit: String?,
    val dailyCnyLimit: String?,
    val dailyEurLimit: String?,
    val maxConcurrentRequests: Int?,
    val requestsPerMinute: Int?,
    val quotaUsage: ClientKeyQuotaUsageResponse,
    val createdAt: Long,
    val rotatedAt: Long?,
    val revokedAt: Long?,
)

fun ClientKeyRecord.toResponse() = ClientKeyResponse(
    id = id,
    name = name,
    keyPrefix = keyPrefix,
    enabled = enabled,
    expiresAt = expiresAt,
    dailyTokenLimit = quotas.dailyTokens,
    dailyUsdLimit = quotas.dailyCostLimit("USD")?.stripTrailingZeros()?.toPlainString(),
    dailyCnyLimit = quotas.dailyCostLimit("CNY")?.stripTrailingZeros()?.toPlainString(),
    dailyEurLimit = quotas.dailyCostLimit("EUR")?.stripTrailingZeros()?.toPlainString(),
    maxConcurrentRequests = quotas.maxConcurrentRequests,
    requestsPerMinute = quotas.requestsPerMinute,
    quotaUsage = ClientKeyQuotaUsageResponse(),
    createdAt = createdAt,
    rotatedAt = rotatedAt,
    revokedAt = revokedAt,
)

fun ClientKeyRecord.toResponse(usage: ClientKeyQuotaUsageResponse) = ClientKeyResponse(
    id = id,
    name = name,
    keyPrefix = keyPrefix,
    enabled = enabled,
    expiresAt = expiresAt,
    dailyTokenLimit = quotas.dailyTokens,
    dailyUsdLimit = quotas.dailyCostLimit("USD")?.stripTrailingZeros()?.toPlainString(),
    dailyCnyLimit = quotas.dailyCostLimit("CNY")?.stripTrailingZeros()?.toPlainString(),
    dailyEurLimit = quotas.dailyCostLimit("EUR")?.stripTrailingZeros()?.toPlainString(),
    maxConcurrentRequests = quotas.maxConcurrentRequests,
    requestsPerMinute = quotas.requestsPerMinute,
    quotaUsage = usage,
    createdAt = createdAt,
    rotatedAt = rotatedAt,
    revokedAt = revokedAt,
)

fun quotaUsageResponse(usage: ClientKeyQuotaUsage) = ClientKeyQuotaUsageResponse(
    dailyTokens = usage.dailyTokens,
    dailyUsdCost = usage.dailyCost("USD").stripTrailingZeros().toPlainString(),
    dailyCnyCost = usage.dailyCost("CNY").stripTrailingZeros().toPlainString(),
    dailyEurCost = usage.dailyCost("EUR").stripTrailingZeros().toPlainString(),
    concurrentRequests = usage.concurrentRequests,
    currentMinuteRequests = usage.currentMinuteRequests,
)

@Serializable
data class ClientKeyQuotaUsageResponse(
    val dailyTokens: Long = 0,
    val dailyUsdCost: String = "0",
    val dailyCnyCost: String = "0",
    val dailyEurCost: String = "0",
    val concurrentRequests: Int = 0,
    val currentMinuteRequests: Int = 0,
)

/**
 * 管理面实时占用快照。计数只在当前实例内存中，进程重启或换实例后归零。
 *
 * @author 文杰
 * @since 2026-11-06
 */
@Serializable
data class OccupancyResponse(
    val sampledAt: Long,
    val channels: List<ChannelOccupancyResponse> = emptyList(),
    val clientKeys: List<ClientKeyOccupancyResponse> = emptyList(),
)

@Serializable
data class ChannelOccupancyResponse(
    val channelId: String,
    val concurrentRequests: Int,
    val keys: List<ChannelKeyOccupancyResponse> = emptyList(),
)

@Serializable
data class ChannelKeyOccupancyResponse(
    val keyId: String,
    val concurrentRequests: Int,
)

@Serializable
data class ClientKeyOccupancyResponse(
    val clientKeyId: String,
    val concurrentRequests: Int,
)

@Serializable
data class UsageReportResponse(
    val from: Long,
    val to: Long,
    val granularity: String,
    val total: UsageTotalResponse,
    val trend: List<UsageTrendResponse>,
    val byClientKey: List<UsageGroupResponse>,
    val byChannel: List<UsageGroupResponse>,
    val byModel: List<UsageGroupResponse>,
    val byChannelKey: List<UsageGroupResponse>,
    val byProtocol: List<UsageGroupResponse>,
    val byErrorCode: List<UsageGroupResponse>,
)

@Serializable
data class UsageTotalResponse(
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cachedWriteTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
    val activeClientKeys: Long,
    val failovers: Long,
    val avgFirstTokenMs: Long? = null,
    val avgTotalMs: Long? = null,
    val costs: Map<String, String>,
)

@Serializable
data class UsageGroupResponse(
    val id: String,
    val name: String? = null,
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cachedWriteTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
    val failovers: Long,
    val avgFirstTokenMs: Long? = null,
    val avgTotalMs: Long? = null,
    val costs: Map<String, String>,
)

@Serializable
data class UsageTrendResponse(
    val bucketStart: Long,
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cachedWriteTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
    val costs: Map<String, String>,
)

@Serializable
data class RetentionPolicyResponse(
    val retentionDays: Int,
    val batchSize: Int,
    val cleanupEnabled: Boolean,
    val cleanupIntervalMs: Long,
    val cutoff: Long,
)

@Serializable
data class UpdateRetentionPolicyRequest(
    val cleanupEnabled: Boolean,
)

@Serializable
data class CleanupResultResponse(val deleted: Long, val cutoff: Long)

fun UsageReport.toResponse() = UsageReportResponse(
    from = from,
    to = to,
    granularity = granularity,
    total = total.toResponse(),
    trend = trend.map { it.toResponse() },
    byClientKey = byClientKey.map { it.toResponse() },
    byChannel = byChannel.map { it.toResponse() },
    byModel = byModel.map { it.toResponse() },
    byChannelKey = byChannelKey.map { it.toResponse() },
    byProtocol = byProtocol.map { it.toResponse() },
    byErrorCode = byErrorCode.map { it.toResponse() },
)

fun UsageTotal.toResponse() = UsageTotalResponse(
    requests = requests,
    errors = errors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
    cachedWriteTokens = cachedWriteTokens,
    reasoningTokens = reasoningTokens,
    totalTokens = totalTokens,
    activeClientKeys = activeClientKeys,
    failovers = failovers,
    avgFirstTokenMs = avgFirstTokenMs,
    avgTotalMs = avgTotalMs,
    costs = costs.mapValues { (_, value) -> value.stripTrailingZeros().toPlainString() },
)

fun UsageGroup.toResponse() = UsageGroupResponse(
    id = id,
    name = name,
    requests = requests,
    errors = errors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
    cachedWriteTokens = cachedWriteTokens,
    reasoningTokens = reasoningTokens,
    totalTokens = totalTokens,
    failovers = failovers,
    avgFirstTokenMs = avgFirstTokenMs,
    avgTotalMs = avgTotalMs,
    costs = costs.mapValues { (_, value) -> value.stripTrailingZeros().toPlainString() },
)

fun UsageTrendPoint.toResponse() = UsageTrendResponse(
    bucketStart = bucketStart,
    requests = requests,
    errors = errors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
    cachedWriteTokens = cachedWriteTokens,
    reasoningTokens = reasoningTokens,
    totalTokens = totalTokens,
    costs = costs.mapValues { (_, value) -> value.stripTrailingZeros().toPlainString() },
)

fun RetentionPolicy.toResponse(now: Long) = RetentionPolicyResponse(
    retentionDays = retentionDays,
    batchSize = batchSize,
    cleanupEnabled = cleanupEnabled,
    cleanupIntervalMs = cleanupIntervalMs,
    cutoff = now - retentionDays * 86_400_000L,
)

fun CleanupResult.toResponse() = CleanupResultResponse(deleted, cutoff)

/**
 * 新建或轮换调用方密钥响应。key 只在本次响应返回一次。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ClientKeyCreatedResponse(
    val id: String,
    val name: String,
    val key: String,
    val keyPrefix: String,
    val enabled: Boolean,
    val expiresAt: Long?,
    val dailyTokenLimit: Long?,
    val dailyUsdLimit: String?,
    val dailyCnyLimit: String?,
    val dailyEurLimit: String?,
    val maxConcurrentRequests: Int?,
    val requestsPerMinute: Int?,
    val createdAt: Long,
    val rotatedAt: Long?,
    val revokedAt: Long?,
)

fun ClientKeyRecord.toCreatedResponse(rawKey: String) = ClientKeyCreatedResponse(
    id = id,
    name = name,
    key = rawKey,
    keyPrefix = keyPrefix,
    enabled = enabled,
    expiresAt = expiresAt,
    dailyTokenLimit = quotas.dailyTokens,
    dailyUsdLimit = quotas.dailyCostLimit("USD")?.stripTrailingZeros()?.toPlainString(),
    dailyCnyLimit = quotas.dailyCostLimit("CNY")?.stripTrailingZeros()?.toPlainString(),
    dailyEurLimit = quotas.dailyCostLimit("EUR")?.stripTrailingZeros()?.toPlainString(),
    maxConcurrentRequests = quotas.maxConcurrentRequests,
    requestsPerMinute = quotas.requestsPerMinute,
    createdAt = createdAt,
    rotatedAt = rotatedAt,
    revokedAt = revokedAt,
)

/**
 * 管理面写操作审计读响应。
 */
@Serializable
data class AdminAuditLogResponse(
    val id: Long,
    val actor: String?,
    val action: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val result: String,
    val requestId: String,
    val sourceIp: String?,
    val errorCode: String?,
    val createdAt: Long,
    val details: JsonObject? = null,
)

fun AdminAuditRecord.toResponse() = AdminAuditLogResponse(
    id = id,
    actor = actor,
    action = action,
    method = method,
    path = path,
    statusCode = statusCode,
    result = result,
    requestId = requestId,
    sourceIp = sourceIp,
    errorCode = errorCode,
    createdAt = createdAt,
    details = details,
)

/**
 * 模型计费单价写请求。价格单位是每百万 token，使用字符串避免浮点误差。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ModelPriceRequest(
    val channelId: String,
    val upstreamModel: String,
    val currency: String = "USD",
    val inputPricePerMillion: String? = null,
    val outputPricePerMillion: String? = null,
    val cachedInputPricePerMillion: String? = null,
    val cachedInputWritePricePerMillion: String? = null,
    val pricingMode: String = PricingMode.TOKEN.value,
    val perRequestPrice: String? = null,
    val priceTiers: List<ModelPriceTierRequest> = emptyList(),
)

@Serializable
data class ModelPriceTierRequest(
    val upToTokens: Long? = null,
    val inputPricePerMillion: String,
    val outputPricePerMillion: String,
    val cachedInputPricePerMillion: String? = null,
    val cachedInputWritePricePerMillion: String? = null,
)

@Serializable
data class ModelPriceTierResponse(
    val upToTokens: Long?,
    val inputPricePerMillion: String,
    val outputPricePerMillion: String,
    val cachedInputPricePerMillion: String? = null,
    val cachedInputWritePricePerMillion: String? = null,
)

/**
 * 模型计费单价读响应。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ModelPriceResponse(
    val id: String,
    val channelId: String,
    val channelName: String,
    val upstreamModel: String,
    val currency: String,
    val inputPricePerMillion: String,
    val outputPricePerMillion: String,
    val cachedInputPricePerMillion: String?,
    val cachedInputWritePricePerMillion: String? = null,
    val pricingMode: String = PricingMode.TOKEN.value,
    val perRequestPrice: String? = null,
    val priceTiers: List<ModelPriceTierResponse> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

fun ModelPriceRecord.toResponse() = ModelPriceResponse(
    id = id,
    channelId = channelId,
    channelName = channelName,
    upstreamModel = upstreamModel,
    currency = currency,
    inputPricePerMillion = inputPricePerMillion.toPlainString(),
    outputPricePerMillion = outputPricePerMillion.toPlainString(),
    cachedInputPricePerMillion = cachedInputPricePerMillion?.toPlainString(),
    cachedInputWritePricePerMillion = cachedInputWritePricePerMillion?.toPlainString(),
    pricingMode = pricingMode.value,
    perRequestPrice = perRequestPrice?.toPlainString(),
    priceTiers = priceTiers.map { it.toResponse() },
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * 模型默认单价写请求。价格单位是每百万 token，使用字符串避免浮点误差。
 */
@Serializable
data class ModelDefaultPriceRequest(
    val modelId: String,
    val currency: String = "USD",
    val inputPricePerMillion: String? = null,
    val outputPricePerMillion: String? = null,
    val cachedInputPricePerMillion: String? = null,
    val cachedInputWritePricePerMillion: String? = null,
    val pricingMode: String = PricingMode.TOKEN.value,
    val perRequestPrice: String? = null,
    val priceTiers: List<ModelPriceTierRequest> = emptyList(),
)
/**
 * 模型默认单价读响应。
 */
@Serializable
data class ModelDefaultPriceResponse(
    val id: String,
    val modelId: String,
    val currency: String,
    val inputPricePerMillion: String,
    val outputPricePerMillion: String,
    val cachedInputPricePerMillion: String?,
    val cachedInputWritePricePerMillion: String? = null,
    val pricingMode: String = PricingMode.TOKEN.value,
    val perRequestPrice: String? = null,
    val priceTiers: List<ModelPriceTierResponse> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

fun DefaultModelPriceRecord.toResponse() = ModelDefaultPriceResponse(
    id = id,
    modelId = modelId,
    currency = currency,
    inputPricePerMillion = inputPricePerMillion.toPlainString(),
    outputPricePerMillion = outputPricePerMillion.toPlainString(),
    cachedInputPricePerMillion = cachedInputPricePerMillion?.toPlainString(),
    cachedInputWritePricePerMillion = cachedInputWritePricePerMillion?.toPlainString(),
    pricingMode = pricingMode.value,
    perRequestPrice = perRequestPrice?.toPlainString(),
    priceTiers = priceTiers.map { it.toResponse() },
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * 同一渠道内多个上游模型批量套用单价。
 */
@Serializable
data class ModelPriceBatchRequest(
    val channelId: String,
    val upstreamModels: List<String>,
    val currency: String = "USD",
    val inputPricePerMillion: String? = null,
    val outputPricePerMillion: String? = null,
    val cachedInputPricePerMillion: String? = null,
    val cachedInputWritePricePerMillion: String? = null,
    val pricingMode: String = PricingMode.TOKEN.value,
    val perRequestPrice: String? = null,
    val priceTiers: List<ModelPriceTierRequest> = emptyList(),
)

private fun ModelPriceTier.toResponse() = ModelPriceTierResponse(
    upToTokens = upToTokens,
    inputPricePerMillion = inputPricePerMillion.toPlainString(),
    outputPricePerMillion = outputPricePerMillion.toPlainString(),
    cachedInputPricePerMillion = cachedInputPricePerMillion?.toPlainString(),
    cachedInputWritePricePerMillion = cachedInputWritePricePerMillion?.toPlainString(),
)

/**
 * 模型参考单价响应。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ModelPriceReferenceResponse(
    val model: String,
    val matchedModel: String,
    val currency: String,
    val inputPricePerMillion: String,
    val outputPricePerMillion: String,
    val cachedInputPricePerMillion: String? = null,
    val provider: String? = null,
    val source: String,
)

/**
 * USD 基准的币种汇率。价格换算在管理面完成，不改变历史计费规则。
 */
@Serializable
data class ExchangeRatesResponse(
    val base: String,
    val date: String,
    val source: String,
    val rates: Map<String, String>,
)

/**
 * 请求日志读响应。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class RequestLogResponse(
    val id: Long,
    val requestId: String,
    val traceId: String?,
    val clientKeyId: String?,
    val clientKeyName: String?,
    val clientIp: String? = null,
    val clientUserAgent: String? = null,
    val alias: String,
    val channelId: String?,
    val channelName: String?,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val upstreamModel: String?,
    val protocol: String,
    val upstreamProtocol: String? = null,
    val stream: Boolean?,
    val statusCode: Int,
    val firstTokenMs: Long?,
    val totalMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long?,
    val cachedWriteTokens: Long? = null,
    val reasoningTokens: Long?,
    val reasoningEffort: String?,
    val totalTokens: Long?,
    val usageKnown: Boolean,
    val costAmount: String?,
    val costCurrency: String?,
    val inputPricePerMillion: String?,
    val outputPricePerMillion: String?,
    val cachedInputPricePerMillion: String?,
    val cachedInputWritePricePerMillion: String? = null,
    val pricingMode: String = PricingMode.TOKEN.value,
    val perRequestPrice: String? = null,
    val priceTiers: List<ModelPriceTierResponse> = emptyList(),
    val errorCode: String?,
    val failureStage: String?,
    val errorMessage: String?,
    val upstreamErrorCode: String?,
    val upstreamErrorBody: String?,
    val createdAt: Long,
    val upstreamAttempts: Int?,
    val attempts: List<UpstreamAttemptResponse> = emptyList(),
)

/** 单次上游尝试或路由跳过的日志明细。 */
@Serializable
data class UpstreamAttemptResponse(
    val index: Int,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val upstreamModel: String? = null,
    val upstreamReportedModel: String? = null,
    val upstreamProtocol: String? = null,
    val statusCode: Int? = null,
    val upstreamErrorStatusCode: Int? = null,
    val errorCode: String? = null,
    val upstreamErrorCode: String? = null,
    val errorMessage: String? = null,
    val elapsedMs: Long,
    val firstTokenMs: Long? = null,
    val committed: Boolean = false,
    val retryable: Boolean = false,
    val skipped: Boolean = false,
    val skipReason: String? = null,
)

fun RequestLogRecord.toResponse() = RequestLogResponse(
    id = id,
    requestId = requestId,
    traceId = traceId,
    clientKeyId = clientKeyId,
    clientKeyName = clientKeyName,
    clientIp = clientIp,
    clientUserAgent = clientUserAgent,
    alias = alias,
    channelId = channelId,
    channelName = channelName,
    channelKeyId = channelKeyId,
    channelKeyName = channelKeyName,
    upstreamModel = upstreamModel,
    protocol = protocol.value,
    upstreamProtocol = upstreamProtocol?.value,
    stream = stream,
    statusCode = statusCode,
    firstTokenMs = firstTokenMs,
    totalMs = totalMs,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
    cachedWriteTokens = cachedWriteTokens,
    reasoningTokens = reasoningTokens,
    reasoningEffort = reasoningEffort,
    totalTokens = totalTokens,
    usageKnown = usageKnown,
    costAmount = costAmount?.toPlainString(),
    costCurrency = costCurrency,
    inputPricePerMillion = inputPricePerMillion?.toPlainString(),
    outputPricePerMillion = outputPricePerMillion?.toPlainString(),
    cachedInputPricePerMillion = cachedInputPricePerMillion?.toPlainString(),
    cachedInputWritePricePerMillion = cachedInputWritePricePerMillion?.toPlainString(),
    pricingMode = pricingMode.value,
    perRequestPrice = perRequestPrice?.toPlainString(),
    priceTiers = priceTiers.map { it.toResponse() },
    errorCode = errorCode,
    failureStage = failureStage?.value,
    errorMessage = errorMessage,
    upstreamErrorCode = upstreamErrorCode,
    upstreamErrorBody = upstreamErrorBody,
    createdAt = createdAt,
    upstreamAttempts = upstreamAttempts,
    attempts = attempts.map { attempt ->
        UpstreamAttemptResponse(
            index = attempt.index,
            channelId = attempt.channelId,
            channelName = attempt.channelName,
            channelKeyId = attempt.channelKeyId,
            channelKeyName = attempt.channelKeyName,
            upstreamModel = attempt.upstreamModel,
            upstreamReportedModel = attempt.upstreamReportedModel,
            upstreamProtocol = attempt.upstreamProtocol?.value,
            statusCode = attempt.statusCode,
            upstreamErrorStatusCode = attempt.upstreamErrorStatusCode,
            errorCode = attempt.errorCode,
            upstreamErrorCode = attempt.upstreamErrorCode,
            errorMessage = attempt.errorMessage,
            elapsedMs = attempt.elapsedMs,
            firstTokenMs = attempt.firstTokenMs,
            committed = attempt.committed,
            retryable = attempt.retryable,
            skipped = attempt.skipped,
            skipReason = attempt.skipReason,
        )
    },
)

/**
 * 上游渠道返回的一个模型。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ChannelModelResponse(
    val id: String,
    val ownedBy: String? = null,
    val authorized: Boolean = false,
    val canonicalModelId: String? = null,
    val mappingSource: String? = null,
)

/**
 * 上游渠道的模型列表响应。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ChannelModelsResponse(
    val `object`: String = "list",
    val data: List<ChannelModelResponse>,
)

/**
 * models.dev 目录中的一个模型 ID。
 */
@Serializable
data class ModelsDevModelResponse(
    val id: String,
    val canonicalId: String,
    val provider: String,
    val providerId: String,
    val developer: String,
    val developerId: String,
)

/**
 * 渠道模型的规范 ID 自动匹配请求。
 */
@Serializable
data class ModelSuggestionRequest(val models: List<String>)

@Serializable
data class ModelSuggestionItem(
    val model: String,
    val candidates: List<ModelsDevModelResponse>,
)

@Serializable
data class ModelSuggestionsResponse(
    val loaded: Boolean,
    val error: String? = null,
    val data: List<ModelSuggestionItem>,
)

/**
 * 渠道保存前的上游模型发现请求。编辑时可只传 channelId，复用已保存的凭据。
 */
@Serializable
data class ChannelModelDiscoveryRequest(
    val baseUrl: String,
    val credential: String? = null,
    val channelId: String? = null,
    val keyId: String? = null,
    val authType: String? = null,
    val protocols: List<String>? = null,
    val requestOverrides: UpstreamRequestOverrides? = null,
    val protocolAuthTypes: Map<String, String>? = null,
)

/**
 * 协议探测请求。model 为空时使用上游 /models 的第一个模型。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ProtocolProbeRequest(val model: String? = null, val keyId: String? = null)

/**
 * 单个协议的探测结果。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ProtocolProbeResultResponse(
    val protocol: String,
    val supported: Boolean,
    val statusCode: Int? = null,
    val message: String,
)

/**
 * 渠道协议探测响应。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ProtocolProbeResponse(
    val model: String,
    val keyId: String? = null,
    val keyName: String? = null,
    val results: List<ProtocolProbeResultResponse>,
)

/** 模型连通性测试的全局默认设置。 */
@Serializable
data class ModelTestSettingsRequest(
    val defaultPrompt: String,
    val defaultReasoningEffort: String? = null,
)

@Serializable
data class ModelTestSettingsResponse(
    val defaultPrompt: String,
    val defaultReasoningEffort: JsonElement = JsonNull,
    val updatedAt: Long,
)

fun ModelTestSettingsRecord.toResponse() = ModelTestSettingsResponse(
    defaultPrompt = defaultPrompt,
    defaultReasoningEffort = JsonNull,
    updatedAt = updatedAt,
)

/** 对指定渠道模型发送一次真实请求。 */
@Serializable
data class ChannelModelTestRequest(
    val model: String,
    val protocol: String,
    val keyId: String? = null,
    val prompt: String? = null,
    val reasoningEffort: String? = null,
)

@Serializable
data class ChannelModelTestUsageResponse(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
)

fun Usage.toTestResponse() = ChannelModelTestUsageResponse(
    inputTokens = input,
    outputTokens = output,
    cachedTokens = cached,
    reasoningTokens = reasoning,
    totalTokens = total,
)

@Serializable
data class ChannelModelTestResponse(
    val success: Boolean,
    val model: String,
    val protocol: String,
    val keyId: String? = null,
    val keyName: String? = null,
    val statusCode: Int? = null,
    val latencyMs: Long,
    val prompt: String,
    val reasoningEffort: JsonElement = JsonNull,
    val responseText: String? = null,
    val errorMessage: String? = null,
    val usage: ChannelModelTestUsageResponse? = null,
    val upstreamReportedModel: String? = null,
)

fun ModelConnectivityTestResult.toResponse(
    model: String,
    protocol: GatewayProtocol,
    keyId: String?,
    keyName: String?,
    prompt: String,
) = ChannelModelTestResponse(
    success = success,
    model = model,
    protocol = protocol.value,
    keyId = keyId,
    keyName = keyName,
    statusCode = statusCode,
    latencyMs = latencyMs,
    prompt = prompt,
    reasoningEffort = JsonNull,
    responseText = responseText,
    errorMessage = errorMessage,
    usage = usage?.toTestResponse(),
    upstreamReportedModel = upstreamReportedModel,
)

/** 智商检测的内置题目定义，供管理台展示默认提示词与判分口径。 */
@Serializable
data class IqBenchmarkDefinitionResponse(
    val id: String,
    val label: String,
    val summary: String,
    val prompt: String,
    val grading: String,
)

fun IqBenchmark.toDefinitionResponse() = IqBenchmarkDefinitionResponse(
    id = value,
    label = label,
    summary = summary,
    prompt = prompt,
    grading = grading,
)

/** 可供智商检测选择的渠道模型与可用凭据。 */
@Serializable
data class IqTestTargetCredentialResponse(
    val id: String,
    val name: String,
    val priority: Int,
)

@Serializable
data class IqTestTargetResponse(
    val channelId: String,
    val channelName: String,
    val channelEnabled: Boolean,
    val model: String,
    val protocols: List<String>,
    val credentials: List<IqTestTargetCredentialResponse>,
)

@Serializable
data class IqTestRunRequest(
    val channelId: String,
    val model: String,
    val benchmark: String,
    val keyId: String? = null,
    val protocol: String? = null,
    val judgeId: String? = null,
    /** 可选的本次提示词；留空时使用题目内置提示词。 */
    val prompt: String? = null,
)

@Serializable
data class IqTestCheckResponse(
    val id: String,
    val label: String,
    val passed: Boolean,
    val detail: String? = null,
    /** 为 false 时只是提示，不参与自动判定。 */
    val decisive: Boolean = true,
)

fun IqStructureCheck.toResponse() = IqTestCheckResponse(
    id = id,
    label = label,
    passed = passed,
    detail = detail,
    decisive = decisive,
)

@Serializable
data class IqJudgeResultResponse(
    val judgeId: String,
    val judgeLabel: String,
    val verdict: String,
    val label: String,
    val reason: String? = null,
    val score: Double? = null,
    val details: Map<String, String> = emptyMap(),
    val checkedAt: Long? = null,
)

@Serializable
data class IqJudgeInfoResponse(
    val id: String,
    val label: String,
    val description: String,
    val builtIn: Boolean,
    val apiVersion: Int,
)

@Serializable
data class IqJudgeFailureResponse(
    val source: String,
    val message: String,
)

@Serializable
data class IqJudgesResponse(
    val judges: List<IqJudgeInfoResponse>,
    val failures: List<IqJudgeFailureResponse>,
    val defaultJudgeId: String,
)

@Serializable
data class IqTestManualVerdictRequest(
    val verdict: String,
    val score: Int? = null,
    val note: String? = null,
)

fun IqJudgeInfo.toResponse() = IqJudgeInfoResponse(
    id = id,
    label = displayName,
    description = description,
    builtIn = builtIn,
    apiVersion = apiVersion,
)

fun IqJudgePluginFailure.toResponse() = IqJudgeFailureResponse(source, message)

@Serializable
data class IqTestRunResponse(
    val id: Long,
    val benchmark: String,
    val benchmarkLabel: String,
    val verdict: String,
    val verdictLabel: String,
    val success: Boolean,
    val channelId: String,
    val channelName: String,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val model: String,
    val upstreamModel: String,
    val protocol: String,
    val statusCode: Int? = null,
    val latencyMs: Long,
    val createdAt: Long,
    val prompt: String? = null,
    val responseText: String? = null,
    val html: String? = null,
    val checks: List<IqTestCheckResponse> = emptyList(),
    val expectedAnswer: Int? = null,
    val matchedAnswer: Int? = null,
    val errorMessage: String? = null,
    val usage: ChannelModelTestUsageResponse? = null,
    val upstreamReportedModel: String? = null,
    val judgeResult: IqJudgeResultResponse? = null,
    val manualVerdict: String? = null,
    val manualScore: Int? = null,
    val manualNote: String? = null,
    val manualUpdatedAt: Long? = null,
)

fun IqTestRunRecord.toResponse(includeDetail: Boolean): IqTestRunResponse {
    val benchmarkType = IqBenchmark.fromValue(benchmark)
    val structure = if (includeDetail && benchmarkType == IqBenchmark.PELICAN) {
        analyzePelicanStructure(responseText)
    } else {
        null
    }
    val automaticJudge = toJudgeResult()
    val evaluation: IqTestEvaluation? = if (includeDetail && benchmarkType != null) {
        if (benchmarkType == IqBenchmark.CANDY && judgeId != null && success) {
            // 旧版路由总会写入 judgeId；新版糖果题不再解析判分器，据此保留旧记录的结论与预览。
            evaluateLegacyCandy(responseText, verdict, expectedAnswer, matchedAnswer)
        } else {
            evaluateIqTest(
                benchmark = benchmarkType,
                success = success,
                responseText = responseText,
                errorMessage = errorMessage,
                structure = structure,
                judgeResult = automaticJudge,
                manualVerdict = manualVerdict,
            )
        }
    } else {
        null
    }
    val effectiveVerdict = evaluation?.verdict?.value ?: verdict
    return IqTestRunResponse(
        id = id,
        benchmark = benchmarkType?.value ?: benchmark,
        benchmarkLabel = benchmarkType?.label ?: benchmark,
        verdict = effectiveVerdict,
        verdictLabel = effectiveVerdictLabel(effectiveVerdict, benchmarkType, manualVerdict),
        success = success,
        channelId = channelId,
        channelName = channelName,
        channelKeyId = channelKeyId,
        channelKeyName = channelKeyName,
        model = model,
        upstreamModel = upstreamModel,
        protocol = protocol,
        statusCode = statusCode,
        latencyMs = latencyMs,
        createdAt = createdAt,
        prompt = prompt.takeIf { includeDetail },
        responseText = responseText.takeIf { includeDetail },
        html = evaluation?.html.takeIf { includeDetail },
        checks = if (includeDetail) evaluation?.checks?.map { it.toResponse() }.orEmpty() else emptyList(),
        expectedAnswer = evaluation?.expectedAnswer.takeIf { includeDetail },
        matchedAnswer = evaluation?.matchedAnswer.takeIf { includeDetail },
        errorMessage = errorMessage,
        usage = usage?.toTestResponse(),
        upstreamReportedModel = upstreamReportedModel,
        judgeResult = toJudgeResponse(),
        manualVerdict = manualVerdict,
        manualScore = manualScore,
        manualNote = manualNote,
        manualUpdatedAt = manualUpdatedAt,
    )
}

private fun IqTestRunRecord.toJudgeResult(): IqJudgeResult? {
    val verdict = judgeVerdict
        ?.let { value -> runCatching { IqJudgeVerdict.valueOf(value.uppercase()) }.getOrNull() }
        ?: return null
    return IqJudgeResult(
        verdict = verdict,
        label = judgeResultLabel ?: judgeLabel ?: judgeId ?: "判分器",
        reason = judgeReason,
        score = judgeScore,
        details = parseJudgeDetails(judgeDetails),
    )
}

private fun IqTestRunRecord.toJudgeResponse(): IqJudgeResultResponse? {
    val id = judgeId ?: return null
    val resultVerdict = judgeVerdict ?: return null
    return IqJudgeResultResponse(
        judgeId = id,
        judgeLabel = judgeLabel ?: id,
        verdict = resultVerdict,
        label = judgeResultLabel ?: resultVerdict,
        reason = judgeReason,
        score = judgeScore,
        details = parseJudgeDetails(judgeDetails),
        checkedAt = judgeCheckedAt,
    )
}

private fun parseJudgeDetails(value: String?): Map<String, String> {
    if (value.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.parseToJsonElement(value).jsonObject.mapValues { (_, element) ->
            element.jsonPrimitive.contentOrNull ?: element.toString()
        }
    }.getOrDefault(emptyMap())
}

private fun effectiveVerdictLabel(
    verdict: String,
    benchmark: IqBenchmark?,
    manualVerdict: String?,
): String {
    if (manualVerdict != null) {
        return when (manualVerdict) {
            IqVerdict.PASSED.value -> "人工通过"
            IqVerdict.INCORRECT.value -> "人工未通过"
            else -> iqVerdictLabel(verdict, benchmark)
        }
    }
    return iqVerdictLabel(verdict, benchmark)
}

private fun iqVerdictLabel(verdict: String, benchmark: IqBenchmark?): String = when (verdict) {
    IqVerdict.PASSED.value -> "通过"
    IqVerdict.INCORRECT.value -> "未通过"
    IqVerdict.REVIEW.value -> "需人工查看"
    IqVerdict.FAILED.value -> "失败"
    else -> verdict
}

/**
 * 订阅账号导入/重新授权。refresh token 只在请求中出现一次，服务端加密后不再回显。
 *
 * @author 文杰
 * @since 2026-11-06
 */
@Serializable
data class OAuthAccountImportRequest(
    val name: String? = null,
    val provider: String,
    val refreshToken: String,
    val accountEmail: String? = null,
    val accountPlan: String? = null,
    val proxyUrl: String? = null,
    val accessToken: String? = null,
    val accessTokenExpiresAt: Long? = null,
    val tokenType: String? = null,
    val scope: String? = null,
    val channelIds: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val priority: Int = 1,
)

@Serializable
data class OAuthLoginStartRequest(
    val provider: String,
    /** 为空表示新建账号；非空表示对已有账号重新授权。 */
    val accountId: String? = null,
    val name: String? = null,
    val channelIds: List<String> = emptyList(),
    val proxyUrl: String? = null,
)

@Serializable
data class OAuthLoginStartResponse(
    val loginId: String,
    val authorizeUrl: String,
    val redirectUri: String,
    val expiresAt: Long,
)

@Serializable
data class OAuthLoginCompleteRequest(
    /** 浏览器最终停留的回调地址；只需包含 code 与 state。 */
    val callbackUrl: String,
)

@Serializable
data class OAuthAccountPolicyRequest(
    val windowLimit: Int? = null,
    val windowSeconds: Int = 10_800,
    val maxConcurrentRequests: Int = 1,
)

@Serializable
data class OAuthAccountSettingsRequest(
    val name: String? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
    val proxyUrl: String? = null,
    val clearProxy: Boolean = false,
)

@Serializable
data class OAuthAccountChannelsRequest(
    val channelIds: List<String>,
)

@Serializable
data class OAuthAccountModelsRequest(
    /** 空列表表示继承所关联渠道的全部模型。 */
    val models: List<String>,
)

@Serializable
data class OAuthAccountPolicyResponse(
    val windowLimit: Int? = null,
    val windowSeconds: Int,
    val maxConcurrentRequests: Int,
)

@Serializable
data class OAuthAccountRuntimeResponse(
    val windowUsed: Int = 0,
    val cooldownUntil: Long? = null,
    val consecutiveRateLimits: Int = 0,
    val activeRequests: Int = 0,
    val quotaBlockedUntil: Long? = null,
)

@Serializable
data class OAuthQuotaWindowResponse(
    val usedPercent: Double,
    val remainingPercent: Double,
    val windowSeconds: Int? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class OAuthQuotaResponse(
    val primary: OAuthQuotaWindowResponse? = null,
    val secondary: OAuthQuotaWindowResponse? = null,
    val allowed: Boolean? = null,
    val creditsBalance: Double? = null,
    val creditsUnlimited: Boolean = false,
    val resetCredits: Int? = null,
    val checkedAt: Long,
    val error: String? = null,
)

@Serializable
data class OAuthAccountResponse(
    val id: String,
    val name: String,
    val provider: String,
    val accountEmail: String? = null,
    val accountPlan: String? = null,
    val authState: String,
    val enabled: Boolean,
    val priority: Int,
    /** 空列表表示继承所关联渠道的全部模型。 */
    val models: List<String> = emptyList(),
    val channelIds: List<String> = emptyList(),
    val channels: List<OAuthAccountChannelResponse> = emptyList(),
    val tokenType: String,
    val scope: String? = null,
    val accessTokenExpiresAt: Long? = null,
    val lastRefreshedAt: Long? = null,
    val refreshFailureCount: Int = 0,
    val lastRefreshError: String? = null,
    val proxyUrl: String? = null,
    /** 上游账号 id；用于额度查询与多账号去重，不参与鉴权。 */
    val chatgptAccountId: String? = null,
    val quota: OAuthQuotaResponse? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val policy: OAuthAccountPolicyResponse,
    val runtime: OAuthAccountRuntimeResponse,
)

@Serializable
data class OAuthAccountChannelResponse(
    val id: String,
    val name: String,
)

@Serializable
data class OAuthProviderModelsRequest(
    val models: List<String> = emptyList(),
)

@Serializable
data class OAuthProviderModelResponse(
    val id: String,
    val name: String,
)

@Serializable
data class OAuthProviderModelsResponse(
    val models: List<OAuthProviderModelResponse> = emptyList(),
)

@Serializable
data class OAuthProviderResponse(
    val id: String,
    val label: String,
    val configured: Boolean,
    /** 是否支持从网关发起交互式登录；不支持时只能导入 refresh token。 */
    val loginSupported: Boolean = false,
    /** 该 provider 的官方端点渠道由系统置备，账号导入后自动关联。 */
    val managedChannel: Boolean = false,
)

fun OAuthAccountPolicy.toResponse() = OAuthAccountPolicyResponse(
    windowLimit = windowLimit,
    windowSeconds = windowSeconds,
    maxConcurrentRequests = maxConcurrentRequests,
)

fun OAuthQuotaWindow.toResponse() = OAuthQuotaWindowResponse(
    usedPercent = usedPercent,
    remainingPercent = remainingPercent,
    windowSeconds = windowSeconds,
    resetsAt = resetsAt,
)

fun OAuthQuotaSnapshot.toResponse() = OAuthQuotaResponse(
    primary = primary?.toResponse(),
    secondary = secondary?.toResponse(),
    allowed = allowed,
    creditsBalance = creditsBalance,
    creditsUnlimited = creditsUnlimited,
    resetCredits = resetCredits,
    checkedAt = checkedAt,
    error = error,
)

fun OAuthAccountRecord.toResponse(
    channels: List<OAuthAccountChannelResponse>,
    policy: OAuthAccountPolicy,
    runtime: OAuthAccountRuntimeState?,
) = OAuthAccountResponse(
    id = id,
    name = name,
    provider = provider.value,
    accountEmail = accountEmail,
    accountPlan = accountPlan,
    authState = authState.value,
    enabled = enabled,
    priority = priority,
    models = models,
    channelIds = channelIds.sorted(),
    channels = channels,
    tokenType = tokenType,
    scope = scope,
    accessTokenExpiresAt = accessTokenExpiresAt,
    lastRefreshedAt = lastRefreshedAt,
    refreshFailureCount = refreshFailureCount,
    lastRefreshError = lastRefreshError,
    proxyUrl = proxyUrl,
    chatgptAccountId = chatgptAccountId,
    quota = quota?.toResponse(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    policy = policy.toResponse(),
    runtime = OAuthAccountRuntimeResponse(
        windowUsed = runtime?.windowUsed ?: 0,
        cooldownUntil = runtime?.cooldownUntil,
        consecutiveRateLimits = runtime?.consecutiveRateLimits ?: 0,
        activeRequests = runtime?.activeRequests ?: 0,
        quotaBlockedUntil = runtime?.quotaBlockedUntil,
    ),
)

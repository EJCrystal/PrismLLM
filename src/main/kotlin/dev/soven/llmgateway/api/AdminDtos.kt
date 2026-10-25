package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.AdminAuditRecord
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.quota.ClientKeyQuotaUsage
import dev.soven.llmgateway.quota.TeamLimitPolicy
import dev.soven.llmgateway.quota.TeamLimitUsage
import dev.soven.llmgateway.report.CleanupResult
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.report.UsageGroup
import dev.soven.llmgateway.report.UsageReport
import dev.soven.llmgateway.report.UsageTotal
import dev.soven.llmgateway.repo.AliasWithCandidates
import kotlinx.serialization.Serializable

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

@Serializable
data class ChannelRequest(
    val name: String,
    val baseUrl: String,
    val credential: String? = null,
    val authType: String = "bearer",
    val protocols: List<String> = listOf(GatewayProtocol.CHAT_COMPLETIONS.value),
    val enabled: Boolean = true,
    val models: List<String>? = null,
    val modelMappings: List<ChannelModelMappingRequest>? = null,
    val priority: Int? = null,
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
    protocols = protocols.map { it.value }.sorted(),
    enabled = enabled,
    credentialSet = credentialCiphertext.isNotBlank(),
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
)

/**
 * 候选写请求。position 是优先级档位，省略时按数组顺序；同档候选分摊流量。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class CandidateRequest(
    val channelId: String,
    val upstreamModel: String,
    val position: Int? = null,
)

/**
 * 模型别名写请求。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class AliasRequest(
    val alias: String,
    val enabled: Boolean = true,
    val candidates: List<CandidateRequest>,
)

/**
 * 候选读响应。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class CandidateResponse(
    val id: String,
    val channelId: String,
    val channelName: String,
    val upstreamModel: String,
    val position: Int,
)

/**
 * 模型别名读响应。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class AliasResponse(
    val id: String,
    val alias: String,
    val enabled: Boolean,
    val candidates: List<CandidateResponse>,
    val createdAt: Long,
    val updatedAt: Long,
)

fun AliasWithCandidates.toResponse() = AliasResponse(
    id = alias.id,
    alias = alias.alias,
    enabled = alias.enabled,
    candidates = candidates.map {
        CandidateResponse(
            id = it.candidate.id,
            channelId = it.candidate.channelId,
            channelName = it.channelName,
            upstreamModel = it.candidate.upstreamModel,
            position = it.candidate.position,
        )
    },
    createdAt = alias.createdAt,
    updatedAt = alias.updatedAt,
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
    val team: String? = null,
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
    val team: String?,
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
    team = team,
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
    team = team,
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

@Serializable
data class UsageReportResponse(
    val from: Long,
    val to: Long,
    val total: UsageTotalResponse,
    val byClientKey: List<UsageGroupResponse>,
    val byTeam: List<UsageGroupResponse>,
)

@Serializable
data class UsageTotalResponse(
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
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
data class CleanupResultResponse(val deleted: Long, val cutoff: Long)

fun UsageReport.toResponse() = UsageReportResponse(
    from = from,
    to = to,
    total = total.toResponse(),
    byClientKey = byClientKey.map { it.toResponse() },
    byTeam = byTeam.map { it.toResponse() },
)

fun UsageTotal.toResponse() = UsageTotalResponse(
    requests = requests,
    errors = errors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTokens = totalTokens,
    costs = costs.mapValues { (_, value) -> value.stripTrailingZeros().toPlainString() },
)

fun UsageGroup.toResponse() = UsageGroupResponse(
    id = id,
    name = name,
    requests = requests,
    errors = errors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
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
    val team: String?,
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
    team = team,
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
)

/**
 * 团队请求速率与并发策略写请求。null 表示该维度不限制。
 */
@Serializable
data class TeamLimitRequest(
    val requestsPerMinute: Int? = null,
    val maxConcurrentRequests: Int? = null,
)

/**
 * 团队限流策略及当前单实例用量。
 */
@Serializable
data class TeamLimitResponse(
    val team: String,
    val requestsPerMinute: Int?,
    val maxConcurrentRequests: Int?,
    val currentMinuteRequests: Int,
    val activeConcurrentRequests: Int,
    val configured: Boolean,
    val createdAt: Long?,
    val updatedAt: Long?,
)

fun TeamLimitPolicy.toResponse(usage: TeamLimitUsage) = TeamLimitResponse(
    team = team,
    requestsPerMinute = requestsPerMinute,
    maxConcurrentRequests = maxConcurrentRequests,
    currentMinuteRequests = usage.currentMinuteRequests,
    activeConcurrentRequests = usage.activeConcurrentRequests,
    configured = true,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun unconfiguredTeamLimit(team: String, usage: TeamLimitUsage) = TeamLimitResponse(
    team = team,
    requestsPerMinute = null,
    maxConcurrentRequests = null,
    currentMinuteRequests = usage.currentMinuteRequests,
    activeConcurrentRequests = usage.activeConcurrentRequests,
    configured = false,
    createdAt = null,
    updatedAt = null,
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
    val inputPricePerMillion: String,
    val outputPricePerMillion: String,
    val cachedInputPricePerMillion: String? = null,
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
    createdAt = createdAt,
    updatedAt = updatedAt,
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
    val requestId: String,
    val traceId: String?,
    val clientKeyId: String?,
    val clientKeyName: String?,
    val alias: String,
    val channelId: String?,
    val channelName: String?,
    val upstreamModel: String?,
    val protocol: String,
    val statusCode: Int,
    val firstTokenMs: Long?,
    val totalMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long?,
    val reasoningTokens: Long?,
    val reasoningEffort: String?,
    val totalTokens: Long?,
    val usageKnown: Boolean,
    val costAmount: String?,
    val costCurrency: String?,
    val inputPricePerMillion: String?,
    val outputPricePerMillion: String?,
    val cachedInputPricePerMillion: String?,
    val errorCode: String?,
    val failureStage: String?,
    val errorMessage: String?,
    val upstreamErrorCode: String?,
    val createdAt: Long,
    val upstreamAttempts: Int?,
)

fun RequestLogRecord.toResponse() = RequestLogResponse(
    requestId = requestId,
    traceId = traceId,
    clientKeyId = clientKeyId,
    clientKeyName = clientKeyName,
    alias = alias,
    channelId = channelId,
    channelName = channelName,
    upstreamModel = upstreamModel,
    protocol = protocol.value,
    statusCode = statusCode,
    firstTokenMs = firstTokenMs,
    totalMs = totalMs,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedTokens = cachedTokens,
    reasoningTokens = reasoningTokens,
    reasoningEffort = reasoningEffort,
    totalTokens = totalTokens,
    usageKnown = usageKnown,
    costAmount = costAmount?.toPlainString(),
    costCurrency = costCurrency,
    inputPricePerMillion = inputPricePerMillion?.toPlainString(),
    outputPricePerMillion = outputPricePerMillion?.toPlainString(),
    cachedInputPricePerMillion = cachedInputPricePerMillion?.toPlainString(),
    errorCode = errorCode,
    failureStage = failureStage?.value,
    errorMessage = errorMessage,
    upstreamErrorCode = upstreamErrorCode,
    createdAt = createdAt,
    upstreamAttempts = upstreamAttempts,
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
 * 管理面展示的对外模型条目，source 区分别名与上游直连。
 */
@Serializable
data class ExposedModelResponse(
    val id: String,
    val source: String,
    val ownedBy: String = "llm-gateway",
    val canonicalModelId: String? = null,
    val upstreamModels: List<String> = emptyList(),
    val channelNames: List<String> = emptyList(),
)

/**
 * `/v1/models` 当前对外结果的只读快照。
 */
@Serializable
data class ExposedModelsResponse(
    val `object`: String = "list",
    val data: List<ExposedModelResponse>,
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
 * models.dev 目录及其与当前对外模型的匹配结果。matches 的 key 是对外模型 ID。
 */
@Serializable
data class ModelsDevCatalogResponse(
    val source: String,
    val loaded: Boolean,
    val error: String? = null,
    val data: List<ModelsDevModelResponse>,
    val matches: Map<String, List<ModelsDevModelResponse>> = emptyMap(),
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
)

/**
 * 协议探测请求。model 为空时使用上游 /models 的第一个模型。
 *
 * @author 文杰
 * @since 2026-10-22
 */
@Serializable
data class ProtocolProbeRequest(val model: String? = null)

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
    val results: List<ProtocolProbeResultResponse>,
)

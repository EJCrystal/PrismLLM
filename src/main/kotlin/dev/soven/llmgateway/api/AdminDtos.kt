package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.RequestLogRecord
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
    val exposeUpstreamModels: Boolean? = null,
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
    val exposeUpstreamModels: Boolean,
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
    exposeUpstreamModels = exposeUpstreamModels,
)

/**
 * 候选写请求。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class CandidateRequest(val channelId: String, val upstreamModel: String)

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
    val enabled: Boolean = true,
    val expiresAt: Long? = null,
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
    val createdAt: Long,
)

fun ClientKeyRecord.toResponse() = ClientKeyResponse(
    id = id,
    name = name,
    keyPrefix = keyPrefix,
    enabled = enabled,
    expiresAt = expiresAt,
    createdAt = createdAt,
)

/**
 * 新建调用方密钥响应。key 只在创建时返回一次。
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
    val createdAt: Long,
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
)

fun RequestLogRecord.toResponse() = RequestLogResponse(
    requestId = requestId,
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
    val exposeUpstreamModels: Boolean,
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

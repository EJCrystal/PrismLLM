package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.ClientKeyRecord
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
data class MeResponse(val username: String)

/**
 * 渠道写请求。credential 为空时表示保持原凭据。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@Serializable
data class ChannelRequest(
    val name: String,
    val baseUrl: String,
    val credential: String? = null,
    val authType: String = "bearer",
    val protocols: List<String> = listOf(GatewayProtocol.CHAT_COMPLETIONS.value),
    val enabled: Boolean = true,
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

package dev.soven.llmgateway.domain

import java.math.BigDecimal
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits

/**
 * 渠道记录。上游凭据只以密文形式存在，读取时按需解密。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class ChannelModelRecord(
    val upstreamModel: String,
    val canonicalModelId: String? = null,
    val mappingSource: String? = null,
)

data class ChannelRecord(
    val id: String,
    val name: String,
    val baseUrl: String,
    val credentialCiphertext: String,
    val credentialNonce: String,
    val credentialKeyVersion: Int,
    val authType: String,
    val protocols: Set<GatewayProtocol>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val modelMappings: List<ChannelModelRecord> = emptyList(),
    val priority: Int = 0,
) {
    val models: List<String> get() = modelMappings.map { it.upstreamModel }
}

/**
 * 模型别名记录。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class AliasRecord(
    val id: String,
    val alias: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 模型别名下的一个候选。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class CandidateRecord(
    val id: String,
    val aliasId: String,
    val channelId: String,
    val upstreamModel: String,
    val position: Int,
)

/**
 * 解析后的路由候选，只包含数据面需要的字段。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class RouteCandidate(
    val channel: ChannelRecord,
    val upstreamModel: String,
    val position: Int,
) {
    /** 计费使用的模型 ID：优先渠道配置的规范 ID，未配置时回退上游模型。 */
    val pricingModelId: String
        get() = channel.modelMappings
            .firstOrNull { it.upstreamModel.trim() == upstreamModel.trim() }
            ?.canonicalModelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: upstreamModel.trim()
}

/**
 * 上游 /models 返回的模型。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class UpstreamModel(
    val id: String,
    val ownedBy: String? = null,
)

/**
 * 调用方密钥记录。只保存哈希和展示前缀。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class ClientKeyRecord(
    val id: String,
    val name: String,
    val keyHash: String,
    val keyPrefix: String,
    val enabled: Boolean,
    val expiresAt: Long?,
    val quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    val createdAt: Long,
    val rotatedAt: Long? = null,
    val revokedAt: Long? = null,
)

/**
 * 一条管理面写操作审计记录。字段只包含请求元数据。
 */
data class AdminAuditRecord(
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

/**
 * 渠道内上游模型的计费单价。单价按每百万 token 计算。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class ModelPriceRecord(
    val id: String,
    val channelId: String,
    val channelName: String,
    val upstreamModel: String,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val createdAt: Long,
    val updatedAt: Long,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
    val currentVersion: Int = 1,
    val effectiveFrom: Long = 0L,
)

/** 按对外模型 ID 配置的默认单价。 */
data class DefaultModelPriceRecord(
    val id: String,
    val modelId: String,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val createdAt: Long,
    val updatedAt: Long,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
    val currentVersion: Int = 1,
    val effectiveFrom: Long = 0L,
)

/** 渠道模型单价的具体版本记录。 */
data class ModelPriceVersionRecord(
    val id: String,
    val channelId: String,
    val upstreamModel: String,
    val version: Int,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
    val effectiveFrom: Long,
    val status: String,
    val createdAt: Long,
)

/** 模型默认单价的具体版本记录。 */
data class DefaultModelPriceVersionRecord(
    val id: String,
    val modelId: String,
    val version: Int,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
    val effectiveFrom: Long,
    val status: String,
    val createdAt: Long,
)

/**
 * 管理员账号记录。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class AdminUserRecord(
    val id: String,
    val username: String,
    val passwordHash: String,
    val avatarDataUrl: String? = null,
)

/**
 * 请求日志记录。默认不含 prompt 与响应正文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class RequestLogRecord(
    val requestId: String,
    val traceId: String? = null,
    val clientKeyId: String?,
    val clientKeyName: String?,
    val alias: String,
    val channelId: String?,
    val channelName: String?,
    val upstreamModel: String?,
    val protocol: GatewayProtocol,
    val stream: Boolean? = null,
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
    val costAmount: BigDecimal?,
    val costCurrency: String?,
    val inputPricePerMillion: BigDecimal?,
    val outputPricePerMillion: BigDecimal?,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
    val priceVersion: Int? = null,
    val priceVersionId: String? = null,
    val errorCode: String?,
    val failureStage: FailureStage?,
    val errorMessage: String?,
    val upstreamErrorCode: String?,
    val createdAt: Long,
    val upstreamAttempts: Int? = null,
    val pricingModelId: String? = null,
    val id: Long = 0,
)

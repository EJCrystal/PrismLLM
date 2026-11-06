package dev.soven.llmgateway.domain

import java.math.BigDecimal
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.security.EncryptedCredential
import kotlinx.serialization.json.JsonObject

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

/**
 * 渠道密钥对应上游分组的主动限流规则。规则作用于该密钥支持的全部模型。
 */
data class ChannelKeyRateLimitPolicy(
    val enabled: Boolean = false,
    val requestsPerMinute: Int? = null,
    val maxConcurrentRequests: Int? = null,
) {
    init {
        require(requestsPerMinute == null || requestsPerMinute in 1..1_000_000) {
            "每分钟请求数必须在 1..1000000 之间"
        }
        require(maxConcurrentRequests == null || maxConcurrentRequests in 1..100_000) {
            "最大并发请求数必须在 1..100000 之间"
        }
        require(!enabled || requestsPerMinute != null || maxConcurrentRequests != null) {
            "启用限流时至少需要配置每分钟请求数或最大并发请求数"
        }
    }

    val configured: Boolean
        get() = enabled && (requestsPerMinute != null || maxConcurrentRequests != null)
}

/**
 * 渠道下的上游密钥。凭据只以密文形式存在，模型集合表示该密钥可调用的上游模型。
 */
data class ChannelKeyRecord(
    val id: String,
    val channelId: String,
    val name: String,
    val credentialCiphertext: String,
    val credentialNonce: String,
    val credentialKeyVersion: Int,
    val state: ChannelKeyState,
    val priority: Int,
    val models: List<String> = emptyList(),
    val rateLimit: ChannelKeyRateLimitPolicy = ChannelKeyRateLimitPolicy(),
    val createdAt: Long,
    val updatedAt: Long,
    val stateVersion: Long = 0,
    val autoDisabledAt: Long? = null,
    val autoDisableReason: String? = null,
    val autoDisableUntil: Long? = null,
    /** 临时停用到期后的探测租约；非空且未到期时其他请求不得再探测。 */
    val probeUntil: Long? = null,
    /** null 表示尚未单独配置；不继承渠道的账户身份。 */
    val quota: ChannelQuotaSettings? = null,
    val quotaCredential: EncryptedCredential? = null,
    /** 非空表示这是订阅账号，需要走账号级滑动窗口、并发与 429 冷却。 */
    val oauthPolicy: OAuthAccountPolicy? = null,
) {
    val credentialSet: Boolean get() = credentialCiphertext.isNotBlank()

    /** 是否处于启用状态；自动停用与人工停用都不算启用。 */
    val enabled: Boolean get() = state == ChannelKeyState.ACTIVE

    /** 只有临时停用会到期自动恢复。 */
    val autoRecoverable: Boolean get() = state == ChannelKeyState.AUTO_TEMPORARY

    /**
     * 是否允许路由。临时停用到期即视为可用，不依赖后台清理任务；
     * 人工停用与永久停用永不自动放行。
     */
    fun isRoutable(now: Long = System.currentTimeMillis()): Boolean = when (state) {
        ChannelKeyState.ACTIVE -> true
        ChannelKeyState.AUTO_TEMPORARY -> autoDisableUntil != null && now >= autoDisableUntil &&
            (probeUntil == null || now >= probeUntil)
        ChannelKeyState.MANUAL_DISABLED, ChannelKeyState.AUTO_PERMANENT -> false
    }

    fun supports(upstreamModel: String): Boolean = upstreamModel in models
}

data class ChannelRecord(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: String,
    val protocols: List<GatewayProtocol>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val modelMappings: List<ChannelModelRecord> = emptyList(),
    val priority: Int = 0,
    val keys: List<ChannelKeyRecord> = emptyList(),
    val requestOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    val quota: ChannelQuotaSettings = ChannelQuotaSettings(),
    /** 仅供额度插件使用，不加入模型转发密钥集合。 */
    val quotaCredential: EncryptedCredential? = null,
    /** Optional per-protocol overrides; an absent entry inherits [authType]. */
    val protocolAuthTypes: Map<String, String> = emptyMap(),
) {
    val models: List<String> get() = modelMappings.map { it.upstreamModel }

    /**
     * 支持指定上游模型的可路由密钥，按密钥优先级、名称和 id 稳定排序。
     * 临时停用到期后重新参与路由，人工与永久停用永不参与。
     */
    fun keysFor(upstreamModel: String, now: Long = System.currentTimeMillis()): List<ChannelKeyRecord> = keys
        .asSequence()
        .filter { it.isRoutable(now) && it.supports(upstreamModel) }
        .sortedWith(compareBy({ it.priority }, { it.name }, { it.id }))
        .toList()
}

/**
 * 解析后的路由候选，只包含数据面需要的字段。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class RouteCandidate(
    val channel: ChannelRecord,
    val key: ChannelKeyRecord,
    val upstreamModel: String,
    /** 选路档位：直连候选取渠道优先级，数值越小越优先。 */
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
    val details: JsonObject? = null,
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
)

/** 渠道模型连通性测试的全局默认值。 */
data class ModelTestSettingsRecord(
    val defaultPrompt: String,
    val updatedAt: Long,
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
    /** 调用方 IP；取不到时为 null。 */
    val clientIp: String? = null,
    /** 调用方 User-Agent，截断保存；缺失时为 null。 */
    val clientUserAgent: String? = null,
    val alias: String,
    val channelId: String?,
    val channelName: String?,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val upstreamModel: String?,
    val protocol: GatewayProtocol,
    val upstreamProtocol: GatewayProtocol? = null,
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
    val errorCode: String?,
    val failureStage: FailureStage?,
    val errorMessage: String?,
    val upstreamErrorCode: String?,
    val upstreamErrorBody: String? = null,
    val createdAt: Long,
    val upstreamAttempts: Int? = null,
    /** 每次上游尝试或路由跳过的明细，按发生顺序排列。 */
    val attempts: List<UpstreamAttemptRecord> = emptyList(),
    val pricingModelId: String? = null,
    val id: Long = 0,
)

/**
 * 单次上游尝试或路由跳过的日志明细。只记录路由与结果元数据，不保存 prompt 或响应正文。
 *
 * @author 文杰
 * @since 2026-10-30
 */
data class UpstreamAttemptRecord(
    /** 实际上游尝试序号；路由跳过记录为 0。 */
    val index: Int,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val upstreamModel: String? = null,
    /** 上游响应自报的模型标识；历史记录或上游未提供时为 null。 */
    val upstreamReportedModel: String? = null,
    val upstreamProtocol: GatewayProtocol? = null,
    /** 未拿到上游 HTTP 响应时为 null。 */
    val statusCode: Int? = null,
    val upstreamErrorStatusCode: Int? = null,
    /** 本次尝试的网关错误码；成功为 null。 */
    val errorCode: String? = null,
    /** 上游返回的错误码；成功或非上游错误为 null。 */
    val upstreamErrorCode: String? = null,
    val errorMessage: String? = null,
    val elapsedMs: Long,
    val firstTokenMs: Long? = null,
    /** 本次尝试已向客户端提交响应。 */
    val committed: Boolean = false,
    val retryable: Boolean = false,
    /** 该条记录是路由阶段被跳过的候选，没有发起上游请求。 */
    val skipped: Boolean = false,
    /** 跳过原因：cooldown 或 oauth_window / oauth_concurrency / oauth_cooldown。skipped=false 时为 null。 */
    val skipReason: String? = null,
)

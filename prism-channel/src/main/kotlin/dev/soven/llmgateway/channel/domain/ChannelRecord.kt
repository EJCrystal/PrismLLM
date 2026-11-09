package dev.soven.llmgateway.channel.domain

import dev.soven.llmgateway.channel.domain.key.ChannelKeyDisableMode
import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.shared.crypto.EncryptedCredential
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.shared.domain.UpstreamRequestOverrides

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
) {
    /**
     * 对外暴露、也是数据面请求匹配的模型 ID。
     *
     * 映射只按 canonical ID 对外时，上游名不再是合法请求名；没有 canonical ID 时沿用上游名。
     */
    val publicModelId: String get() = canonicalModelId ?: upstreamModel
}

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
 * 数据面可路由的上游凭据。静态渠道密钥与订阅账号都实现这一接口，
 * 路由、冷却、并发占用和凭据解析不再依赖 channel_keys 这一张表。
 *
 * 订阅账号由 subscription-oauth 上下文实现，接口不能声明为 sealed：
 * 实现跨模块后需要保持可扩展，具体分支由使用方的 `when` 显式兜底。
 */
interface UpstreamCredential {
    val id: String
    val name: String
    val priority: Int
    val models: List<String>
    val enabled: Boolean

    val isOAuth: Boolean

    fun supports(upstreamModel: String): Boolean

    fun isRoutable(now: Long = System.currentTimeMillis()): Boolean
}

/**
 * 渠道下的上游密钥。凭据只以密文形式存在，模型集合表示该密钥可调用的上游模型。
 */
data class ChannelKeyRecord(
    override val id: String,
    val channelId: String,
    override val name: String,
    val credentialCiphertext: String,
    val credentialNonce: String,
    val credentialKeyVersion: Int,
    val state: ChannelKeyState,
    override val priority: Int,
    override val models: List<String> = emptyList(),
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
) : UpstreamCredential {
    override val isOAuth: Boolean get() = false

    override val enabled: Boolean get() = state == ChannelKeyState.ACTIVE
    val credentialSet: Boolean get() = credentialCiphertext.isNotBlank()

    /** 只有临时停用会到期自动恢复。 */
    val autoRecoverable: Boolean get() = state == ChannelKeyState.AUTO_TEMPORARY

    /**
     * 是否允许路由。临时停用到期即视为可用，不依赖后台清理任务；
     * 人工停用与永久停用永不自动放行。
     */
    override fun isRoutable(now: Long): Boolean = state.isRoutableAt(autoDisableUntil, probeUntil, now)

    /**
     * 临时停用密钥在 [now] 之后变得可路由的时间点，条件是 [isRoutable] 里两个时间都要满足：
     * 停用到期时间与仍在生效的探测租约，取较晚者。
     *
     * 返回 null 表示这条密钥不会自行恢复（人工/永久停用、缺少到期时间，或 [now] 时已可路由），
     * 调用方据此决定是否给出重试提示。
     */
    fun recoveryAt(now: Long): Long? {
        if (state != ChannelKeyState.AUTO_TEMPORARY) return null
        if (isRoutable(now)) return null
        val until = autoDisableUntil ?: return null
        return maxOf(until, probeUntil ?: until)
    }

    override fun supports(upstreamModel: String): Boolean = upstreamModel in models
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
    /** 通过 oauth_account_channels 关联到本渠道的订阅账号。 */
    val oauthAccounts: List<UpstreamCredential> = emptyList(),
    val requestOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    val quota: ChannelQuotaSettings = ChannelQuotaSettings(),
    /** 仅供额度插件使用，不加入模型转发密钥集合。 */
    val quotaCredential: EncryptedCredential? = null,
    /** Optional per-protocol overrides; an absent entry inherits [authType]. */
    val protocolAuthTypes: Map<String, String> = emptyMap(),
    /** custom: 用户自建；provider: 系统按订阅 provider 置备。 */
    val kind: ChannelKind = ChannelKind.CUSTOM,
    val provider: OAuthProvider? = null,
    /** 渠道级密钥自动禁用策略的形态：继承全局、自定义（未命中回落全局）或关闭。 */
    val keyDisableMode: ChannelKeyDisableMode = ChannelKeyDisableMode.INHERIT,
) {
    /** 是否配置了渠道级策略；只有继承全局时为 false。 */
    val keyDisablePolicySet: Boolean get() = keyDisableMode != ChannelKeyDisableMode.INHERIT

    val isProviderChannel: Boolean get() = kind == ChannelKind.PROVIDER
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

    /** 支持指定上游模型的可路由凭据，静态密钥与订阅账号一起参与排序。 */
    fun credentialsFor(upstreamModel: String, now: Long = System.currentTimeMillis()): List<UpstreamCredential> =
        (keys + oauthAccounts)
            .asSequence()
            .filter { it.isRoutable(now) && it.supports(upstreamModel) }
            .sortedWith(compareBy({ it.priority }, { it.name }, { it.id }))
            .toList()

    /**
     * 当前可路由、且至少支持一个已授权模型的凭据数（静态密钥 + 订阅账号）。
     *
     * 渠道启用只表达运营意图：凭据全部不可路由时渠道接不到任何请求，健康度据此展示。
     * [oauthAccounts] 未加载时只统计静态密钥，调用方需自行保证数据完整。
     */
    fun routableCredentialCount(now: Long = System.currentTimeMillis()): Int {
        val authorized = models.toSet()
        return (keys + oauthAccounts).count { credential ->
            credential.isRoutable(now) && authorized.any(credential::supports)
        }
    }
}

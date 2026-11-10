package dev.soven.prismllm.spi.quota

import java.math.BigDecimal

/**
 * 上游渠道额度查询插件的稳定入口。
 *
 * 插件只负责把一个渠道的上游额度转换为 [ChannelQuotaResult]；它不参与请求转发、
 * 密钥状态变更或数据库写入。实现必须提供公开的无参构造函数，并通过
 * META-INF/services/dev.soven.prismllm.spi.quota.ChannelQuotaProvider 注册。
 *
 * 该接口使用阻塞方法，是为了让 Java、Kotlin 和其他 JVM 语言都能实现；调用方会在
 * 专用的有界线程池中执行插件，不应在插件中启动长生命周期线程。
 * 不同渠道可以并发调用同一插件实例，实现必须保证线程安全并响应线程中断。
 */
interface ChannelQuotaProvider : AutoCloseable {
    /** 稳定的机器可读 ID，只允许小写字母、数字、.、_ 和 -。 */
    val id: String

    /** 管理界面展示名称。 */
    val displayName: String
        get() = id

    /** SPI 主版本。当前唯一支持的版本是 1。 */
    val apiVersion: Int
        get() = API_VERSION

    /**
     * 查询渠道级额度。凭据只在本次调用期间以明文存在，插件不得记录、缓存或返回它。
     *
     * 插件应将第三方返回的数据先验证再映射；无法确认额度时返回明确的失败状态，
     * 不要把未知值映射为 0。
     */
    fun query(request: ChannelQuotaQuery): ChannelQuotaResult

    /** 网关关闭时释放插件持有的 HTTP 客户端等资源。 */
    override fun close() {}

    companion object {
        const val API_VERSION = 1
        const val ID_PATTERN = "[a-z0-9][a-z0-9._-]{0,63}"
    }
}

/** 传给额度插件的最小渠道上下文。 */
data class ChannelQuotaQuery(
    val channelId: String,
    val baseUrl: String,
    val authType: String,
    val credential: String,
    /** 预留给插件的 JSON 配置；核心不会解释其内容。 */
    val configJson: String = "{}",
) {
    override fun toString(): String = "ChannelQuotaQuery(channelId=" + channelId + ", credential=[REDACTED])"
}

/**
 * 渠道级额度查询结果。多个密钥共用一个上游账户时只返回一份结果，禁止按密钥累加。
 */
data class ChannelQuotaResult(
    val status: ChannelQuotaStatus,
    val remaining: BigDecimal? = null,
    val used: BigDecimal? = null,
    val limit: BigDecimal? = null,
    /** 例如 USD、CNY、quota 或 tokens；未知时为 null。 */
    val unit: String? = null,
    val planName: String? = null,
    /** 面向管理员的简短错误信息，不应包含凭据或完整上游响应。 */
    val message: String? = null,
    val observedAt: Long = System.currentTimeMillis(),
) {
    init {
        // 钱包可透支，负余额应按原值展示；不能把它当成未知或无限制。
        require(used == null || used >= BigDecimal.ZERO) { "used must be non-negative" }
        require(limit == null || limit >= BigDecimal.ZERO) { "limit must be non-negative" }
        require(status != ChannelQuotaStatus.AVAILABLE || remaining != null) { "available quota requires remaining" }
        require(status == ChannelQuotaStatus.AVAILABLE || status == ChannelQuotaStatus.UNLIMITED ||
            (remaining == null && used == null && limit == null)) {
            "quota values are only valid for AVAILABLE or UNLIMITED results"
        }
    }
}

enum class ChannelQuotaStatus {
    AVAILABLE,
    UNLIMITED,
    UNSUPPORTED,
    AUTHENTICATION_FAILED,
    RATE_LIMITED,
    ERROR,
}

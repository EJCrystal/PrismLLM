package dev.soven.llmgateway.domain

import dev.soven.llmgateway.shared.domain.GatewayProtocol

/** 渠道来源：用户自建，或系统按订阅 provider 置备。 */
enum class ChannelKind(val value: String) {
    CUSTOM("custom"),
    PROVIDER("provider"),
    ;

    companion object {
        fun fromValue(value: String?): ChannelKind? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 订阅账号的官方端点。订阅凭据只能用于提供方自己的 API，因此端点写成代码常量，
 * 不随渠道配置变化；系统按 provider 置备一条隐藏渠道来承载它。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class ProviderEndpoint(
    val provider: OAuthProvider,
    val channelName: String,
    val baseUrl: String,
    val authType: String,
    val protocols: List<GatewayProtocol>,
    /** 首次置备时写入的种子模型；之后可通过渠道接口调整。 */
    val models: List<String> = emptyList(),
) {
    /** 网关是否已实现该 provider 的上游协议；未实现时不置备渠道。 */
    val servable: Boolean get() = protocols.isNotEmpty()
}

/** 各订阅 provider 的官方端点。 */
object ProviderEndpoints {
    /** Codex 的官方 Responses 端点。 */
    val chatGpt = ProviderEndpoint(
        provider = OAuthProvider.CHATGPT,
        channelName = "OpenAI 订阅（系统）",
        baseUrl = "https://chatgpt.com/backend-api/codex",
        authType = "bearer",
        protocols = listOf(GatewayProtocol.RESPONSES),
        models = listOf("gpt-5-codex", "gpt-5", "codex-mini-latest"),
    )

    /**
     * Antigravity 走 Google Code Assist：Gemini 请求包一层信封，project 与 tier 按账号
     * 由 loadCodeAssist 发现。模型列表留空，由管理员通过
     * PUT /admin/api/oauth-providers/antigravity/models 配置。
     */
    val antigravity = ProviderEndpoint(
        provider = OAuthProvider.ANTIGRAVITY,
        channelName = "Google Antigravity（系统）",
        baseUrl = "https://daily-cloudcode-pa.googleapis.com",
        authType = "bearer",
        protocols = listOf(GatewayProtocol.GEMINI),
    )

    private val all: List<ProviderEndpoint> = listOf(chatGpt, antigravity)

    fun of(provider: OAuthProvider): ProviderEndpoint = all.first { it.provider == provider }
}

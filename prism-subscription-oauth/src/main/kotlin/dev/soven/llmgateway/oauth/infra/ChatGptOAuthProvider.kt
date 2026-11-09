package dev.soven.llmgateway.oauth.infra

import dev.soven.llmgateway.oauth.domain.OAuthProviderConfig
import dev.soven.llmgateway.shared.domain.OAuthProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ChatGPT / Codex OAuth 的 refresh token 交换。使用 Auth0 兼容的 JSON 请求体。
 * client id 由配置提供，默认值来自 Codex CLI 的公开客户端。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class ChatGptOAuthProvider(
    private val config: OAuthProviderConfig,
    private val timeoutMs: Long = 30_000,
    private val proxyAllowedHosts: Set<String> = emptySet(),
) : OAuthTokenProvider {
    override val provider = OAuthProvider.CHATGPT

    override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
        val body = buildJsonObject {
            put("client_id", config.clientId)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }.toString()
        val response = postTokenRequest(
            endpoint = config.tokenEndpoint,
            contentType = "application/json",
            body = body,
            proxyUrl = proxyUrl,
            timeoutMs = timeoutMs,
            proxyAllowedHosts = proxyAllowedHosts,
        )
        return response.toOAuthTokenResponse()
    }
}

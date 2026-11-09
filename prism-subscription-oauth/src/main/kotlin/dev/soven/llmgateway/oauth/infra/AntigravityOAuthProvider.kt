package dev.soven.llmgateway.oauth.infra

import dev.soven.llmgateway.oauth.domain.OAuthProviderConfig
import dev.soven.llmgateway.shared.domain.OAuthProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Antigravity 使用的 Google OAuth refresh token 交换（表单编码）。
 * client id 与 client secret 默认取 Antigravity 桌面客户端的公开值，可用环境变量覆盖。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class AntigravityOAuthProvider(
    private val config: OAuthProviderConfig,
    private val timeoutMs: Long = 30_000,
    private val proxyAllowedHosts: Set<String> = emptySet(),
) : OAuthTokenProvider {
    override val provider = OAuthProvider.ANTIGRAVITY

    override suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse {
        val form = buildList {
            add("client_id" to config.clientId)
            config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret" to it) }
            add("grant_type" to "refresh_token")
            add("refresh_token" to refreshToken)
        }.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        val response = postTokenRequest(
            endpoint = config.tokenEndpoint,
            contentType = "application/x-www-form-urlencoded",
            body = form,
            proxyUrl = proxyUrl,
            timeoutMs = timeoutMs,
            proxyAllowedHosts = proxyAllowedHosts,
        )
        return response.toOAuthTokenResponse()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

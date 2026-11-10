package dev.soven.prismllm.proxy

import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"

/** Applies the configured upstream credential scheme and the native Messages version headers. */
fun HttpRequestBuilder.applyUpstreamAuthentication(
    credential: String,
    authType: String,
    protocol: GatewayProtocol? = null,
    anthropicVersion: String? = null,
    anthropicBeta: String? = null,
) {
    when (authType) {
        "anthropic" -> header("x-api-key", credential)
        "google" -> header("x-goog-api-key", credential)
        else -> header(HttpHeaders.Authorization, "Bearer $credential")
    }
    if (protocol == GatewayProtocol.MESSAGES || authType == "anthropic") {
        header("anthropic-version", anthropicVersion?.takeIf { it.isNotBlank() } ?: DEFAULT_ANTHROPIC_VERSION)
    }
    if (protocol == GatewayProtocol.MESSAGES) {
        anthropicBeta?.takeIf { it.isNotBlank() }?.let { header("anthropic-beta", it) }
    }
}

/**
 * Code Assist 请求的客户端身份。请求头目录按协议给 Gemini 选的通用 UA 不是
 * Antigravity 的，直接追加会拼成两个 UA；这里替换成全局目录里的 Antigravity 版本。
 */
fun HttpRequestBuilder.applyCodeAssistIdentity(userAgent: String) {
    headers.remove(HttpHeaders.UserAgent)
    headers.remove("X-Goog-Api-Client")
    header(HttpHeaders.UserAgent, userAgent)
    header("X-Goog-Api-Client", "gl-node/22.21.1")
}

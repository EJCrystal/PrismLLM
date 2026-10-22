package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.security.CredentialCipher
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一次协议探测结果。supported 表示上游端点存在且可用于该协议，不代表具体模型一定可用。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class ProtocolProbeResult(
    val protocol: GatewayProtocol,
    val supported: Boolean,
    val statusCode: Int?,
    val message: String,
)

/**
 * 用最小请求探测渠道是否提供 Chat Completions 与 Responses 端点。
 *
 * @author 文杰
 * @since 2026-10-22
 */
suspend fun probeUpstreamProtocols(
    channel: ChannelRecord,
    model: String,
    credentialCipher: CredentialCipher,
    httpClient: HttpClient,
    timeoutMs: Long = 20_000,
): List<ProtocolProbeResult> {
    val credential = runCatching {
        credentialCipher.decrypt(channel.credentialCiphertext, channel.credentialNonce)
    }.getOrElse {
        throw GatewayException(
            HttpStatusCode.BadGateway,
            "upstream_credential_invalid",
            "渠道凭据无法解密",
            retryable = false,
        )
    }

    return GatewayProtocol.entries.map { protocol ->
        try {
            withTimeout(timeoutMs) {
                val response = httpClient.post(probeUrl(channel, protocol)) {
                    header(HttpHeaders.Authorization, "Bearer $credential")
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(TextContent(probeBody(protocol, model), ContentType.Application.Json))
                }
                val status = response.status.value
                response.bodyAsText()
                ProtocolProbeResult(
                    protocol = protocol,
                    supported = status in 200..299 || status in setOf(400, 422, 429),
                    statusCode = status,
                    message = statusMessage(status),
                )
            }
        } catch (e: TimeoutCancellationException) {
            ProtocolProbeResult(protocol, false, null, "探测超时")
        } catch (e: Throwable) {
            ProtocolProbeResult(protocol, false, null, "无法连接上游")
        }
    }
}

private fun probeUrl(channel: ChannelRecord, protocol: GatewayProtocol): String =
    channel.baseUrl.trimEnd('/') + when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> "/chat/completions"
        GatewayProtocol.RESPONSES -> "/responses"
    }

private fun probeBody(protocol: GatewayProtocol, model: String): String = when (protocol) {
    GatewayProtocol.CHAT_COMPLETIONS -> buildJsonObject {
        put("model", model)
        put("stream", false)
        put("max_tokens", 1)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    },
                )
            },
        )
    }.toString()
    GatewayProtocol.RESPONSES -> buildJsonObject {
        put("model", model)
        put("stream", false)
        put("max_output_tokens", 1)
        put("input", "ping")
    }.toString()
}

private fun statusMessage(status: Int): String = when {
    status in 200..299 -> "端点可用"
    status in setOf(400, 422) -> "端点存在，但探测请求被拒绝（HTTP $status）"
    status == 429 -> "端点存在，但被限流（HTTP 429）"
    status in setOf(401, 403) -> "凭据无效或无权访问（HTTP $status）"
    status in setOf(404, 405) -> "上游未提供该协议端点（HTTP $status）"
    status >= 500 -> "上游错误（HTTP $status）"
    else -> "HTTP $status"
}

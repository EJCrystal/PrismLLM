package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.authTypeFor
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.oauth.KeyCredentialResolver
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
 * 管理面主动发现渠道支持的四种协议，包括尚未声明的协议，以便填写渠道配置。
 * 每个协议发送一次最多 1 token 的最小请求；数据面仍只路由到声明了对应协议的渠道。
 *
 * @author 文杰
 * @since 2026-10-22
 */
suspend fun probeUpstreamProtocols(
    channel: ChannelRecord,
    key: ChannelKeyRecord,
    model: String,
    credentials: KeyCredentialResolver,
    httpClient: HttpClient,
    timeoutMs: Long = 20_000,
    globalOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    headerProfiles: List<UpstreamHeaderProfile> = UpstreamHeaderProfiles.defaults,
): List<ProtocolProbeResult> {
    val credential = resolveSavedCredential(credentials, key)

    return GatewayProtocol.entries.map { protocol ->
        try {
            withTimeout(timeoutMs) {
                val authType = channel.authTypeFor(protocol)
                val policy = UpstreamRequestPolicy(
                    globalOverrides, channel.requestOverrides, protocol, authType, headerProfiles,
                )
                val body = policy.body(
                    kotlinx.serialization.json.Json.parseToJsonElement(probeBody(protocol, model)) as kotlinx.serialization.json.JsonObject,
                    model,
                )
                val response = httpClient.post(upstreamRequestUrl(channel.baseUrl, protocol, model)) {
                    applyUpstreamAuthentication(credential, authType, protocol)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    policy.applyHeaders(this)
                    setBody(TextContent(limitModelTestTokens(body, protocol, 1).toString(), ContentType.Application.Json))
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
    GatewayProtocol.MESSAGES -> buildJsonObject {
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
    GatewayProtocol.GEMINI -> buildModelTestBody(protocol, model, "ping", 1)
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

package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import io.ktor.http.encodeURLPathPart

/** Gemini places the model and streaming action in the URL; the other protocols use JSON model/stream. */
fun upstreamRequestUrl(baseUrl: String, protocol: GatewayProtocol, model: String, stream: Boolean = false): String {
    val base = baseUrl.trimEnd('/')
    if (protocol != GatewayProtocol.GEMINI) return base + requireNotNull(protocol.endpointPath)
    val action = if (stream) "streamGenerateContent" else "generateContent"
    val modelPath = model.removePrefix("models/").encodeURLPathPart()
    return "$base/models/$modelPath:$action" + if (stream) "?alt=sse" else ""
}

fun ChannelRecord.modelListProtocol(): GatewayProtocol? = when (authType) {
    "anthropic" -> GatewayProtocol.MESSAGES
    "google" -> GatewayProtocol.GEMINI
    else -> protocols.singleOrNull()
}

fun isGeminiRequestPath(path: String): Boolean =
    path == "/v1beta/models" || path.startsWith("/v1beta/models/") || path.startsWith("/v1/models/")

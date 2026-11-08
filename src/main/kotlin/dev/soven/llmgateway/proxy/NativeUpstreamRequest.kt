package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.UpstreamEnvelope
import io.ktor.http.encodeURLPathPart

/** Gemini places the model and streaming action in the URL; the other protocols use JSON model/stream. */
fun upstreamRequestUrl(
    baseUrl: String,
    protocol: GatewayProtocol,
    model: String,
    stream: Boolean = false,
    envelope: UpstreamEnvelope = UpstreamEnvelope.NONE,
): String {
    val base = baseUrl.trimEnd('/')
    if (envelope == UpstreamEnvelope.CODE_ASSIST) {
        val action = if (stream) "streamGenerateContent" else "generateContent"
        return base + "/v1internal:" + action + if (stream) "?alt=sse" else ""
    }
    if (protocol != GatewayProtocol.GEMINI) return base + requireNotNull(protocol.endpointPath)
    val action = if (stream) "streamGenerateContent" else "generateContent"
    val modelPath = model.removePrefix("models/").encodeURLPathPart()
    return "$base/models/$modelPath:$action" + if (stream) "?alt=sse" else ""
}

fun ChannelRecord.modelListProtocol(): GatewayProtocol? =
    modelListProtocol(protocols, authType, protocolAuthTypes)

/**
 * The model-list endpoint is protocol-neutral, but its URL and auth headers
 * still need one protocol context. Prefer explicit Gemini/Messages overrides, then
 * legacy special auth, other configured overrides, and a single declared protocol.
 */
fun modelListProtocol(
    protocols: List<GatewayProtocol>,
    authType: String,
    protocolAuthTypes: Map<String, String> = emptyMap(),
): GatewayProtocol? {
    val explicitGemini = protocols.firstOrNull {
        it == GatewayProtocol.GEMINI && protocolAuthTypes.containsKey(it.value)
    }
    val explicitMessages = protocols.firstOrNull {
        it == GatewayProtocol.MESSAGES && protocolAuthTypes.containsKey(it.value)
    }
    val explicitOther = protocols.firstOrNull { protocolAuthTypes.containsKey(it.value) }
    val legacySpecial = when (authType) {
        "anthropic" -> GatewayProtocol.MESSAGES
        "google" -> GatewayProtocol.GEMINI
        else -> null
    }
    return explicitGemini ?: explicitMessages ?: legacySpecial ?: explicitOther ?: protocols.singleOrNull()
}

fun isGeminiRequestPath(path: String): Boolean =
    path == "/v1beta/models" || path.startsWith("/v1beta/models/") || path.startsWith("/v1/models/")

package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Maximum model identifier retained from an untrusted upstream response. */
internal const val MAX_REPORTED_MODEL_CHARS = 200

/** Extract the model identifier declared by an upstream response, when present. */
fun extractReportedModel(protocol: GatewayProtocol, json: JsonObject?): String? {
    if (json == null) return null

    val candidates = when (protocol) {
        GatewayProtocol.RESPONSES -> listOf(json["model"], (json["response"] as? JsonObject)?.get("model"))
        GatewayProtocol.MESSAGES -> listOf(json["model"], (json["message"] as? JsonObject)?.get("model"))
        GatewayProtocol.GEMINI -> listOf(json["modelVersion"], json["model"])
        GatewayProtocol.CHAT_COMPLETIONS -> listOf(json["model"])
    }

    return candidates.firstNotNullOfOrNull { element ->
        (element as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_REPORTED_MODEL_CHARS)
    }
}

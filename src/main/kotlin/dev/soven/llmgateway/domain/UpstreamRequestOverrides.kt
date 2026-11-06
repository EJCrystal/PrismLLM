package dev.soven.llmgateway.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A layer applied to the final native upstream request. JSON null in body is a merge-patch deletion. */
@Serializable
data class UpstreamProtocolOverrides(
    val headerProfile: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String?> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
)

/** Channel settings inherit the global layers unless explicitly opted out. */
@Serializable
data class UpstreamRequestOverrides(
    val inheritGlobal: Boolean = true,
    val headerProfile: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String?> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap()),
    val protocols: Map<String, UpstreamProtocolOverrides> = emptyMap(),
    /** auto / always / never：是否在 Chat Completions 请求中补齐 assistant 的 reasoning_content。 */
    val reasoningContentPolicy: String? = null,
) {
    fun common() = UpstreamProtocolOverrides(headerProfile, userAgent, headers, body)
}

data class UpstreamRequestSettingsRecord(
    val overrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    val updatedAt: Long = 0,
)

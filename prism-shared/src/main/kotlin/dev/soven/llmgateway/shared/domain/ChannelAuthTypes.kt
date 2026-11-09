package dev.soven.llmgateway.shared.domain

/** Supported authentication schemes for credentials sent to an upstream channel. */
const val AUTH_TYPE_BEARER = "bearer"
const val AUTH_TYPE_ANTHROPIC = "anthropic"
const val AUTH_TYPE_GOOGLE = "google"

val SUPPORTED_UPSTREAM_AUTH_TYPES: Set<String> = setOf(
    AUTH_TYPE_BEARER,
    AUTH_TYPE_ANTHROPIC,
    AUTH_TYPE_GOOGLE,
)

/**
 * Resolves the authentication scheme for one outbound protocol.
 * An absent protocol override deliberately falls back to the legacy channel-level value.
 */
fun effectiveAuthType(
    channelAuthType: String,
    protocolAuthTypes: Map<String, String> = emptyMap(),
    protocol: GatewayProtocol? = null,
): String = protocol?.value?.let { protocolAuthTypes[it] } ?: channelAuthType

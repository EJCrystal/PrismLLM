package dev.soven.prismllm.shared.domain

import java.security.SecureRandom

/** W3C trace context used for gateway logs and upstream propagation. */
data class TraceContext(
    val traceId: String,
    val spanId: String,
    val traceFlags: String,
) {
    val traceparent: String get() = "00-$traceId-$spanId-$traceFlags"
}

object TraceContextFactory {
    private val random = SecureRandom()
    private val traceparentPattern = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

    fun fromHeaderOrCreate(value: String?): TraceContext {
        val match = value?.lowercase()?.let(traceparentPattern::matchEntire)
        val incomingTraceId = match?.groupValues?.getOrNull(1)
        val incomingSpanId = match?.groupValues?.getOrNull(2)
        val incomingFlags = match?.groupValues?.getOrNull(3)
        if (
            incomingTraceId != null &&
            incomingSpanId != null &&
            incomingFlags != null &&
            incomingTraceId != "0".repeat(32) &&
            incomingSpanId != "0".repeat(16)
        ) {
            return TraceContext(incomingTraceId, randomId(8), incomingFlags)
        }
        return TraceContext(randomId(16), randomId(8), "01")
    }

    private fun randomId(bytes: Int): String {
        val value = ByteArray(bytes)
        do {
            random.nextBytes(value)
        } while (value.all { it == 0.toByte() })
        return value.joinToString("") { "%02x".format(it) }
    }
}

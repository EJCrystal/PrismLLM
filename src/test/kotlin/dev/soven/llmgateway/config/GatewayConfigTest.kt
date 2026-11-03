package dev.soven.llmgateway.config

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayConfigTest {
    private val requiredEnv = mapOf(
        "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        "ADMIN_PASSWORD" to "test-password",
    )

    @Test
    fun connectionTimeoutDefaultsToThirtySeconds() {
        assertEquals(30_000L, GatewayConfig.fromEnv(requiredEnv).connectTimeoutMs)
    }

    @Test
    fun connectionTimeoutCanBeOverridden() {
        val config = GatewayConfig.fromEnv(requiredEnv + ("CONNECT_TIMEOUT_MS" to "60000"))

        assertEquals(60_000L, config.connectTimeoutMs)
    }

    @Test
    fun blankConnectionTimeoutUsesDefault() {
        val config = GatewayConfig.fromEnv(requiredEnv + ("CONNECT_TIMEOUT_MS" to " "))

        assertEquals(30_000L, config.connectTimeoutMs)
    }
}

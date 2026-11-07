package dev.soven.llmgateway.config

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun trustedProxyNetworksCanBeConfiguredAsIpv4AndIpv6Cidrs() {
        val config = GatewayConfig.fromEnv(
            requiredEnv + ("TRUSTED_PROXY_CIDRS" to "172.19.0.0/16, 2001:db8:1234::/48"),
        )

        assertTrue(config.trustedProxyNetworks.any { it.contains("172.19.4.8") })
        assertFalse(config.trustedProxyNetworks.any { it.contains("172.20.4.8") })
        assertTrue(config.trustedProxyNetworks.any { it.contains("2001:db8:1234::5") })
        assertFalse(config.trustedProxyNetworks.any { it.contains("2001:db8:5678::5") })
    }

    @Test
    fun trustedProxyNetworkNormalizesIpv4MappedIpv6Prefixes() {
        val config = GatewayConfig.fromEnv(
            requiredEnv + ("TRUSTED_PROXY_CIDRS" to "::ffff:192.0.2.0/120"),
        )

        assertTrue(config.trustedProxyNetworks.single().contains("192.0.2.255"))
        assertTrue(config.trustedProxyNetworks.single().contains("::ffff:192.0.2.255"))
        assertFalse(config.trustedProxyNetworks.single().contains("192.0.3.0"))
        assertFailsWith<IllegalArgumentException> {
            TrustedProxyNetwork.parse("::ffff:192.0.2.0/95")
        }
    }

    @Test
    fun webSearchIsDisabledWithoutAKey() {
        assertFalse(GatewayConfig.fromEnv(requiredEnv).webSearch.enabled)
    }

    @Test
    fun webSearchApiKeyEnablesTavilyByDefault() {
        val config = GatewayConfig.fromEnv(requiredEnv + ("WEB_SEARCH_API_KEY" to "tvly-test"))

        assertTrue(config.webSearch.enabled)
        assertEquals("tavily", config.webSearch.provider)
    }

    @Test
    fun searxngRequiresABaseUrl() {
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(
                requiredEnv + mapOf(
                    "WEB_SEARCH_ENABLED" to "true",
                    "WEB_SEARCH_PROVIDER" to "searxng",
                ),
            )
        }
    }

    @Test
    fun keyBearingSearchBackendsRequireHttps() {
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(
                requiredEnv + mapOf(
                    "WEB_SEARCH_ENABLED" to "true",
                    "WEB_SEARCH_PROVIDER" to "tavily",
                    "WEB_SEARCH_API_KEY" to "tvly-test",
                    "WEB_SEARCH_BASE_URL" to "http://search.example.com",
                ),
            )
        }

        val local = GatewayConfig.fromEnv(
            requiredEnv + mapOf(
                "WEB_SEARCH_ENABLED" to "true",
                "WEB_SEARCH_PROVIDER" to "tavily",
                "WEB_SEARCH_API_KEY" to "tvly-test",
                "WEB_SEARCH_BASE_URL" to "http://127.0.0.1:8080",
            ),
        )
        assertEquals("http://127.0.0.1:8080", local.webSearch.baseUrl)
    }
}

package dev.soven.prismllm.config

import org.junit.jupiter.api.Tag
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class GatewayConfigTest {
    private val requiredEnv = mapOf(
        "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        "ADMIN_PASSWORD" to "test-password",
    )

    @Test
    fun legacyAgyEnvironmentCannotOverrideManagedRuntimeDefaults() {
        val legacy = mapOf(
            "ANTIGRAVITY_AGY_SCRIPT" to "/legacy/antigravity_agy_admin.py",
            "ANTIGRAVITY_AGY_STATE_DIR" to "/legacy/profiles",
            "ANTIGRAVITY_AGY_PYTHON" to "invalid-relative-python",
            "ANTIGRAVITY_AGY_PROXY" to "http://legacy.invalid:7897",
        )
        for (enabled in listOf("true", "false", "invalid")) {
            val config = GatewayConfig.fromEnv(requiredEnv + legacy + ("ANTIGRAVITY_AGY_ENABLED" to enabled))
            assertEquals(AntigravityAgyConfig.defaults(), config.antigravityAgy)
        }
    }

    @Test
    fun channelProxyAllowlistIsIndependentFromOAuthAndValidated() {
        val config = GatewayConfig.fromEnv(requiredEnv + mapOf(
            "CHANNEL_PROXY_ALLOWED_HOSTS" to " Proxy.Example,host.docker.internal,*.onesproxy.vip ",
            "OAUTH_PROXY_ALLOWED_HOSTS" to "oauth.example",
        ))
        assertEquals(
            setOf("proxy.example", "host.docker.internal", "*.onesproxy.vip"),
            config.channelProxyAllowedHosts,
        )
        assertEquals(setOf("oauth.example"), config.oauth.proxyAllowedHosts)
        assertTrue(GatewayConfig.fromEnv(requiredEnv).channelProxyAllowedHosts.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("CHANNEL_PROXY_ALLOWED_HOSTS" to "http://proxy.example:7897"))
        }
    }

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
    fun healthThresholdsUseTheirOwnDefaultsAndStayIndependentOfCooldown() {
        val defaults = GatewayConfig.fromEnv(requiredEnv)

        assertEquals(10_000L, defaults.cooldown.ttftSlowThresholdMs)
        assertEquals(30_000L, defaults.health.slowFirstTokenMs)
        assertEquals(10_000L, defaults.health.slowP50FirstTokenMs)

        // 只调冷却是冷却的事，大盘阈值不跟着漂。
        val cooldownOnly = GatewayConfig.fromEnv(requiredEnv + ("TTFT_SLOW_THRESHOLD_MS" to "4000"))
        assertEquals(4_000L, cooldownOnly.cooldown.ttftSlowThresholdMs)
        assertEquals(30_000L, cooldownOnly.health.slowFirstTokenMs)
        assertEquals(10_000L, cooldownOnly.health.slowP50FirstTokenMs)

        val healthOnly = GatewayConfig.fromEnv(
            requiredEnv + mapOf(
                "TTFT_SLOW_THRESHOLD_MS" to "10000",
                "HEALTH_SLOW_FIRST_TOKEN_MS" to "30000",
                "HEALTH_SLOW_P50_FIRST_TOKEN_MS" to "8000",
            ),
        )
        assertEquals(10_000L, healthOnly.cooldown.ttftSlowThresholdMs)
        assertEquals(30_000L, healthOnly.health.slowFirstTokenMs)
        assertEquals(8_000L, healthOnly.health.slowP50FirstTokenMs)
    }

    @Test
    fun healthP50DefaultNeverExceedsTheP95Threshold() {
        val config = GatewayConfig.fromEnv(requiredEnv + ("HEALTH_SLOW_FIRST_TOKEN_MS" to "3000"))

        assertEquals(3_000L, config.health.slowFirstTokenMs)
        assertEquals(3_000L, config.health.slowP50FirstTokenMs)
    }

    @Test
    fun healthThresholdsRejectOutOfRangeAndInvertedValues() {
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("HEALTH_SLOW_FIRST_TOKEN_MS" to "50"))
        }
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("HEALTH_SLOW_P50_FIRST_TOKEN_MS" to "50"))
        }
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(
                requiredEnv + mapOf(
                    "HEALTH_SLOW_FIRST_TOKEN_MS" to "5000",
                    "HEALTH_SLOW_P50_FIRST_TOKEN_MS" to "6000",
                ),
            )
        }
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
    fun iqJudgeTimeoutDefaultsTo60Seconds() {
        val config = GatewayConfig.fromEnv(requiredEnv)

        assertEquals(60_000L, config.iqJudgeTimeoutMs)
    }

    @Test
    fun iqTestTimeoutIsUnsetByDefaultSoBenchmarksKeepTheirOwnValues() {
        val config = GatewayConfig.fromEnv(requiredEnv)

        assertNull(config.iqTestTimeoutMs)
    }

    @Test
    fun iqTestTimeoutCanBeOverriddenAndRejectsOutOfRangeValues() {
        val config = GatewayConfig.fromEnv(requiredEnv + ("IQ_TEST_TIMEOUT_MS" to "240000"))

        assertEquals(240_000L, config.iqTestTimeoutMs)

        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("IQ_TEST_TIMEOUT_MS" to "500"))
        }
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("IQ_TEST_TIMEOUT_MS" to "600001"))
        }
        // 非法整数与其它数值环境变量一样由 error() 抛出。
        assertFailsWith<IllegalStateException> {
            GatewayConfig.fromEnv(requiredEnv + ("IQ_TEST_TIMEOUT_MS" to "abc"))
        }
    }

    @Test
    fun blankIqTestTimeoutKeepsPerBenchmarkDefaults() {
        val config = GatewayConfig.fromEnv(requiredEnv + ("IQ_TEST_TIMEOUT_MS" to " "))

        assertNull(config.iqTestTimeoutMs)
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

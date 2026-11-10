package dev.soven.prismllm.config

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
    fun visionJudgeIsDisabledByDefault() {
        val config = GatewayConfig.fromEnv(requiredEnv)

        assertFalse(config.iqVisionEnabled)
        assertEquals(60_000L, config.iqJudgeTimeoutMs)
        assertEquals(1_024, config.iqVisionRenderWidth)
        assertEquals(4_000_000, config.iqVisionMaxImageBytes)
        assertEquals("", config.iqVisionBrowserPath)
        assertEquals(15_000L, config.iqVisionBrowserTimeoutMs)
    }

    @Test
    fun visionJudgeRaisesDefaultJudgeTimeoutToCoverRenderingBudget() {
        val config = GatewayConfig.fromEnv(
            requiredEnv + mapOf(
                "IQ_VISION_ENABLED" to "true",
                "IQ_VISION_BASE_URL" to "http://localhost:8080/v1",
                "IQ_VISION_MODEL" to "vision-model",
            ),
        )

        // 6 帧 × 15 秒浏览器超时 + 60 秒视觉模型超时。
        assertEquals(150_000L, config.iqJudgeTimeoutMs)
    }

    @Test
    fun visionJudgeKeepsExplicitJudgeTimeout() {
        val config = GatewayConfig.fromEnv(
            requiredEnv + mapOf(
                "IQ_VISION_ENABLED" to "true",
                "IQ_VISION_BASE_URL" to "http://localhost:8080/v1",
                "IQ_VISION_MODEL" to "vision-model",
                "IQ_JUDGE_TIMEOUT_MS" to "60000",
            ),
        )

        assertEquals(60_000L, config.iqJudgeTimeoutMs)
    }

    @Test
    fun visionJudgeRequiresEndpointAndModelWhenEnabled() {
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("IQ_VISION_ENABLED" to "true"))
        }
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(
                requiredEnv + mapOf(
                    "IQ_VISION_ENABLED" to "true",
                    "IQ_VISION_BASE_URL" to "http://localhost:8080/v1",
                ),
            )
        }
    }

    @Test
    fun visionJudgeReadsEndpointModelAndLimits() {
        val config = GatewayConfig.fromEnv(
            requiredEnv + mapOf(
                "IQ_VISION_ENABLED" to "true",
                "IQ_VISION_BASE_URL" to "http://localhost:8080/v1",
                "IQ_VISION_API_KEY" to "vision-key",
                "IQ_VISION_MODEL" to "vision-model",
                "IQ_VISION_TIMEOUT_MS" to "90000",
                "IQ_VISION_RENDER_WIDTH" to "640",
                "IQ_VISION_MAX_IMAGE_BYTES" to "500000",
                "IQ_VISION_BROWSER_PATH" to "/usr/bin/google-chrome-stable",
                "IQ_VISION_BROWSER_TIMEOUT_MS" to "20000",
            ),
        )

        assertTrue(config.iqVisionEnabled)
        assertEquals("http://localhost:8080/v1", config.iqVisionBaseUrl)
        assertEquals("vision-key", config.iqVisionApiKey)
        assertEquals("vision-model", config.iqVisionModel)
        assertEquals(90_000L, config.iqVisionTimeoutMs)
        assertEquals(640, config.iqVisionRenderWidth)
        assertEquals(500_000, config.iqVisionMaxImageBytes)
        assertEquals("/usr/bin/google-chrome-stable", config.iqVisionBrowserPath)
        assertEquals(20_000L, config.iqVisionBrowserTimeoutMs)
    }

    @Test
    fun visionJudgeRejectsOutOfRangeBrowserTimeout() {
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(requiredEnv + ("IQ_VISION_BROWSER_TIMEOUT_MS" to "500"))
        }
    }

    @Test
    fun visionJudgeRejectsOutOfRangeRenderWidth() {
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.fromEnv(
                requiredEnv + mapOf(
                    "IQ_VISION_ENABLED" to "true",
                    "IQ_VISION_BASE_URL" to "http://localhost:8080/v1",
                    "IQ_VISION_MODEL" to "vision-model",
                    "IQ_VISION_RENDER_WIDTH" to "128",
                ),
            )
        }
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

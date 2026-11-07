package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.config.OAuthProviderConfig
import dev.soven.llmgateway.config.TrustedProxyNetwork
import dev.soven.llmgateway.config.WebSearchConfig
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SystemConfigOverviewTest {
    @Test
    fun `groups expose effective runtime values and expected kinds`() {
        val response = buildSystemConfigResponse(config())

        assertEquals(
            listOf("runtime", "timeouts", "cooldown", "upstream", "web-search", "oauth"),
            response.sections.map { it.id },
        )
        assertEquals("4", response.item("MAX_ATTEMPTS").value)
        assertEquals("number", response.item("MAX_ATTEMPTS").kind)
        assertEquals("30000", response.item("CONNECT_TIMEOUT_MS").value)
        assertEquals("duration", response.item("CONNECT_TIMEOUT_MS").kind)
        assertEquals("true", response.item("COOKIE_SECURE").value)
        assertEquals("1", response.item("TRUSTED_PROXY_CIDRS").value)
        assertEquals("false", response.item("WEB_SEARCH_ENABLED").value)
        assertEquals("tavily", response.item("WEB_SEARCH_PROVIDER").value)
        assertEquals("6", response.item("WEB_SEARCH_MAX_RESULTS").value)
        assertEquals("round_robin", response.item("OAUTH_ROUTING").value)
    }

    @Test
    fun `source distinguishes environment defaults and derived values`() {
        val response = buildSystemConfigResponse(
            config(providedEnvKeys = setOf("MAX_ATTEMPTS", "CONNECT_TIMEOUT_MS")),
        )

        assertEquals("environment", response.item("MAX_ATTEMPTS").source)
        assertEquals("default", response.item("TOTAL_TIMEOUT_MS").source)
        assertEquals("derived", response.item("OAUTH_ANTIGRAVITY_CLIENT_ID").source)
    }

    @Test
    fun `catalog url drops query fragment and user info`() {
        val response = buildSystemConfigResponse(
            config(upstreamHeaderCatalogUrl = "https://user:pass@example.com/manifest.json?token=secret#section"),
        )

        assertEquals("https://example.com/manifest.json", response.item("UPSTREAM_HEADER_CATALOG_URL").value)
    }

    @Test
    fun `antigravity status reflects provider configuration without exposing secrets`() {
        val response = buildSystemConfigResponse(
            config(
                antigravity = OAuthProviderConfig(
                    clientId = "client-id",
                    clientSecret = "client-secret",
                    tokenEndpoint = "https://oauth2.googleapis.com/token",
                ),
            ),
        )

        assertEquals("已配置", response.item("OAUTH_ANTIGRAVITY_CLIENT_ID").value)
        val encoded = Json.encodeToString(response)
        assertFalse(encoded.contains("client-secret"))
        assertFalse(encoded.contains("db-secret"))
        assertFalse(encoded.contains("test-password"))
    }

    private fun SystemConfigResponse.item(key: String) = assertNotNull(
        sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.key == key },
        "missing config item $key",
    )

    @Test
    fun `web search api key only exposes whether it is configured`() {
        val response = buildSystemConfigResponse(
            config(
                providedEnvKeys = setOf("WEB_SEARCH_ENABLED", "WEB_SEARCH_API_KEY"),
                webSearch = WebSearchConfig(
                    enabled = true,
                    provider = "tavily",
                    apiKey = "tvly-secret",
                    baseUrl = "https://search.example.com/v1?token=hidden",
                ),
            ),
        )

        assertEquals("true", response.item("WEB_SEARCH_ENABLED").value)
        assertEquals("environment", response.item("WEB_SEARCH_ENABLED").source)
        assertEquals("已配置", response.item("WEB_SEARCH_API_KEY").value)
        assertEquals("https://search.example.com/v1", response.item("WEB_SEARCH_BASE_URL").value)
        val encoded = Json.encodeToString(response)
        assertFalse(encoded.contains("tvly-secret"))
        assertFalse(encoded.contains("token=hidden"))
    }

    private fun config(
        providedEnvKeys: Set<String> = emptySet(),
        upstreamHeaderCatalogUrl: String? = null,
        antigravity: OAuthProviderConfig? = null,
        webSearch: WebSearchConfig = WebSearchConfig(),
    ) = GatewayConfig(
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost:5432/llm_gateway",
        databaseUser = "llm_gateway",
        databasePassword = "db-secret",
        masterKey = ByteArray(32) { 7 },
        adminUsername = "admin",
        adminPassword = "test-password",
        corsOrigins = listOf("https://console.example.com"),
        requestBodyLimitBytes = 10L * 1024 * 1024,
        connectTimeoutMs = 30_000,
        firstTokenTimeoutMs = 120_000,
        idleTimeoutMs = 60_000,
        totalTimeoutMs = 600_000,
        maxAttempts = 4,
        drainTimeoutMs = 30_000,
        staticDir = Path.of("web/dist"),
        cookieSecure = true,
        retention = RetentionPolicy(),
        cooldown = CooldownPolicy(),
        keyAutoRecoveryIntervalMs = 30_000,
        upstreamHeaderRefreshIntervalMs = 3_600_000,
        upstreamHeaderCatalogUrl = upstreamHeaderCatalogUrl,
        upstreamHeaderRefreshEnabled = true,
        quotaPluginDir = Path.of("plugins"),
        trustedProxyNetworks = TrustedProxyNetwork.parseList("10.0.0.0/8"),
        oauth = OAuthConfig(antigravity = antigravity),
        webSearch = webSearch,
        providedEnvKeys = providedEnvKeys,
    )
}

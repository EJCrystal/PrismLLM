package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.WebSearchConfig
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebSearchSettingsDtosTest {
    private val defaults = WebSearchConfig(
        enabled = false,
        provider = "tavily",
        apiKey = null,
        baseUrl = null,
    )

    @Test
    fun `response reports environment defaults when nothing is stored`() {
        val response = webSearchSettingsResponse(null, defaults)

        assertEquals("environment", response.source)
        assertEquals("none", response.apiKeySource)
        assertEquals(false, response.apiKeySet)
        assertEquals(0, response.updatedAt)
    }

    @Test
    fun `response reports stored key and settings source`() {
        val stored = WebSearchSettingsRecord(
            enabled = true,
            provider = "brave",
            apiKey = "stored-key",
            baseUrl = null,
            maxResults = 8,
            timeoutMs = 10_000,
            maxRounds = 5,
            maxPageChars = 1_200,
            updatedAt = 789,
        )

        val response = webSearchSettingsResponse(stored, defaults)

        assertEquals("settings", response.source)
        assertEquals("settings", response.apiKeySource)
        assertEquals(true, response.apiKeySet)
        assertEquals("brave", response.provider)
        assertEquals(789, response.updatedAt)
    }

    @Test
    fun `response reports environment key when settings have none`() {
        val stored = WebSearchSettingsRecord(
            enabled = true,
            provider = "tavily",
            apiKey = null,
            baseUrl = null,
            maxResults = 6,
            timeoutMs = 15_000,
            maxRounds = 6,
            maxPageChars = 1_500,
            updatedAt = 1,
        )

        val response = webSearchSettingsResponse(stored, defaults.copy(apiKey = "env-key"))

        assertEquals("settings", response.source)
        assertEquals("environment", response.apiKeySource)
        assertEquals(true, response.apiKeySet)
    }

    @Test
    fun `response does not report an environment key after switching providers`() {
        val stored = WebSearchSettingsRecord(
            enabled = true,
            provider = "brave",
            apiKey = null,
            baseUrl = null,
            maxResults = 6,
            timeoutMs = 15_000,
            maxRounds = 6,
            maxPageChars = 1_500,
            updatedAt = 1,
        )

        val response = webSearchSettingsResponse(stored, defaults.copy(provider = "tavily", apiKey = "env-key"))

        assertEquals("none", response.apiKeySource)
        assertEquals(false, response.apiKeySet)
    }

    @Test
    fun `response exposes unreadable stored keys`() {
        val stored = WebSearchSettingsRecord(
            enabled = true,
            provider = "tavily",
            apiKey = null,
            baseUrl = null,
            maxResults = 6,
            timeoutMs = 15_000,
            maxRounds = 6,
            maxPageChars = 1_500,
            updatedAt = 1,
            apiKeyUnreadable = true,
        )

        val response = webSearchSettingsResponse(stored, defaults)

        assertEquals(true, response.apiKeyUnreadable)
        assertEquals(false, response.apiKeySet)
    }

    @Test
    fun `key bearing backends require https except loopback and searxng`() {
        assertNotNull(
            webSearchSettingsError(
                defaults.copy(enabled = true, provider = "tavily", apiKey = "key", baseUrl = "http://search.example.com"),
            ),
        )
        assertNull(
            webSearchSettingsError(
                defaults.copy(enabled = true, provider = "tavily", apiKey = "key", baseUrl = "http://127.0.0.1:8080"),
            ),
        )
        assertNull(
            webSearchSettingsError(
                defaults.copy(enabled = true, provider = "tavily", apiKey = "key", baseUrl = "https://search.example.com"),
            ),
        )
        assertNull(
            webSearchSettingsError(
                defaults.copy(enabled = true, provider = "searxng", baseUrl = "http://searx.internal"),
            ),
        )
    }

    @Test
    fun `base url length and format are validated`() {
        assertNotNull(
            webSearchSettingsError(
                defaults.copy(enabled = false, baseUrl = "https://example.com/" + "a".repeat(500)),
            ),
        )
        assertNotNull(webSearchSettingsError(defaults.copy(baseUrl = "not a url")))
        assertNotNull(webSearchSettingsError(defaults.copy(baseUrl = "https://example.com?token=1")))
    }

    @Test
    fun `validation rejects unsupported providers and out of range values`() {
        assertNotNull(webSearchSettingsError(defaults.copy(provider = "unknown")))
        assertNotNull(webSearchSettingsError(defaults.copy(maxResults = 0)))
        assertNotNull(webSearchSettingsError(defaults.copy(timeoutMs = 999)))
        assertNotNull(webSearchSettingsError(defaults.copy(maxRounds = 11)))
        assertNotNull(webSearchSettingsError(defaults.copy(maxPageChars = 99)))
    }

    @Test
    fun `enabled searxng needs an address and other providers need a key`() {
        assertNotNull(
            webSearchSettingsError(
                defaults.copy(enabled = true, provider = "searxng", baseUrl = null),
            ),
        )
        assertNull(
            webSearchSettingsError(
                defaults.copy(enabled = true, provider = "searxng", baseUrl = "https://searx.example.com"),
            ),
        )
        assertNotNull(webSearchSettingsError(defaults.copy(enabled = true, provider = "tavily", apiKey = null)))
        assertNull(webSearchSettingsError(defaults.copy(enabled = true, provider = "tavily", apiKey = "key")))
    }

    @Test
    fun `disabled settings do not require a key`() {
        assertNull(webSearchSettingsError(defaults.copy(enabled = false, provider = "tavily", apiKey = null)))
    }
}

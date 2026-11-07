package dev.soven.llmgateway.domain

import dev.soven.llmgateway.config.WebSearchConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebSearchSettingsTest {
    private val defaults = WebSearchConfig(
        enabled = true,
        provider = "brave",
        apiKey = "env-key",
        baseUrl = "https://env.example.com",
        maxResults = 6,
        timeoutMs = 15_000,
        maxRounds = 6,
        maxPageChars = 1_500,
    )

    @Test
    fun `missing settings keep environment defaults`() {
        val stored: WebSearchSettingsRecord? = null

        assertEquals(defaults, stored.effective(defaults))
    }

    @Test
    fun `stored settings override defaults and fall back for unset secrets`() {
        val stored = WebSearchSettingsRecord(
            enabled = false,
            provider = "tavily",
            apiKey = null,
            baseUrl = null,
            maxResults = 10,
            timeoutMs = 20_000,
            maxRounds = 4,
            maxPageChars = 2_000,
            updatedAt = 123,
        )

        val effective = stored.effective(defaults)

        assertFalse(effective.enabled)
        assertEquals("tavily", effective.provider)
        assertEquals("env-key", effective.apiKey)
        assertEquals("https://env.example.com", effective.baseUrl)
        assertEquals(10, effective.maxResults)
        assertEquals(20_000, effective.timeoutMs)
        assertEquals(4, effective.maxRounds)
        assertEquals(2_000, effective.maxPageChars)
    }

    @Test
    fun `stored api key and base url win over environment`() {
        val stored = WebSearchSettingsRecord(
            enabled = true,
            provider = "searxng",
            apiKey = "stored-key",
            baseUrl = "https://searx.example.com",
            maxResults = 3,
            timeoutMs = 5_000,
            maxRounds = 2,
            maxPageChars = 800,
            updatedAt = 456,
        )

        val effective = stored.effective(defaults)

        assertEquals("stored-key", effective.apiKey)
        assertEquals("https://searx.example.com", effective.baseUrl)
        assertEquals("searxng", effective.provider)
    }
}

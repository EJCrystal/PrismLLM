package dev.soven.llmgateway.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpstreamErrorBodySanitizerTest {

    @Test
    fun `redacts secrets from upstream error body`() {
        val body = """{"error":{"message":"Bearer abc.def.ghi sk-1234567890abcdef","api_key":"plain-secret","code":"invalid_api_key"}}"""

        val sanitized = sanitizeUpstreamErrorBody(body)

        assertNotNull(sanitized)
        assertFalse(sanitized.contains("abc.def.ghi"))
        assertFalse(sanitized.contains("sk-1234567890abcdef"))
        assertFalse(sanitized.contains("plain-secret"))
        assertTrue(sanitized.contains("Bearer [REDACTED]"))
        assertTrue(sanitized.contains("\"api_key\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"code\":\"invalid_api_key\""))
    }

    @Test
    fun `truncates upstream error body to eight kilobytes`() {
        val sanitized = sanitizeUpstreamErrorBody("x".repeat(10_000))

        assertEquals(8_192, sanitized?.length)
    }

    @Test
    fun `blank upstream error body is not stored`() {
        assertNull(sanitizeUpstreamErrorBody(null))
        assertNull(sanitizeUpstreamErrorBody("  \n "))
    }
}

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
        val body = """{"error":{"message":"Bearer abc.def.ghi sk-1234567890abcdef","api_key":"plain-secret","x-api-key":"header-key","access_token":"token-val","accessToken":"token-val2","code":"invalid_api_key"}}"""

        val sanitized = sanitizeUpstreamErrorBody(body)

        assertNotNull(sanitized)
        assertFalse(sanitized.contains("abc.def.ghi"))
        assertFalse(sanitized.contains("sk-1234567890abcdef"))
        assertFalse(sanitized.contains("plain-secret"))
        assertFalse(sanitized.contains("header-key"))
        assertFalse(sanitized.contains("token-val"))
        assertFalse(sanitized.contains("token-val2"))
        assertTrue(sanitized.contains("Bearer [REDACTED]"))
        assertTrue(sanitized.contains("\"api_key\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"x-api-key\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"access_token\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"accessToken\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"code\":\"invalid_api_key\""))
    }

    @Test
    fun `redacts key-value pairs and truncated secrets without closing quote`() {
        val kvBody = "upstream error occurred: x-api-key=secret123&other=val access_token=secret456"
        val sanitizedKv = sanitizeUpstreamErrorBody(kvBody)
        assertNotNull(sanitizedKv)
        assertFalse(sanitizedKv.contains("secret123"))
        assertFalse(sanitizedKv.contains("secret456"))
        assertTrue(sanitizedKv.contains("x-api-key=[REDACTED]"))
        assertTrue(sanitizedKv.contains("access_token=[REDACTED]"))

        val truncatedBody = """{"x-api-key":"unclosed-secret-at-end"""
        val sanitizedTruncated = sanitizeUpstreamErrorBody(truncatedBody)
        assertNotNull(sanitizedTruncated)
        assertFalse(sanitizedTruncated.contains("unclosed-secret-at-end"))
        assertTrue(sanitizedTruncated.contains("\"x-api-key\":\"[REDACTED]"))
    }

    @Test
    fun `truncates upstream error body to eight kilobytes preserving utf8 boundaries`() {
        val asciiSanitized = sanitizeUpstreamErrorBody("x".repeat(10_000))
        assertNotNull(asciiSanitized)
        assertEquals(8_192, asciiSanitized.toByteArray(Charsets.UTF_8).size)
        assertEquals(8_192, asciiSanitized.length)

        // 3-byte UTF-8 Chinese characters: 3000 * 3 = 9000 bytes > 8192 bytes
        val multiByteSanitized = sanitizeUpstreamErrorBody("中".repeat(3_000))
        assertNotNull(multiByteSanitized)
        val byteSize = multiByteSanitized.toByteArray(Charsets.UTF_8).size
        assertTrue(byteSize <= 8_192)
        assertEquals(8_190, byteSize) // 2730 * 3 = 8190
        assertEquals(2_730, multiByteSanitized.length)
        assertTrue(multiByteSanitized.all { it == '中' })
    }

    @Test
    fun `redacts secret fields with escaped quotes completely`() {
        val jsonWithEscapedQuote = """{"api_key":"prefix\"SECRET_SUFFIX","message":"normal"}"""
        val sanitized = sanitizeUpstreamErrorBody(jsonWithEscapedQuote)
        assertNotNull(sanitized)
        assertFalse(sanitized.contains("SECRET_SUFFIX"))
        assertFalse(sanitized.contains("prefix"))
        assertTrue(sanitized.contains("\"api_key\":\"[REDACTED]\""))
        assertTrue(sanitized.contains("\"message\":\"normal\""))
    }

    @Test
    fun `blank upstream error body is not stored`() {
        assertNull(sanitizeUpstreamErrorBody(null))
        assertNull(sanitizeUpstreamErrorBody("  \n "))
    }
}

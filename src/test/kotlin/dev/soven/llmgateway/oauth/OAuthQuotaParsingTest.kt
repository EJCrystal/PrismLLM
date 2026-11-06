package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthQuotaWindow
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthQuotaParsingTest {
    private val now = 1_762_400_000_000L

    @Test
    fun `reads both windows credits and plan from the usage payload`() {
        val body = """
            {
              "plan_type": "plus",
              "rate_limit": {
                "allowed": false,
                "primary_window": {
                  "used_percent": 100,
                  "limit_window_seconds": 18000,
                  "reset_at": ${now / 1_000 + 600}
                },
                "secondary_window": {
                  "used_percent": 42.5,
                  "limit_window_seconds": 604800,
                  "reset_after_seconds": 86400
                }
              },
              "credits": {"balance": "12.5", "unlimited": false},
              "rate_limit_reset_credits": {"available_count": 2}
            }
        """.trimIndent()

        val usage = assertNotNull(parseChatGptUsage(body, now))
        assertEquals("plus", usage.planType)
        assertFalse(usage.allowed!!)
        assertEquals(100.0, usage.primary!!.usedPercent)
        assertEquals(18_000, usage.primary!!.windowSeconds)
        assertEquals(now + 600_000, usage.primary!!.resetsAt)
        assertEquals(0.0, usage.primary!!.remainingPercent)
        assertEquals(42.5, usage.secondary!!.usedPercent)
        assertEquals(now + 86_400_000, usage.secondary!!.resetsAt)
        assertEquals(12.5, usage.creditsBalance)
        assertFalse(usage.creditsUnlimited)
        assertEquals(2, usage.resetCredits)
    }

    @Test
    fun `clamps percentages and drops implausible windows`() {
        val body = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 140,
                  "limit_window_seconds": 99999999999,
                  "reset_at": 99999999999
                }
              }
            }
        """.trimIndent()

        val usage = assertNotNull(parseChatGptUsage(body, now))
        val primary = assertNotNull(usage.primary)
        assertEquals(100.0, primary.usedPercent)
        assertNull(primary.windowSeconds)
        assertNull(primary.resetsAt)
        assertNull(usage.allowed)
    }

    @Test
    fun `tolerates missing fields and rejects non-object bodies`() {
        val empty = assertNotNull(parseChatGptUsage("""{"rate_limit":{}}""", now))
        assertNull(empty.primary)
        assertNull(empty.secondary)
        assertNull(empty.creditsBalance)
        assertFalse(empty.creditsUnlimited)

        assertNull(parseChatGptUsage("not json", now))
        assertNull(parseChatGptUsage("[]", now))
    }

    @Test
    fun `unlimited credits are treated as usable even when windows are exhausted`() {
        val body = """
            {
              "rate_limit": {"primary_window": {"used_percent": 100, "reset_after_seconds": 60}},
              "credits": {"unlimited": true}
            }
        """.trimIndent()

        val usage = assertNotNull(parseChatGptUsage(body, now))
        assertTrue(usage.creditsUnlimited)
        assertNull(
            OAuthQuotaSnapshot(
                primary = usage.primary,
                creditsUnlimited = usage.creditsUnlimited,
                checkedAt = now,
            ).blockedUntil(now),
        )
    }

    @Test
    fun `exhausted window without a reset time blocks for the supplied fallback`() {
        val snapshot = OAuthQuotaSnapshot(
            primary = OAuthQuotaWindow(usedPercent = 100.0),
            checkedAt = now,
        )
        assertNull(snapshot.blockedUntil(now))
        assertEquals(now + 60_000, snapshot.blockedUntil(now, 60_000))
        // 异常时钟下按上界饱和，不会得到负数。
        assertEquals(Long.MAX_VALUE, snapshot.blockedUntil(Long.MAX_VALUE - 10, 60_000))
    }

    @Test
    fun `extracts account id and plan from the access token claims`() {
        val token = jwt(
            """
                {
                  "email": "user@example.com",
                  "https://api.openai.com/auth": {
                    "chatgpt_account_id": "acc-123",
                    "chatgpt_plan_type": "free"
                  }
                }
            """.trimIndent(),
        )

        val claims = assertNotNull(parseOAuthTokenClaims(token))
        assertEquals("acc-123", claims.chatgptAccountId)
        assertEquals("free", claims.planType)
        assertEquals("user@example.com", claims.email)
    }

    @Test
    fun `opaque tokens and payloads without identity claims return null`() {
        assertNull(parseOAuthTokenClaims(null))
        assertNull(parseOAuthTokenClaims("sk-opaque-token"))
        assertNull(parseOAuthTokenClaims("a.b"))
        assertNull(parseOAuthTokenClaims(jwt("""{"sub":"abc"}""")))
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            encoder.encodeToString("""{"alg":"none"}""".toByteArray()),
            encoder.encodeToString(payload.toByteArray()),
            "sig",
        ).joinToString(".")
    }
}

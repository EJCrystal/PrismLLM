package dev.soven.llmgateway.quota.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientKeyQuotaPolicyTest {
    @Test
    fun `allows usage below daily token and currency budgets`() {
        val result = checkClientKeyQuota(
            ClientKeyQuotaLimits(
                dailyTokens = 100,
                dailyCosts = mapOf("USD" to BigDecimal("1.00"), "CNY" to BigDecimal("7")),
                maxConcurrentRequests = 2,
            ),
            ClientKeyQuotaUsage(
                dailyTokens = 99,
                dailyCosts = mapOf("USD" to BigDecimal("0.99"), "CNY" to BigDecimal("6.99")),
                concurrentRequests = 1,
            ),
        )

        assertTrue(result.allowed)
        assertNull(result.reason)
    }

    @Test
    fun `rejects exactly consumed token budget`() {
        val result = checkClientKeyQuota(
            ClientKeyQuotaLimits(dailyTokens = 10),
            ClientKeyQuotaUsage(dailyTokens = 10),
        )

        assertFalse(result.allowed)
        assertEquals(QuotaRejectionReason.DAILY_TOKENS, result.reason)
    }

    @Test
    fun `checks each currency independently without conversion`() {
        val limits = ClientKeyQuotaLimits(dailyCosts = mapOf(
            "USD" to BigDecimal("1.00"),
            "CNY" to BigDecimal("7.00"),
        ))

        assertFalse(checkClientKeyQuota(
            limits,
            ClientKeyQuotaUsage(dailyCosts = mapOf("USD" to BigDecimal("1.01"))),
        ).allowed)
        assertTrue(checkClientKeyQuota(
            limits,
            ClientKeyQuotaUsage(dailyCosts = mapOf("CNY" to BigDecimal("6.99"))),
        ).allowed)
    }

    @Test
    fun `rejects when concurrency limit is exhausted`() {
        val result = checkClientKeyQuota(
            ClientKeyQuotaLimits(maxConcurrentRequests = 3),
            ClientKeyQuotaUsage(concurrentRequests = 3),
        )

        assertFalse(result.allowed)
        assertEquals(QuotaRejectionReason.CONCURRENCY, result.reason)
    }

    @Test
    fun `zero token budget blocks all new requests`() {
        assertFalse(checkClientKeyQuota(
            ClientKeyQuotaLimits(dailyTokens = 0),
            ClientKeyQuotaUsage(),
        ).allowed)
    }

    @Test
    fun `daily period changes exactly at UTC midnight`() {
        val midnight = 86_400_000L * 20_011
        assertEquals(dailyQuotaPeriod(midnight - 1), dailyQuotaPeriod(midnight) - 1)
        assertEquals(dailyQuotaPeriod(midnight), dailyQuotaPeriod(midnight + 86_400_000L - 1))
    }

    @Test
    fun `cost limits accept optional values and strip trailing zeros`() {
        assertNull(parseDailyCostLimit(null))
        assertNull(parseDailyCostLimit("  "))
        assertEquals(BigDecimal("1"), parseDailyCostLimit("1.00000000"))
        assertEquals(BigDecimal("0"), parseDailyCostLimit("0"))
    }

    @Test
    fun `cost limits reject negative or excess precision`() {
        assertFalse(runCatching { parseDailyCostLimit("-0.01") }.isSuccess)
        assertFalse(runCatching { parseDailyCostLimit("0.000000001") }.isSuccess)
        assertFalse(runCatching { parseDailyCostLimit("not-a-number") }.isSuccess)
    }

    @Test
    fun `token accounting falls back to input plus output`() {
        assertEquals(0, quotaTokensFromUsage(null, null, null))
        assertEquals(12, quotaTokensFromUsage(null, 10, 2))
        assertEquals(13, quotaTokensFromUsage(13, 10, 3))
        assertEquals(0, quotaTokensFromUsage(-5, null, null))
    }
}

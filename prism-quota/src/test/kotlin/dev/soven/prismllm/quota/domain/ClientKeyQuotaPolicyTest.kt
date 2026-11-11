package dev.soven.prismllm.quota.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientKeyQuotaPolicyTest {
    @Test
    fun `allows usage below daily token and usd budget`() {
        val result = checkClientKeyQuota(
            ClientKeyQuotaLimits(
                dailyTokens = 100,
                dailyUsdLimit = BigDecimal("1.00"),
                maxConcurrentRequests = 2,
            ),
            ClientKeyQuotaUsage(
                dailyTokens = 99,
                dailyUsdCost = BigDecimal("0.99"),
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
    fun `rejects usd budget when consumed cost reaches the limit`() {
        val limits = ClientKeyQuotaLimits(dailyUsdLimit = BigDecimal("1.00"))

        assertFalse(checkClientKeyQuota(
            limits,
            ClientKeyQuotaUsage(dailyUsdCost = BigDecimal("1.01")),
        ).allowed)
        assertTrue(checkClientKeyQuota(
            limits,
            ClientKeyQuotaUsage(dailyUsdCost = BigDecimal("0.99")),
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

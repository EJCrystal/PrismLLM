package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthQuotaWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthQuotaHeadersTest {
    private val now = 1_762_400_000_000L

    @Test
    fun `reads both windows with epoch seconds and after seconds`() {
        val headers = mapOf(
            "x-codex-primary-used-percent" to "96.5",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-after-seconds" to "1800",
            "x-codex-secondary-used-percent" to "42",
            "x-codex-secondary-window-minutes" to "10080",
            "x-codex-secondary-reset-at" to "1762403600",
        )
        val reading = parseCodexQuotaHeaders(headers::get, now)!!

        assertEquals(96.5, reading.primary?.usedPercent)
        assertEquals(300 * 60, reading.primary?.windowSeconds)
        assertEquals(now + 1_800_000, reading.primary?.resetsAt)
        assertEquals(42.0, reading.secondary?.usedPercent)
        assertEquals(7 * 86_400, reading.secondary?.windowSeconds)
        assertEquals(1_762_403_600_000, reading.secondary?.resetsAt)
    }

    @Test
    fun `reset after seconds saturates near the clock limit`() {
        val reading = parseCodexQuotaHeaders(
            mapOf(
                "x-codex-primary-used-percent" to "100",
                "x-codex-primary-window-minutes" to "300",
                "x-codex-primary-reset-after-seconds" to "60",
            )::get,
            Long.MAX_VALUE - 10,
        )!!

        assertEquals(Long.MAX_VALUE, reading.primary?.resetsAt)
    }

    @Test
    fun `ignores incomplete windows and clamps the used share`() {
        assertNull(parseCodexQuotaHeaders({ null }, now))
        assertNull(
            parseCodexQuotaHeaders(
                mapOf(
                    "x-codex-primary-used-percent" to "50",
                    "x-codex-secondary-window-minutes" to "10080",
                )::get,
                now,
            ),
        )
        val reading = parseCodexQuotaHeaders(
            mapOf(
                "x-codex-primary-used-percent" to "140",
                "x-codex-primary-window-minutes" to "300",
            )::get,
            now,
        )!!
        assertEquals(100.0, reading.primary?.usedPercent)
        assertNull(reading.primary?.resetsAt)
    }

    @Test
    fun `merging keeps polled credits and aligns windows by span`() {
        val previous = OAuthQuotaSnapshot(
            primary = OAuthQuotaWindow(usedPercent = 80.0, windowSeconds = 18_000),
            secondary = OAuthQuotaWindow(usedPercent = 20.0, windowSeconds = 604_800),
            allowed = false,
            creditsBalance = 12.5,
            resetCredits = 2,
            checkedAt = now - 60_000,
            error = "network_error",
        )
        val reading = OAuthQuotaHeaderReading(
            // 上游把每周窗口标成 primary 时按窗口时长归位，而不是覆盖 5 小时窗口。
            primary = OAuthQuotaWindow(usedPercent = 100.0, windowSeconds = 604_800, resetsAt = now + 3_600_000),
            secondary = OAuthQuotaWindow(usedPercent = 90.0, windowSeconds = 18_000),
        )
        val merged = previous.mergeQuotaHeaders(reading, now)

        assertEquals(90.0, merged.primary?.usedPercent)
        assertEquals(100.0, merged.secondary?.usedPercent)
        assertEquals(false, merged.allowed)
        assertEquals(12.5, merged.creditsBalance)
        assertEquals(2, merged.resetCredits)
        assertEquals(now, merged.checkedAt)
        assertNull(merged.error)
    }

    @Test
    fun `merging into no snapshot keeps the named windows`() {
        val merged = null.mergeQuotaHeaders(
            OAuthQuotaHeaderReading(
                primary = OAuthQuotaWindow(usedPercent = 100.0, windowSeconds = 18_000, resetsAt = now + 60_000),
            ),
            now,
        )

        assertEquals(100.0, merged.primary?.usedPercent)
        assertNull(merged.secondary)
        assertNull(merged.allowed)
        assertEquals(false, merged.creditsUnlimited)
    }
}

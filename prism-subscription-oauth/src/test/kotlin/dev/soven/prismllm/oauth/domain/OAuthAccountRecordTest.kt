package dev.soven.prismllm.oauth.domain

import dev.soven.prismllm.shared.domain.OAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthAccountRecordTest {
    @Test
    fun `policy enforces window and concurrency bounds`() {
        OAuthAccountPolicy(windowLimit = null, windowSeconds = 60, maxConcurrentRequests = 1)
        OAuthAccountPolicy(windowLimit = 100_000, windowSeconds = 604_800, maxConcurrentRequests = 1_000)

        assertFailsWith<IllegalArgumentException> { OAuthAccountPolicy(windowLimit = 0) }
        assertFailsWith<IllegalArgumentException> { OAuthAccountPolicy(windowLimit = 100_001) }
        assertFailsWith<IllegalArgumentException> { OAuthAccountPolicy(windowSeconds = 59) }
        assertFailsWith<IllegalArgumentException> { OAuthAccountPolicy(windowSeconds = 604_801) }
        assertFailsWith<IllegalArgumentException> { OAuthAccountPolicy(maxConcurrentRequests = 0) }
        assertFailsWith<IllegalArgumentException> { OAuthAccountPolicy(maxConcurrentRequests = 1_001) }
    }

    @Test
    fun `empty models inherit the bound channel models`() {
        val inherited = account(models = emptyList())
        assertTrue(inherited.supports("any-model"))
        assertTrue(account(models = listOf("gpt-5")).supports("gpt-5"))
        assertFalse(account(models = listOf("gpt-5")).supports("gpt-4.1"))
    }

    @Test
    fun `account catalog restricts models beyond the shared channel list`() {
        val catalog = listOf(
            OAuthAccountModelCatalogEntry("gemini-3.8-flash-high", "gemini-3.8-flash"),
            OAuthAccountModelCatalogEntry("claude-opus-5-5-high", "claude-opus-5-5"),
        )
        // 未同步目录时沿用渠道模型；同步后只认自己目录里的变体。
        assertTrue(account(models = emptyList()).supports("gemini-4-argon-high"))
        assertTrue(account(models = emptyList(), modelCatalog = catalog).supports("gemini-3.8-flash-high"))
        assertFalse(account(models = emptyList(), modelCatalog = catalog).supports("gemini-4-argon-high"))
        // 显式授权与目录同时生效，取交集。
        assertFalse(account(models = listOf("gemini-4-argon-high"), modelCatalog = catalog).supports("gemini-4-argon-high"))
        assertFalse(account(models = listOf("gemini-3.8-flash-high"), modelCatalog = catalog).supports("claude-opus-5-5-high"))
    }

    @Test
    fun `routability follows the manual enabled flag`() {
        assertTrue(account(enabled = true).isRoutable(now = 1_000))
        assertFalse(account(enabled = false).isRoutable(now = 1_000))
        assertEquals(true, account().isOAuth)
    }

    @Test
    fun `has access token requires a complete encrypted triplet`() {
        assertFalse(account().hasAccessToken)
        assertFalse(account(accessTokenCiphertext = "c", accessTokenNonce = "n", accessTokenKeyVersion = 1).hasAccessToken)
        assertFalse(account(accessTokenCiphertext = "c", accessTokenNonce = "n", accessTokenExpiresAt = 1_000).hasAccessToken)
        assertTrue(
            account(
                accessTokenCiphertext = "c",
                accessTokenNonce = "n",
                accessTokenKeyVersion = 1,
                accessTokenExpiresAt = 1_000,
            ).hasAccessToken,
        )
    }

    @Test
    fun `refresh due uses half the token lifetime when it is shorter than the configured lead`() {
        assertEquals(
            700_000,
            account(accessTokenExpiresAt = 1_000_000, lastRefreshedAt = 400_000).refreshDueAt(300_000),
        )
        assertEquals(
            40_000,
            account(accessTokenExpiresAt = 100_000, lastRefreshedAt = 0).refreshDueAt(300_000),
        )
    }

    @Test
    fun `refresh due clamps a corrupt lifetime and falls back without issue time`() {
        assertEquals(
            40_000,
            account(accessTokenExpiresAt = 100_000, lastRefreshedAt = 200_000).refreshDueAt(300_000),
        )
        assertEquals(
            700_000,
            account(accessTokenExpiresAt = 1_000_000, lastRefreshedAt = null).refreshDueAt(300_000),
        )
        assertEquals(Long.MIN_VALUE, account().refreshDueAt(300_000))
    }

    @Test
    fun `quota window reports remaining percentage and exhaustion`() {
        assertEquals(40.0, OAuthQuotaWindow(usedPercent = 60.0).remainingPercent)
        assertEquals(100.0, OAuthQuotaWindow(usedPercent = -5.0).remainingPercent)
        assertEquals(0.0, OAuthQuotaWindow(usedPercent = 105.0).remainingPercent)

        assertTrue(OAuthQuotaWindow(usedPercent = 100.0).isExhausted(now = 1_000))
        assertTrue(OAuthQuotaWindow(usedPercent = 100.0, resetsAt = 2_000).isExhausted(now = 1_000))
        assertFalse(OAuthQuotaWindow(usedPercent = 100.0, resetsAt = 1_000).isExhausted(now = 1_000))
        assertFalse(OAuthQuotaWindow(usedPercent = 99.9).isExhausted(now = 1_000))
    }

    @Test
    fun `exhausted quota blocks until the latest reset`() {
        val exhausted = OAuthQuotaSnapshot(
            primary = OAuthQuotaWindow(usedPercent = 100.0, resetsAt = 2_000_000),
            secondary = OAuthQuotaWindow(usedPercent = 100.0, resetsAt = 3_000_000),
            checkedAt = 1_000_000,
        )

        assertEquals(3_000_000, exhausted.blockedUntil(now = 1_000_000))
        assertNull(
            exhausted.copy(
                primary = OAuthQuotaWindow(usedPercent = 99.0),
                secondary = null,
            ).blockedUntil(now = 1_000_000),
        )
    }

    @Test
    fun `credits override an exhausted window`() {
        val exhausted = OAuthQuotaSnapshot(
            primary = OAuthQuotaWindow(usedPercent = 100.0, resetsAt = 2_000_000),
            creditsBalance = 1.0,
            checkedAt = 1_000_000,
        )

        assertNull(exhausted.blockedUntil(now = 1_000_000))
        assertNull(exhausted.copy(creditsBalance = 0.0, creditsUnlimited = true).blockedUntil(now = 1_000_000))
    }

    @Test
    fun `exhausted window without reset falls back only when a fallback is configured`() {
        val exhausted = OAuthQuotaSnapshot(
            primary = OAuthQuotaWindow(usedPercent = 100.0),
            checkedAt = 1_000_000,
        )

        assertNull(exhausted.blockedUntil(now = 1_000_000))
        assertNull(exhausted.blockedUntil(now = 1_000_000, fallbackMs = 0))
        assertEquals(1_005_000, exhausted.blockedUntil(now = 1_000_000, fallbackMs = 5_000))
    }

    @Test
    fun `stale quota readings are rejected by the freshness gate`() {
        val snapshot = OAuthQuotaSnapshot(primary = OAuthQuotaWindow(usedPercent = 100.0), checkedAt = 1_000)

        assertTrue(snapshot.isFresh(now = 2_000, maxAgeMs = 1_000))
        assertFalse(snapshot.isFresh(now = 2_001, maxAgeMs = 1_000))
        assertFalse(OAuthQuotaSnapshot(checkedAt = 0).isFresh(now = 2_000, maxAgeMs = 1_000))
    }

    private fun account(
        enabled: Boolean = true,
        models: List<String> = emptyList(),
        modelCatalog: List<OAuthAccountModelCatalogEntry>? = null,
        accessTokenCiphertext: String? = null,
        accessTokenNonce: String? = null,
        accessTokenKeyVersion: Int? = null,
        accessTokenExpiresAt: Long? = null,
        lastRefreshedAt: Long? = null,
    ) = OAuthAccountRecord(
        id = "account-1",
        name = "account",
        provider = OAuthProvider.CHATGPT,
        refreshTokenCiphertext = "c",
        refreshTokenNonce = "n",
        refreshTokenKeyVersion = 1,
        accessTokenCiphertext = accessTokenCiphertext,
        accessTokenNonce = accessTokenNonce,
        accessTokenKeyVersion = accessTokenKeyVersion,
        accessTokenExpiresAt = accessTokenExpiresAt,
        enabled = enabled,
        models = models,
        modelCatalog = modelCatalog,
        lastRefreshedAt = lastRefreshedAt,
        createdAt = 0,
        updatedAt = 0,
    )
}
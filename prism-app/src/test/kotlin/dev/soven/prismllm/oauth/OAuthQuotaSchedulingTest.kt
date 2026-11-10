package dev.soven.prismllm.oauth

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.oauth.domain.OAuthQuotaSnapshot
import dev.soven.prismllm.oauth.domain.OAuthQuotaWindow
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.repo.OAuthProviderChannelLookup
import dev.soven.prismllm.shared.crypto.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 额度扫描排队回归：失败也必须推进“最近一次尝试”，否则持续失败的账号会一直排在队首，
 * 账号数超过一轮扫描上限时会把健康账号的额度刷新饿死。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthQuotaSchedulingTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var cipher: CredentialCipher

    @BeforeAll
    fun start() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        factory = DatabaseFactory(
            GatewayConfig.fromEnv(
                mapOf(
                    "DATABASE_URL" to postgres.jdbcUrl,
                    "DATABASE_USER" to postgres.username,
                    "DATABASE_PASSWORD" to postgres.password,
                    "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
                    "ADMIN_PASSWORD" to "test-password",
                ),
            ),
        )
        factory.migrate()
        cipher = CredentialCipher(ByteArray(32) { 9 })
        channels = ChannelRepository(factory.database, cipher)
        accounts = OAuthAccountRepository(factory.database, OAuthProviderChannelLookup())
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `a failed quota read is pushed behind accounts that were never tried`() = runBlocking {
        val failing = createAccount()
        val healthy = createAccount()
        val attemptedAt = 1_000_000L

        assertTrue(accounts.saveQuotaError(failing, "timeout", attemptedAt))

        val due = accounts.listDueForQuota(attemptedBefore = attemptedAt).map { it.id }
        assertFalse(failing in due)
        assertTrue(healthy in due)
    }

    @Test
    fun `antigravity accounts are included in the quota sweep`() = runBlocking {
        val accountId = createAccount(OAuthProvider.ANTIGRAVITY)

        assertTrue(accountId in accounts.listDueForQuota(attemptedBefore = 1_000_000L).map { it.id })
    }

    @Test
    fun `a successful read records both the freshness and the attempt time`() = runBlocking {
        val accountId = createAccount()
        val checkedAt = 5_000L

        assertTrue(
            accounts.saveQuota(
                accountId,
                OAuthQuotaSnapshot(primary = OAuthQuotaWindow(usedPercent = 12.5), checkedAt = checkedAt),
            ),
        )

        assertEquals(checkedAt, accounts.find(accountId)!!.quota!!.checkedAt)
        assertFalse(accountId in accounts.listDueForQuota(attemptedBefore = checkedAt).map { it.id })
        assertTrue(accountId in accounts.listDueForQuota(attemptedBefore = checkedAt + 1).map { it.id })
    }

    private fun createAccount(provider: OAuthProvider = OAuthProvider.CHATGPT): String = runBlocking {
        val channel = channels.create(
            name = "oauth-quota-" + UUID.randomUUID(),
            baseUrl = "https://subscription.example/v1",
            keys = listOf(NewChannelKey("account", cipher.encrypt("refresh-token"))),
            authType = "bearer",
            enabled = true,
        )
        accounts.create(
            NewOAuthAccount(
                name = "quota-account",
                provider = provider,
                accountEmail = "quota-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                channelIds = listOf(channel.id),
            ),
        )
            .id
    }
}

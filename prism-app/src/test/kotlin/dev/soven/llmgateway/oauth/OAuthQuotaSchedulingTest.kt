package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.oauth.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.oauth.domain.OAuthQuotaWindow
import dev.soven.llmgateway.channel.persistence.ChannelRepository
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.shared.crypto.CredentialCipher
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
        accounts = OAuthAccountRepository(factory.database)
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

    private fun createAccount(): String = runBlocking {
        val channel = channels.create(
            name = "oauth-quota-" + UUID.randomUUID(),
            baseUrl = "https://chatgpt.example/v1",
            keys = listOf(NewChannelKey("account", cipher.encrypt("refresh-token"))),
            authType = "bearer",
            enabled = true,
        )
        accounts.create(
            NewOAuthAccount(
                name = "quota-account",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "quota-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                channelIds = listOf(channel.id),
            ),
        )
            .id
    }
}

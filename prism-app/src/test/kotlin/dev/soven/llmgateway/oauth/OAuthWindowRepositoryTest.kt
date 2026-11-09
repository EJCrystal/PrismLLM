package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.oauth.domain.OAuthAccountPolicy
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.oauth.domain.OAuthWindowSnapshot
import dev.soven.llmgateway.channel.persistence.ChannelRepository
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.oauth.persistence.OAuthWindowRepository
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
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthWindowRepositoryTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var channels: ChannelRepository
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var windows: OAuthWindowRepository
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
        windows = OAuthWindowRepository(factory.database)
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `new accounts use the default scheduling policy`() = runBlocking {
        val accountId = createAccount()
        assertEquals(OAuthAccountPolicy(), accounts.find(accountId)!!.policy)
    }

    @Test
    fun `persists and restores a snapshot together with the account policy`() = runBlocking {
        val accountId = createAccount()
        val policy = OAuthAccountPolicy(windowLimit = 7, windowSeconds = 3_600, maxConcurrentRequests = 2)
        assertTrue(accounts.updatePolicy(accountId, policy))

        windows.upsert(
            OAuthWindowSnapshot(
                accountId = accountId,
                hits = listOf(1_000L, 2_000L),
                cooldownUntil = 5_000L,
                consecutiveRateLimits = 3,
                updatedAt = 6_000L,
            ),
        )

        val stored = windows.loadAll().single { it.snapshot.accountId == accountId }
        assertEquals(listOf(1_000L, 2_000L), stored.snapshot.hits)
        assertEquals(5_000L, stored.snapshot.cooldownUntil)
        assertEquals(3, stored.snapshot.consecutiveRateLimits)
        assertEquals(policy, stored.policy)

        assertEquals(policy, accounts.find(accountId)!!.policy)
    }

    @Test
    fun `upsert keeps one row per account and replaces the snapshot`() = runBlocking {
        val accountId = createAccount()
        windows.upsert(OAuthWindowSnapshot(accountId, hits = listOf(1L, 2L), updatedAt = 1L))
        windows.upsert(OAuthWindowSnapshot(accountId, hits = listOf(3L), cooldownUntil = 9L, updatedAt = 2L))

        val matching = windows.loadAll().filter { it.snapshot.accountId == accountId }
        assertEquals(1, matching.size)
        assertEquals(listOf(3L), matching.single().snapshot.hits)
        assertEquals(9L, matching.single().snapshot.cooldownUntil)
    }

    @Test
    fun `deleting the oauth account cascades the window state`() = runBlocking {
        val accountId = createAccount()
        windows.upsert(OAuthWindowSnapshot(accountId, hits = listOf(1L), updatedAt = 1L))

        assertTrue(accounts.delete(accountId))

        assertTrue(windows.loadAll().none { it.snapshot.accountId == accountId })
    }

    private fun createAccount(): String = runBlocking {
        val channel = channels.create(
            name = "oauth-window-" + UUID.randomUUID(),
            baseUrl = "https://chatgpt.example/v1",
            keys = listOf(NewChannelKey("account", cipher.encrypt("refresh-token"))),
            authType = "bearer",
            enabled = true,
        )
        accounts.create(
            NewOAuthAccount(
                name = "window-account",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "window-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
                channelIds = listOf(channel.id),
            ),
        )
            .id
    }
}

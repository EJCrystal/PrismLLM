package dev.soven.prismllm.oauth.persistence

import dev.soven.prismllm.oauth.domain.OAuthAccountPolicy
import dev.soven.prismllm.oauth.domain.OAuthWindowSnapshot
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class OAuthWindowRepositoryTest {
    private lateinit var factory: TestDatabase
    private lateinit var accounts: OAuthAccountRepository
    private lateinit var windows: OAuthWindowRepository
    private lateinit var cipher: CredentialCipher

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        val databaseUrl = SharedPostgres.newDatabase()
        factory = TestDatabase(databaseUrl, postgres.username, postgres.password)
        cipher = CredentialCipher(ByteArray(32) { 9 })
        accounts = OAuthAccountRepository(factory.database, ProviderChannelLookup { emptySet() })
        windows = OAuthWindowRepository(factory.database)
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
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
        assertEquals(0L, suspendTransaction(factory.database) {
            OAuthAccountWindows.selectAll().where { OAuthAccountWindows.accountId eq accountId }.count()
        })
    }

    private fun createAccount(): String = runBlocking {
        accounts.create(
            NewOAuthAccount(
                name = "window-account",
                provider = OAuthProvider.CHATGPT,
                accountEmail = "window-${UUID.randomUUID()}@example.com",
                refreshToken = cipher.encrypt("refresh-token"),
            ),
        )
            .id
    }
}
